#!/usr/bin/env python3
"""The notes gate: the release being cut must have Play release notes of its own.

Run by CI on every pull request, and worth running by hand before opening one:

    python3 scripts/notes-gate.py
    python3 scripts/notes-gate.py --report   # what does this branch owe?
    python3 scripts/notes-gate.py --pending  # will the release this branch feeds have notes?

The rule: **the version Play is told about is the version the notes describe.** `play-metadata.py`
takes the newest `### x.y.z` under `## Release notes` in docs/store-listing.md, and nothing else
consults it. So when release-please bumps `versionName` and the notes stay where they were, the
mismatch is silent and the *previous* release's text ships attached to the new build.

That is not hypothetical. In the app this template was extracted from, 1.9.0 reached production
carrying 1.8.0's notes — text that predated four features users could now see. Play does not
allow release notes to be edited on a live production release, so the correction could not be made
where it was needed; it had to wait for the next upload. A gate is cheaper than that.

Deliberately a gate rather than a test, for the same reason the translation gate is one: it has to
fail on release-please's PR, which is the one moment the two versions are allowed to disagree and the
last moment the disagreement can still be fixed for free.

**Notes may run ahead of `versionName`; only behind it is a failure.** Writing `### 0.3.0` while the
tree still says 0.2.0 is an ordinary branch doing the work before release-please's bump lands, and it
is the only way to satisfy this gate without committing to release-please's own PR branch - which the
action force-pushes over on the next push to main, taking a hand-written note with it. Nothing can
ship the wrong text in that window: the internal-track publish uploads no notes at all, and the
production workflow is manual, approved, and runs against a released tag where the two agree again.
What stays fatal is the direction that actually shipped 1.9.0 describing 1.8.0 - notes *behind* the
build.

Satisfying it when nothing owner-visible changed is one edit, not a rewrite. 1.8.0 is the precedent
already in the document: rename the heading and say why the nine bodies stand unchanged. The gate
reads the heading, so that decision stays deliberate instead of being made by default.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app/build.gradle.kts"
LISTING = ROOT / "docs" / "store-listing.md"

# Anchored on release-please's own marker rather than on `versionName` alone: that comment is what
# the bump is applied to, so if it ever moves this gate should fail loudly rather than read some
# other line that happens to look similar.
VERSION_NAME = re.compile(r'versionName\s*=\s*"(?P<version>\d+\.\d+\.\d+)"\s*//\s*x-release-please-version')
VERSION_HEADING = re.compile(r"^###\s+(?P<version>\d+\.\d+\.\d+)")


def whatsnew():
    """Import play-whatsnew.py, whose filename is not a valid module name.

    Reused rather than reimplemented: it already knows how to find the Release notes section, which
    locales the AAB actually ships, and Play's 500-character limit. A second copy of that would be a
    second thing to keep true.
    """
    path = Path(__file__).parent / "play-whatsnew.py"
    spec = importlib.util.spec_from_file_location("play_whatsnew", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["play_whatsnew"] = module
    spec.loader.exec_module(module)
    return module


def version_name() -> str:
    match = VERSION_NAME.search(GRADLE.read_text(encoding="utf-8"))
    if not match:
        sys.exit(
            f"no `versionName = \"x.y.z\" // x-release-please-version` in {GRADLE.relative_to(ROOT)}.\n"
            "release-please writes that line; without it neither the bump nor this gate works."
        )
    return match.group("version")


def order(version: str) -> tuple[int, ...]:
    """`x.y.z` as a comparable tuple, so "ahead" and "behind" are decidable rather than "different".

    Only the three numbers: release-please is configured `release-type: simple` with no pre-release
    tags, and both regexes above already refuse anything else.
    """
    return tuple(int(part) for part in version.split("."))


def notes_versions(text: str) -> list[str]:
    """Every `### x.y.z` under `## Release notes`, in document order (newest last)."""
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.startswith("## Release notes")), None)
    if start is None:
        sys.exit(f"no '## Release notes' section in {LISTING.relative_to(ROOT)}")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    return [m.group("version") for line in lines[start:end] if (m := VERSION_HEADING.match(line))]


# --pending: the same rule, one step earlier.
#
# The gate above can only fail on release-please's PR, because that is the first place versionName
# carries the new number - and by then the fix is a separate docs PR, one extra PR per release. --pending
# predicts the number instead, from the same commits release-please will read, so the notes can be
# written on the branch that brings the change. It is what Claude Code's push hook runs
# (.claude/settings.json, through scripts/push-notes-hook.py); nothing in CI calls it.

MANIFEST = ".release-please-manifest.json"
CONFIG = ROOT / "release-please-config.json"
CONVENTIONAL = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?(?P<bang>!)?: ")
BREAKING_FOOTER = re.compile(r"^BREAKING[ -]CHANGE: ", re.MULTILINE)
# The types release-please's default changelog shows. Every other type is hidden, and a stretch of
# only hidden types proposes no release at all - which is why a docs-only branch never owes notes.
PATCH_TYPES = {"fix", "perf", "revert", "deps"}
# Distinct from the 1 that sys.exit(message) and an uncaught exception both produce, so the push hook
# can block on "notes owed" and merely warn when this script itself is broken.
OWED = 2


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True, text=True).stdout


def messages(*revs: str) -> list[str]:
    """Full commit messages for a rev range. Bodies too, because a BREAKING CHANGE footer lives there."""
    return [m.strip() for m in git("log", "--format=%B%x1e", *revs).split("\x1e") if m.strip()]


def bump(message: str) -> int:
    """0 none, 1 patch, 2 minor, 3 breaking - release-please's reading of one commit."""
    match = CONVENTIONAL.match(message)
    if not match:
        return 0
    if match["bang"] or BREAKING_FOOTER.search(message):
        return 3
    if match["type"] == "feat":
        return 2
    return 1 if match["type"] in PATCH_TYPES else 0


def next_version(current: str, level: int) -> str | None:
    """release-please's default versioning strategy, including its two pre-1.0 softenings.

    Read from the config rather than assumed, because they are per-repo choices: below 1.0,
    `bump-minor-pre-major` turns a breaking change into a minor, and `bump-patch-for-minor-pre-major`
    turns a `feat` into a patch. Neither ever applies to the other's case.
    """
    if level == 0:
        return None
    major, minor, patch = order(current)
    package = json.loads(CONFIG.read_text(encoding="utf-8"))["packages"]["."]
    pre_major = major == 0
    if level == 3 and not (pre_major and package.get("bump-minor-pre-major")):
        return f"{major + 1}.0.0"
    if level == 3 or (level == 2 and not (pre_major and package.get("bump-patch-for-minor-pre-major"))):
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def pending(base: str) -> int:
    listing = LISTING.relative_to(ROOT).as_posix()
    # A repository fresh from bootstrap.py has no remote branch until its first push, and nothing has
    # been released to compare against - so nothing can be owed yet.
    if subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--verify", "--quiet", base], capture_output=True).returncode:
        return 0
    # The release commit is the last one to touch the manifest - release-please's own record, so no
    # tag has to have been fetched and no subject line has to be parsed.
    release = git("log", "-n1", "--format=%H", base, "--", MANIFEST).strip()
    if not release:
        sys.exit(f"no commit on {base} touches {MANIFEST}; cannot tell where the last release was")
    current = json.loads(git("show", f"{base}:{MANIFEST}"))["."]

    # What main already holds since that release, plus what this branch adds. --cherry-pick drops a
    # branch commit whose patch main already has: rebase merging gives every merged commit a new hash,
    # so a branch cut before the release would otherwise count work that has already shipped.
    commits = messages(f"{release}..{base}") + messages(
        "--cherry-pick", "--right-only", "--no-merges", f"{base}...HEAD"
    )
    level = max((bump(m) for m in commits), default=0)
    upcoming = next_version(current, level)
    if upcoming is None:
        return 0

    # Either side counts: notes already merged to main arrive with the rebase even if this branch
    # predates them. Read from commits, not the working tree - a push sends commits.
    written = set(notes_versions(git("show", f"HEAD:{listing}"))) | set(
        notes_versions(git("show", f"{base}:{listing}"))
    )
    if upcoming in written:
        return 0

    releasable = "\n".join(f"  - {m.splitlines()[0]}" for m in commits if bump(m))
    print(
        f"Release notes owed. Once this branch merges, release-please will propose {upcoming} (from\n"
        f"{current}), and {listing} has no `### {upcoming}` under Release notes - not on this branch,\n"
        f"not on {base}. Its release PR would fail the notes gate. The commits it will count:\n"
        f"{releasable}\n\n"
        f"Write `### {upcoming}` on this branch and commit it before pushing: a fenced note per shipped\n"
        f"locale, English first, the rest translated from it per docs/translator-brief.md. If nothing\n"
        f"above is owner-visible, move the newest heading to {upcoming} and say why the bodies stand\n"
        f"unchanged instead (1.8.0 is the worked example).",
        file=sys.stderr,
    )
    return OWED


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", action="store_true", help="print the state and always exit 0")
    parser.add_argument(
        "--pending",
        nargs="?",
        const="origin/main",
        metavar="BASE",
        help=f"predict release-please's next version from BASE + this branch; exit {OWED} if its notes are unwritten",
    )
    args = parser.parse_args()
    if args.pending:
        return pending(args.pending)

    shipping = version_name()
    text = LISTING.read_text(encoding="utf-8")
    versions = notes_versions(text)
    if not versions:
        sys.exit(f"no '### <version>' subsection under Release notes in {LISTING.relative_to(ROOT)}")
    module = whatsnew()
    module.require_order(versions)
    newest = versions[-1]

    # Ahead is the pre-bump window and is allowed; behind is the failure this gate exists for. See
    # this module's docstring for why the asymmetry is safe rather than convenient.
    ahead = order(newest) > order(shipping)

    if newest != shipping and not ahead:
        message = (
            f"versionName is {shipping}, but the newest release notes are for {newest}.\n\n"
            f"play-metadata.py uploads the newest notes it finds, so {shipping} would ship {newest}'s\n"
            f"text — which is exactly how 1.9.0 reached production describing 1.8.0. Play will not let\n"
            f"you edit notes on a live release, so this is the last cheap moment to fix it.\n\n"
            f"Add `### {shipping}` to the Release notes section of {LISTING.relative_to(ROOT)}, after\n"
            f"`### {newest}`, with a fenced note per shipped locale.\n\n"
            f"If nothing owner-visible changed, that is a rename, not a rewrite: move the heading to\n"
            f"{shipping} and say why the bodies stand unchanged. 1.8.0 is the worked example."
        )
        if args.report:
            print(f"OWED: {message}")
            return 0
        sys.exit(message)

    # Now prove the newest note set is actually usable — in the ahead case too, because a note that
    # cannot ship is worth finding on the branch that wrote it rather than on the release PR.
    # play-whatsnew already enforces both halves, so failing here means the notes exist but could
    # not ship.
    _, notes = module.parse(text, newest)
    shipped = module.shipped_locales()
    missing = sorted(shipped - notes.keys())
    if missing:
        sys.exit(
            f"{newest} has notes for {len(notes)} locales but the app ships {len(shipped)}.\n"
            f"Missing: {', '.join(missing)}.\n"
            "A language the build carries and the listing does not falls back to English in the store."
        )
    over = {locale: len(body) for locale, body in notes.items() if len(body) > module.LIMIT}
    if over:
        detail = ", ".join(f"{locale} {n}/{module.LIMIT}" for locale, n in sorted(over.items()))
        sys.exit(f"{newest} has notes over Play's limit: {detail}.\nPlay truncates rather than warns.")

    longest = max(len(body) for body in notes.values())
    if ahead:
        print(
            f"notes gate: {newest} is written and waiting, ahead of versionName {shipping} — "
            "release-please's bump is what closes the gap."
        )
    print(f"notes gate: {newest} has {len(notes)} locales, longest {longest}/{module.LIMIT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
