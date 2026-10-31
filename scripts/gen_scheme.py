"""Generate the app's full M3 colour scheme from the four brand seeds.

**The seeds are the judgement; the ~36 roles are arithmetic.** Material's tonal-palette
construction (CAM16/HCT) derives every role from a seed hue and chroma at a fixed tone, and those
fixed tonal relationships are what make the contrast ratios hold across the whole scheme. Picking
one role by hand breaks the relationship it has with its family, and the failure surfaces as a
contrast bug on one screen out of thirty rather than as a visible mistake here.

So: change the four seeds below, re-run, and take the whole file.

    python3 scripts/gen_scheme.py > app/src/main/java/<namespace>/theme/Color.kt

Pick seeds by hue, not by taste for the exact swatch — the generator moves the tone anyway.
`primary` is the brand; `neutral` decides whether every surface in the app reads warm or cool, and
is the one with the widest reach.
"""
import sys
from hct import TonalPalette, argb_from_hex, hex_from_argb, xyz_from_argb

SEED_PRIMARY = "4C6FA5"    # Slate blue
SEED_SECONDARY = "6B7A8F"  # Muted steel
SEED_TERTIARY = "9A6BA5"   # Heather
SEED_NEUTRAL = "DCDFE4"    # Cool grey

PACKAGE = "app.starter"  # the Kotlin package root; bootstrap.py rewrites this

P = TonalPalette.from_argb(argb_from_hex(SEED_PRIMARY))
S = TonalPalette.from_argb(argb_from_hex(SEED_SECONDARY))
T = TonalPalette.from_argb(argb_from_hex(SEED_TERTIARY))
_n = TonalPalette.from_argb(argb_from_hex(SEED_NEUTRAL))
# Surfaces keep the neutral seed exactly; outlines carry slightly more chroma,
# which is M3's own neutral/neutralVariant split (6 vs 8) applied to our hue.
N = TonalPalette.from_hue_and_chroma(_n.hue, _n.chroma)
NV = TonalPalette.from_hue_and_chroma(_n.hue, 8.0)
# Error stays M3 baseline: it is a safety signal, not a brand decision.
E = TonalPalette.from_hue_and_chroma(25.0, 84.0)

LIGHT = [
    ("primary", P, 40), ("onPrimary", P, 100),
    ("primaryContainer", P, 90), ("onPrimaryContainer", P, 10),
    ("inversePrimary", P, 80),
    ("secondary", S, 40), ("onSecondary", S, 100),
    ("secondaryContainer", S, 90), ("onSecondaryContainer", S, 10),
    ("tertiary", T, 40), ("onTertiary", T, 100),
    ("tertiaryContainer", T, 90), ("onTertiaryContainer", T, 10),
    ("error", E, 40), ("onError", E, 100),
    ("errorContainer", E, 90), ("onErrorContainer", E, 10),
    ("background", N, 98), ("onBackground", N, 10),
    ("surface", N, 98), ("onSurface", N, 10),
    ("surfaceVariant", NV, 90), ("onSurfaceVariant", NV, 30),
    ("surfaceDim", N, 87), ("surfaceBright", N, 98),
    ("surfaceContainerLowest", N, 100), ("surfaceContainerLow", N, 96),
    ("surfaceContainer", N, 94), ("surfaceContainerHigh", N, 92),
    ("surfaceContainerHighest", N, 90),
    ("inverseSurface", N, 20), ("inverseOnSurface", N, 95),
    ("outline", NV, 50), ("outlineVariant", NV, 80),
    ("scrim", N, 0),
]

DARK = [
    ("primary", P, 80), ("onPrimary", P, 20),
    ("primaryContainer", P, 30), ("onPrimaryContainer", P, 90),
    ("inversePrimary", P, 40),
    ("secondary", S, 80), ("onSecondary", S, 20),
    ("secondaryContainer", S, 30), ("onSecondaryContainer", S, 90),
    ("tertiary", T, 80), ("onTertiary", T, 20),
    ("tertiaryContainer", T, 30), ("onTertiaryContainer", T, 90),
    ("error", E, 80), ("onError", E, 20),
    ("errorContainer", E, 30), ("onErrorContainer", E, 90),
    ("background", N, 6), ("onBackground", N, 90),
    ("surface", N, 6), ("onSurface", N, 90),
    ("surfaceVariant", NV, 30), ("onSurfaceVariant", NV, 80),
    ("surfaceDim", N, 6), ("surfaceBright", N, 24),
    ("surfaceContainerLowest", N, 4), ("surfaceContainerLow", N, 10),
    ("surfaceContainer", N, 12), ("surfaceContainerHigh", N, 17),
    ("surfaceContainerHighest", N, 22),
    ("inverseSurface", N, 90), ("inverseOnSurface", N, 20),
    ("outline", NV, 60), ("outlineVariant", NV, 30),
    ("scrim", N, 0),
]


def rel_luminance(argb):
    return xyz_from_argb(argb)[1] / 100.0


def contrast(a, b):
    la, lb = rel_luminance(a), rel_luminance(b)
    lo, hi = min(la, lb), max(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def emit(name, rows):
    print("private val %s =" % name)
    print("    %s(" % ("lightColorScheme" if "Light" in name else "darkColorScheme"))
    for role, pal, tone in rows:
        print("        %s = Color(0xFF%s)," % (role, pal.hexes(tone)))
    print("    )")
    print()


def main():
    print(f"""package {PACKAGE}.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// GENERATED — do not hand-edit individual roles.
//
// Both schemes are derived from four brand seeds by Material's tonal palette
// construction (CAM16/HCT): the seeds are the judgement, the ~36 roles are
// arithmetic. Hand-picking a role breaks the fixed tonal relationship it holds
// with the rest of its family, and the failure shows up as a contrast bug on one
// screen out of thirty rather than as a visible mistake here.
//
//   primary   #{SEED_PRIMARY}  — brand: filled buttons, FAB, selected tab
//   secondary #{SEED_SECONDARY}  — quiet containers: chips, tonal buttons, selected segment
//   tertiary  #{SEED_TERTIARY}  — accents and caution markers, used sparingly
//   neutral   #{SEED_NEUTRAL}  — seeds every surface, so it decides warm or cool overall
//
// Error stays M3 baseline (hue 25, chroma 84): a safety signal, not a brand choice.
//
// To change the palette, edit the seeds in scripts/gen_scheme.py and re-run it.
// Colours come from MaterialTheme, never literals, and dynamic colour defaults
// off (ADR-0006) so this file is what a user actually sees.
""")
    emit("AppLightColors", LIGHT)
    emit("AppDarkColors", DARK)
    print("internal val LightColors = AppLightColors")
    print("internal val DarkColors = AppDarkColors")

    # Contrast report to stderr so it never lands in the Kotlin file.
    def pal(rows, role):
        for r, p, t in rows:
            if r == role:
                return p.tone(t)
    checks = [
        ("onSurface / surface", "onSurface", "surface", 4.5),
        ("onSurfaceVariant / surface", "onSurfaceVariant", "surface", 4.5),
        ("onSurfaceVariant / surfaceContainerLow", "onSurfaceVariant", "surfaceContainerLow", 4.5),
        ("onPrimary / primary", "onPrimary", "primary", 4.5),
        ("onPrimaryContainer / primaryContainer", "onPrimaryContainer", "primaryContainer", 4.5),
        ("onSecondaryContainer / secondaryContainer", "onSecondaryContainer", "secondaryContainer", 4.5),
        ("onTertiaryContainer / tertiaryContainer", "onTertiaryContainer", "tertiaryContainer", 4.5),
        ("onErrorContainer / errorContainer", "onErrorContainer", "errorContainer", 4.5),
        ("primary / surface  (chart line)", "primary", "surface", 3.0),
        ("outline / surface", "outline", "surface", 3.0),
        ("outlineVariant / surfaceContainerLow (divider)", "outlineVariant", "surfaceContainerLow", 1.2),
    ]
    for label, rows in (("LIGHT", LIGHT), ("DARK", DARK)):
        print("\n%s" % label, file=sys.stderr)
        for name, fg, bg, want in checks:
            c = contrast(pal(rows, fg), pal(rows, bg))
            flag = "ok  " if c >= want else "FAIL"
            print("  %s %-48s %5.2f:1  (need %.1f)" % (flag, name, c, want), file=sys.stderr)


if __name__ == "__main__":
    main()
