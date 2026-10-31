#!/usr/bin/env python3
"""Pad a device screenshot out to Play's 9:16 phone-screenshot ratio.

The test Xiaomi is 1220x2712, which is 2.22:1 — taller than the 9:16 Play documents for
phone screenshots. Rather than crop the UI or squash it, this centres the shot on a 9:16
canvas filled with the screenshot's own edge colour, so the padding reads as background
rather than as letterboxing.

    python3 art/pad-screenshot.py <in.png> <out.png>
    python3 art/pad-screenshot.py <in.png> <out.png> --crop-status-bar [--status-bar-px N]

`--crop-status-bar` removes the top `--status-bar-px` (default [STATUS_BAR_PX]) before padding, and
**the screenshot path does not need it any more.** The problem it solved is real: the driver holds
the phone in Do Not Disturb for the length of a run — see `set_dnd` in `scripts/edge-to-edge.py` —
and Zen puts a crossed bell in the status bar of every frame, alongside notification icons and a
network-speed readout that are nobody's business.

**This file used to say HyperOS ignores SystemUI demo mode outright. That was wrong, or has stopped
being true** — re-measured by an app built from this template (2026-09): `sysui_demo_allowed` plus
the notifications/battery commands empties the bar. `set_demo_status_bar` in
`scripts/edge-to-edge.py` records what the ROM honours and what it drops, and `screenshots.py` turns
it on for every run. So the bar is cleaned at capture time and kept in frame, which keeps the shot
the shape of a phone.

The flag stays for a device where demo mode does not work — MIUI 12.5 ignores it — because cropping
is still the only answer to a bar that cannot be emptied. Prefer emptying it: a cropped shot is not
the shape of anything the user has ever held. Another phone has another inset: pass its portrait
`statusBars` height, read from `adb shell dumpsys window`, as `--status-bar-px`.
"""

import argparse
from collections import Counter

from PIL import Image

RATIO_W, RATIO_H = 9, 16

# The portrait `statusBars` inset on the test Xiaomi, measured by the edge-to-edge matrix rather
# than derived from a density: 130px at 480dpi, and it coincides with the punch-hole cutout, so one
# number takes both. `scripts/edge-to-edge.py` is what measures it. A different phone would need its own
# reading — this is a device constant, not a Play one.
STATUS_BAR_PX = 130


def edge_colour(img):
    """Most common pixel down the left and right edges — the app's background, not the
    status bar or the nav bar, both of which only touch the top and bottom."""
    px = img.load()
    w, h = img.size
    counts = Counter()
    for y in range(h // 8, h - h // 8):
        counts[px[0, y]] += 1
        counts[px[w - 1, y]] += 1
    return counts.most_common(1)[0][0]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("src")
    parser.add_argument("dst")
    parser.add_argument(
        "--crop-status-bar",
        action="store_true",
        help="drop the top --status-bar-px pixels (status bar and cutout) before padding",
    )
    parser.add_argument(
        "--status-bar-px",
        type=int,
        default=STATUS_BAR_PX,
        help="the phone's portrait statusBars inset (default: %(default)s, the test Xiaomi's)",
    )
    args = parser.parse_args()

    src, dst = args.src, args.dst
    img = Image.open(src).convert("RGB")
    if args.crop_status_bar:
        img = img.crop((0, args.status_bar_px, img.size[0], img.size[1]))
    w, h = img.size

    target_w = max(w, round(h * RATIO_W / RATIO_H))
    target_h = max(h, round(w * RATIO_H / RATIO_W))
    # Only one dimension actually grows; the other is already at or past the ratio.
    if target_w / target_h > RATIO_W / RATIO_H:
        target_h = round(target_w * RATIO_H / RATIO_W)
    else:
        target_w = round(target_h * RATIO_W / RATIO_H)

    canvas = Image.new("RGB", (target_w, target_h), edge_colour(img))
    canvas.paste(img, ((target_w - w) // 2, (target_h - h) // 2))
    canvas.save(dst, "PNG", optimize=True)

    ratio = target_w / target_h
    cropped = f" (status bar cropped, -{args.status_bar_px}px)" if args.crop_status_bar else ""
    print(f"{dst}  {w}x{h} -> {target_w}x{target_h}  ({ratio:.4f}, 9:16 = {RATIO_W / RATIO_H:.4f}){cropped}")


if __name__ == "__main__":
    main()
