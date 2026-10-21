#!/usr/bin/env python3
"""把「改动前」与「改动后」的两组截图并排拼成一张对照表。

用法：
    python tools/compare_sheet.py .tmp/adapt/baseline/C411 .tmp/adapt/dialogs/C411 \
        01_dashboard.png 08_drawer.png -o .tmp/adapt/cmp_C411.png
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # noqa: BLE001
    print("!! 需要 Pillow：pip install pillow")
    sys.exit(2)

BG = (24, 26, 30)
FG = (232, 236, 240)
MUTED = (150, 158, 170)
GREEN = (120, 200, 140)


def load_font(size: int):
    for name in ("msyh.ttc", "msyhl.ttc", "simhei.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("names", nargs="+")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--cell", type=int, default=340)
    args = ap.parse_args(argv[1:])

    before, after = Path(args.before), Path(args.after)
    rows = []
    for name in args.names:
        b, a = before / name, after / name
        if not b.exists() or not a.exists():
            print(f"  跳过 {name}（缺文件）")
            continue
        rows.append((name, Image.open(b).convert("RGB"), Image.open(a).convert("RGB")))

    if not rows:
        print("!! 没有可对比的图片")
        return 2

    title_font = load_font(20)
    label_font = load_font(15)

    cells = []
    for name, bi, ai in rows:
        bh = int(bi.height * args.cell / bi.width)
        ah = int(ai.height * args.cell / ai.width)
        cells.append(
            (
                name,
                bi.resize((args.cell, bh), Image.LANCZOS),
                ai.resize((args.cell, ah), Image.LANCZOS),
                max(bh, ah),
            )
        )

    gap, label_h, head = 14, 24, 46
    W = args.cell * 2 + gap * 3
    H = head + len(cells) * (max(c[3] for c in cells) + label_h + gap) + gap
    sheet = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(sheet)

    draw.text((gap, 12), "左：改动前    右：改动后", font=title_font, fill=FG)
    draw.text((gap + args.cell + gap, 12), "", font=title_font, fill=GREEN)

    y = head
    for name, bi, ai, ch in cells:
        draw.text((gap, y), name, font=label_font, fill=MUTED)
        draw.text((gap + args.cell + gap, y), name, font=label_font, fill=GREEN)
        sheet.paste(bi, (gap, y + label_h))
        sheet.paste(ai, (gap + args.cell + gap, y + label_h))
        y += ch + label_h + gap

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(f"拼好 {len(cells)} 组对照 → {out}  ({W}x{H})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
