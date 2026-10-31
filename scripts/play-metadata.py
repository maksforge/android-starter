#!/usr/bin/env python3
"""Build fastlane's metadata tree from `docs/store-listing.md`, for the production upload.

`supply` reads a directory tree — one file per field per locale — where this repository keeps its
listing copy in one reviewable markdown document. Rather than maintain both, this **generates the
tree** at upload time and throws it away afterwards. `store-listing.md` stays the only authored copy,
so a listing change is still one diff in one file, reviewed in a PR like anything else.

    fastlane/metadata/android/pl-PL/title.txt
                                    short_description.txt
                                    full_description.txt
                                    changelogs/<versionCode>.txt
                                    images/phoneScreenshots/1_home.png …
                                    images/featureGraphic.png

⚠️ **Changelogs are named by versionCode, not by semver.** `406.txt`, not `1.9.0.txt` — supply keys
release notes to the build they belong to, and a name Play cannot match is silently ignored rather
than rejected. The code comes from `git rev-list --count HEAD`, the same source `app/build.gradle.kts`
derives it from; pass `--version-code` when generating for a build that is not this checkout's HEAD.

Screenshots are copied from `art/play-screenshots/`, whose filenames carry both their order and their
locale — `1_home-pl.png`. The locale suffix is stripped on the way in, because supply already knows
the language from the directory it is putting the file in.

Feature graphics are copied the same way: `art/play-feature-graphic.png` into the default language
and `art/play-feature-graphic-<tag>.png` into that tag's. Play shows a language with no graphic of its
own the default's, so only translated ones are worth a file. It is here at all because a graphic that
only the Console holds goes stale unnoticed: an app built from this template had a layout fix sit in
the repository for five weeks while Play served the old file.

No file ends in a newline: supply uploads each verbatim and Play counts the newline against the
limit, so a 500-character release note goes up as 501 and the whole release is refused.

⚠ **A blank field is written as no file at all, never as an empty one.** An empty file is a value:
supply reads it and asks Play to store `""`, and Play refuses the whole edit - *"This app has no short
description (promotional text) for language pl-PL"*, measured by a validate-only promotion of an
app built from this template. A missing file leaves that field as Play already has it.

⚠ **Omitting the file is not enough on its own, and `--strict` is the part that protects Play.**
supply 2.240.1 fetches the listing for *every locale directory* - an empty one when Play has never
had that locale - fills in whichever `.txt` files it finds, and saves it regardless. A locale's
directory always exists, because its changelog lives in it. So a locale Play has never had, with its
descriptions omitted, still arrives as a new listing with no short description, and is refused the
same way. Two things keep that from reaching Play, one per path:

- **The run that uploads the listing** passes `--strict`, which refuses an incomplete locale here,
  before supply starts.
- **Every other run** passes supply `--skip_upload_metadata`, so it never saves a listing at all and
  an incomplete locale is harmless. Those runs only warn, because a release must not stop over copy
  it is not uploading.

"Incomplete" means one rule however it is spelled: a field whose fenced block is blank, a field whose
heading is missing, or a shipped language with no section here at all.

Usage:
    python3 scripts/play-metadata.py --out fastlane/metadata/android
    python3 scripts/play-metadata.py --out DIR --version-code 406
    python3 scripts/play-metadata.py --out DIR --strict   # the listing-upload run

Exits non-zero when a field is over Play's limit, and - with `--strict` - when any shipped language's
listing is incomplete. Release-note completeness is `play-whatsnew.py`'s, on every path, and is not
relaxed by anything here.
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LISTING = ROOT / "docs" / "store-listing.md"
SCREENSHOTS = ROOT / "art" / "play-screenshots"
FEATURE_GRAPHIC = ROOT / "art" / "play-feature-graphic.png"
DEFAULT_LOCALE = "en-US"

# Reuse the release-note parser rather than growing a second one — same document, same fenced-block
# shape, and the locale table must not be allowed to disagree between the two.
_SPEC = importlib.util.spec_from_file_location("play_whatsnew", Path(__file__).parent / "play-whatsnew.py")
whatsnew = importlib.util.module_from_spec(_SPEC)
sys.modules["play_whatsnew"] = whatsnew
_SPEC.loader.exec_module(whatsnew)

LOCALES = whatsnew.LOCALES

# Play's own limits. Each is enforced because Play truncates or rejects at upload, hours after the
# person who wrote the copy has moved on.
LIMITS = {"title": 30, "short_description": 80, "full_description": 4000}

# The `###` heading each field is written under, in store-listing.md.
FIELDS = {
    "App name": "title",
    "Short description": "short_description",
    "Full description": "full_description",
}

# The locale tag a screenshot filename carries (see screenshots.py's locale_tag) -> Play's code.
APP_TAG = {
    "en": "en-US",
    "pl": "pl-PL",
    "cs": "cs-CZ",
    "de": "de-DE",
    "es": "es-ES",
    "fr": "fr-FR",
    "it": "it-IT",
    "pt-BR": "pt-BR",
    "uk": "uk",
}

LANGUAGE_HEADING = re.compile(r"^##\s+(?P<name>[^(\n]+?)(?:\s*\(.*\))?\s*$")
FIELD_HEADING = re.compile(r"^###\s+(?P<field>App name|Short description|Full description)\s*—")
SHOT = re.compile(r"^(?P<order>\d+_)?(?P<scene>.+?)-(?P<tag>[A-Za-z]{2}(?:-[A-Za-z]{2,4})?)\.png$")


def version_code() -> str:
    """The same number app/build.gradle.kts derives versionCode from."""
    return subprocess.run(
        ["git", "rev-list", "--count", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.strip()


def fenced(lines: list[str], start: int, stop: int) -> str | None:
    """The body of the first ``` block at or after `start`."""
    i = start
    while i < stop and not lines[i].startswith("```"):
        i += 1
    if i >= stop:
        return None
    j = i + 1
    while j < stop and not lines[j].startswith("```"):
        j += 1
    return "\n".join(lines[i + 1 : j]).strip()


def parse_listings(text: str) -> dict[str, dict[str, str]]:
    """`{play locale: {title, short_description, full_description}}` from the listing document."""
    lines = text.splitlines()
    # Every `##` that names a language we know. Other `##` sections in this file (Release notes,
    # Open, the shared fields) are skipped by not matching the table.
    heads: list[tuple[str, int]] = []
    for i, line in enumerate(lines):
        m = LANGUAGE_HEADING.match(line)
        if m and m.group("name").strip() in LOCALES:
            heads.append((LOCALES[m.group("name").strip()], i))

    listings: dict[str, dict[str, str]] = {}
    for n, (locale, at) in enumerate(heads):
        # A language section ends at the next `##` of any kind, not at the next *language* — the
        # Release notes section sits between languages and must not be swallowed into one.
        stop = next((i for i in range(at + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
        fields: dict[str, str] = {}
        for i in range(at, stop):
            m = FIELD_HEADING.match(lines[i])
            if not m:
                continue
            body = fenced(lines, i + 1, stop)
            if body is not None:
                fields[FIELDS[m.group("field")]] = body
        if fields:
            listings[locale] = fields
    return listings


def copy_screenshots(out: Path) -> dict[str, int]:
    """Fan `art/play-screenshots/1_home-pl.png` out into supply's per-locale image directories.

    Returns `{play locale: count}`. Missing entirely is not an error: the directory does not exist
    until the listing set has been re-shot, and a metadata run that only updates text is legitimate.
    """
    counts: dict[str, int] = {}
    if not SCREENSHOTS.is_dir():
        return counts
    for png in sorted(SCREENSHOTS.glob("*.png")):
        m = SHOT.match(png.name)
        if not m:
            print(f"-- skipping {png.name}: not <order>_<scene>-<locale>.png", file=sys.stderr)
            continue
        locale = APP_TAG.get(m.group("tag"))
        if locale is None:
            print(f"-- skipping {png.name}: unknown locale tag {m.group('tag')!r}", file=sys.stderr)
            continue
        target = out / locale / "images" / "phoneScreenshots"
        target.mkdir(parents=True, exist_ok=True)
        # The locale suffix is dropped: supply takes the language from the directory, and the order
        # prefix is what it sorts on.
        shutil.copy2(png, target / f"{m.group('order') or ''}{m.group('scene')}.png")
        counts[locale] = counts.get(locale, 0) + 1
    return counts


def copy_feature_graphics(out: Path) -> list[str]:
    """`art/play-feature-graphic[-<tag>].png` -> each language's `images/featureGraphic.png`.

    Returns the Play locales that got one. `art/make-feature-graphic.py` renders the sources; this only
    carries them, so a re-render reaches Play on the next listing upload with nothing else to remember.
    """
    sources = {DEFAULT_LOCALE: FEATURE_GRAPHIC}
    for png in sorted(FEATURE_GRAPHIC.parent.glob(f"{FEATURE_GRAPHIC.stem}-*.png")):
        tag = png.stem.removeprefix(f"{FEATURE_GRAPHIC.stem}-")
        locale = APP_TAG.get(tag)
        if locale is None:
            print(f"-- skipping {png.name}: unknown locale tag {tag!r}", file=sys.stderr)
            continue
        sources[locale] = png
    copied = []
    for locale, png in sources.items():
        if not png.is_file():
            continue
        target = out / locale / "images"
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(png, target / "featureGraphic.png")
        copied.append(locale)
    return sorted(copied)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="fastlane/metadata/android")
    parser.add_argument("--version-code", help="changelog filename; default `git rev-list --count HEAD`")
    parser.add_argument("--release-version", help="which release's notes; default the newest in the document")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="refuse an incomplete listing instead of warning; pass it on the run that uploads the listing",
    )
    args = parser.parse_args()

    text = LISTING.read_text(encoding="utf-8")
    listings = parse_listings(text)
    release, notes = whatsnew.parse(text, args.release_version)
    code = args.version_code or version_code()

    # Two kinds of finding. An over-limit field is wrong on every path: the document is broken, and a
    # later listing run would be refused for it. A gap is only wrong where the listing is uploaded,
    # which is what --strict says.
    problems = []
    gaps = []
    for locale, fields in sorted(listings.items()):
        for field, limit in LIMITS.items():
            if not fields.get(field):
                gaps.append(f"{locale} has no {field} - no file written, so Play keeps whatever it has")
            elif len(fields[field]) > limit:
                problems.append(f"{locale} {field} is {len(fields[field])} characters, over Play's {limit}")
    for locale in sorted(whatsnew.shipped_locales() - set(listings)):
        gaps.append(f"{locale} is shipped in the app but has no listing")

    args.out.mkdir(parents=True, exist_ok=True)
    for locale, fields in listings.items():
        directory = args.out / locale
        directory.mkdir(parents=True, exist_ok=True)
        for field, body in fields.items():
            if body:  # a blank is left out rather than written as "" - see the docstring
                (directory / f"{field}.txt").write_text(body, encoding="utf-8")
        if locale in notes:
            changelogs = directory / "changelogs"
            changelogs.mkdir(exist_ok=True)
            (changelogs / f"{code}.txt").write_text(notes[locale], encoding="utf-8")

    shots = copy_screenshots(args.out)
    graphics = copy_feature_graphics(args.out)
    complete = sorted(locale for locale, fields in listings.items() if all(fields.get(f) for f in LIMITS))
    incomplete = sorted(set(listings) - set(complete))
    print(
        f"listings: {len(complete)} complete"
        + (f", {len(incomplete)} incomplete ({', '.join(incomplete)})" if incomplete else "")
        + f"  notes: {len(notes)} (release {release}, changelog {code}.txt)"
    )
    print(f"screenshots: {sum(shots.values())} across {len(shots)} locales" if shots else "screenshots: none (art/play-screenshots/ absent — text-only run)")
    print(f"feature graphics: {', '.join(graphics)}" if graphics else "feature graphics: none (art/play-feature-graphic.png absent)")

    for gap in gaps:
        print(f"{'FAILED' if args.strict else 'WARNING'}: {gap}", file=sys.stderr)
    if gaps and not args.strict:
        print(
            "-- harmless while supply skips the listing; a listing upload must pass --strict, "
            "because supply saves every locale it has a directory for",
            file=sys.stderr,
        )
    for problem in problems:
        print(f"FAILED: {problem}", file=sys.stderr)
    return 1 if problems or (gaps and args.strict) else 0


if __name__ == "__main__":
    raise SystemExit(main())
