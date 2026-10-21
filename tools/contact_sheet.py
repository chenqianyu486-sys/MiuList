#!/usr/bin/env python3
"""把一批截图拼成一张联络表（contact sheet）。

## 为什么需要它

验收要看的是「一堆截图」而不是「一张图」——但几十张图丢进聊天里没法看。
所以把每个断点的所有页面/弹层拼成一张带标题的网格图：
一张图 = 一个断点的全貌，翻页即可。

用法：

    python tools/contact_sheet.py .tmp/adapt/dialogs/C411 .tmp/adapt/dialogs/C411.png
    python tools/contact_sheet.py .tmp/adapt/dialogs/A320 .tmp/adapt/dialogs/A320.png --cols 4 --cell 300
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
MUTED = (140, 150, 162)


def load_font(size: int):
    for name in ("msyh.ttc", "msyhl.ttc", "simhei.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="截图目录")
    ap.add_argument("out", help="输出图片")
    ap.add_argument("--cols", type=int, default=4)
    ap.add_argument("--cell", type=int, default=300, help="每格的宽度（px）")
    ap.add_argument("--title", default="", help="图顶端的大标题")
    args = ap.parse_args(argv[1:])

    src = Path(args.src)
    if not src.is_dir():
        print(f"!! 目录不存在：{src}")
        return 2
    files = sorted(src.glob("*.png"))
    if not files:
        print(f"!! 目录里没有 png：{src}")
        return 2

    title_font = load_font(22)
    label_font = load_font(15)

    # 先量一遍，决定每格的高度
    thumbs = []
    for f in files:
        im = Image.open(f).convert("RGB")
        h = int(im.height * args.cell / im.width)
        thumbs.append((f, im.resize((args.cell, h), Image.LANCZOS), h))

    label_h = 24
    cols = max(1, min(args.cols, len(thumbs)))
    rows = (len(thumbs) + cols - 1) // cols
    cell_h = max(t[2] for t in thumbs)
    head = 52 if args.title else 0
    gap = 12

    W = cols * args.cell + (cols + 1) * gap
    H = head + rows * (cell_h + label_h) + (rows + 1) * gap
    sheet = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(sheet)

    if args.title:
        draw.text((gap, 14), args.title, font=title_font, fill=FG)

    for i, (f, im, h) in enumerate(thumbs):
        r, c = divmod(i, cols)
        x = gap + c * (args.cell + gap)
        y = head + gap + r * (cell_h + label_h + gap)
        draw.text((x, y), f.stem, font=label_font, fill=MUTED)
        sheet.paste(im, (x, y + label_h))
        draw.rectangle(
            [x, y + label_h, x + im.width - 1, y + label_h + im.height - 1],
            outline=(60, 66, 74),
        )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(f"拼好 {len(thumbs)} 张 → {out}  ({W}x{H})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
