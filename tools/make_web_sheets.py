#!/usr/bin/env python3
"""把网页端的截图矩阵拼成联络表。

`tools/contact_sheet.py` 按文件名排序拼一个目录；这里先把矩阵按**屏幕尺寸**
分组、按**场景顺序**重命名，交给它拼。

用法：
    python tools/make_web_sheets.py .tmp/shots-v6 .tmp/sheets-web
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 场景的展示顺序（截图文件名是 `<尺寸>-<场景>.png`）
SCENES = [
    ("today", "01_总览"),
    ("week", "02_课表"),
    ("tasks", "03_清单"),
    ("sync", "04_设置·连接"),
    ("snapshot", "05_设置·快照"),
]

SIZES = [
    ("phone-390", "手机 390×844"),
    ("tablet-768", "平板 768×1024"),
    ("desktop-1280", "桌面 1280×800"),
    ("desktop-1600", "桌面 1600×900"),
]


def main() -> int:
    src = Path(sys.argv[1] if len(sys.argv) > 1 else ".tmp/shots-v6")
    out = Path(sys.argv[2] if len(sys.argv) > 2 else ".tmp/sheets-web")
    if not src.is_dir():
        print(f"!! 目录不存在：{src}")
        return 2

    stage_root = out / "_stage"
    if stage_root.exists():
        shutil.rmtree(stage_root)
    out.mkdir(parents=True, exist_ok=True)

    made = []
    for size, title in SIZES:
        stage = stage_root / size
        stage.mkdir(parents=True)
        n = 0
        for scene, label in SCENES:
            f = src / f"{size}-{scene}.png"
            if not f.exists():
                print(f"!! 缺图：{f.name}")
                continue
            shutil.copy2(f, stage / f"{label}.png")
            n += 1
        if not n:
            continue
        target = out / f"sheet_{size}.png"
        subprocess.run(
            [sys.executable, str(ROOT / "tools/contact_sheet.py"), str(stage), str(target),
             "--cols", "3", "--cell", "360", "--title", f"MiuList 网页端 · {title}"],
            check=False,
        )
        made.append(target)

    # 再来一张全尺寸总览（20 张，5 列）
    all_stage = stage_root / "all"
    all_stage.mkdir(parents=True)
    for size, _size_title in SIZES:
        for scene, label in SCENES:
            f = src / f"{size}-{scene}.png"
            if f.exists():
                # 用**尺寸键**当前缀：两张桌面图都叫「桌面」，会互相覆盖。
                shutil.copy2(f, all_stage / f"{size}_{label}.png")
    overview = out / "sheet_all.png"
    subprocess.run(
        [sys.executable, str(ROOT / "tools/contact_sheet.py"), str(all_stage), str(overview),
         "--cols", "5", "--cell", "320", "--title", "MiuList 网页端 · 全尺寸总览"],
        check=False,
    )
    made.append(overview)

    print("\n联络表：")
    for p in made:
        print(f"  {p}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
