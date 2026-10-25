#!/usr/bin/env python3
"""把「改动前 / 改动后」的同一块区域裁出来并排放大 —— 看字号变化要近观。

用法：
    python tools/zoom_region.py before.png after.png -o out.png --band 0.15 0.55
    --band 是相对纵向区间（上界、下界，0~1），用来只截卡片那一段。
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
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--band", nargs=2, type=float, default=(0.0, 1.0),
                    help="纵向相对区间，默认整张")
    ap.add_argument("--title", default="")
    args = ap.parse_args(argv)

    b = Image.open(args.before).convert("RGB")
    a = Image.open(args.after).convert("RGB")
    if b.size != a.size:
        print(f"!! 两张图尺寸不同：{b.size} vs {a.size}")
        return 2

    w, h = b.size
    top = int(h * args.band[0])
    bot = int(h * args.band[1])
    bc = b.crop((0, top, w, bot))
    ac = a.crop((0, top, w, bot))

    scale = 1.0
    if bc.width > 900:
        scale = 900 / bc.width
    bc = bc.resize((int(bc.width * scale), int(bc.height * scale)), Image.LANCZOS)
    ac = ac.resize((int(ac.width * scale), int(ac.height * scale)), Image.LANCZOS)

    label_h, gap, head = 26, 12, (44 if args.title else 0)
    W = bc.width + ac.width + gap * 3
    H = head + max(bc.height, ac.height) + label_h + gap
    sheet = Image.new("RGB", (W, H), (24, 26, 30))
    draw = ImageDraw.Draw(sheet)
    f = load_font(16)
    if args.title:
        draw.text((gap, 12), args.title, font=load_font(20), fill=(232, 236, 240))

    draw.text((gap, head), "改动前", font=f, fill=(150, 158, 170))
    draw.text((gap * 2 + bc.width, head), "改动后", font=f, fill=(120, 200, 140))
    sheet.paste(bc, (gap, head + label_h))
    sheet.paste(ac, (gap * 2 + bc.width, head + label_h))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(f"→ {out} ({W}x{H})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
