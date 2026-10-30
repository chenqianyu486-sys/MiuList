#!/usr/bin/env python3
"""发版助手：生成发布说明、拼 GitHub API 的 JSON 载荷。

## 为什么单独一个 Python 文件

PowerShell 5.1 的 `ConvertTo-Json` 会把字符串序列化成 `{"value": "..."}` 这种**对象**，
GitHub 收到后判正文非法（先报 Invalid request，换 curl 又报 body is too long）。
拼 JSON 这种事交给一个真正会转义的实现，比跟 PowerShell 的隐式类型转换较劲靠谱。

用法：
    python tools/release_helper.py notes  --version v1.0.2 --prev-tag v1.0.1 --commits-file x.txt
    python tools/release_helper.py payload --name "..." --tag v1.0.2 --notes-file notes.md --out payload.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HEADER_TESTING = """## MiuList {version}（测试版）

> ⚠️ **这是测试版，不是正式发布版。** 功能尚未经过充分验证，可能存在 bug 或数据问题，
> 请先在测试机上安装，重要数据请自行备份。正式版会在测试反馈收敛后另行发布。

{summary}

### 🎨 这一版改了什么

{changes}

### 📦 安装

- 下载 `{apk_name}` 直接安装，无需 Google Play。
- 最低系统要求：Android 7.0（API 24）；targetSdk 36。
- 覆盖安装：本版本与上一版使用**同一把上传密钥**（证书 SHA-256 一致），
  可直接覆盖升级，不会丢数据。

### ⚠️ 说明

- 自签名发布，签名密钥不在仓库中，请自行妥善保管。
- 项目基于 GPL-3.0 开源，欢迎提 issue 与 PR。
"""

# 正式版说明：结构与测试版一致，只把「这是测试版」那一段警告换成一句确认。
#
# 为什么单独一份模板而不是给测试版模板加个开关：两者的**开头语气**完全不同 ——
# 测试版要劝人别当正式版用，正式版要说清「可以升级了，但先备份」。
# 用一个模板加条件拼字符串，最后一定会漏掉某一处的措辞。
HEADER_STABLE = """## MiuList {version}（正式版）

> ✅ 这是正式版。升级前建议先备份数据。

{summary}

### 🎨 这一版改了什么

{changes}

### 📦 安装

- 下载 `{apk_name}` 直接安装，无需 Google Play。
- 最低系统要求：Android 7.0（API 24）；targetSdk 36。
- 覆盖安装：本版本与上一版使用**同一把上传密钥**（证书 SHA-256 一致），
  可直接覆盖升级，不会丢数据。

### ⚠️ 说明

- 自签名发布，签名密钥不在仓库中，请自行妥善保管。
- 项目基于 GPL-3.0 开源，欢迎提 issue 与 PR。
"""


def cmd_notes(args: argparse.Namespace) -> int:
    commits = []
    if args.commits_file and Path(args.commits_file).exists():
        for line in Path(args.commits_file).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            # 自动同步的天气数据不进说明：它不是「这一版改了什么」。
            if line.lower().startswith("chore(weather)"):
                continue
            commits.append(line)

    if commits:
        changes = "\n".join(f"- {c}" for c in commits)
    else:
        changes = "- （本次没有功能性提交）"

    if args.summary:
        summary = args.summary.strip()
    else:
        summary = f"v{args.prev_tag.lstrip('v')} 之后的版本。"

    # 正式版用正式版模板：说明里留着「这是测试版」的警告，
    # 会让用户以为拿到的不是能长期用的版本。
    template = HEADER_STABLE if args.stable else HEADER_TESTING
    text = template.format(
        version=args.version,
        summary=summary,
        changes=changes,
        apk_name=args.apk_name,
    )
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"发布说明已写入 {args.out}（{len(text)} 字符）")
    else:
        sys.stdout.write(text)
    return 0


def cmd_payload(args: argparse.Namespace) -> int:
    notes = Path(args.notes_file).read_text(encoding="utf-8")
    payload = {
        "name": args.name,
        "body": notes,
        "prerelease": bool(args.prerelease),
        "draft": False,
    }
    # tag_name 是 GitHub 建 release 的**必填**项：少了它，API 直接 422
    # 「"tag_name" wasn't supplied.」（v1.0.2 那次真踩过）
    if args.tag:
        payload["tag_name"] = args.tag
    # ensure_ascii=False：中文与 emoji 原样写出，文件再按 UTF-8 存盘。
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    Path(args.out).write_text(text, encoding="utf-8")
    print(f"载荷已写入 {args.out}（{len(text)} 字符）")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    n = sub.add_parser("notes", help="生成发布说明")
    n.add_argument("--version", required=True)
    n.add_argument("--prev-tag", default="")
    n.add_argument("--summary", default="")
    n.add_argument("--apk-name", default="")
    n.add_argument("--commits-file", default="")
    n.add_argument("--out", default="")
    n.add_argument("--stable", action="store_true", help="生成正式版说明（不写测试版警告）")
    n.set_defaults(func=cmd_notes)

    p = sub.add_parser("payload", help="拼 GitHub API 的 JSON 载荷")
    p.add_argument("--name", required=True)
    p.add_argument("--tag", default="", help="tag_name（建 release 必填）")
    p.add_argument("--notes-file", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--prerelease", action="store_true")
    p.set_defaults(func=cmd_payload)

    args = ap.parse_args(argv[1:])
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
