#!/usr/bin/env python3
"""把「页面矩阵」与「弹层矩阵」拼成每个断点一张联络表。

用法：
    python tools/make_sheets.py
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGES = ROOT / ".tmp/adapt/matrix"
DIALOGS = ROOT / ".tmp/adapt/dialogs"
STAGE = ROOT / ".tmp/adapt/sheets"
OUT = ROOT / ".tmp/adapt/sheets_out"

# 断点 -> (页面矩阵里的目录名, 弹层矩阵里的目录名)
BREAKPOINTS = [
    ("A320", "A320", "320dp 最窄的手机"),
    ("C411", "C411", "411dp 设计基准（= 你的 1080×2400 屏）"),
    ("G800", "G800", "800dp 宽屏（平板 / 折叠屏展开）"),
    ("E411f20", "E411f20", "411dp + 系统字号 2.0 倍"),
]

# 联络表里的顺序：先页面，再弹层
ORDER = [
    ("pages", "dashboard.png", "01_总览"),
    ("pages", "schedule.png", "02_课表"),
    ("pages", "checklist.png", "03_清单"),
    ("dialogs", "04_week_picker.png", "04_周次选择"),
    ("dialogs", "05_task_detail.png", "05_任务详情"),
    ("dialogs", "06_task_edit.png", "06_新建待办"),
    ("dialogs", "07_course_detail.png", "07_课程详情"),
    ("dialogs", "08_drawer.png", "08_更多抽屉"),
    ("dialogs", "09_cloud_account.png", "09_教务网账户"),
    ("dialogs", "10_academic_calendar.png", "10_本学期校历"),
    ("dialogs", "11_course_category.png", "11_课程分类"),
    ("dialogs", "12_category_rules.png", "12_课程联动规则"),
    ("dialogs", "13_task_rules.png", "13_待办生成规则"),
    ("dialogs", "14_add_course.png", "14_添加课程"),
    ("dialogs", "15_settings.png", "15_设置页"),
    ("dialogs", "16_transfer.png", "16_数据导入导出"),
    ("dialogs", "17_task_category_manage.png", "17_待办分类管理"),
    ("dialogs", "18_wallpaper.png", "18_自定义壁纸"),
    ("dialogs", "19_new_task_type.png", "19_新建待办类型"),
]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for page_label, dlg_label, title in BREAKPOINTS:
        stage = STAGE / page_label
        if stage.exists():
            shutil.rmtree(stage)
        stage.mkdir(parents=True)
        srcs = {"pages": PAGES / page_label, "dialogs": DIALOGS / dlg_label}
        missing = []
        for kind, name, out_name in ORDER:
            src = srcs[kind] / name
            if not src.exists():
                missing.append(name)
                continue
            shutil.copy2(src, stage / f"{out_name}.png")
        print(f"[{page_label}] 拼入 {len(list(stage.glob('*.png')))} 张" + (f"  缺: {missing}" if missing else ""))
        out = OUT / f"sheet_{page_label}.png"
        subprocess.run(
            [sys.executable, str(ROOT / "tools/contact_sheet.py"), str(stage), str(out),
             "--cols", "4", "--cell", "300", "--title", f"MiuList · {title}（{page_label}）"],
            check=False,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
