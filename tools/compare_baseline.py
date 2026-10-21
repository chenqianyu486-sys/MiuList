#!/usr/bin/env python3
"""把一次矩阵抓图与基线目录逐张比对，报告差异率并写出差异图。

## 为什么要有基线

自适应改动有个坏毛病：**改一处，好几屏跟着动**。比如把主卡的排法改了，
课表页、弹层、抽屉的某张截图也可能跟着变——不是因为它们错了，
而是因为它们共享同一个字阶或同一个内容列宽。

没有基线的时候，这种「连带变化」只能靠人眼在四张图之间扫，
而且一定会漏。有了基线，改动前后跑两遍矩阵，差异率一目了然：
**该变的变了、不该变的没动**，就是一次可验收的回归。

## 怎么用

    # 先把当前状态存成基线
    python tools/compare_baseline.py --save .tmp/adapt/baseline .tmp/adapt/matrix

    # 改完代码再抓一遍，比对
    python tools/compare_baseline.py .tmp/adapt/baseline .tmp/adapt/matrix

退出码：0 = 全部在阈值内；1 = 有超阈值的差异；2 = 用法错误。
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageChops

# 差异率阈值（0..1）。
#
# 0.002 是个很小的数：一张 1080×2400 的截图里 0.2% 已经是五千多个像素。
# 毛玻璃背景（壁纸 + 模糊 + 颗粒噪声）每次渲染不可能逐像素一致，
# 所以阈值不能是 0；但也不能大到「布局变了也算通过」。
# 3% 是一个能同时容忍噪声、又能抓住版式变化的量级：
# 一张卡挪了位置，那张卡占的面积就已经超过 3%。
DEFAULT_THRESHOLD = 0.03


def diff_ratio(a: Image.Image, b: Image.Image) -> tuple[float, Image.Image | None]:
    """两张图的差异像素占比。尺寸不同则返回 1.0（视作完全变了）。"""
    if a.size != b.size:
        return 1.0, None
    diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    # 逐像素取「有没有任一通道不一样」
    gray = diff.convert("L").point(lambda v: 255 if v > 8 else 0)
    changed = sum(gray.histogram()[255:])
    total = gray.size[0] * gray.size[1]
    return (changed / total if total else 0.0), diff


def iter_shots(root: Path):
    yield from sorted(p for p in root.rglob("*.png"))


def save_baseline(src: Path, dst: Path) -> int:
    count = 0
    for shot in iter_shots(src):
        target = dst / shot.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(shot.read_bytes())
        count += 1
    print(f"已存基线：{count} 张 → {dst}")
    return count


def compare(baseline: Path, current: Path, threshold: float) -> int:
    if not baseline.is_dir():
        print(f"!! 基线目录不存在：{baseline}")
        return 2
    if not current.is_dir():
        print(f"!! 当前目录不存在：{current}")
        return 2

    diff_root = current / "_diff"
    over = 0
    checked = 0

    for shot in iter_shots(current):
        rel = shot.relative_to(current)
        base_shot = baseline / rel
        if not base_shot.exists():
            print(f"  + 新增 {rel}（基线里没有，不算差异）")
            continue
        try:
            with Image.open(base_shot) as a, Image.open(shot) as b:
                ratio, diff = diff_ratio(a, b)
        except OSError as exc:
            print(f"  !! {rel} 打不开：{exc}")
            over += 1
            continue

        checked += 1
        mark = "  " if ratio <= threshold else "!!"
        print(f"  {mark} {rel}: 差异 {ratio * 100:.2f}%")
        if ratio > threshold and diff is not None:
            diff_root.mkdir(parents=True, exist_ok=True)
            diff_path = diff_root / rel
            diff_path.parent.mkdir(parents=True, exist_ok=True)
            diff.save(diff_path)
            over += 1

    for base_shot in iter_shots(baseline):
        rel = base_shot.relative_to(baseline)
        if not (current / rel).exists():
            print(f"  - 消失 {rel}（基线里有，这次没抓到）")

    print(f"  比对 {checked} 张，超阈值 {over} 张（阈值 {threshold * 100:.1f}%）")
    if over:
        print(f"  差异图：{diff_root}")
    return 1 if over else 0


def main(argv: list[str]) -> int:
    args = argv[1:]
    if not args:
        print(__doc__)
        return 2

    if args[0] == "--save":
        if len(args) < 3:
            print("用法：compare_baseline.py --save <基线目录> <当前目录>")
            return 2
        save_baseline(Path(args[2]), Path(args[1]))
        return 0

    if len(args) < 2:
        print("用法：compare_baseline.py <基线目录> <当前目录> [阈值]")
        return 2

    threshold = float(args[2]) if len(args) > 2 else DEFAULT_THRESHOLD
    return compare(Path(args[0]), Path(args[1]), threshold)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
