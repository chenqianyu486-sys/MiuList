#!/usr/bin/env python3
"""
看 `weather.json` 有多旧 —— **上游新鲜度的那个信号**。

## 为什么需要它

托管文件的调度会滞后：GitHub 的定时任务在高峰期会**跳过整点**。
实测 93 次调度，平均间隔 4.45 小时、最长 10.77 小时 —— 而 cron 写的是每小时一次。
于是「声明每小时」与「实际多久一次」是两回事，而且**没有任何信号**说
「这份天气已经多久没更新了」。

这个脚本就是那个信号：超过上限就**退出码非 0**，
看门狗工作流（`.github/workflows/weather-watchdog.yml`）据此补跑一次同步，
并把结论亮出来（红的）。

## 为什么用文件自己的 `lastUpdated` 而不是文件修改时间

`lastUpdated` 是 CI 写文件时打的时间戳，写在文件**内容**里 ——
所以它跟着文件走（提交、推送、镜像都带着它），而 git 的提交时间、
文件系统 mtime 都不跟着内容走。用内容里的那个戳，判的是**这份数据**多旧。

## 用法

    python scripts/check_weather_freshness.py [--max-age-hours 3] [路径]

退出码：0 = 还新鲜；1 = 太旧或读不出来。
"""
import argparse
import json
import os
import sys
import time


def default_path() -> str:
    """仓库根目录下的 weather.json（脚本在 scripts/ 下，所以往上一级）。"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(script_dir), "weather.json")


def age_hours(path: str) -> float:
    """这份文件有多旧（小时）。读不出时间戳就抛异常，不猜。"""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    last = data.get("lastUpdated")
    if not isinstance(last, (int, float)) or isinstance(last, bool):
        raise ValueError("weather.json 里没有可用的 lastUpdated")
    return (time.time() * 1000.0 - float(last)) / 1000.0 / 3600.0


def main() -> int:
    parser = argparse.ArgumentParser(description="检查 weather.json 有多旧")
    parser.add_argument(
        "--max-age-hours",
        type=float,
        default=3.0,
        help="超过这个小时数就算太旧（默认 3，与上游的承诺一致）",
    )
    parser.add_argument("path", nargs="?", default=None, help="weather.json 的路径")
    args = parser.parse_args()

    path = args.path or default_path()
    try:
        age = age_hours(path)
    except Exception as e:  # 读不出来同样是「不新鲜」，不该当成「新鲜」放过去
        print(f"::error::读不出 {path} 的新鲜度：{e}", file=sys.stderr)
        return 1

    print(f"weather.json 距上次写入 {age:.2f} 小时（上限 {args.max_age_hours} 小时）")
    if age < 0:
        # 时间戳在未来 = 两端时钟不一致，或者文件被改坏了。
        # 这种情况下「有多旧」根本判不出来，不能当成「很新」放过去。
        print(
            f"::error::lastUpdated 在未来（{age:.2f} 小时），时钟不一致或文件异常",
            file=sys.stderr,
        )
        return 1
    if age > args.max_age_hours:
        print(
            f"::error::这份天气已经 {age:.2f} 小时没更新了（上限 {args.max_age_hours} 小时）",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
