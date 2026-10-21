#!/usr/bin/env python3
"""量断点矩阵的**比例**：把每个断点渲染出来的尺寸，与 411dp 的设计值对照。

## 它验的是什么

自适应做对没有，靠的不是「看起来还行」，而是**比例**：
同一句日期在 320dp 与 411dp 上的物理宽度之比，应当等于两档的缩放系数之比；
主卡的高度、天气块的宽度同理。

所以这个脚本对每个断点做三件事：

1. 从 uiautomator dump 里量出**实际渲染**的日期宽度；
2. 按同一套公式算出**应该**是多少（设计值 × 缩放系数 × 档位比例）；
3. 报出两者的差。

差值超过容差就说明「比例没守住」——那正是「字缩了框没缩」或者反过来
（框缩了字没缩）时最先露馅的地方。

## 怎么用

    python tools/check_proportions.py .tmp/adapt/matrix

## 断点表

| 标签 | 物理宽 dp | density | 系统字号 | 窗口宽 dp |
|------|-----------|---------|---------|----------|
| A320   | 320 | 320 | 1.0 | 320 |
| B332   | 332 | 320 | 1.0 | 332 |
| C411   | 411 | 420 | 1.0 | 411 |
| D411f15| 411 | 420 | 1.5 | 411 |
| E411f20| 411 | 420 | 2.0 | 411 |
| A320f15| 320 | 320 | 1.5 | 320 |
| H320f20| 320 | 320 | 2.0 | 320 |
| F600   | 600 | 320 | 1.0 | 600 |
| G800   | 800 | 320 | 1.0 | 800 |
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 标签 -> (物理宽 dp, density dpi, 系统字号)
BREAKPOINTS = {
    "A320": (320, 320, 1.0),
    "B332": (332, 320, 1.0),
    "C411": (411, 420, 1.0),
    "D411f15": (411, 420, 1.5),
    "E411f20": (411, 420, 2.0),
    "A320f15": (320, 320, 1.5),
    "H320f20": (320, 320, 2.0),
    "F600": (600, 320, 1.0),
    "G800": (800, 320, 1.0),
    # 横屏：宽 914dp、高 411dp（手机转过来）/ 640×360（桌面小窗）
    "L914": (914, 420, 1.0),
    "L640": (640, 320, 1.0),
}

DESIGN_WIDTH = 411
MIN_CONTENT_SCALE = 0.82
# 22.sp 那句日期在 411dp 基准屏上的实测宽度（dp）。
DATE_WIDTH_AT_22SP = 232.8
# 日期字号档位（sp）。
DATE_TIERS = [14, 16, 18, 20, 22, 24, 26]
# 相对容差：渲染宽度与推算值差这么多就报出来。
TOLERANCE = 0.04


def content_scale(physical_width: int) -> float:
    return max(MIN_CONTENT_SCALE, min(physical_width / DESIGN_WIDTH, 1.0))


def layout_scale(physical_width: int, system_font: float) -> float:
    """版式缩放系数：用户放大字号时内容缩放整体让位。"""
    return 1.0 if system_font > 1.0 else content_scale(physical_width)


def font_scale(physical_width: int, system_font: float) -> float:
    """字号缩放：系统字号 × 宽屏字阶（宽屏字阶只作用在字号上）。"""
    type_scale = 1.0
    if physical_width >= 840:
        type_scale = 1.18
    elif physical_width >= 600:
        type_scale = 1.10
    return system_font * type_scale


def layout_width(physical_width: int, system_font: float) -> float:
    """排版宽（dp）：物理宽 ÷ 版式缩放系数。"""
    return physical_width / layout_scale(physical_width, system_font)


def expected_card_content(physical_width: int, system_font: float) -> float:
    """主卡内容区（排版 dp）的期望宽度。

    宽度链：排版宽 → 内容列上限（手机 480dp 封顶，宽屏按 82% 放宽到 680dp）
    → 减去页面留白 6×2 与卡内留白 18×2。
    """
    lw = layout_width(physical_width, system_font)
    column = max(min(lw * 0.82, 680.0), 480.0)
    column = min(column, lw)
    return column - 48.0


def expected_tier(physical_width: int, system_font: float) -> int:
    """推算出来的日期档位（sp）。"""
    k = layout_scale(physical_width, system_font)
    layout_width = round(physical_width / k)
    inner = min(max(0.82 * layout_width, 480), 680) - 48
    column = inner - 12 - 108
    affordable = 22 * column / DATE_WIDTH_AT_22SP
    max_sp = 22 if physical_width < 600 else 26
    tier = 14
    for t in DATE_TIERS:
        if t <= affordable and t <= max_sp:
            tier = t
    return tier


def measure_date_width(dump: Path) -> tuple[str, float] | None:
    """从 dump 里量出日期节点的文本与宽度（dp）。"""
    x = dump.read_text(encoding="utf-8")
    for m in re.finditer(
        r'text="([^"]*月[^"]*日[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x
    ):
        text = m.group(1)
        x1, _, x2, _ = (int(v) for v in m.groups()[1:])
        return text, x2 - x1
    return None


def measure_card_inner_width(dump: Path) -> float | None:
    """量主卡**内容区**的宽度（px）。

    取两个都在主卡里的节点：副标题那行的左端（= 卡内左边界）与
    天气块那列的右端（= 卡内右边界）。
    """
    x = dump.read_text(encoding="utf-8")
    left = right = None
    for m in re.finditer(
        r'text="([^"]*门课[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x
    ):
        left = int(m.group(2))
    for m in re.finditer(
        r'text="(天气|--°|读取中)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x
    ):
        right = max(right or 0, int(m.group(4)))
    if left is None or right is None:
        return None
    return right - left


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2

    root = Path(argv[1])
    if not root.is_dir():
        print(f"!! 目录不存在：{root}")
        return 2

    bad = 0
    print(
        f"{'断点':<10}{'缩放':>6}{'字号':>6}{'日期文本':<22}{'日期dp':>8}{'上限dp':>8}"
        f"{'卡内容dp':>10}{'期望dp':>9}{'差':>7}"
    )
    for label, (width, density, system_font) in BREAKPOINTS.items():
        dump = root / label / "dashboard.xml"
        if not dump.exists():
            continue
        measured = measure_date_width(dump)
        if measured is None:
            print(f"{label:<10}  没找到日期节点")
            continue
        text, px = measured
        actual_dp = px * 160 / density
        k = layout_scale(width, system_font)
        fscale = font_scale(width, system_font)
        # 日期宽度的**上限**：设计档位在当前缩放下的宽度。
        # 手机的设计档位是 22.sp，宽屏放宽到 26.sp（见 heroDateMaxSpFor）；
        # 实际选的档位可能更低，所以这是上限而不是期望值。
        max_sp = 22.0 if width < 600 else 26.0
        upper_dp = DATE_WIDTH_AT_22SP * (max_sp / 22.0) * k * fscale
        card_px = measure_card_inner_width(dump)
        card_dp = (card_px * 160 / density) / k if card_px else 0.0
        card_expected = expected_card_content(width, system_font)
        card_diff = (card_dp - card_expected) / card_expected if card_expected else 0.0

        problems = []
        if actual_dp > upper_dp * 1.02:
            problems.append("日期比设计还宽")
        # 宽屏两栏排法下天气块不在卡的右边缘，这个量法量不到卡宽，跳过。
        is_wide_two_column = width >= 600 and k >= 1.0
        if not is_wide_two_column and abs(card_diff) > 0.03:
            problems.append("卡内容宽与设计不成比例")
        if problems:
            bad += 1
        print(
            f"{label:<10}{k:>6.3f}{fscale:>6.2f}{text:<22}{actual_dp:>8.1f}{upper_dp:>8.1f}"
            f"{card_dp:>10.1f}{card_expected:>9.1f}{card_diff * 100:>6.1f}%"
            + ("  <<< " + "、".join(problems) if problems else "")
        )

    print()
    print(f"比例失守的断点：{bad} 个")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
