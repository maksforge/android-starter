#!/usr/bin/env python3
"""Assert an AAB declares the permissions and device requirements we think it does.

    python3 scripts/aab-permissions.py [path/to/app-release.aab]

Why this exists: in the app this template was extracted from, a release note said the
app declared two permissions and the artifact declared six. WorkManager's manifest had
merged in WAKE_LOCK, ACCESS_NETWORK_STATE and FOREGROUND_SERVICE, none of which
appeared anywhere in that app's source. Later it happened again and bigger: a barcode
scanner dependency brought INTERNET through a transitive nobody would think to read
(com.google.android.datatransport:transport-backend-cct) — on an app whose privacy
policy said it had no network.

So the permission set is asserted against a list kept here, and adding a dependency
that merges a new one **fails** rather than passing quietly. When it does fail, the
fix is to decide what the new permission means for the Play Console, write that into
`docs/play-app-content.md`, and only then add it below.

⚠ **EXPECTED describes the template as shipped, and it is yours to rewrite.** The
first app built from this template failed its first release upload here, because the
table still listed the first app's permissions. Every row below names the file that
declares or merges it: delete a feature, delete its row in the same commit, and the
gate stays a reading of *this* app rather than of the one it was copied from.

**<uses-feature> is checked too, and it is not a lesser half.** A merged
`android.hardware.camera` at required="true" — the default when the attribute is
omitted — filters the app off every device without a camera on Play. That is a
distribution change no permission list would show, so the tool of record has to
be able to see it.

**Orientation is checked too, as a comparison rather than a ban.** A scanner library
once shipped `android:screenOrientation="portrait"` on an invisible delegate activity;
`tools:remove` takes it back out, and nothing in the app's source would show if a
dependency bump quietly put one back. The merged *text* manifest is no help either —
it keeps XML comments, so a grep there hits our own explanation of the removal.
EXPECTED_ORIENTATION names every lock this app decided on, and both directions fail:
a lock nobody decided on, and a decided lock gone missing. It was a blanket ban once,
and that is how an app built from this template tagged a release that never reached
Play — it had grown a deliberate portrait lock and the gate still asserted it had none.

`strings | grep` cannot do this job: it cannot tell a <uses-permission> from an
android:permission guard on a service, and this artifact carries three of the
latter (BIND_JOB_SERVICE, and DUMP twice) that are not requests at all. So the
protobuf gets walked properly.

Exits non-zero if the artifact's <uses-permission> set differs from EXPECTED, if it
declares a <uses-feature> not accounted for in EXPECTED_FEATURES, or if the screen
locks it carries are not exactly EXPECTED_ORIENTATION.
"""

import sys
import zipfile

# Field numbers from aapt2's Resources.proto, hard-coded for the same reason
# scripts/aab-version.py hard-codes them: a few numbers beat a protoc dependency
# in a script whose whole job is to have no moving parts.
NODE_ELEMENT = 1
ELEM_NAME, ELEM_ATTRIBUTE, ELEM_CHILD = 3, 4, 5
ATTR_NAME, ATTR_VALUE = 2, 3
# android:required survives twice over: aapt2 keeps the source string in ATTR_VALUE
# *and* compiles it into an Item. The string is what gets read; the Item is the
# fallback for an attribute a library set by resource reference, where there is no
# source string to read. Field 6 is that Item, field 7 inside it is Primitive, and
# field 8 there is the boolean. Reached by number for the same reason as everything
# above: no protoc dependency in a script whose whole job is to have no moving parts.
ATTR_COMPILED_ITEM = 6
ITEM_PRIM = 7
PRIM_BOOLEAN = 8
# Primitive's two integer fields, for an attribute that compiled to a number.
# android:screenOrientation turned out not to need them — aapt2 keeps "portrait" in
# ATTR_VALUE, checked against a fixture rather than assumed — but an attribute set by
# resource reference has no source string, and the reading worth having then is the
# number, because an orientation that is silently unreadable is the one that ships.
PRIM_INT_DEC = 6
PRIM_INT_HEX = 7

# Every <uses-permission> the release artifact is allowed to carry. Each is
# accounted for in docs/play-app-content.md — keep the two in step. Read off the
# template's own bundle, so the list is true on the day you clone it; it stops
# being true the day you remove the reminders, WorkManager or the tip, and that is
# the day to edit it.
EXPECTED = {
    # Declared in AndroidManifest.xml. Runtime on API 33+, asked for from a screen
    # that explains it first (work/NotificationPermission.kt, ADR-0003).
    "android.permission.POST_NOTIFICATIONS": "ours — reminder notifications, ADR-0003",
    # Declared. Install-time and invisible; BootReceiver re-arms the sweep after a restart.
    "android.permission.RECEIVE_BOOT_COMPLETED": "ours — work/BootReceiver.kt, ADR-0003",
    # Declared, and deliberately not USE_EXACT_ALARM (the manifest comment says why).
    # Delete the manifest line and this row together if nothing needs a precise moment.
    "android.permission.SCHEDULE_EXACT_ALARM": "ours — work/ExactAlarms.kt, ADR-0003",
    # The three WorkManager merges, none of them in our source. WAKE_LOCK and
    # FOREGROUND_SERVICE leave with WorkManager; ACCESS_NETWORK_STATE is merged by
    # the Play Billing Library's datatransport too, and leaves only once both are
    # gone. An artifact that carries one again after its owners left has a new
    # dependency that wants a wake lock or network state, which is a Data safety
    # question before it is a build one.
    "android.permission.WAKE_LOCK": "WorkManager",
    "android.permission.ACCESS_NETWORK_STATE": (
        "WorkManager and Play Billing's datatransport — read connectivity state, open nothing"
    ),
    "android.permission.FOREGROUND_SERVICE": "WorkManager — its SystemForegroundService, never started here",
    # The tip (ADR-0007), merged from the Play Billing Library. It lets the app talk to
    # the Play Store app's billing service; no dialog, and no data of the user's. Its
    # datatransport dependency also merges INTERNET, which the manifest removes.
    "com.android.vending.BILLING": "the Play Billing Library — the tip, ADR-0007",
    # AndroidX defines and uses this itself, signature-level. The prefix is the
    # applicationId, which differs between the debug and release builds, so it is
    # matched by suffix rather than spelled out.
    "*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": "AndroidX, signature-level",
}

# Permissions that must never appear. Absence is what the Data safety answers
# rest on, so it is asserted rather than assumed.
FORBIDDEN = {
    "com.google.android.gms.permission.AD_ID": "Data safety says no advertising ID",
    "android.permission.QUERY_ALL_PACKAGES": "the <queries> element names one package instead",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS": "Play restricts it; work/BatteryExemption.kt deep-links instead",
    # "No backend, no account" is a claim the Data safety form and the privacy policy
    # both make, and this is the only thing that checks it against the artifact: a
    # dependency that merges INTERNET makes the claim false without touching a line of
    # our source. An app that genuinely needs the network moves it to EXPECTED — and
    # rewrites both of those documents in the same commit.
    "android.permission.INTERNET": "no network: a merged one makes the privacy policy and Data safety wrong",
    # The TakePicture intent needs no permission. Declaring CAMERA would make the
    # intent *require* it to be granted, and changes the listing.
    "android.permission.CAMERA": "the camera intent needs none; declaring it changes the listing",
    # Play permits USE_EXACT_ALARM only for alarm clocks and calendars.
    "android.permission.USE_EXACT_ALARM": "Play policy: alarm-clock and calendar apps only; SCHEDULE_EXACT_ALARM instead",
}

# Every <uses-feature> the artifact is allowed to carry, with the required= value
# each is allowed to carry it at. Empty on purpose: the template declares none, and
# nothing it depends on merges one — asserted here rather than assumed.
#
# A feature at required="true" is a *distribution* rule — Play hides the app from
# every device without it — which is why an unlisted one fails here rather than
# being printed as a curiosity. If one ever arrives, the fix is to decide whether
# the feature is worth the devices it costs, write that into play-app-content.md,
# and set it to False here (`android:required="false"`) unless it genuinely is
# required.
EXPECTED_FEATURES: dict[str, bool] = {}

# Every android:screenOrientation the release artifact is allowed to carry, and the
# value it is allowed to carry it at, keyed by fully-qualified class name. Empty:
# the template locks no screen. If the product decides to lock one, it goes here
# with the reason, e.g.
#
#     "<namespace>.MainActivity": ("portrait", "ours — the full app, decided <date>"),
#
# and from then on both directions are findings: a lock that is not in this map is
# one nobody decided on, and one in the map the artifact does not carry is a
# product decision undone by an edit nobody read.
EXPECTED_ORIENTATION: dict[str, tuple[str, str]] = {}

# Locks the *release* artifact never sees, because the activity carrying them is in
# app/src/debug/ — allowed when this runs against a debug bundle, never required.
# Keyed by class name, not applicationId: the debug build suffixes the latter only.
DEBUG_ONLY_ORIENTATION: dict[str, tuple[str, str]] = {}


def read_varint(buf, i):
    shift = result = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def fields(buf):
    """Yield (field_number, payload) for one protobuf message."""
    i = 0
    while i < len(buf):
        key, i = read_varint(buf, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(buf, i)
            yield number, value
        elif wire == 1:
            yield number, buf[i:i + 8]
            i += 8
        elif wire == 2:
            length, i = read_varint(buf, i)
            yield number, buf[i:i + length]
            i += length
        elif wire == 5:
            yield number, buf[i:i + 4]
            i += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")


def as_element(node_blob):
    return next((p for n, p in fields(node_blob) if n == NODE_ELEMENT), None)


def walk(element):
    """Yield (tag, {attribute: value}, [(attribute, raw payload)]) per element.

    The raw payloads ride along because a boolean attribute has no string value
    at all — see [required_attribute], which has to go into the compiled Item to
    read android:required.
    """
    tag, attrs, raw, children = None, {}, [], []
    for number, payload in fields(element):
        if number == ELEM_NAME and isinstance(payload, bytes):
            tag = payload.decode(errors="replace")
        elif number == ELEM_ATTRIBUTE:
            name = value = None
            for anumber, apayload in fields(payload):
                if anumber == ATTR_NAME and isinstance(apayload, bytes):
                    name = apayload.decode(errors="replace")
                elif anumber == ATTR_VALUE and isinstance(apayload, bytes):
                    value = apayload.decode(errors="replace")
            if name:
                attrs[name] = value
                raw.append((name, payload))
        elif number == ELEM_CHILD:
            child = as_element(payload)
            if child is not None:
                children.append(child)

    yield tag, attrs, raw
    for child in children:
        yield from walk(child)


def compiled_primitive(attrs_raw, wanted):
    """(field number, value) of one attribute's compiled Primitive, or None.

    An attribute aapt2 compiled from literal text keeps that text in ATTR_VALUE; a
    boolean has no text form at all, and one set by resource reference lost its
    literal, so for both the only answer left is inside the compiled Item.
    """
    for name, payload in attrs_raw:
        if name != wanted:
            continue
        for number, value in fields(payload):
            if number != ATTR_COMPILED_ITEM or not isinstance(value, bytes):
                continue
            for inumber, ipayload in fields(value):
                if inumber != ITEM_PRIM or not isinstance(ipayload, bytes):
                    continue
                return next(iter(fields(ipayload)), None)
    return None


def required_attribute(attrs, attrs_raw):
    """The android:required boolean of a <uses-feature>, or None when it is omitted.

    Omitted means **true** to the platform, which is the whole reason this is read
    rather than assumed — the dangerous case is the one nobody wrote down.
    """
    if "required" not in attrs:
        return None
    text = attrs.get("required")
    if text:
        return text.strip().lower() not in ("false", "0")

    primitive = compiled_primitive(attrs_raw, "required")
    if primitive is not None and primitive[0] == PRIM_BOOLEAN:
        return bool(primitive[1])
    # Declared and unreadable is treated as declared-and-required: the conservative
    # reading is the one that fails loudly rather than the one that ships quietly.
    return True


def orientation_attribute(attrs, attrs_raw):
    """What android:screenOrientation this element asks for, or None when absent.

    The *value* is the finding now that one lock is expected, so an attribute that
    cannot be read still returns something rather than None: unreadable has to fail
    the comparison below, and None would read as "this element declares none".
    """
    if "screenOrientation" not in attrs:
        return None
    text = attrs.get("screenOrientation")
    if text:
        return text

    primitive = compiled_primitive(attrs_raw, "screenOrientation")
    if primitive is not None and primitive[0] in (PRIM_INT_DEC, PRIM_INT_HEX):
        return f"compiled value {primitive[1]}"
    return "declared, and its value could not be read"


def matches(permission, allowed):
    return permission == allowed or (
        allowed.startswith("*.") and permission.endswith(allowed[1:])
    )


def main():
    # Built here rather than beside the two maps so that they stay the only place a
    # lock is written down: what is expected, plus what a debug bundle is allowed to
    # add, is what may appear.
    allowed_orientation = {**EXPECTED_ORIENTATION, **DEBUG_ONLY_ORIENTATION}

    path = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/bundle/release/app-release.aab"
    try:
        with zipfile.ZipFile(path) as bundle:
            blob = bundle.read("base/manifest/AndroidManifest.xml")
    except FileNotFoundError:
        sys.exit(f"no such bundle: {path}\nRun ./gradlew bundleRelease first.")
    except KeyError:
        sys.exit(f"{path} has no base/manifest/AndroidManifest.xml — is it an AAB?")

    root = as_element(blob)
    if root is None:
        sys.exit("no root element in base/manifest/AndroidManifest.xml")

    requested, guards, features, oriented, handled, activities = [], [], [], [], [], []
    for tag, attrs, raw in walk(root):
        name = attrs.get("name")
        if tag in ("uses-permission", "uses-permission-sdk-23") and name:
            requested.append(name)
        elif tag == "uses-feature" and name:
            features.append((name, required_attribute(attrs, raw)))
        elif tag in ("service", "receiver", "provider", "activity") and attrs.get("permission"):
            guards.append((name or "?", attrs["permission"]))

        # Not part of that chain: an orientation lock is worth finding on an activity
        # that also carries a permission guard, and those two branches are exclusive.
        orientation = orientation_attribute(attrs, raw)
        if orientation is not None:
            oriented.append((name or tag, orientation))
        if tag == "activity":
            activities.append(name or "?")
            if attrs.get("configChanges"):
                handled.append((name or "?", attrs["configChanges"]))

    for permission in sorted(requested):
        note = next((n for a, n in EXPECTED.items() if matches(permission, a)), None)
        print(f"  {'ok ' if note else 'NEW'} {permission}" + (f"  — {note}" if note else ""))

    # Guards are context, not requests: android:permission on a component says who
    # may *call* it. Printed so they are never mistaken for the list above.
    for component, permission in guards:
        print(f"  ·   {permission}  — guard on {component.rsplit('.', 1)[-1]}, not a request")

    # Context, like the guards: an activity that handles a configuration change itself
    # is not recreated by it. Printed for the same reason as the guards: it is a
    # component's own declaration showing up in a list of ours.
    for component, changes in handled:
        print(f"  ·   configChanges {changes}  — {component.rsplit('.', 1)[-1]} handles these itself")

    # Context too, and the one line here that says a deliberate decision is still in
    # the artifact: an expected lock prints rather than staying invisible until the
    # day it goes missing.
    for component, value in sorted(oriented):
        expected = allowed_orientation.get(component)
        note = expected[1] if expected and expected[0] == value else "NOT EXPECTED — see below"
        print(f"  ·   screenOrientation {value}  — {component.rsplit('.', 1)[-1]}, {note}")

    # An omitted android:required reads as true to the platform, and the print says
    # so rather than showing a blank — the silent default is the dangerous one.
    for feature, required in sorted(features):
        shown = "required" if required in (True, None) else "optional"
        default = " (by default — the attribute is absent)" if required is None else ""
        print(f"  !   uses-feature {feature} — {shown}{default}")

    unexpected = [p for p in requested if not any(matches(p, a) for a in EXPECTED)]
    absent = [a for a in EXPECTED if not any(matches(p, a) for p in requested)]
    forbidden = {p: why for p, why in FORBIDDEN.items() if p in requested}
    unexpected_features = [
        (f, r) for f, r in features if f not in EXPECTED_FEATURES or EXPECTED_FEATURES[f] != (r in (True, None))
    ]
    unexpected_orientation = [
        (c, v) for c, v in oriented if c not in allowed_orientation or allowed_orientation[c][0] != v
    ]
    absent_orientation = [(c, v) for c, (v, _) in EXPECTED_ORIENTATION.items() if (c, v) not in oriented]

    problems = []
    if unexpected:
        problems.append(
            "NEW permissions not accounted for in docs/play-app-content.md:\n"
            + "\n".join(f"  {p}" for p in sorted(unexpected))
            + "\nDecide what each means for the Play Console, write it down, then add it to EXPECTED."
        )
    if absent:
        problems.append(
            "EXPECTED permissions missing from the artifact:\n"
            + "\n".join(f"  {p}" for p in sorted(absent))
        )
    if forbidden:
        problems.append(
            "FORBIDDEN permissions present:\n"
            + "\n".join(f"  {p} — {why}" for p, why in sorted(forbidden.items()))
        )
    if unexpected_features:
        problems.append(
            "<uses-feature> not accounted for:\n"
            + "\n".join(
                f"  {f} — required={'true (by default)' if r is None else str(r).lower()}"
                for f, r in sorted(unexpected_features)
            )
            + "\nA required feature is a distribution rule: Play hides the app from every device\n"
            "without it. Decide whether it is worth those devices, write that into\n"
            "docs/play-app-content.md, then add it to EXPECTED_FEATURES."
        )

    if unexpected_orientation:
        problems.append(
            "android:screenOrientation nobody here decided on:\n"
            + "\n".join(f"  {c} — {v}" for c, v in sorted(unexpected_orientation))
            + "\nEvery lock this app decided on is in EXPECTED_ORIENTATION, and this one is not.\n"
            "A dependency's own manifest is the usual source;\n"
            "take it back out with tools:remove rather than tools:replace with a value, which\n"
            "lint's DiscouragedApi flags without reading it. If the lock is ours and meant,\n"
            "add it above with the reason — that is a decision, not a line to make green."
        )
    if absent_orientation:
        problems.append(
            "EXPECTED screenOrientation missing from the artifact:\n"
            + "\n".join(f"  {c} — {v}" for c, v in sorted(absent_orientation))
            + "\nThe lock is a product decision, not a default. Gone from the artifact means an\n"
            "edit undid it, or a manifest merge dropped it — either way a screen that was meant\n"
            "to stay put now turns. Remove it here too if that is now the decision."
        )

    if problems:
        print("\n" + "\n\n".join(problems) + "\n\nDo not upload this artifact.", file=sys.stderr)
        return 1

    print(
        f"\n{len(requested)} permissions, all accounted for; "
        f"none of the {len(FORBIDDEN)} forbidden ones present; "
        f"{len(features)} <uses-feature> declared; "
        f"{len(oriented)} of the {len(activities)} activities lock their orientation, "
        f"every one of them expected"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
