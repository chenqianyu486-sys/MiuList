#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把电脑端启动器打包成一个**独立的 exe**。

目标电脑不需要装 Python，也不需要装 Node —— 双击就能用。

    python tools/pc-web/build.py

产物：`tools/pc-web/dist/MiuList-Web.exe`

## 打包里带了什么

- `web/` 整份资源（网页端本体）→ exe 内部解压到临时目录再托管；
- App 的图标（从 `mipmap-xxxhdpi/ic_launcher.png` 转成多尺寸 ico）。

**不带** `crypto.test.mjs` —— 那是开发时跑的自测脚本，
打进发布包只是白占体积，还让人以为线上要跑测试（App 那边的 Gradle 任务也是这么筛的）。
"""

import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
WEB_DIR = os.path.join(ROOT, "web")
ICON_PNG = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher.png")
ICON_ICO = os.path.join(HERE, "build", "icon.ico")

DIST = os.path.join(HERE, "dist")
WORK = os.path.join(HERE, "build")
SPEC = os.path.join(HERE, "build")


def make_icon() -> str:
    """把 App 的图标转成 exe 能用的 ico。"""
    if not os.path.isfile(ICON_PNG):
        print("！找不到图标，跳过：%s" % ICON_PNG)
        return ""
    try:
        from PIL import Image
    except ImportError:
        print("！没装 Pillow，跳过图标")
        return ""
    os.makedirs(WORK, exist_ok=True)
    image = Image.open(ICON_PNG).convert("RGBA")
    # 透明底铺成方形：ico 的尺寸必须是正的。
    size = max(image.size)
    square = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    square.paste(image, ((size - image.width) // 2, (size - image.height) // 2), image)
    square.save(ICON_ICO, sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("图标：%s" % ICON_ICO)
    return ICON_ICO


def stage_web() -> str:
    """把 `web/` 复制到暂存目录，剔掉开发时跑的自测。

    PyInstaller 的 `--add-data` 没法排除单个文件，所以先过一道。
    剔掉的是 `*.test.mjs` —— 那是开发时跑的自测脚本，
    打进发布包只是白占体积，还让人以为线上要跑测试
    （App 那边的 Gradle 任务也是这么筛的）。
    """
    stage = os.path.join(WORK, "webstage")
    if os.path.isdir(stage):
        shutil.rmtree(stage)
    shutil.copytree(WEB_DIR, stage)
    removed = []
    for root, _dirs, files in os.walk(stage):
        for name in files:
            if name.endswith(".test.mjs"):
                target = os.path.join(root, name)
                os.remove(target)
                removed.append(os.path.relpath(target, stage))
    if removed:
        print("剔除自测文件：%s" % "、".join(removed))
    return stage


def main() -> int:
    if not os.path.isdir(WEB_DIR):
        raise SystemExit("找不到网页端资源目录：%s" % WEB_DIR)
    # 旧网页端的入口页已经删除（桌面端以 desktop/ 为准，见 desktop/README.md）。
    # `web/` 现在只剩共享模块，没有 index.html —— 照旧打包会得到一个**打开就是
    # 404** 的 exe，而且全程不报错。宁可在这里把话说清楚。
    if not os.path.isfile(os.path.join(WEB_DIR, "index.html")):
        raise SystemExit(
            "旧网页端已删除，%s 里没有 index.html，打不出可用的 exe。\n"
            "这个启动器已经退役（保留仅为历史参考）；\n"
            "现在的桌面端在 desktop/ 里：cd desktop && npm run build，"
            "产物是单文件 desktop/dist/MiuList-Web.html。" % WEB_DIR
        )

    os.makedirs(DIST, exist_ok=True)
    os.makedirs(WORK, exist_ok=True)

    icon = make_icon()
    stage = stage_web()

    command = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
        "--name", "MiuList-Web",
        "--distpath", DIST,
        "--workpath", os.path.join(WORK, "work"),
        "--specpath", SPEC,
        # 网页端资源：SRC;DEST（Windows 用分号）
        "--add-data", "%s%sweb" % (stage, os.pathsep),
    ]
    if icon:
        command += ["--icon", icon]
    command.append(os.path.join(HERE, "launcher.py"))

    print("打包中…")
    subprocess.check_call(command, cwd=HERE)

    exe = os.path.join(DIST, "MiuList-Web.exe")
    if not os.path.isfile(exe):
        raise SystemExit("打包没产出 exe：%s" % exe)
    print("\n完成：%s（%.1f MB）" % (exe, os.path.getsize(exe) / 1024 / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
