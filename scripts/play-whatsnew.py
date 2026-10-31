#!/usr/bin/env python3
"""Render Play's "What's new" for every shipped language, as one pasteable block.

The Console takes release notes for all languages in a **single** textarea, tagged by locale:

    <en-US>
    …
    </en-US>
    <pl-PL>
    …
    </pl-PL>

Nine notes pasted one listing at a time is nine chances to put the Czech text on the Ukrainian
listing, and Play does not warn — it just publishes what you gave it. This turns the nine blocks
already written in `docs/store-listing.md` into that one block.

**`store-listing.md` stays the source.** The notes are authored there, translated from the English
per `translator-brief.md`, and their character counts are measured there against Play's 500 limit.
Nothing is retyped here; this only reformats, so there is no second copy to drift.

Usage:
    python3 scripts/play-whatsnew.py                    # newest version in the file
    python3 scripts/play-whatsnew.py --version 1.8.0    # a named one
    python3 scripts/play-whatsnew.py --dir DIR          # also write whatsnew-<locale> files

Exits non-zero when a note is over 500 characters or a shipped language has no note at all — both
are things Play accepts silently and an owner then sees.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

LISTING = Path(__file__).resolve().parent.parent / "docs" / "store-listing.md"
RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

# Play's limit. It truncates rather than warns, which is why this is asserted and not trusted.
LIMIT = 500

# The heading label used in store-listing.md -> Play's locale code. Play wants a *region*, so `pl`
# alone is not accepted; `pt-BR` is already regional and stays as it is.
LOCALES = {
    "English": "en-US",
    "Polish": "pl-PL",
    "German": "de-DE",
    "Spanish": "es-ES",
    "French": "fr-FR",
    "Italian": "it-IT",
    "Brazilian Portuguese": "pt-BR",
    "Czech": "cs-CZ",
    "Ukrainian": "uk",
}

# The same mapping seen from the other end: an Android resource qualifier -> Play's code. This is
# what lets the script prove the note set matches what the *app* actually ships, rather than
# matching whatever the document happens to list.
RES_QUALIFIER = {
    "cs": "cs-CZ",
    "de": "de-DE",
    "es": "es-ES",
    "fr": "fr-FR",
    "it": "it-IT",
    "pl": "pl-PL",
    "pt-rBR": "pt-BR",
    "uk": "uk",
}

LABEL = re.compile(r"^\*\*(?P<name>[^*]+)\*\*\s*—\s*\d+/\d+:\s*$")
VERSION_HEADING = re.compile(r"^###\s+(?P<version>\d+\.\d+\.\d+)")


def shipped_locales() -> set[str]:
    """Every language the AAB carries, read off the resource directories.

    The base `values/` is English and has no qualifier, so it is added explicitly.
    """
    found = {"en-US"}
    for path in RES.glob("values-*"):
        qualifier = path.name[len("values-") :]
        if qualifier in RES_QUALIFIER:
            found.add(RES_QUALIFIER[qualifier])
    return found


def require_order(versions: list[str]) -> None:
    """Exit unless every version heading is newer than the one above it.

    Refused rather than sorted: "newest" is the last heading here and in notes-gate.py, so one
    written above its predecessor would upload the older notes — which an app built from this
    template did, writing 0.12.0 above 0.11.0.
    """
    numbers = [tuple(int(part) for part in v.split(".")) for v in versions]
    for earlier, later, a, b in zip(versions, versions[1:], numbers, numbers[1:]):
        if b <= a:
            sys.exit(f"release notes out of order: ### {later} comes after ### {earlier}; write newest last")


def parse(text: str, version: str | None) -> tuple[str, dict[str, str]]:
    """Pull the fenced note bodies out of the Release notes section of the listing document.

    Returns the version heading it read and `{play locale: note}`. A label with no fenced block
    after it is ignored rather than guessed at.
    """
    lines = text.splitlines()

    # Bound the search to the Release notes section: the document has fenced blocks elsewhere
    # (the full descriptions), and a stray `**English** — n/500:` outside this section would be a
    # different thing entirely.
    start = next((i for i, line in enumerate(lines) if line.startswith("## Release notes")), None)
    if start is None:
        sys.exit("no '## Release notes' section in docs/store-listing.md")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    section = lines[start:end]

    versions: list[tuple[str, int]] = [
        (m.group("version"), i) for i, line in enumerate(section) if (m := VERSION_HEADING.match(line))
    ]
    if not versions:
        sys.exit("no '### <version>' subsection under Release notes")
    require_order([v for v, _ in versions])
    if version is None:
        chosen, at = versions[-1]
    else:
        match = [(v, i) for v, i in versions if v == version]
        if not match:
            sys.exit(f"no notes for {version}; found {', '.join(v for v, _ in versions)}")
        chosen, at = match[0]
    stop = next((i for v, i in versions if i > at), len(section))

    notes: dict[str, str] = {}
    i = at
    while i < stop:
        label = LABEL.match(section[i])
        if not label:
            i += 1
            continue
        name = label.group("name").strip()
        # Skip forward to the opening fence, then take everything up to the closing one.
        j = i + 1
        while j < stop and not section[j].startswith("```"):
            j += 1
        if j >= stop:
            i += 1
            continue
        k = j + 1
        while k < stop and not section[k].startswith("```"):
            k += 1
        body = "\n".join(section[j + 1 : k]).strip()
        if name not in LOCALES:
            sys.exit(f"'{name}' is not a language this script knows — add it to LOCALES")
        notes[LOCALES[name]] = body
        i = k + 1
    return chosen, notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="which release's notes, default the newest in the file")
    parser.add_argument("--dir", type=Path, help="also write whatsnew-<locale> files into this directory")
    args = parser.parse_args()

    version, notes = parse(LISTING.read_text(encoding="utf-8"), args.version)

    problems = []
    for locale, body in sorted(notes.items()):
        if len(body) > LIMIT:
            problems.append(f"{locale} is {len(body)} characters, over Play's {LIMIT} — Play truncates silently")
    missing = shipped_locales() - set(notes)
    for locale in sorted(missing):
        problems.append(f"{locale} is shipped in the app but has no note for {version}")
    # Not an error: a listing may carry a language the app does not, and that is the listing's call.
    extra = set(notes) - shipped_locales()

    # en-US first because it is the listing's default language and the one a reviewer reads; the
    # rest alphabetical so two runs of this script always produce the same block.
    order = (["en-US"] if "en-US" in notes else []) + sorted(k for k in notes if k != "en-US")
    block = "\n".join(f"<{locale}>\n{notes[locale]}\n</{locale}>" for locale in order)

    print(block)
    print(f"\n-- {version}: {len(notes)} locales, longest {max(len(b) for b in notes.values())}/{LIMIT}", file=sys.stderr)
    if extra:
        print(f"-- note only (not shipped in the app): {', '.join(sorted(extra))}", file=sys.stderr)

    if args.dir:
        args.dir.mkdir(parents=True, exist_ok=True)
        for locale, body in notes.items():
            # No trailing newline: Play counts it against the limit.
            (args.dir / f"whatsnew-{locale}").write_text(body, encoding="utf-8")
        print(f"-- wrote {len(notes)} files to {args.dir}", file=sys.stderr)

    for problem in problems:
        print(f"FAILED: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
