#!/usr/bin/env python3
"""Render the 1024x500 Play feature graphics, one per listing language in `COPY`.

The default language's is `art/play-feature-graphic.png`; every other is
`art/play-feature-graphic-<tag>.png`, tagged like the screenshots, and `scripts/play-metadata.py`
carries each into its own listing language. A language with no graphic of its own shows the
default's, so add one only where the copy is translated.

Original art only: the mark comes from `mark.py`, the same declaration the launcher icon is
generated from, so the two assets cannot drift into being different marks. Text is *rendered* with
Noto Sans, which the OFL explicitly permits without any notice — what it restricts is redistributing
glyph outlines as art.

**Play crops this graphic.** The asset is 2.048:1 and Play drops it into a 16:9 box with
`object-fit: cover` on the search-results card, so a slice of each side never reaches a viewer. It
does not look like a bug: the graphic is correct in every local preview and in the Console's own
uploader, and wrong only on the surface most people see it on. The app this template came from met
it on a live listing, and the first app built from the template reproduced it almost exactly. So the
layout is declared against the *cropped* frame — see CROP — and render() measures what it produced and
exits non-zero rather than trusting that the constants still hold.

**The colours are read out of `theme/Color.kt`**, never pasted: the palette is regenerated from seeds
by `scripts/gen_scheme.py`, and pasted inks stay the old hue on a ground that has moved — which is
exactly what happened the first time an app built from this template changed its palette.

The ground is `feature-background.png` beside this file when there is one (cover-fitted; record its
provenance in art/README.md), and otherwise a gradient between two roles of the dark scheme.

    python3 art/make-feature-graphic.py
"""

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import mark

ART = Path(__file__).parent

# The strings this graphic is *about*, keyed by listing-language tag (None is the default language).
# Edit them; everything else is layout. Translate from the English, per docs/translator-brief.md.
APP_NAME = "Starter"
COPY = {
    None: ("One line about your app", "Private  ·  Offline  ·  No ads  ·  Free"),
}
BACKGROUND = ART / "feature-background.png"
W, H = 1024, 500
S = 4  # supersample factor; everything below is in final pixels, scaled by S at draw time

# What Play's 16:9 card takes off each side, derived from the two aspect ratios rather than measured
# once and pasted. MARGIN is the gap from the *canvas* edge, sized to leave a real margin inside the
# crop as well, and everything is positioned against it — including the mark, which is right-aligned
# to it rather than placed by eye, so it cannot drift back over the line when the mark is redrawn.
CROP = round(W * (1 - (16 / 9) / (W / H)) / 2)  # 68px lost from each side
VISIBLE_MARGIN = 40  # what a viewer of the cropped graphic should actually see
MARGIN = CROP + VISIBLE_MARGIN  # 108px from the canvas edge to any content, both sides

MARK_SIZE = 190
MARK_CY = 215
MIN_GAP = 24  # the copy must clear the mark by this much; render() checks it

FONT_DIR = Path("/usr/share/fonts/truetype/noto")
FONT_BOLD = FONT_DIR / "NotoSans-Bold.ttf"
FONT_REG = FONT_DIR / "NotoSans-Regular.ttf"


def color_kt():
    """The generated `theme/Color.kt`, found rather than spelled out so a namespace rename cannot break it."""
    found = sorted((ART.parent / "app/src/main/java").rglob("theme/Color.kt"))
    if not found:
        raise SystemExit("no app/src/main/java/**/theme/Color.kt — run scripts/gen_scheme.py first")
    return found[0]


def scheme(*roles):
    """Colour roles read out of `theme/Color.kt`'s dark scheme, as (r, g, b)."""
    path = color_kt()
    body = path.read_text().split("darkColorScheme(", 1)[-1]
    out = []
    for role in roles:
        found = re.search(rf"\b{role} = Color\(0x[0-9A-Fa-f]{{2}}([0-9A-Fa-f]{{6}})\)", body)
        if not found:
            raise SystemExit(f"{path}: no `{role}` in the dark scheme — has it been renamed?")
        h = found.group(1)
        out.append(tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)))
    return out


GROUND_TOP, NIGHT, INK = scheme("primaryContainer", "background", "onSurface")


def _step(t):
    """INK mixed `t` of the way towards NIGHT, componentwise.

    Two steps down from the wordmark towards the ground, so the three text weights read as one
    family. Tinted rather than grey — a neutral grey on a coloured ground looks like a mistake — and
    computed rather than pasted, for the reason in the module docstring.
    """
    return tuple(round(s + (g - s) * t) for s, g in zip(INK, NIGHT))


TAGLINE_INK = _step(0.16)
SUBTLE = _step(0.34)


def ground(size):
    """`feature-background.png` cover-fitted to the canvas, or a diagonal gradient in its absence.

    Cover rather than a plain resize: a straight `resize()` would silently stretch a replacement that
    is not 2.048:1, and a stretched photograph is the kind of wrong that only looks slightly odd.
    """
    w, h = size
    if BACKGROUND.exists():
        img = Image.open(BACKGROUND).convert("RGB")
        k = max(w / img.width, h / img.height)
        scaled = img.resize((round(img.width * k), round(img.height * k)), Image.LANCZOS)
        left, top = (scaled.width - w) // 2, (scaled.height - h) // 2
        return scaled.crop((left, top, left + w, top + h))

    # Built small along the diagonal and resized: a per-pixel loop at full size is slow for nothing.
    small = Image.new("RGB", (w // 4, h // 4))
    px = small.load()
    for y in range(small.height):
        for x in range(small.width):
            t = (x / small.width + y / small.height) / 2
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(GROUND_TOP, NIGHT))
    return small.resize((w, h), Image.BICUBIC)


def draw_mark(base, cx, cy, size):
    """The shared mark from `mark.py`, centred on (cx, cy) at `size` px across, in final pixels.

    Painted onto its own layer so the hole can be punched to full transparency rather than filled
    with a guess at the ground colour — the ground here is a gradient or a photograph, so a painted
    hole would match it at one height at best. The launcher icon gets the same hole from the same
    declaration, by winding rather than by alpha.
    """
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x0, y0, x1, y1 = mark.bounds()
    k = size / max(x1 - x0, y1 - y0)

    def tf(p):
        return ((cx + (p[0] - (x0 + x1) / 2) * k) * S, (cy + (p[1] - (y0 + y1) / 2) * k) * S)

    for e in mark.PARTS:
        d.polygon(mark.outline(e, tf), fill=(0, 0, 0, 0) if e.hole else mark.MARK_INK + (255,))
    base.alpha_composite(layer)


def out_path(tag):
    return ART / ("play-feature-graphic.png" if tag is None else f"play-feature-graphic-{tag}.png")


def render(out_file, tagline_text, features_text):
    base = ground((W, H)).convert("RGBA").resize((W * S, H * S), Image.BICUBIC)

    mark_left = W - MARGIN - MARK_SIZE
    draw_mark(base, cx=W - MARGIN - MARK_SIZE / 2, cy=MARK_CY, size=MARK_SIZE)

    d = ImageDraw.Draw(base)
    wordmark = ImageFont.truetype(str(FONT_BOLD), 104 * S)
    tagline = ImageFont.truetype(str(FONT_REG), 31 * S)
    features = ImageFont.truetype(str(FONT_REG), 25 * S)

    x = MARGIN * S
    d.text((x, 240 * S), APP_NAME, font=wordmark, fill=INK, anchor="ls")
    d.text((x, 296 * S), tagline_text, font=tagline, fill=TAGLINE_INK, anchor="ls")
    d.text((x, 372 * S), features_text, font=features, fill=SUBTLE, anchor="ls")

    out = base.resize((W, H), Image.LANCZOS).convert("RGB")  # RGB: Play rejects alpha
    out.save(out_file, "PNG", optimize=True)

    # Measured, not assumed. Both of these have been wrong in a graphic generated from this file.
    widest = max(d.textlength(t, font=f) / S for t, f in
                 ((APP_NAME, wordmark), (tagline_text, tagline), (features_text, features)))
    right = MARGIN + widest
    gap = mark_left - right
    clipped = right > W - MARGIN
    collides = gap < MIN_GAP
    print(f"{out_file.name}: copy spans x {MARGIN}..{right:.0f}, mark x {mark_left}..{W - MARGIN}"
          f" — {'CLIPPED by the 16:9 crop' if clipped else 'inside the crop'}")
    print(f"  copy-to-mark gap {gap:.0f}px vs {MIN_GAP}px minimum — {'COLLIDES' if collides else 'ok'}")
    print(f"  {out.size[0]}x{out.size[1]}  {out_file.stat().st_size / 1024:.0f} KiB  mode={out.mode}")
    if clipped or collides:
        print(f"Shorten {out_file.name}'s copy or shrink MARK_SIZE; it was written, but do not upload it.",
              file=sys.stderr)
        return 1
    return 0


def main():
    print(f"crop takes {CROP}px per side -> Play shows x {CROP}..{W - CROP}")
    return max(render(out_path(tag), *copy) for tag, copy in COPY.items())


if __name__ == "__main__":
    sys.exit(main())
