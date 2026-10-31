#!/usr/bin/env python3
"""Drive the app through the edge-to-edge matrix and check it against the system insets.

Play Console raises edge-to-edge against every app targeting SDK 35+, and the notice is generic
advice rather than a detected defect. `MainActivity` already calls `enableEdgeToEdge()` and the
shell's `Scaffold` owns the insets, so the mechanism is in place; what is owed is *evidence*, and
evidence for four configurations across twenty-odd screens is not something anyone captures by hand
twice.

So this drives it. For each cell of the matrix it sets the rotation and the navigation mode, walks
the app to each scene, saves a screenshot, and — the part that makes the screenshots reviewable —
asks `uiautomator` where every text, icon and control actually landed, then intersects those
rectangles with the system-bar and display-cutout rectangles `dumpsys` reports for that same
configuration. A control inside the navigation bar's rectangle is the defect the checkpoint is
looking for, found by arithmetic instead of by squinting at 80 PNGs.

The screenshots are still the deliverable: this narrows which ones a human has to open.

Usage:
    scripts/edge-to-edge.py --out /path/to/dir            # the whole matrix
    scripts/edge-to-edge.py --out DIR --config landscape-threebutton
    scripts/edge-to-edge.py --out DIR --scene home,weight-chart
    scripts/edge-to-edge.py --out DIR --locale pl         # the same walk, in Polish
    scripts/edge-to-edge.py --out DIR --assert-clean       # exit 1 on a defect, for CI
    scripts/edge-to-edge.py --out DIR --retry-unreached 2  # re-walk a scene the driver missed
    scripts/edge-to-edge.py --restore                     # hand the phone back to auto-rotate

The phone is left in whatever configuration the last cell used; `--restore` puts it back.

**It runs on an emulator as well as on the phone.** Navigation mode is set through whichever
mechanism the device family actually reads — see [set_nav_mode] — and the configurations a device
cannot be put into are dropped by name rather than captured under a label that would be a lie; see
[usable_configs], which is what keeps API 26-28 honest about having no gesture navigation at all.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import project  # noqa: E402  — the path insert above has to come first

# The debug install by default: it is the scratch copy, the one a wipe costs nothing, and the one
# with the debug-only seeding surface. [select_build] is how a run says otherwise.
PACKAGE = project.DEBUG_APPLICATION_ID
ACTIVITY = project.MAIN_ACTIVITY

# Whether [wipe] may `pm clear` the install this run drives. False on the shipped install, which is
# somebody's real app with no `run-as` to back anything up first. See [select_build].
WIPEABLE = True


def select_build(build: str) -> None:
    """Point the driver at the debug install (`debug`) or at the shipped one (`release`).

    **`release` is the build a store screenshot has to come from**, and not for tidiness: the
    developer-only Settings section lives in `app/src/debug/`, so on the shipped build
    `DebugSettings()` is a no-op and the section is *absent* rather than hidden. A set shot off
    `debug` photographs *Seed sample data* the moment any scene scrolls Settings — which is what an
    app built from this template's first trial run did.

    It also takes the wipe away, which is the dangerous half. `debug` is a scratch install nobody
    mourns; the release id is the app on the phone's home screen — installed from Play, or with
    `bundletool` from a local `bundleRelease` — and a capture run reseeds, that is `pm clear`s, once
    per cell. So [WIPEABLE] goes false here and [wipe] refuses, rather than leaving it to whoever
    remembers a flag at 2am.

    ⚠ **Pointed at the wrong install, nothing fails**: the walk photographs a real app that answers
    every needle, just not the one the run was for. Both can be on the phone at once, which is the
    normal state (`applicationIdSuffix`), so the flag is the only thing that says which.
    """
    global PACKAGE, ACTIVITY, WIPEABLE
    if build not in ("debug", "release"):
        raise SystemExit(f"unknown build {build!r}: expected 'debug' or 'release'")
    PACKAGE = project.DEBUG_APPLICATION_ID if build == "debug" else project.APPLICATION_ID
    ACTIVITY = f"{PACKAGE}/{project.NAMESPACE}.MainActivity"
    WIPEABLE = build == "debug"


# The three inset types a screen can be wrongly drawn under. `displayCutout` is listed separately
# from `statusBars` on purpose: in portrait they coincide, and in landscape they do not — which is
# the whole reason landscape is in this matrix.
INSET_TYPES = ("statusBars", "navigationBars", "displayCutout")


# --------------------------------------------------------------------------------------------
# adb
# --------------------------------------------------------------------------------------------


def adb(*args: str, binary: bool = False) -> str | bytes:
    """Run an adb command. `binary` uses exec-out, which does not mangle \\n into \\r\\n."""
    result = subprocess.run(["adb", *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def shell(cmd: str) -> str:
    return adb("shell", cmd)


def shell_ok(cmd: str) -> bool:
    """Run a shell command, reporting whether it worked instead of raising.

    For the handful of calls that address a *device feature* rather than the app: a command missing
    on an older API level must not abort a two-hour matrix, and `adb` reports that case as a
    non-zero exit rather than as an exception worth unwinding for.
    """
    return subprocess.run(["adb", "shell", cmd], capture_output=True).returncode == 0


def settle(seconds: float = 0.6) -> None:
    time.sleep(seconds)


# --------------------------------------------------------------------------------------------
# The device configuration under test
# --------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Config:
    name: str
    rotation: int  # user_rotation: 0 portrait, 1 landscape (90°)
    nav: str  # "gesture" | "threebutton"


CONFIGS = [
    # Four cells: two navigation modes, two rotations. If the app locks its activity to portrait,
    # delete the two landscape cells here and in ci.yml's matrix — see [repin_rotation] for why
    # keeping them is worse than dropping them.
    Config("portrait-gesture", 0, "gesture"),
    Config("portrait-threebutton", 0, "threebutton"),
    Config("landscape-gesture", 1, "gesture"),
    Config("landscape-threebutton", 1, "threebutton"),
]


_FAMILY: "str | None" = None


def device_family() -> str:
    """"miui" for HyperOS, "aosp" for everything else — an emulator, in practice.

    The seam exists for exactly one line of [apply_config], and it is detected rather than passed in
    because a run that has to be *told* what it is running on is a run somebody eventually tells
    wrong. What makes it worth detecting: the AOSP overlay path is a no-op **that reports success**
    on this phone, so a cell driven the wrong way still captures, still checks and still says
    "clean" — against an inset that never moved.
    """
    global _FAMILY
    if _FAMILY is None:
        _FAMILY = "miui" if shell("getprop ro.miui.ui.version.name").strip() else "aosp"
    return _FAMILY


_API: "int | None" = None


def api_level() -> int:
    global _API
    if _API is None:
        _API = int(shell("getprop ro.build.version.sdk").strip())
    return _API


# Gesture navigation is Android 10. Below it the platform has no gestural overlay to enable and no
# `force_fsg_nav_bar` to set, so a "gesture" cell on API 26-28 is a three-button cell wearing the
# wrong name — see [usable_configs].
GESTURE_MIN_API = 29

# `POST_NOTIFICATIONS` is Android 13. Below it the permission does not exist, `pm grant` fails with
# a non-zero exit, and there is nothing to grant anyway: notifications are on until an owner turns
# them off, so the banner the grant below exists to avoid cannot appear. The first nightly matrix
# run died here on API 26, one minute in.
POST_NOTIFICATIONS_MIN_API = 33

# Per-app languages are Android 13, and so is `cmd locale set-app-locales`. Below it there is no
# per-app locale to hand back, and `shell()` raising on the missing command would kill `--restore`
# (here and in `screenshots.py`) before it put rotation and Do Not Disturb back.
APP_LOCALES_MIN_API = 33

# The AOSP navigation-mode overlays, all declaring `android:category="…systemui.navbar"`, which is
# what makes `enable-exclusive --category` a single call rather than an enable plus N disables.
NAVBAR_OVERLAYS = {
    "gesture": "com.android.internal.systemui.navbar.gestural",
    "threebutton": "com.android.internal.systemui.navbar.threebutton",
}

# `cmd overlay list`, read once. Not every image that reports API 29+ actually ships the overlays:
# the first nightly matrix run died one minute in on `enable-exclusive` returning 255 on the API 34
# `aosp_atd` emulator, while API 36 on the *same* target went straight through. An emulator image is
# free to omit a system overlay, and an API level is not a promise that it did not.
_OVERLAY_LIST: "str | None" = None


# `secure navigation_mode`: the platform's own record of which navigation the device is in, and the
# only reading left when the overlays are missing. 1 is the two-button mode of Android 9-10, which
# this matrix has no cell for.
NAV_MODE_SETTING = {"0": "threebutton", "2": "gesture"}


def current_nav_mode() -> "str | None":
    """The navigation mode the device is in, or None if the setting cannot be read or understood.

    None is not "three-button": an unreadable mode means a cell claiming to be in one would be the
    silently-wrong cell [usable_configs] exists to prevent, so it is skipped instead of assumed.
    """
    raw = shell("settings get secure navigation_mode").strip()
    return NAV_MODE_SETTING.get(raw)


def navbar_overlay_present(nav: str) -> bool:
    """Whether the overlay [set_nav_mode] would enable is installed at all.

    Presence, deliberately not *enabled*: `cmd overlay list` prints `[x] <package>` for an enabled
    overlay and `[ ] <package>` for an installed one that is off, and the gestural overlay is off on
    every device the moment before it is turned on. Asking for `[x]` would drop every cell.
    """
    global _OVERLAY_LIST
    if _OVERLAY_LIST is None:
        result = subprocess.run(["adb", "shell", "cmd overlay list"], capture_output=True)
        _OVERLAY_LIST = result.stdout.decode("utf-8", "replace") if result.returncode == 0 else ""
    return NAVBAR_OVERLAYS[nav] in _OVERLAY_LIST


def set_nav_mode(nav: str) -> None:
    """Put the device into `nav`, by whichever mechanism its family actually reads."""
    if device_family() == "miui":
        shell(f"settings put global force_fsg_nav_bar {1 if nav == 'gesture' else 0}")
        return
    if api_level() < GESTURE_MIN_API:
        # Three-button is the only navigation this platform has and it is already in place, so the
        # honest action is none. `enable-exclusive` is not reliably present this far back either,
        # and [usable_configs] has already dropped the gesture cells — so `nav` can only be
        # "threebutton" by the time control reaches here.
        return
    if not navbar_overlay_present(nav):
        # Same shape of no-op, reached a different way: an image with no navigation overlays cannot
        # be moved between modes at all, so [usable_configs] kept this cell only because the device
        # is already in `nav`. Calling `enable-exclusive` here is what took the API 34 leg down —
        # with 255 and `Unable to retrieve overlay information`, for the three-button cells that
        # image can otherwise run perfectly well.
        return
    shell(f"cmd overlay enable-exclusive --category {NAVBAR_OVERLAYS[nav]}")


@dataclass(frozen=True)
class Unreachable:
    """Why a cell cannot run, and whether that is a fact about the device or a failure to read it.

    The distinction decides an exit status. A leg whose every cell names a capability the device
    genuinely lacks has done everything there was to do, and going red for it would train everyone
    to ignore a red leg. A leg that could not *tell* has proven nothing, and green would be a lie.
    """

    reason: str
    readable: bool = True


def unreachable_reason(config: "Config") -> "Unreachable | None":
    """Why this device cannot be put into `config`, or None if it can.

    A reason rather than a boolean because it is printed and written into the report: "skipped" on
    its own, three weeks later in a CI artifact, is indistinguishable from "nobody asked for it".
    """
    # MIUI drives navigation through `force_fsg_nav_bar` and never touches the overlays, which are
    # all present and all disabled on that phone — so their absence would say nothing there.
    if device_family() == "miui":
        return None
    if config.nav == "gesture" and api_level() < GESTURE_MIN_API:
        return Unreachable(f"gesture navigation needs API {GESTURE_MIN_API} (this is {api_level()})")
    if api_level() < GESTURE_MIN_API:
        # Three-button is the only navigation the platform has, and it is already in place.
        return None
    if navbar_overlay_present(config.nav):
        return None
    # No overlay for this mode: the device cannot be moved into it. It may already be there, which
    # is the one case that still yields an honest cell — the mode is right, nothing had to set it.
    current = current_nav_mode()
    if current == config.nav:
        return None
    return Unreachable(
        f"this image ships no {config.nav} navigation overlay, and the device is in "
        f"{current or 'a mode that cannot be read'} — the mode cannot be set",
        readable=current is not None,
    )


def usable_configs(configs: "list[Config]") -> "tuple[list[Config], list[tuple[Config, Unreachable]]]":
    """Split the requested configs into the ones this device can be put into, and the rest.

    **A skipped cell and a silently wrong one are not the same result.** Below API 29 there is no
    gesture navigation on the platform, so pinning one leaves the three-button bar exactly where it
    was — and the cell would capture, check and report "clean" under a name describing a
    configuration the device was never in. That is the failure [_PINNED] exists to prevent, arriving
    by a different road, and it is worse in CI where nobody is watching the screen.

    **Nor is a skipped cell the same as a dead run.** An image that reports API 34 and has no
    gestural overlay used to reach [set_nav_mode] and abort the whole matrix on a 255, taking the
    three-button cells — which that image can perfectly well run — down with it. Unreachable is a
    property of a cell, not of the run.
    """
    kept: list[Config] = []
    dropped: list[tuple[Config, Unreachable]] = []
    for config in configs:
        reason = unreachable_reason(config)
        if reason is None:
            kept.append(config)
        else:
            dropped.append((config, reason))
    return kept, dropped


# The config the phone is currently pinned to, so a wipe can put the rotation back. `pm clear` kills
# the app, which hands the foreground to the portrait-locked launcher, and HyperOS writes
# `user_rotation` back to 0 when it does — silently turning a landscape cell into a second portrait
# one that still captures, still checks, and still reports "clean".
_PINNED: "Config | None" = None


def apply_config(config: Config) -> None:
    """Pin the rotation and the navigation mode, then wait for the window to settle.

    HyperOS does not use the AOSP `com.android.internal.systemui.navbar.*` overlays — they are all
    present and all disabled — so the navigation mode is flipped through MIUI's own
    `force_fsg_nav_bar` global instead. Verified against the navigation bar's reported inset, which
    is the thing being tested and cannot be faked by the setting alone. Everything else — an
    emulator — has the overlays and no such global, and takes the other half of [set_nav_mode].
    """
    global _PINNED
    _PINNED = config
    shell("settings put system accelerometer_rotation 0")
    shell(f"settings put system user_rotation {config.rotation}")
    set_nav_mode(config.nav)
    # SystemUI rebuilds the navigation bar out of process; there is nothing to poll that is ready
    # before the new inset is published, so this is a wait rather than a check.
    settle(3.0)


def repin_rotation() -> None:
    """Put back the rotation the last force-stop cost us.

    **Anything that takes the foreground away from this app can unpin the rotation, and the platform
    does it silently.** `user_rotation` only turns a display whose top activity permits turning, so
    the moment the app dies the launcher is in front — and launchers are portrait-locked — the window
    manager settles on ROTATION_0 and writes `user_rotation` back to `0`. Nothing reports this: the
    cell keeps its name, the screenshots keep coming, and [read_insets] keeps telling the truth about
    a rotation that is no longer the one being tested. An app built from this template found, walking
    a new scene, that **every landscape cell had been a second portrait cell, for every scene, since
    the harness was written**: a `force-stop` alone took `user_rotation` from 1 to 0 within three
    seconds on an API 33 emulator. The tell is a report whose header insets are landscape and whose
    scene insets are portrait, and portrait-sized PNGs in a directory called `landscape-*`.

    So this is not only a wipe's problem, which is how it was first written. Every caller that
    force-stops — [wipe] and [relaunch] here, and any step you add that does — is a place a
    landscape cell can quietly become a second portrait one, and each calls this afterwards.

    **Read before write, because the write is not the cost — the settle is.** A portrait cell never
    loses anything and would otherwise pay 1.5s per scene to be told so; the round-trip that asks is
    an order of magnitude cheaper. `accelerometer_rotation` is read in the same trip rather than
    written blind: on a device that *has* a sensor, leaving it enabled hands the display back to the
    hand holding the phone, which is the same defect by a slower road.

    ⚠ **An app that locks its activity to portrait makes the landscape cells meaningless**, not
    merely redundant: every scene starts from the locked activity, so they capture portrait under a
    landscape name. Drop them from [CONFIGS] and from the CI matrix in the same commit as the lock
    (and add the lock to `aab-permissions.py`'s EXPECTED_ORIENTATION).
    """
    if _PINNED is None:
        return
    live = shell(
        "settings get system user_rotation; settings get system accelerometer_rotation"
    ).split()
    if live == [str(_PINNED.rotation), "0"]:
        return
    shell("settings put system accelerometer_rotation 0")
    shell(f"settings put system user_rotation {_PINNED.rotation}")
    settle(1.5)


def restore_device() -> None:
    # Gesture is the phone's own default; below API 29 there is no such thing to go back to.
    set_nav_mode("gesture" if api_level() >= GESTURE_MIN_API else "threebutton")
    shell("settings put system accelerometer_rotation 1")
    set_dnd(False)
    set_demo_status_bar(False)


def set_dnd(on: bool) -> None:
    """Silence heads-up notifications for the run, and hand them back afterwards.

    **Setup and teardown, not a note in a document.** A heads-up notification posts over whatever
    screen the driver is on, exactly where its next tap lands. The tap opens the notification
    instead, and `AUTO_CANCEL` clears the banner on the way — so the evidence afterwards looks
    impossible: a scene that walked somewhere nobody asked it to, and no notification anywhere to
    explain it. Worth pinning *before* it costs you a run: from the artifacts alone it is close to
    undiagnosable.

    `set_dnd` and not a revoked `POST_NOTIFICATIONS`: the scenes photograph reminder copy, and an
    app that cannot post notifications draws a blocked-state banner instead — which would make the
    screenshots lie about a different thing. Zen suppresses the *presentation* and leaves the app's
    own state alone (verified: `zen_mode` reads 2 with it on and 0 with it off).

    It is **phone-wide**, which is why every caller's `off` belongs in a `finally`. A crashed run
    must not leave somebody's phone silent.
    """
    if not shell_ok(f"cmd notification set_dnd {'on' if on else 'off'}"):
        # Tolerated rather than fatal: on an emulator with no heads-up banner to suppress this is
        # cosmetic, and losing a whole matrix to a missing device command would not be.
        print("  -- note: `cmd notification set_dnd` unavailable, continuing without it")
    settle(0.5)


# What `sysui_demo_allowed` read before a run turned demo mode on, so [set_demo_status_bar] can put
# back exactly that. None means "not touched this run".
_DEMO_ALLOWED: "str | None" = None


def set_demo_status_bar(on: bool) -> None:
    """Empty the status bar of this phone's own state for the length of a capture run.

    **The status bar is in every screenshot, and by default it is a photograph of somebody's phone**:
    notification icons, a network-speed readout, bluetooth, Do Not Disturb, a real battery
    percentage — none of which is the app, all of which ships to a store listing in every language.

    SystemUI's demo mode is the platform's own answer: a broadcast that tells the status bar to draw a
    fixed set of icons instead of the real ones. It is gated behind a global that is off by default,
    so this reads that global before writing it and [restore_device] puts back what it found.

    **HyperOS honours some of it and silently ignores the rest** (measured by an app built from this
    template, 2026-09): hiding notifications and pinning the battery level work; the charging bolt
    stays (and the cable has to be in for adb); the clock ignores the requested time but stops being
    live, which is what a multi-locale set wants anyway; and the signal and wifi icons disappear
    rather than pinning to full. Fewer icons than asked for is the safe direction for a listing — do
    not "fix" the ignored rows another way: the point is emptiness, not a fake phone.

    Like [set_dnd] this is **phone-wide**, so every caller's `off` belongs in a `finally` — and
    unlike Do Not Disturb, a demo status bar left behind looks like a broken phone.
    """
    global _DEMO_ALLOWED
    if on:
        _DEMO_ALLOWED = shell("settings get global sysui_demo_allowed").strip()
        shell("settings put global sysui_demo_allowed 1")
        if not shell_ok("am broadcast -a com.android.systemui.demo -e command enter"):
            # Tolerated rather than fatal, on [set_dnd]'s reasoning: an emulator or a ROM without
            # demo mode should cost the shots their clean status bar, not the whole matrix.
            print("  -- note: SystemUI demo mode unavailable, status bar will show real state")
            return
        for command in (
            "-e command notifications -e visible false",
            "-e command battery -e level 100 -e plugged false",
        ):
            shell(f"am broadcast -a com.android.systemui.demo {command}")
        settle(1.0)
        return
    # Exit unconditionally, even if `enter` was never reached: a previous crashed run is exactly the
    # state this has to be able to clear, and the broadcast is harmless when nothing is in demo mode.
    shell("am broadcast -a com.android.systemui.demo -e command exit")
    if _DEMO_ALLOWED in (None, "null", ""):
        # It was unset, so delete rather than write a 0 — `put` would leave a row behind that the
        # phone never had.
        shell("settings delete global sysui_demo_allowed")
    else:
        shell(f"settings put global sysui_demo_allowed {_DEMO_ALLOWED}")
    _DEMO_ALLOWED = None
    settle(0.5)


# The app language every wipe has to put back, since `pm clear` drops it. None is the device default.
_LOCALE: "str | None" = None


def set_locale(locale: str | None) -> None:
    """Pin the app's language, or hand it back to the device default.

    Per-app locales are system state keyed by package, which is why this is `cmd locale` and not the
    in-app Settings picker: the same mechanism the picker drives, reachable without eight taps.

    **It does not survive `pm clear`**, so the chosen locale is remembered here and re-applied by
    [wipe] — the one place this driver clears the app. Doing it there rather than at the top of each
    suite is what keeps the `empty` suite honest: its scenes wipe as their *first step*, so a locale
    applied only once per cell would shoot the setup wizard in English inside a Polish run.
    """
    if locale is not None:
        require_bcp47(locale)
    if api_level() < APP_LOCALES_MIN_API:
        if locale is None:
            return
        sys.exit(f"--locale needs per-app languages, API {APP_LOCALES_MIN_API}+; this is API {api_level()}")
    global _LOCALE
    _LOCALE = locale
    if locale is None:
        shell(f"cmd locale set-app-locales {PACKAGE} --locales")
    else:
        shell(f"cmd locale set-app-locales {PACKAGE} --locales {locale}")
    settle(1.5)


@dataclass(frozen=True)
class Rect:
    left: int
    top: int
    right: int
    bottom: int

    def intersects(self, other: "Rect") -> bool:
        return (
            self.left < other.right
            and other.left < self.right
            and self.top < other.bottom
            and other.top < self.bottom
        )

    def overlap(self, other: "Rect") -> "Rect | None":
        if not self.intersects(other):
            return None
        return Rect(
            max(self.left, other.left),
            max(self.top, other.top),
            min(self.right, other.right),
            min(self.bottom, other.bottom),
        )

    @property
    def area(self) -> int:
        return max(0, self.right - self.left) * max(0, self.bottom - self.top)

    def as_list(self) -> list[int]:
        return [self.left, self.top, self.right, self.bottom]


# Two spellings, and the older one matters: `id=` is API 34+, and below it the line reads
# `InsetsSource type=ITYPE_STATUS_BAR …`. With only the modern pattern the driver reads *no insets at
# all* at an API 33 floor — and a cell with no insets reports every scene clean, because nothing was
# checked. An app built from this template found that on its first run at its minSdk.
INSET_RE = re.compile(
    r"InsetsSource (?:id=\w+ )?type=(\w+) frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] visible=(\w+)"
)

# The pre-34 names for the three types this driver cares about. The cutout has one source per edge
# down there and one type up here, which is why several map to the same name — [read_insets] already
# keeps the largest of any repeated type.
LEGACY_INSET_TYPES = {
    "ITYPE_STATUS_BAR": "statusBars",
    "ITYPE_NAVIGATION_BAR": "navigationBars",
    "ITYPE_EXTRA_NAVIGATION_BAR": "navigationBars",
    "ITYPE_TOP_DISPLAY_CUTOUT": "displayCutout",
    "ITYPE_BOTTOM_DISPLAY_CUTOUT": "displayCutout",
    "ITYPE_LEFT_DISPLAY_CUTOUT": "displayCutout",
    "ITYPE_RIGHT_DISPLAY_CUTOUT": "displayCutout",
}


def read_insets() -> dict[str, Rect]:
    """The system inset rectangles for the *current* rotation, straight from the window manager.

    Several providers report the same type (the status bar has one per top window); they agree, so
    the largest is taken. An invisible source contributes nothing — a hidden navigation bar is not
    an area anything can be wrongly drawn under.
    """
    found: dict[str, Rect] = {}
    for match in INSET_RE.finditer(shell("dumpsys window displays")):
        kind, left, top, right, bottom, visible = match.groups()
        kind = LEGACY_INSET_TYPES.get(kind, kind)
        if kind not in INSET_TYPES or visible != "true":
            continue
        rect = Rect(int(left), int(top), int(right), int(bottom))
        if kind not in found or rect.area > found[kind].area:
            found[kind] = rect
    return found or read_bar_windows()


# API 26 prints no `InsetsSource` line, and API 29 prints them under names (`TYPE_TOP_BAR`) the map
# above does not know. The bars are still windows of their own (`NavigationBar0` from API 30 on), with
# a frame and a shown-or-not surface: the same rectangle and visibility, read one level down.
BAR_WINDOWS = {"StatusBar": "statusBars", "NavigationBar": "navigationBars"}
BAR_WINDOW_RE = re.compile(r"Window #\d+ Window\{[^}]* u0 (StatusBar|NavigationBar)\d*\}:")
MFRAME_RE = re.compile(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def read_bar_windows() -> dict[str, Rect]:
    found: dict[str, Rect] = {}
    for block in shell("dumpsys window windows").split("  Window #")[1:]:
        title = BAR_WINDOW_RE.match("Window #" + block)
        kind = BAR_WINDOWS.get(title.group(1)) if title else None
        frame = MFRAME_RE.search(block)
        if kind is None or frame is None or "Surface: shown=true" not in block:
            continue
        rect = Rect(*(int(v) for v in frame.groups()))
        if rect.area > 0:
            found[kind] = rect
    return found


# --------------------------------------------------------------------------------------------
# Reading the screen
# --------------------------------------------------------------------------------------------

NODE_RE = re.compile(r"<node ([^>]*?)/?>")
ATTR_RE = re.compile(r'([\w:-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass
class Node:
    bounds: Rect
    text: str
    desc: str
    cls: str
    package: str
    clickable: bool
    # Compose publishes `Modifier.selectable`'s state as the accessibility node's `isSelected`, and
    # the navigation bar is the only place this app uses it — so exactly one node in a top-level
    # dump carries it, and it is the current tab. Verified in a dump rather than assumed: the item
    # itself carries no text (the label is a child `TextView`), which is why [showing_home] compares
    # rectangles instead of reading it off the labelled node.
    selected: bool = False

    @property
    def label(self) -> str:
        return self.text or self.desc or self.cls.rsplit(".", 1)[-1]


def dump_ui() -> list[Node]:
    """Every visible node on screen, ours and the system's.

    `uiautomator dump` writes to the device and `exec-out cat` reads it back byte-for-byte; going
    through `adb shell cat` corrupts the XML on the way out.
    """
    for attempt in range(3):
        try:
            shell("uiautomator dump /sdcard/e2e-dump.xml")
            raw = adb("exec-out", "cat", "/sdcard/e2e-dump.xml", binary=True)
            xml = raw.decode("utf-8", "replace")
            if "<node" in xml:
                break
        except subprocess.CalledProcessError:
            pass
        settle(1.0)
    else:
        return []

    nodes: list[Node] = []
    for match in NODE_RE.finditer(xml):
        # **Unescape, because this reads XML with a regex and the labels are English prose.** The
        # dump writes `Backup &amp; restore`, so a needle spelled with a real ampersand —
        # `"Backup & restore"` — matches nothing at all, and a label like that tends to sit at the
        # head of many scenes. **A fully green Polish run could not see it**, because the Polish
        # labels carried no ampersand — a defect unreachable in one locale and fatal in the other,
        # which is why a locale run is not evidence about English, or the reverse.
        attrs = {name: html.unescape(value) for name, value in ATTR_RE.findall(match.group(1))}
        bounds_match = BOUNDS_RE.search(attrs.get("bounds", ""))
        if not bounds_match:
            continue
        left, top, right, bottom = (int(value) for value in bounds_match.groups())
        nodes.append(
            Node(
                bounds=Rect(left, top, right, bottom),
                text=attrs.get("text", ""),
                desc=attrs.get("content-desc", ""),
                cls=attrs.get("class", ""),
                package=attrs.get("package", ""),
                clickable=attrs.get("clickable") == "true",
                selected=attrs.get("selected") == "true",
            )
        )
    return nodes


def screen_signature(nodes: list[Node]) -> str:
    """What the app's screen looks like, as a string, for "did anything happen?" comparisons."""
    return "|".join(f"{n.label}{n.bounds.as_list()}" for n in nodes if n.package == PACKAGE)


# What each English needle means in the locale under capture, filled in by [resolve_needles] before
# the first tap and empty for an English run — where every lookup is the identity.
_TRANSLATED: dict[str, str] = {}


def find(
    nodes: list[Node],
    needle: str,
    *,
    text_only: bool = False,
    exact: bool = False,
) -> Node | None:
    """The smallest node whose text or description contains `needle`, case-insensitively.

    Smallest, because Compose reports a merged semantics node for a whole row as well as the leaf
    inside it, and the leaf is the one whose centre is unambiguously on the thing named.

    `text_only` drops content descriptions from the match, which is how a **label** is told apart
    from an **icon that carries the same words**. A FAB whose `contentDescription` reuses the same
    string as the row it opens — which is the right thing to do, rather than spending a second string
    in every language — is indistinguishable from that row to a plain needle, and the FAB is usually
    the smaller node, so the driver taps the button that is already open. The row has text and no
    description; the FAB has a description and no text. Structure, not copy.

    `exact` goes further and demands the node's **text be the needle entire** — no descriptions, no
    substrings. It exists for the five tab labels, where "contains" is not a strong enough claim: a
    tab label can sit *inside* another string on the same screen, and then the smallest-node rule
    picks the wrong one. A tab label is routinely a *substring* of the FAB description on the same
    screen once both are translated — Czech `Pozorování` inside `Zapsat pozorování`, say — and the
    FAB is usually the smaller node, so the driver taps the FAB and photographs the sheet it opened
    instead of the tab it was asked for. English is often clean here only by accident of grammar,
    which is why this is a bug you meet on the first non-English run. See [TAB_NEEDLES].

    **Every needle is translated here**, which is the one place it can be: `tap`, `return_to_home`
    and `showing_home` all arrive through this function, so a locale run needs no second table and
    no scene rewritten. See [resolve_needles].
    """
    needle = _TRANSLATED.get(needle, needle).casefold()

    def matches_label(node: Node) -> bool:
        if exact:
            return node.text.casefold() == needle
        return needle in node.text.casefold() or (
            not text_only and needle in node.desc.casefold()
        )

    matches = [
        node
        for node in nodes
        if node.package == PACKAGE and matches_label(node) and node.bounds.area > 0
    ]
    return min(matches, key=lambda node: node.bounds.area) if matches else None


# --------------------------------------------------------------------------------------------
# Driving
# --------------------------------------------------------------------------------------------


class StepFailed(Exception):
    pass


def wait_for_app(timeout: float = 12.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if PACKAGE in shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"):
            # Focus arrives before the first composition does, and a tap sent into the gap is
            # swallowed — the node is found in the dump and the tap lands on nothing. Long enough
            # to cover a cold start of the heaviest screen.
            settle(1.8)
            return
        settle(0.4)
    raise StepFailed("the app never took focus")


def relaunch() -> None:
    """A fresh start, so every scene walks from the same place.

    `force-stop` rather than plain `am start`: the back stack is saved state, so a warm start would
    reopen wherever the previous scene left off.

    **`force-stop` is not enough on its own, and the gap cost a whole cell.** It kills the process but
    leaves the task record, so Android may restore the saved-instance bundle on the next `am start` —
    the app comes back on whatever screen it was last on rather than at Home. One stray tap is then
    permanent: in the app this template came from, a reminder notification posted mid-run, drawn
    exactly where the driver was about to tap; the tap landed on the banner instead of the app, and
    every scene afterwards relaunched into the screen the notification opened and failed on a needle
    that was never wrong. `-S` force-stops, and `0x10008000` is `FLAG_ACTIVITY_CLEAR_TASK | NEW_TASK`, which
    drops the record the restore reads from. **A scene must not be able to inherit the last one's
    screen** — that is what makes 61 scenes independent rather than a sequence.

    **Everything above is what the intent *asks* for; [return_to_home] is what checks it.** Measured
    from a detail route and from a top-level tab that is not the root: both relaunches landed on the
    root with the tab bar up, so on that phone the flags do clear the restored Nav3 stack. That makes the step after this one a verification rather than a
    repair — which is worth one dump per scene, because the alternative is trusting an argument.
    """
    shell(f"am start -S -n {ACTIVITY} -f 0x10008000")
    wait_for_app()
    repin_rotation()  # `-S` is a force-stop, and a force-stop costs the rotation — see [repin_rotation]


# The two needles the isolation step is built on, both from the shell rather than from any screen.
# The bottom bar is drawn only while `onDetailScreen` is false (Navigation.kt), so finding a tab in
# it is exactly the question "is a top-level destination on screen?" — and [HOME_TAB] is the bar's
# first item, the one the back stack is rooted at. Naming them here rather than inline is what lets
# a locale run translate them with the rest of the table.
TAB_BAR = "Items"
HOME_TAB = "Items"

# The top-level destinations, named so [tap] can tell a tab from anything else it is asked to
# press. Two things follow from being on this list, and both are about a tab label being a *short*
# string that other strings can quote: the needle is matched exactly rather than by substring
# ([find]'s `exact`), and the tap is not believed until the tab it names reports itself selected
# ([showing_tab]). Kept as the English literals the scene table is written in — translation happens
# inside [find], one layer down.
#
# Exactness narrows the trap without closing it. Translation routinely makes a tab label collide
# with some other string on a screen — a two-word English label can become a one-word translation
# that is also a button somewhere — and where that happens, [showing_tab] turns it into a *failed
# scene* rather than a wrong screenshot. That is the division of labour: exactness is the fix, the
# assertion is what makes the next collision survivable.
TAB_NEEDLES = (HOME_TAB, "Settings")

# How many times [return_to_home] may press back before it gives up. Six covers the deepest route in
# the app (Settings → Licences → one licence, plus a dialog and an IME) with room to spare; it is a
# bound rather than a target, and hitting it is a failure and not a retry budget.
BACK_BOUND = 6


def showing_home(nodes: list[Node]) -> bool:
    """Is the *Home* tab the selected one, rather than merely some top-level tab?

    The distinction is the whole reason this exists. A restored back stack does not have to be a
    detail route — it can be another *tab*, which has the tab bar, passes every "are we at
    the shell?" check, and is still the wrong screen for `home`, `home-bottom` and every scene that
    taps *Edit* on a profile card.

    The selected navigation item carries no text of its own, so this is geometry: the one node in
    the dump with `selected="true"` is the current tab, and the *Home* label is inside it exactly
    when Home is that tab.
    """
    return showing_tab(nodes, HOME_TAB)


def showing_tab(nodes: list[Node], needle: str) -> bool:
    """Is the tab called `needle` the selected one?

    [showing_home]'s geometry, asked about any tab rather than only about the root — which is what
    turns a tab tap from "something on screen moved" into "the app is on that tab". Without it, a
    mis-tap that opens a sheet counts as movement, and the run reports every scene reached while
    some of them photographed the wrong screen entirely.

    Exact, because the whole point is to not be fooled by a label quoted inside another string —
    the same reason the tap itself is exact. See [TAB_NEEDLES].
    """
    label = find(nodes, needle, exact=True)
    if label is None:
        return False
    selected = next((node for node in nodes if node.package == PACKAGE and node.selected), None)
    return selected is not None and selected.bounds.intersects(label.bounds)


def return_to_home() -> None:
    """Put the app on the Home tab, or fail the scene saying so.

    **What it found, which is not what it was written to fix.** It was written on the reading that
    `am start -S -f 0x10008000` does not clear a restored Nav3 back stack. Checked directly — walk
    into a detail screen, relaunch; switch tab, relaunch — and **both came back on the root**. So
    the flags do their job, and a cell that relaunches into the same wrong screen over and over is
    better explained by a notification than by the stack: a reminder that re-arms at process start
    and is already past fires immediately, posting a fresh heads-up over *every* scene rather than
    poisoning one. [set_dnd] is the fix for that, and this is the check that says so — it
    prints when it has to correct anything, so a run that never prints is evidence about the
    relaunch and not merely the absence of a complaint.

    **`KEYCODE_BACK` alone is not the fix**, which is why this is bounded and checks first. Backing
    past Home exits to the launcher and makes every following scene worse, so the loop presses back
    only while the shell is *not* on screen, and stops the moment it is.

    Failing loudly is the other half. A driver that cannot find Home must not tap into whatever is
    open — that produces a screenshot of the wrong screen under the right name, which is worse than
    a skipped scene because it is evidence that looks like evidence.
    """
    for _ in range(BACK_BOUND + 1):
        nodes = dump_ui()
        if find(nodes, TAB_BAR) is not None:
            break
        back()
    else:
        visible = ", ".join(sorted({n.label for n in dump_ui() if n.package == PACKAGE and n.label})[:20])
        raise StepFailed(f"never reached a top-level tab in {BACK_BOUND} backs; on screen: {visible}")

    if not showing_home(nodes):
        # Only paid when it is actually owed. `tap` verifies by asking the screen whether anything
        # moved, and on the common path — already at Home — that is three taps and three dumps for
        # nothing, which is minutes across a four-cell matrix.
        print("     (restored off Home; corrected)")
        tap(HOME_TAB)


# How far [tap] will scroll looking for a needle, and how many dumps it takes before an unchanged
# screen is believed. The cap matches [swipe_to_end]'s for the same reason — the observation
# timeline holds a year of rows — and the floor is the composition grace the old fixed budget was
# really providing.
TAP_SCROLL_CAP = 16
MIN_TAP_TRIES = 4


def tap(needle: str, *, optional: bool = False, text_only: bool = False) -> None:
    # Retried rather than waited on a fixed delay: a screen that is still composing dumps as an
    # empty ComposeView, and how long that takes depends on the screen, not on the driver. An
    # *optional* tap is not retried — it is asking whether something is there, and four rounds of
    # waiting to be told "no" is most of the run time of the matrix.
    # **Scroll while the screen is still moving, rather than a fixed number of times.** A landscape
    # swipe covers 70%→32% of 1220px — about 464px, against roughly 1030px of a portrait screen — so
    # a budget of four tries reaches ~1856px sideways where it reaches ~4120px upright. A long
    # screen can be several thousand pixels in landscape, which is how three scenes once came back
    # unreachable for controls that were plainly reachable, while a scene aimed at the bottom of the
    # same screen scrolled clean past them. **A fixed budget is a portrait-shaped constant**, the same shape of bug
    # as the rotation a wipe used to cost, and it fails only where the viewport is short.
    #
    # The signature check is what keeps this from being slower: a screen that cannot scroll stops
    # changing after one swipe and gives up sooner than the old budget did. [MIN_TAP_TRIES] is the
    # floor underneath it, because a screen still composing dumps as an empty `ComposeView` and two
    # identical empty dumps must not be read as "nowhere left to go".
    # A tab is matched exactly and then checked, rather than matched loosely and hoped for. Both
    # halves are [TAB_NEEDLES]'s reason for existing.
    is_tab = needle in TAB_NEEDLES
    attempts = 1 if optional else TAP_SCROLL_CAP
    node = None
    previous = ""
    for attempt in range(attempts):
        nodes = dump_ui()
        node = find(nodes, needle, text_only=text_only, exact=is_tab)
        if node is not None:
            break
        if optional:
            settle(1.0)
            continue
        current = screen_signature(nodes)
        if attempt >= MIN_TAP_TRIES - 1 and current == previous:
            break
        previous = current
        swipe_up(nodes)
    if node is None:
        if optional:
            return
        visible = ", ".join(sorted({n.label for n in nodes if n.package == PACKAGE and n.label})[:25])
        raise StepFailed(f"no node matching {needle!r}; on screen: {visible}")
    x = (node.bounds.left + node.bounds.right) // 2
    y = (node.bounds.top + node.bounds.bottom) // 2
    # **`input touchscreen tap`, never bare `input tap`** — the source has to be named. On this phone
    # `input tap` began exiting 0 while delivering nothing at all: not flaky, dropped every time, on a
    # screen provably on and focused, while `input keyevent` and `input swipe` kept working. Proved by
    # A/B on one screen at one coordinate — bare `tap` never moved the selection, `touchscreen tap`
    # moved it every time. `input` picks a default source when none is given, and that inference is
    # what HyperOS stopped honouring; naming the source sidesteps the guess.
    #
    # The retry below stays regardless: exit status still proves nothing, so the screen is asked
    # instead — tap, look, tap again if nothing moved. Without it the first tap after a cold start
    # goes missing often enough to skip whole scenes, and a skipped scene reads like a clean one.
    before = screen_signature(nodes)
    for _ in range(3):
        shell(f"input touchscreen tap {x} {y}")
        settle(1.2)
        after = dump_ui()
        moved = screen_signature(after) != before
        if not is_tab:
            if moved:
                return
            continue
        # **"The screen moved" is not "the app is on that tab".** It was once the whole test, and a
        # sheet opening over Home satisfies it perfectly. Ask the navigation bar instead.
        if showing_tab(after, needle):
            return
        if moved:
            visible = ", ".join(sorted({n.label for n in after if n.package == PACKAGE and n.label})[:25])
            raise StepFailed(
                f"tapped {needle!r}, the screen moved, and that tab is not the selected one — "
                f"the needle named something else; on screen: {visible}",
            )
    if is_tab:
        raise StepFailed(f"tapped {needle!r} three times and never landed on that tab")
    # Fell through: three taps and nothing moved. That is a finding about the scene, not about the
    # phone — a control that does nothing — so it is left to the caller's next step to fail on.


def back() -> None:
    shell("input keyevent KEYCODE_BACK")
    settle(0.8)


def screen_size() -> tuple[int, int]:
    """The display's size **as it is currently rotated**, which `wm size` does not report.

    `wm size` gives the *physical* size and stays portrait-first through every rotation, so the
    orientation has to come from somewhere else — and `user_rotation` is the wrong somewhere. That
    key records what the display was last **pinned** to, and it means nothing while
    `accelerometer_rotation` is 1: it has been read as `1` against a live 1220x2712 portrait
    screen, left over from an earlier run's pinning that `--restore` had handed back.

    [swipe_up] built a 2712-wide swipe out of it and sent every gesture to x=1356, off the right
    edge of a 1220px display. Nothing scrolled, so [tap] read the unchanged screen as "nowhere left
    to go" and the seed walk died on the wizard's *Continue* — before the matrix captured a single
    cell. **The same shape as the two rotation bugs already recorded here**: a value that describes
    what the driver asked for, trusted as a description of what the phone is doing.

    `dumpsys window displays` reports `cur=WxH` already rotated, which is the one reading that
    cannot disagree with the screen. It is also why this does not consult `mRotation`: a rotation
    still has to be turned into a width and a height, and `cur=` is both, measured.
    """
    match = re.search(r"cur=(\d+)x(\d+)", shell("dumpsys window displays"))
    if match:
        return int(match.group(1)), int(match.group(2))
    # Portrait-first from `wm size` is a poor guess, but the caller needs a midpoint rather than the
    # truth, and guessing beats dividing by nothing.
    match = re.search(r"(\d+)x(\d+)", shell("wm size").split(":")[-1])
    return (int(match.group(1)), int(match.group(2))) if match else (1220, 2712)


def content_box(nodes: list[Node]) -> "Rect | None":
    """The rectangle the app's own nodes occupy — **which is the popup when one is open**.

    A `DropdownMenu` is its own window, and a dump taken while one is open contains *only* that
    window's nodes: with a dropdown open, the whole dump sits inside the menu's own rectangle with
    nothing of the screen behind it. So the app's bounding box *is* the menu when one is open, and
    on an ordinary screen it is the whole display — measured, not assumed: two full-screen scenes
    box to exactly `(0, 0, 2712, 1220)` in landscape, which is why scrolling by this box leaves
    every full-screen scene swiping precisely where it always did.
    """
    ours = [node for node in nodes if node.package == PACKAGE]
    if not ours:
        return None
    return Rect(
        min(node.bounds.left for node in ours),
        min(node.bounds.top for node in ours),
        max(node.bounds.right for node in ours),
        max(node.bounds.bottom for node in ours),
    )


def swipe_up(nodes: "list[Node] | None" = None) -> None:
    """Scroll a list towards its end — where the row nearest the navigation bar is.

    **Swipes inside [content_box], not across the middle of the screen.** A `DropdownMenu` a couple
    of hundred dp wide, anchored under an app-bar control, scrolls internally when it is long. A
    swipe at the *screen's* own midpoint is x=1356 on a 2712px-wide landscape display and lands
    **outside** such a menu entirely: the gesture goes to the window behind it, the menu never
    moves, and [tap] reads the unchanged screen as "nowhere left to go".

    That failure mode is why this is worth knowing rather than a detail — it passes in both portrait
    configurations, where the midpoint happens to fall inside the menu, and fails in both landscape
    ones. **A swipe aimed at the screen is a claim that the screen is what scrolls.**

    `nodes` is the dump the caller already holds, so the common path costs no extra dump.
    """
    box = content_box(nodes if nodes is not None else dump_ui())
    if box is None:
        width, height = screen_size()
        box = Rect(0, 0, width, height)
    x = (box.left + box.right) // 2
    span = box.bottom - box.top
    # **Both ends of the swipe have to land inside the scrollable**, which is a smaller target than
    # it looks in landscape: a top-level tab there has a switcher above it and a navigation bar
    # below, leaving content between roughly 26% and 76% of a 1220px screen. Swiping from 75% —
    # comfortably inside a portrait screen — landed on the bottom bar's edge and scrolled nothing,
    # and a swipe that does nothing turns a scroll-to-end scene into a screenshot of the top of the
    # list wearing the name of the bottom.
    shell(f"input swipe {x} {box.top + int(span * 0.70)} {x} {box.top + int(span * 0.32)} 300")
    settle(1.0)


def swipe_to_end() -> None:
    """Scroll until the screen stops changing — the position the last row comes to rest in.

    This is the one that matters. Mid-scroll, a list *should* run under the navigation bar; that is
    what edge-to-edge looks like. The defect is a list whose last row still sits under the bar once
    it has nowhere left to go, and only the end of the scroll can tell the two apart.
    """
    # Each swipe now covers less of the screen, and a long list can hold a year of rows,
    # so the cap is generous: stopping early would report the middle of a list as its end.
    previous = ""
    # Dumped once up front and then reused: the signature dump each round is also the one the next
    # swipe aims by, so scrolling inside [content_box] costs a single extra dump for the whole loop.
    nodes = dump_ui()
    for attempt in range(16):
        swipe_up(nodes)
        nodes = dump_ui()
        current = screen_signature(nodes)
        if current == previous:
            return
        previous = current


def scroller_of(nodes: list[Node], node: Node) -> Rect:
    """The smallest scrolling container around `node` — the viewport its rows move through."""
    around = [
        other.bounds
        for other in nodes
        if other.package == PACKAGE
        and "ScrollView" in other.cls
        and other.bounds.top <= node.bounds.top
        and node.bounds.bottom <= other.bounds.bottom
    ]
    if not around:
        raise StepFailed(f"{node.label!r} is not inside anything that scrolls")
    return min(around, key=lambda rect: rect.area)


def touch_slop_px() -> int:
    """Android's 8dp touch slop in pixels: the distance a finger travels before a list starts to move."""
    match = re.findall(r"density: (\d+)", shell("wm density"))
    return round(8 * int(match[-1]) / 160) if match else 24


def drag(x: int, from_y: int, by: int) -> None:
    """Move the content under the finger by `by` pixels, down for positive."""
    # The first touch slop of a drag never reaches the list (measured: a 100px drag moved it 76),
    # so the finger travels that much further. Slow, so the list does not fling past the release.
    slop = touch_slop_px()
    to_y = from_y + by + (slop if by > 0 else -slop)
    shell(f"input swipe {x} {from_y} {x} {to_y} 1500")
    settle(1.0)


ALIGN_TOLERANCE_PX = 12


def align_top(needle: str, *, optional: bool = False) -> None:
    """Scroll until `needle`'s top sits flush under the app bar, or as near as the list's end allows.

    `swipe_end` alone leaves wherever the last row lands, and that depends on how long the copy above
    it runs in each language — so a scene framed by the end of a list starts mid-row in one locale and
    on a heading in another.
    """
    previous = None
    stuck = 0
    for attempt in range(6):
        nodes = dump_ui()
        node = find(nodes, needle, exact=True)
        if node is None:
            if optional:
                return
            raise StepFailed(f"no node reading exactly {needle!r} to align")
        box = scroller_of(nodes, node)
        # A node cut by the viewport's edge reports only its visible part, so its real top is unknown
        # until it has been brought fully into view.
        clipped = attempt == 0 and node.bounds.top <= box.top
        offset = (box.bottom - box.top) // 4 if clipped else box.top - node.bounds.top
        if not clipped and abs(offset) <= ALIGN_TOLERANCE_PX:
            return
        # Twice, because the first gesture after a cold start can go missing; a list that ignores
        # two drags is at its end, and as near as it can come is the answer.
        stuck = stuck + 1 if node.bounds == previous else 0
        if stuck == 2:
            return
        previous = node.bounds
        middle = (box.top + box.bottom) // 2
        reach = (box.bottom - box.top) // 2 - touch_slop_px() - 1
        drag((box.left + box.right) // 2, middle, max(-reach, min(offset, reach)))


def tap_field(index: str) -> None:
    """Tap the *n*-th editable field on screen, top to bottom. The IME scenes' way in.

    **A needle cannot name a field that has no text.** A hero input with no label and no
    placeholder — where the value is the whole control — has no string to aim at, and the nearest
    matching string is usually a help line *underneath* it, which is a plain `Text`. Tapping that
    focuses nothing, and the scene shoots a form with no keyboard from then on.

    This is the trap worth knowing about, because **a scene with no IME still produces a perfectly
    good screenshot** — nothing fails, and the check silently stops checking. Address a field by
    index (`tap_field`) rather than by a string that happens to sit near it.

    Structure rather than copy is the fix, and it is the right one twice over: an `EditText` is an
    `EditText` in every language, so this is the one needle a locale run cannot break.
    """
    wanted = int(index)
    fields = sorted(
        (node for node in dump_ui() if node.package == PACKAGE and node.cls.endswith("EditText")),
        key=lambda node: (node.bounds.top, node.bounds.left),
    )
    if wanted >= len(fields):
        raise StepFailed(f"asked for editable field {wanted}, found {len(fields)}")
    node = fields[wanted]
    shell(f"input touchscreen tap {(node.bounds.left + node.bounds.right) // 2} "
          f"{(node.bounds.top + node.bounds.bottom) // 2}")
    settle(1.2)


def type_text(value: str) -> None:
    shell(f"input text {value}")
    settle(1.2)


def wipe() -> None:
    """Back to a first run: no preferences, no database, so the setup wizard shows.

    Destructive, and deliberately only reachable from the `empty` suite — the `full` suite runs
    against seeded sample data and a wipe in the middle of it would quietly capture empty screens
    under populated names.
    """
    # **The shipped install is not ours to clear.** Raising here rather than checking at every call
    # site is deliberate: `wipe` has several routes into it — the `empty` suite's own first step,
    # [reset_to_seeded], [ensure_seed] — and a guard on two of them is a guard on none.
    if not WIPEABLE:
        raise StepFailed(
            f"refusing to `pm clear {PACKAGE}`: that is the shipped install, and this would wipe "
            "data nothing here can restore. Run with --no-reseed, or point the driver at debug",
        )
    global _SEEDED
    _SEEDED = None
    shell(f"pm clear {PACKAGE}")
    settle(1.0)
    # Before the launch, not after: `pm clear` drops the per-app locale, and re-applying it to a
    # package that is not running costs nothing, where doing it afterwards restarts the activity.
    if _LOCALE is not None:
        shell(f"cmd locale set-app-locales {PACKAGE} --locales {_LOCALE}")
    shell(f"am start -n {ACTIVITY}")
    wait_for_app()
    # Re-pin the rotation the wipe just cost us. Only the rotation: the navigation mode is a global
    # and survives, and re-writing it would buy another 3s settle per wiping scene for nothing.
    repin_rotation()


def reset_to_seeded() -> None:
    """Wipe, skip the wizard, and seed the debug sample data — a known state, not just a clean one.

    The inverse of [wipe]: the `empty` suite ends with the install wiped, so without this a matrix
    run leaves the phone with no sample data and no media directories at all.

    It matters more than tidiness for repeat runs. Anything the seed stages for a scene to answer — a
    prompt, a pending state — is consumed the first time a scene answers it, so without a reseed a
    second capture cell would find it already gone and quietly shoot the wrong screen.
    """
    wipe()
    for label in SEED_WALK:
        # `tap` scrolls when it cannot find its target, which is what reaches *Add the sample data*:
        # the sample-data block is the debug-only tail of a scrolling Settings column.
        tap(label)
    settle(1.0)

    # Last, because `pm grant` can kill the process and everything above is a tap sequence that
    # would die with it. Every caller relaunches before its next screenshot, so the app picks the
    # permission up cleanly.
    #
    # `pm clear` revokes POST_NOTIFICATIONS, and a denied notification permission is *visible*: a
    # screen that explains it grows a "notifications are off" banner, and its button's label can
    # collide with a needle a later scene taps — which then launches the system's notification
    # settings, so the next scene photographs the Settings app and the one after fails with an empty
    # node list, the foreground no longer being this package. Granting it back is also the honest
    # state: a seeded install stands in for an app
    # in use, not for one whose permission was just refused. The `empty` suite is the deliberate
    # exception and keeps the denied state, because there it is the truth of a first run.
    if api_level() >= POST_NOTIFICATIONS_MIN_API:
        shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
        settle(0.5)
    global _SEEDED
    _SEEDED = ""


# Which seed the phone is currently carrying: "" for the plain sample data, a variant name for one
# of [SeedVariantReceiver]'s, and None for "unknown" — which is what a wipe leaves and what the
# start of every cell asserts, so the first scene of a cell always reseeds exactly as it always did.
_SEEDED: "str | None" = None

SEED_RECEIVER = f"{PACKAGE}/{project.NAMESPACE}.debug.SeedReceiver"


def seed_variant(variant: str) -> None:
    """Add a variant on top of the sample data, through the debug build's own receiver.

    **The default seed is never changed, and that is the constraint rather than a nicety.** Sixty-one
    scenes, the before/after comparison and the Play listing screenshots all rest on it, so a third
    row or a repurposed series would move evidence that is already banked. A variant is additive
    and asked for by name.

    `-f 0x00000020` is `FLAG_INCLUDE_STOPPED_PACKAGES`: a package is in the stopped state after
    `pm clear` until something launches it, and a broadcast to a stopped package is dropped in
    silence — which would look exactly like a variant that seeded nothing.

    The receiver reports through the broadcast result, so this can fail on what actually happened
    rather than on a timeout: `result=0` and the data string it set, or a loud failure naming the
    exception. See [SeedVariantReceiver] for why it is a broadcast at all.
    """
    global _SEEDED
    output = shell(f"am broadcast -n {SEED_RECEIVER} -f 0x00000020 --es variant {variant}")
    if "result=0" not in output:
        raise StepFailed(f"seeding variant {variant!r} failed: {output.strip()[:200]}")
    _SEEDED = variant
    settle(1.0)


# Whether every scene runs with a notification trying to post over it. Off unless `--live-notice`
# asks for it, because it changes what every screenshot contains.
#
# **This reproduces the hazard that wrecks a driver run and is impossible to diagnose from the
# artifacts.** A heads-up notification posts over the screen exactly where the driver is about to
# tap. The tap lands on the banner, `AUTO_CANCEL` clears it, and every scene after that relaunches
# somewhere unexpected and fails on a needle that was never wrong. `set_dnd` is the fix — and this
# flag is what makes a clean run mean anything, because DND suppressing a banner nobody posted is
# not evidence of DND.
#
# Wire it to whatever your debug build can make post on demand, the way `seed_variant` is wired.
_LIVE_NOTICE = False


def arm_live_notice() -> None:
    """Make one notification post over the scene about to be captured. **Unwired in the template.**

    There is nothing generic to put here: what can be made to post on demand is whatever your debug
    build exposes, so this is a seam rather than an implementation. Wire it like [seed_variant] — a
    broadcast to a receiver in `app/src/debug/` that re-arms a slot just behind `now`, checked on
    the broadcast result so a silent no-op cannot pass for an armed one.

    It raises rather than returning quietly because a `--live-notice` run that arms nothing produces
    a full green report of scenes no notification ever covered — evidence for a claim the run never
    tested, which is worse than the red.
    """
    raise StepFailed(
        "--live-notice is an unwired seam in the template: implement arm_live_notice() against "
        "your debug build's receiver before using the flag"
    )


def invalidate_seed() -> None:
    """Forget what the phone is carrying, so the next [ensure_seed] reseeds whatever it asks for."""
    global _SEEDED
    _SEEDED = None


def ensure_seed(variant: str) -> None:
    """Put the phone on the seed this scene asked for, reseeding only when it is not already there.

    Reseeding costs a wipe, a wizard and the sample data — the better part of a minute — so scenes
    are sorted by the seed they want and this is a no-op for every scene but the first of each
    group. Variant scenes therefore cost one reseed each per cell rather than one per scene.
    """
    if _SEEDED == variant:
        return
    print(f"  -- seeding{f' + {variant}' if variant else ''}")
    reset_to_seeded()
    if variant:
        seed_variant(variant)


DATABASE = f"/data/data/{PACKAGE}/databases/{project.DATABASE_FILE}"


def fake_schema_mismatch() -> None:
    """Make the database on disk claim a schema this build does not know, and relaunch.

    `SchemaMismatchScreen` is the one screen that lives outside a `Scaffold` — it pads itself with
    `safeDrawingPadding()` — so it is exactly the outlier this matrix has to look at, and there is
    no way to it through the UI. The wipe guard reads SQLite's own `user_version`, four big-endian
    bytes at offset 60 of the file header, so writing a version nothing recognises is enough.

    The file is copied aside first and put back by [restore_schema_version]. Nothing here consents
    to the wipe: the screen is captured and backed out of.
    """
    shell(f"am force-stop {PACKAGE}")
    settle(0.5)
    run_as = f"run-as {PACKAGE}"
    # Only ever back up the *good* file. This runs once per cell of the matrix, and an unguarded
    # copy on the second cell would file the already-corrupted database as the thing to restore —
    # after which the restore faithfully puts the corruption back and the app never opens again.
    shell(f'{run_as} sh -c "[ -f databases/{project.DATABASE_FILE}.e2e-backup ] || cp databases/{project.DATABASE_FILE} databases/{project.DATABASE_FILE}.e2e-backup"')
    # `user_version` is a big-endian 32-bit field, and every version this project will ever have
    # fits in its last byte — so only byte 63 has to change, to 99. Which is convenient,
    # because 99 is ASCII 'c': `printf c` writes exactly the byte wanted with no escaping to get
    # wrong through two layers of shell.
    shell(f'{run_as} sh -c "printf c > databases/e2e-version.bin"')
    shell(f"{run_as} dd if=databases/e2e-version.bin of=databases/{project.DATABASE_FILE} bs=1 seek=63 conv=notrunc")
    shell(f"am start -n {ACTIVITY}")
    wait_for_app()
    settle(1.5)


def restore_schema_version() -> None:
    shell(f"am force-stop {PACKAGE}")
    settle(0.5)
    run_as = f"run-as {PACKAGE}"
    shell(f"{run_as} cp databases/{project.DATABASE_FILE}.e2e-backup databases/{project.DATABASE_FILE}")
    shell(f"{run_as} rm -f databases/{project.DATABASE_FILE}.e2e-backup databases/e2e-version.bin")


STEP_RUNNERS = {
    "tap": lambda arg: tap(arg),
    "tap?": lambda arg: tap(arg, optional=True),
    # Tap a **label**, never an icon's description — see [find]. The one place it is needed so far
    # is the sheet behind the "+", whose row and whose FAB deliberately say the same words.
    "tap_text": lambda arg: tap(arg, text_only=True),
    "back": lambda arg: back(),
    "swipe_up": lambda arg: swipe_up(),
    "swipe_end": lambda arg: swipe_to_end(),
    "align_top": align_top,
    "align_top?": lambda arg: align_top(arg, optional=True),
    "tap_field": tap_field,
    "type": type_text,
    "wait": lambda arg: settle(float(arg)),
    "wipe": lambda arg: wipe(),
    "fake_schema_mismatch": lambda arg: fake_schema_mismatch(),
}


@dataclass(frozen=True)
class Scene:
    """One screen worth capturing, and the taps that reach it from a cold start.

    `family` is how the review is organised: screens sharing chrome fail identically, so
    they are reviewed as a group against a representative, and a member that differs from its
    representative is itself the finding.
    """

    name: str
    family: str
    steps: list[tuple[str, str]] = field(default_factory=list)
    note: str = ""
    # "full" runs against seeded sample data; "empty" runs against a wiped install, which is the
    # only way to see the first-run wizard and the only honest way to see an empty list.
    suite: str = "full"
    # For an app whose sample data stages a **one-shot prompt** — a dialog waiting on top of every
    # launch until someone answers it, where answering is permanent. A scene that photographs the
    # prompt sets this: it runs first in each cell, is never backed out of or retried, and every
    # other scene dismisses the prompt with [PROMPT_DISMISS] before walking. The template stages none.
    keeps_seeded_prompt: bool = False
    # The seed this scene needs, "" being the plain sample data. Anything else is a variant name the
    # debug build's SeedVariantReceiver knows, added *on top of* the sample data — the default seed
    # is never edited, because every scene and the listing screenshots rest on it. Scenes are grouped
    # by this, so a variant costs one reseed per cell rather than one per scene.
    seed: str = ""


# ---------------------------------------------------------------------------------------------
# THE SCENE TABLE IS YOURS. Everything above this line is the engine; everything below describes
# *this* app, and it is the only part you rewrite when you build a different one.
#
# A scene is a name, a shape, and a list of steps to reach it from a fresh launch. The shape names
# what the assertion expects to find under the system bars — see the `SHAPES` table for what each
# one means. Steps are `(verb, argument)` pairs run in order; `STEP_RUNNERS` lists the verbs.
#
# Three habits that come from a driver that got this wrong repeatedly:
#
#   1. **Name the thing you mean.** `find` is a case-insensitive substring match, so tapping `"Open"`
#      will happily match a permission banner's *Open* button and launch the system Settings app —
#      after which every later scene screenshots Settings and fails on a needle that was never
#      wrong. Tap a row by its own text.
#   2. **Every scene starts from a relaunch, so scenes are independent** — except for anything
#      stored in a preference, which survives it. A scene that needs some state says so in its own
#      steps and pays the taps, rather than depending on the scene before it.
#   3. **Prefer a route that survives landscape.** A button below the fold on a 1220px-tall viewport
#      is a route that works in three configurations out of four.
# ---------------------------------------------------------------------------------------------

# The order matters only in that each scene starts from a relaunch, so they are independent.
SCENES = [
    # --- the screen with no Scaffold at all, reachable only by lying to the schema guard --------
    #
    # `SchemaMismatchScreen` is the one screen outside a `Scaffold` — it pads itself — so it is
    # exactly the outlier this matrix exists to look at, and there is no route to it through the UI.
    Scene(
        "schema-mismatch",
        "chrome-free",
        [("fake_schema_mismatch", "")],
        suite="mismatch",
    ),
    Scene(
        "schema-mismatch-bottom",
        "chrome-free",
        [("fake_schema_mismatch", ""), ("swipe_end", "")],
        suite="mismatch",
    ),

    # --- a wiped install: the empty states, which no seeded run can show ------------------------
    Scene("items-empty", "tab", [("wipe", "")], suite="empty"),

    # --- the tabs ------------------------------------------------------------------------------
    Scene("items", "tab", []),
    Scene("items-bottom", "tab", [("swipe_end", "")]),
    Scene("settings", "tab", [("tap", "Settings")]),
    Scene("settings-bottom", "tab", [("tap", "Settings"), ("swipe_end", "")]),

    # --- detail screens: no bottom bar, so the navigation-bar inset is the app's own problem ----
    Scene("item-detail", "detail", [("tap", "First item")]),
    Scene("backup", "detail", [("tap", "Settings"), ("tap", "Backup and restore")]),
    Scene("support", "detail", [("tap", "Settings"), ("tap", "Help and feedback")]),
    Scene("licences", "detail", [("tap", "Settings"), ("tap", "Open-source licences")]),
    Scene("licences-bottom", "detail", [("tap", "Settings"), ("tap", "Open-source licences"), ("swipe_end", "")]),

    # --- forms: the keyboard is an inset too, and this is where `imePadding` is proved ----------
    Scene("item-editor", "form", [("tap", "Add an item")]),
    Scene("item-editor-bottom", "form", [("tap", "Add an item"), ("swipe_end", "")]),
    Scene(
        "item-editor-keyboard",
        "form",
        # Focus the first field and wait for the keyboard to finish animating in. This is the scene
        # that catches a window being *panned* instead of resized — the top bar, Save button and all,
        # slides off the top of the screen and only a screenshot shows it.
        [("tap", "Add an item"), ("tap_field", "0"), ("wait", "1.5")],
    ),
]


# --------------------------------------------------------------------------------------------
# Needles in another language
# --------------------------------------------------------------------------------------------

RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

# The debug build's own resource overlay. It is not decoration: `9k` moved the debug section's
# thirteen strings out of `app/src/main/res` so they would stop being translated into nine languages,
# and two of their labels are needles this driver taps by name. The driver only ever drives the debug
# build, so its English table is main *plus* this — see [load_strings].
DEBUG_RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "debug" / "res"

# **The needles that belong to the driver rather than to a scene**, and the reason they are named
# here: [scene_needles] can only see what is in [SCENES], so a literal buried in a function is one a
# locale run does not translate — and the first Polish run failed on exactly that, in
# [reset_to_seeded], which is the step every cell starts with.
# The label that dismisses a seeded one-shot prompt before a scene can be walked. The template
# stages none; leave it as None until yours does, and see `Scene.keeps_seeded_prompt`.
PROMPT_DISMISS: "str | None" = None

# The walk from a wiped install to the seeded fixture: into Settings and through the debug-only
# sample-data block at the end of it. Extend this as your app grows a first-run wizard in front of it.
SEED_WALK = ("Settings", "Seed sample data")


def require_bcp47(tag: str) -> None:
    """Refuse the resource spelling of a locale, wherever it is handed in.

    `cmd locale` takes a BCP-47 tag and does not validate it: `pt-rBR` is accepted and comes back
    from `get-app-locales` as `[rbr]`, so the app runs in English while the needle table is
    Portuguese — a run that reports on a language it never displayed. That is worth a sentence
    rather than the `values-pt-rrBR` file-not-found it would otherwise become.
    """
    if "-r" in tag:
        raise SystemExit(
            f"--locale takes a BCP-47 tag, not a resource qualifier: {tag!r} is how "
            f"`values-{tag}/` is spelled. Pass {tag.replace('-r', '-', 1)!r}.",
        )


def resource_qualifier(tag: str) -> str:
    """The `values-` qualifier for a BCP-47 tag: `pl` → `pl`, `pt-BR` → `pt-rBR`.

    Two spellings of one locale, which `TranslationTest.qualifier` already converts between on the
    Kotlin side and this driver did not — `--locale pt-BR` died looking for `values-pt-BR`, and the
    obvious workaround was worse — see [require_bcp47], which is why this is never handed one.
    """
    require_bcp47(tag)
    language, _, region = tag.partition("-")
    return f"{language}-r{region}" if region else language


def load_strings(locale: str | None) -> dict[str, str]:
    """`name -> value` for `values/` or `values-<qualifier>/`, keyed by BCP-47 tag.

    The escapes matter and the markup does not: `uiautomator` reports what is *on screen*, so `\\'`
    is an apostrophe by the time a node carries it, while `&amp;` has already been resolved by the
    XML parser. `itertext` rather than `.text` so a value wrapped in inline markup still comes back
    whole.
    """
    # `en` is the base, not a qualified variant: the English strings live in `values/` and there is
    # no `values-en/` to find. It is still a shipped locale in `locales_config.xml`, so `--locale en`
    # is a thing to ask for — it pins the app to English rather than inheriting a Polish phone — and
    # without this line it died on a missing file before the first tap.
    def read(path: Path) -> dict[str, str]:
        found: dict[str, str] = {}
        for element in ElementTree.parse(path).getroot().findall("string"):
            name = element.get("name")
            if name is None:
                continue
            found[name] = "".join(element.itertext()).replace("\\'", "'").replace('\\"', '"')
        return found

    base = locale is None or locale == "en"
    if not base:
        return read(RES_DIR / f"values-{resource_qualifier(locale)}" / "strings.xml")
    # The debug overlay layered on top, because the debug build is the only one this driver runs and
    # `values/` alone no longer describes it: `9k` moved the debug section's strings to
    # `src/debug/res` and out of the translation gate. Without this the two labels `seed()` and the
    # `reminders-sheet` scenes tap would be unknown resources, and [resolve_needles] would fall to
    # its substring case and pick whichever *other* string happened to contain the words.
    return read(RES_DIR / "values" / "strings.xml") | read(DEBUG_RES_DIR / "values" / "strings.xml")


def scene_needles() -> set[str]:
    """Every string this driver will look for on screen, across all suites."""
    needles = {TAB_BAR, HOME_TAB, *SEED_WALK}
    if PROMPT_DISMISS is not None:
        needles.add(PROMPT_DISMISS)
    for scene in SCENES:
        needles.update(arg for kind, arg in scene.steps if kind in ("tap", "tap?", "tap_text", "align_top", "align_top?"))
    return needles


def resolve_needles(locale: str) -> None:
    """Translate the whole needle table **once, before the first tap** (ADR-0004).

    `--locale` has switched the app for a while; what has never worked is everything after it,
    because the needles are English string literals and a needle like `tap("Settings")` matches nothing
    in Polish. ADR-0004 is what makes the fix small — every user-visible string is a resource in
    every locale, and `PolishTranslationTest` keeps them level — so a needle can resolve *through
    the resource name*.

    Three cases, in order:

    - **an exact match on an English value** → the locale's value for that resource;
    - **the unique resource whose value *contains* the needle** → the locale's *whole* value, which
      still matches because `find` is a substring match against the node's label. That is what
      carries deliberate fragments like `"What you noticed"`;
    - **no match at all** → the literal, unchanged. Sample data is identical in every locale, so
      a row's own name wants exactly this. It is also where a typo lands, which is
      why they are listed rather than passed over.

    **Resolved up front rather than at tap time**, because a run is ~40 s per scene per cell: the
    difference between failing early and failing late is the difference between a minute and an
    evening. An **ambiguous** needle — one whose candidates disagree about the translation — is
    fatal here for the same reason. Picking one would be a coin flip, and the fix is to lengthen the
    needle until it names one thing, which improves the English table too.
    """
    english = load_strings(None)
    translated = load_strings(locale)

    resolved: dict[str, str] = {}
    literals: list[str] = []
    ambiguous: list[str] = []
    by_substring = 0

    for needle in sorted(scene_needles()):
        folded = needle.casefold()
        names = [name for name, value in english.items() if value.casefold() == folded]
        exact = bool(names)
        if not names:
            names = [name for name, value in english.items() if folded in value.casefold()]
        # A resource the target locale does not carry is deliberately invariant — `app_name` is
        # `translatable="false"` on purpose (ADR-0004) — so its English text is already the right
        # needle and its absence is not a gap.
        candidates = {translated[name] for name in names if name in translated}
        if not candidates:
            literals.append(needle)
        elif len(candidates) > 1:
            ambiguous.append(f"    {needle!r} could be any of {sorted(candidates)}")
        else:
            resolved[needle] = candidates.pop()
            by_substring += 0 if exact else 1

    if ambiguous:
        raise SystemExit(
            f"{len(ambiguous)} needle(s) do not name one string in {locale!r}:\n"
            + "\n".join(ambiguous)
            + "\n  Lengthen the needle until it does — a coin flip here is a scene that shoots the "
            "wrong screen.",
        )

    _TRANSLATED.update(resolved)
    print(
        f"needles: {len(resolved)} translated to {locale} "
        f"({by_substring} by substring), {len(literals)} left literal: {', '.join(literals)}",
    )


# --------------------------------------------------------------------------------------------
# The check
# --------------------------------------------------------------------------------------------

# A node the app owns that carries a label or takes a tap. Backgrounds are not semantics nodes, so
# anything reported here is content, and content is what must not sit under a system bar.
def content_nodes(nodes: list[Node]) -> list[Node]:
    return [
        node
        for node in nodes
        if node.package == PACKAGE
        and node.bounds.area > 0
        and (node.text or node.desc or node.clickable)
    ]


def check(nodes: list[Node], insets: dict[str, Rect]) -> list[dict]:
    """Every app-owned label or control that lands inside a system inset, in two tiers.

    The tiers exist because the rectangles Compose publishes to accessibility are **touch** bounds,
    not drawn bounds: `Modifier.minimumInteractiveComponentSize` grows a small control's hit area to
    48dp, and the result is not clipped to the scroll viewport it lives in. So an unlabelled
    clickable node poking into the navigation bar is routinely an artifact of that expansion, while
    a node carrying *text* is something a person can actually read in the wrong place.

    - `drawn`: the node has a label, so there is something legible under a system bar.
    - `touch`: the node has none, so this is a hit area, and only its at-rest position tells you
      whether it is a defect — which is what the `-bottom` scenes are for.
    """
    findings = []
    for node in content_nodes(nodes):
        for kind, rect in insets.items():
            overlap = node.bounds.overlap(rect)
            if overlap is None or overlap.area == 0:
                continue
            # A modal scrim swallows a system bar whole — covering everything is what makes it
            # modal, and the dismiss target is *supposed* to reach under the bars. Content never
            # does: a row that runs under the navigation bar overlaps part of it, not all of it.
            # Matched on that shape rather than on the scrim's label, which is a translated
            # Material string ("Close sheet") and would stop matching in Polish.
            if overlap == rect:
                continue
            findings.append(
                {
                    "tier": "drawn" if (node.text or node.desc) else "touch",
                    "inset": kind,
                    "label": node.label[:60],
                    "class": node.cls.rsplit(".", 1)[-1],
                    "clickable": node.clickable,
                    "bounds": node.bounds.as_list(),
                    "overlap": overlap.as_list(),
                    "overlap_px": overlap.area,
                }
            )
    return findings


def reach_scene(scene: Scene) -> str | None:
    """Walk to one scene from a cold start. Returns the failure message, or None if it was reached.

    Split out of [run_scene] so `screenshots.py` can reuse the walk without the inset checking that
    follows it. The tap sequences in [SCENES] are this file's expensive asset — a second copy of
    them in another script is a copy that drifts, and the drift is silent because both scripts would
    still produce a screenshot of *something*.
    """
    relaunch()
    # After the relaunch, not before: `am start -S` force-stops, and a force-stop cancels every
    # alarm the app has placed. Arming into a live process is also the honest order — the banner
    # arrives *during* the scene, which is the case the driver has to survive.
    #
    # **`full` only**, for the same reason [return_to_home] is: `empty` opens on a wiped install and
    # `mismatch` replaces the whole screen before anything is captured. Arming there would fail every
    # scene in both suites on a seed that is deliberately absent.
    if _LIVE_NOTICE and scene.suite == "full":
        arm_live_notice()
    try:
        if not scene.keeps_seeded_prompt:
            # The prompt is hosted above the shell and so composes a beat after it; asking before
            # that is asking too early, and an optional tap does not wait around to be told no.
            settle(1.2)
            if PROMPT_DISMISS is not None:
                tap(PROMPT_DISMISS, optional=True)
            # After the prompt, never before: the prompt sits over whatever route was restored, and
            # closing it first means [return_to_home] reads the screen underneath rather than a
            # dialog's window.
            #
            # **`full` only, and that is a rule about which suites can be lost rather than a
            # shortcut.** Every `empty` scene opens with its own `wipe`, which clears saved state
            # outright and lands on the wizard — where there is no tab bar and this would fail every
            # scene in the suite. `mismatch` replaces the whole screen with a chrome-free one before
            # anything is captured. Both isolate themselves; only `full` inherits.
            if scene.suite == "full":
                return_to_home()
        # `keeps_seeded_prompt` scenes get neither step, deliberately. **Back can be a destructive
        # answer to a dialog** — where a dialog's dismiss handler is also its "no", a driver that
        # backed its way home would destroy the state the seed set up, for this cell and every cell
        # after it. Scenes that must not be backed out of run first in each cell, directly after the
        # `pm clear` inside [reset_to_seeded], so there is no saved state to restore anyway.
        for kind, arg in scene.steps:
            STEP_RUNNERS[kind](arg)
    except StepFailed as error:
        return str(error)
    return None


def run_scene(scene: Scene, config: Config, out_dir: Path, retries: int = 0) -> dict:
    # **A retry is only honest where the scene puts the phone back itself.** [reach_scene] opens a
    # `full` scene with [return_to_home] and an `empty` one with its own [wipe], so a second attempt
    # starts from a known screen however the first one died — that recovery is what makes retrying a
    # missed tap different from retrying a broken screen.
    #
    # `keeps_seeded_prompt` scenes get no retries, and the rule is about correctness rather than
    # caution. They skip both of those steps deliberately (see [reach_scene]), so attempt two would
    # start wherever attempt one stopped; worse, a half-finished attempt may already have answered
    # the prompt, which is permanent — so the retry would shoot a stale screen and record it clean.
    # That is the exact failure retrying is supposed to avoid, arriving by the other road.
    allowed = 1 + (0 if scene.keeps_seeded_prompt else max(retries, 0))
    for attempt in range(1, allowed + 1):
        error = reach_scene(scene)
        if error is None:
            break
        if attempt < allowed:
            print(f"  {scene.name:28s} retrying ({attempt}/{allowed - 1})  {error[:70]}")
    if error is not None:
        result = {"scene": scene.name, "family": scene.family, "error": error}
        if attempt > 1:
            result["attempts"] = attempt
        return result

    settle(0.8)
    shot = out_dir / f"{scene.name}.png"
    shot.write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    insets = read_insets()
    # A missing bar is the driver or the device failing to show it; without this the scene passes for
    # having been checked against less than the screen, as CI's bar-less emulators did for weeks.
    missing = [kind for kind in ("statusBars", "navigationBars") if kind not in insets]
    if missing:
        error = f"no {' or '.join(missing)} inset could be read"
        return {"scene": scene.name, "family": scene.family, "error": error}
    findings = check(dump_ui(), insets)
    result = {
        "scene": scene.name,
        "family": scene.family,
        "note": scene.note,
        "screenshot": str(shot.relative_to(out_dir.parent)),
        "insets": {kind: rect.as_list() for kind, rect in insets.items()},
        "findings": findings,
    }
    # Only when it is news. A report where every scene carries `"attempts": 1` is a report where the
    # one scene that needed two is no easier to find than it was without the field.
    if attempt > 1:
        result["attempts"] = attempt
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="directory for screenshots and the report")
    parser.add_argument("--config", help="comma-separated config names, default all")
    parser.add_argument("--scene", help="comma-separated scene names, default all in the suite")
    parser.add_argument(
        "--suite",
        default="full",
        choices=["full", "empty", "mismatch"],
        help=(
            "'full' needs the debug sample data seeded; 'empty' WIPES the app to reach the wizard; "
            "'mismatch' corrupts the database's version field and puts it back afterwards"
        ),
    )
    parser.add_argument(
        "--locale",
        help=(
            "BCP-47 tag pinned as the app's language, e.g. pl. The scene needles are translated "
            "through the resource names before the first tap; default leaves the device alone"
        ),
    )
    parser.add_argument(
        "--live-notice",
        action="store_true",
        help=(
            "arm a notification to post over every scene — the case DND exists for. Not wired in "
            "the template: implement [arm_live_notice] for your app first. Debug build only"
        ),
    )
    parser.add_argument("--restore", action="store_true", help="undo the pinned rotation and nav mode")
    parser.add_argument(
        "--build",
        choices=["debug", "release"],
        default="debug",
        help="which install to drive. 'release' is the shipped applicationId and is never wiped",
    )
    parser.add_argument(
        "--retry-unreached",
        type=int,
        default=0,
        metavar="N",
        help=(
            "re-walk a scene up to N more times when the driver fails to reach it. For the nightly, "
            "where a missed tap on a slow emulator is the common red and re-running the whole cell "
            "costs half an hour to repair one scene. Default 0: on real hardware a scene that "
            "cannot be reached is a finding, and the run should say so the first time"
        ),
    )
    parser.add_argument(
        "--assert-clean",
        action="store_true",
        help=(
            "exit non-zero if any scene drew a control under a system inset, or if any scene was "
            "SKIPPED by a driver error. For CI, where nobody reads the report unless it is red"
        ),
    )
    args = parser.parse_args()

    global _LIVE_NOTICE
    _LIVE_NOTICE = args.live_notice

    if args.restore:
        # **Both installs, not the one `--build` happens to name.** `--restore` is "hand the phone
        # back", and the person typing it is not thinking about which build the last run drove.
        # Clearing only the selected one is how a locale stays pinned on the other, reported as
        # handed back — which is what happened to the shipped install of an app built from this.
        for build in ("debug", "release"):
            select_build(build)
            set_locale(None)
        restore_device()
        print("rotation, navigation mode, locale, Do Not Disturb and the status bar handed back to the phone")
        return 0
    select_build(args.build)

    if not args.out:
        parser.error("--out is required unless --restore")

    configs = CONFIGS
    if args.config:
        wanted = set(args.config.split(","))
        configs = [config for config in CONFIGS if config.name in wanted]
    configs, unsupported = usable_configs(configs)
    for config, unreachable in unsupported:
        print(f"-- {config.name}: skipped, {unreachable.reason}")
    if not configs:
        # Every requested cell was skipped. With one job per configuration — which is how CI fans
        # this matrix out — that is the *expected* outcome for a leg like API 26's gesture cells,
        # and failing it would make a red leg mean nothing. But only when the device could actually
        # answer: an unreadable navigation mode is not a capability, it is a question that did not
        # come back, and a green there would be a lie about a cell nobody ran.
        if all(unreachable.readable for _, unreachable in unsupported):
            print(f"-- nothing to run: every requested configuration is one this device cannot enter")
            if args.out:
                args.out.mkdir(parents=True, exist_ok=True)
                (args.out / "report.json").write_text(
                    json.dumps(
                        {
                            "configs": [],
                            "device": {
                                "family": device_family(),
                                "api": api_level(),
                                "model": shell("getprop ro.product.model").strip(),
                                "skipped_configs": {c.name: u.reason for c, u in unsupported},
                            },
                        },
                        indent=2,
                    )
                )
            return 0
        parser.error(f"no requested configuration is reachable on API {api_level()}")
    # The `mismatch` suite patches a version byte into the database file. An app with no database
    # has no such file and no refusal screen, so the suite can only fail — refused here by name
    # rather than left to die on its first `run-as cp` (scripts/project.py, HAS_DATABASE).
    if args.suite == "mismatch" and not project.HAS_DATABASE:
        print("edge-to-edge: no database in this project, so there is no schema mismatch to fake.", file=sys.stderr)
        return 2
    scenes = [scene for scene in SCENES if scene.suite == args.suite]
    if args.scene:
        wanted = set(args.scene.split(","))
        scenes = [scene for scene in SCENES if scene.name in wanted]

    # Before anything touches the phone: a needle that names two strings, or none, is worth knowing
    # about now rather than 40 seconds into a cell (see [resolve_needles]).
    if args.locale:
        resolve_needles(args.locale)
        set_locale(args.locale)

    report = {
        "configs": [],
        # Downloaded from a CI artifact three weeks later, a report that does not say what it ran on
        # is a list of rectangles nobody can act on.
        "device": {
            "family": device_family(),
            "api": api_level(),
            "model": shell("getprop ro.product.model").strip(),
            "skipped_configs": {config.name: unreachable.reason for config, unreachable in unsupported},
        },
    }
    # Before the run rather than with the first cell's screenshots: the report is written in a
    # `finally`, so a run that dies on its first scene would otherwise lose its own error to a
    # missing directory.
    args.out.mkdir(parents=True, exist_ok=True)
    report_path = args.out / f"report-{args.suite}.json"
    set_dnd(True)
    try:
        run_matrix(report, configs, scenes, args.out, args.suite, report_path, args.retry_unreached)
    finally:
        # First in the block, because it is the one piece of cleanup that is about the *phone*
        # rather than about this run's output.
        set_dnd(False)
        # The mismatch suite leaves a deliberately corrupted database behind, so putting it back is
        # the one piece of cleanup that must happen even when a scene throws — a run that crashed
        # halfway is exactly when an app left unopenable is hardest to explain.
        if args.suite == "mismatch":
            restore_schema_version()
        # Write whatever was reached, always. A full matrix is four cells over about two hours on a
        # phone somebody also owns, and writing the report only at the end means an interruption at
        # 95% produces *nothing* — the screenshots survive on disk but the inset findings, which are
        # the point, do not. Runs have had to be stopped mid-cell, and before this the completed cells
        # were lost with them. `run_matrix` also writes after each config, so the file is
        # complete for every cell that finished.
        write_report(report_path, report)
    print(f"\nreport: {report_path}")
    # **Green after a retry is not the same result as green**, and that difference is the whole risk
    # of retrying at all: a regression that only reproduces one run in three stops reddening
    # anything the moment a retry can paper over it. Every scene that needed a second attempt is
    # named here on every run, asserting or not, so "clean" never quietly absorbs "flaky" — the same
    # reason a SKIPPED scene fails the assert rather than counting as clean.
    flaky = [
        (cell["config"], scene["scene"], scene["attempts"])
        for cell in report["configs"]
        for scene in cell["scenes"]
        if scene.get("attempts", 1) > 1
    ]
    for config_name, scene_name, attempts in flaky:
        print(f"flaky: {config_name}/{scene_name} reached on attempt {attempts}")
    if args.assert_clean:
        drawn, broken = 0, 0
        for cell in report["configs"]:
            for scene in cell["scenes"]:
                if "error" in scene:
                    broken += 1
                else:
                    drawn += sum(1 for hit in scene["findings"] if hit["tier"] == "drawn")
        # `drawn` is the defect; `touch` is a target smaller than the guideline inside an inset,
        # which is a judgement call and deliberately not a build breaker. A SKIPPED scene fails too:
        # a driver that could not reach a screen has not shown it to be clean.
        if drawn or broken:
            print(f"FAILED: {drawn} control(s) drawn under a system inset, {broken} scene(s) unreached")
            return 1
        print("clean: nothing drawn under a system inset")
    return 0


def write_report(report_path: Path, report: dict) -> None:
    """Merge this invocation's cells into whatever the file already holds, keyed by scene name.

    **A partial re-shoot must not delete the run it is repairing.** The report was written per
    invocation, so re-running three scenes replaced a whole matrix with those three — which happened
    to a locale run that had to be rebuilt from the directory by hand, and then again to a matrix
    with seven cells to redo after a driver fix, against 285 that stood.

    Replaced by name and never removed, so the merge cannot lose a cell. The cost of that choice is
    that a *renamed* scene leaves its old entry behind — the directory of screenshots is the truth,
    and a reader comparing the two will find the orphan rather than a silently shortened run.
    """
    merged: dict = {"configs": []}
    if report_path.exists():
        try:
            merged = json.loads(report_path.read_text())
        except json.JSONDecodeError:
            # A run killed mid-write leaves a truncated file, and refusing to start because of it
            # would be the wrong way round: this invocation's cells are the ones in hand.
            merged = {"configs": []}
    by_name = {config["config"]: config for config in merged.setdefault("configs", [])}
    for config in report["configs"]:
        existing = by_name.get(config["config"])
        if existing is None:
            merged["configs"].append(config)
            by_name[config["config"]] = config
            continue
        scenes = {scene["scene"]: scene for scene in existing.get("scenes", [])}
        for scene in config.get("scenes", []):
            scenes[scene["scene"]] = scene
        existing.update({key: value for key, value in config.items() if key != "scenes"})
        existing["scenes"] = list(scenes.values())
    report_path.write_text(json.dumps(merged, indent=2))


def run_matrix(
    report: dict,
    configs: list[Config],
    scenes: list[Scene],
    out: Path,
    suite: str,
    report_path: Path | None = None,
    retries: int = 0,
) -> None:
    # `keeps_seeded_prompt` scenes go first, exactly as they do in `screenshots.py`, and for the same
    # reason: every other scene opens by dismissing the prompt, and dismissing it is permanent. In
    # declared order a scene twenty places ahead of the prompt's own would consume it, and the shot
    # would be a plain screen under a dialog's name. Sorting is stable, so every other scene keeps
    # the order it is written in — which the inset findings are read against.
    #
    # Grouped by seed first, so a variant costs one reseed per cell instead of one per scene, and
    # every default-seed scene runs before any variant touches the install. "" sorts before every
    # variant name, which is what puts them in that order.
    scenes = sorted(scenes, key=lambda scene: (scene.seed, not scene.keeps_seeded_prompt))
    for config in configs:
        # Seed *before* pinning the config, never after. Sorting fixes a seeded prompt in the first
        # cell only — answering it is permanent, so cell 1 consumes it and cells 2-4 would shoot a
        # stale screen however they are ordered. But the seed
        # starts with a `pm clear`, and a wipe costs the rotation (see [wipe]), so seeding after
        # `apply_config` silently unpins every landscape cell. `full` only: `empty` wipes on purpose
        # to reach the wizard, and `mismatch` manages its own database surgery.
        if suite == "full":
            # Invalidated rather than compared: a cell must reseed even when the last one left the
            # right variant on the phone, because answering a seeded prompt is permanent and only a
            # fresh seed brings it back. [ensure_seed] then does the work, and asking for the
            # first scene's seed rather than the plain one keeps a variant-only run to one reseed.
            invalidate_seed()
            ensure_seed(scenes[0].seed if scenes else "")
        apply_config(config)
        out_dir = out / config.name
        out_dir.mkdir(parents=True, exist_ok=True)
        # The app has to be in front before the insets mean anything: `user_rotation` only rotates
        # an app that permits it, and the launcher is portrait-locked, so reading them with the
        # launcher on screen reports portrait geometry for a landscape cell. Each scene re-reads
        # them for itself; this is only so the header is not a lie.
        relaunch()
        insets = read_insets()
        print(f"\n=== {config.name}  insets={ {k: v.as_list() for k, v in insets.items()} }")
        results = []
        for scene in scenes:
            # A no-op for every scene but the first of each seed group — see [ensure_seed]. A wipe
            # costs the rotation, which is why it may run here at all: [wipe] re-pins what
            # `apply_config` set, so a mid-cell reseed cannot silently turn a landscape cell into a
            # second portrait one.
            if suite == "full":
                ensure_seed(scene.seed)
            result = run_scene(scene, config, out_dir, retries)
            results.append(result)
            again = f"  (attempt {result['attempts']})" if result.get("attempts", 1) > 1 else ""
            if "error" in result:
                print(f"  {scene.name:28s} SKIPPED  {result['error'][:90]}{again}")
            else:
                hits = result["findings"]
                drawn = [hit for hit in hits if hit["tier"] == "drawn"]
                touch = [hit for hit in hits if hit["tier"] == "touch"]
                mark = "clean" if not hits else f"drawn={len(drawn)} touch={len(touch)} " + str(
                    sorted({hit["inset"] for hit in hits})
                )
                print(f"  {scene.name:28s} {mark}{again}")
        report["configs"].append(
            {
                "config": config.name,
                "insets": {kind: rect.as_list() for kind, rect in insets.items()},
                "scenes": results,
            }
        )
        # Land each cell as it finishes rather than banking four of them against a clean exit.
        if report_path is not None:
            write_report(report_path, report)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as exc:
        # `CalledProcessError.__str__` is the command and the exit status and nothing else, so the
        # device's own explanation — the part that says *why* — is captured and then thrown away.
        # That is how the first nightly matrix arrived: two red cells reporting `exit status 1` and
        # `exit status 255`, neither carrying the one line that would have named the cause.
        detail = (exc.stderr or b"").decode("utf-8", "replace").strip()
        if detail:
            print(f"\n-- the device said: {detail}", file=sys.stderr)
        raise
