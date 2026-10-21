#!/usr/bin/env python3
"""检查一个断点目录下的 uiautomator dump：有没有节点被挤出屏幕。

## 为什么要有这个检查

这个 App 的自适应事故大多长这样：**某个控件被挤到屏幕外**，或者
**被挤成 0 宽**（文字还在，但一个像素都看不见）。

最典型的一次是顶栏：系统字号 2.0 + 320dp 屏时，学期名把整行吃满，
右边两个常驻动作被推到屏幕外——**实测只剩一小条绿色**。截图上肉眼看
「顶栏好像有点怪」，但说不清哪儿怪；节点越界一查，精确到是哪个节点。

所以这个脚本只报**结构上确凿**的问题，不做「看起来不对」的判断：

1. 节点边界超出屏幕（被挤出屏幕）；
2. 有文字但宽或高为 0（被挤没了）。

## 不报什么

**不报「文字被 Ellipsis 截断」**。uiautomator 拿到的是 Compose 的**语义原文**，
截断只发生在渲染层，dump 里看不出来；要判断截断得看截图，那是
compare_baseline.py 的活。这里硬猜只会满屏误报，误报一多，脚本就没人跑了。

用法：
    python tools/check_bounds.py .tmp/adapt/matrix/B332
    python tools/check_bounds.py .tmp/adapt/matrix   # 递归所有子目录
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 允许 1px 的误差：uiautomator 的 bounds 是整数，Compose 的布局是浮点，
# 四舍五入到像素时差 1 是正常的。
TOLERANCE_PX = 1


def bounds_of(node: ET.Element) -> tuple[int, int, int, int] | None:
    raw = node.get("bounds", "")
    # 形如 [x1,y1][x2,y2]
    try:
        left, top, right, bottom = (
            int(part) for part in raw.replace("[", ",").replace("]", ",").split(",") if part != ""
        )
    except ValueError:
        return None
    return left, top, right, bottom


def label_of(node: ET.Element) -> str:
    text = node.get("text", "").strip()
    desc = node.get("content-desc", "").strip()
    if text:
        return f'text="{text}"'
    if desc:
        return f'desc="{desc}"'
    return f'class={node.get("class", "?")}'


def check_dump(path: Path) -> list[str]:
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        return [f"  !! {path.name}: XML 解析失败（dump 可能是空的）: {exc}"]

    root = tree.getroot()
    nodes = list(root.iter("node"))
    if not nodes:
        return [f"  !! {path.name}: dump 里一个节点都没有"]

    # 屏幕尺寸：取所有节点里最大的右/下边界（根节点就是全屏）
    screen_w = screen_h = 0
    for node in nodes:
        box = bounds_of(node)
        if box:
            screen_w = max(screen_w, box[2])
            screen_h = max(screen_h, box[3])

    problems: list[str] = []
    for node in nodes:
        box = bounds_of(node)
        if box is None:
            continue
        left, top, right, bottom = box
        has_text = bool(node.get("text", "").strip() or node.get("content-desc", "").strip())

        if right - left <= 0 or bottom - top <= 0:
            # 全屏那个节点自己是 0 宽高的情况不存在，但保险起见跳过全屏节点
            if not (right - left <= 0 and right == 0 and bottom == 0):
                if has_text:
                    problems.append(
                        f"  !! {path.name}: 节点被挤没了（宽 {right - left} / 高 {bottom - top}）"
                        f" {label_of(node)}"
                    )
            continue

        if (
            left < -TOLERANCE_PX
            or top < -TOLERANCE_PX
            or right > screen_w + TOLERANCE_PX
            or bottom > screen_h + TOLERANCE_PX
        ):
            problems.append(
                f"  !! {path.name}: 节点超出屏幕（{screen_w}x{screen_h}）"
                f" bounds=[{left},{top}][{right},{bottom}] {label_of(node)}"
            )
    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2

    target = Path(argv[1])
    dumps = sorted(target.rglob("*.xml")) if target.is_dir() else [target]
    if not dumps:
        print(f"  没有找到 dump：{target}")
        return 0

    total = 0
    for dump in dumps:
        problems = check_dump(dump)
        for line in problems:
            print(line)
        total += len(problems)

    print(f"  检查 {len(dumps)} 个 dump，发现 {total} 处越界/挤没")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
