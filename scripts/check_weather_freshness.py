#!/usr/bin/env python3
"""
看 `weather.json` **还能不能用** —— 上游那两个信号。

## 为什么需要它

托管文件的调度会滞后：GitHub 的定时任务在高峰期会**跳过整点**。
实测 93 次调度，平均间隔 4.45 小时、最长 10.77 小时 —— 而 cron 写的是每小时一次。
于是「声明每小时」与「实际多久一次」是两回事，而且**没有任何信号**说
「这份天气还能不能用」。

这个脚本就是那个信号：不满足就**退出码非 0**，
看门狗工作流（`.github/workflows/weather-watchdog.yml`）据此补跑一次同步，
并把结论亮出来（红的）。

## 为什么用文件自己的 `lastUpdated` 而不是文件修改时间

`lastUpdated` 是 CI 写文件时打的时间戳，写在文件**内容**里 ——
所以它跟着文件走（提交、推送、镜像都带着它），而 git 的提交时间、
文件系统 mtime 都不跟着内容走。用内容里的那个戳，判的是**这份数据**多旧。

## 它判两件事，不是一件

| 判据 | 判的是什么 | 不满足时客户端会怎样 |
|---|---|---|
| `lastUpdated` 的年龄 ≤ 3 小时 | 这份数据有**多旧** | 仍然显示（退回缓存），下次进来会重新去拉 |
| `date` 是**今天**（北京时区） | 这份数据是**哪一天**的 | **直接不显示**，天气块变成「暂未取到」 |

第二条是后补的，原因见下 —— 少判这一条的看门狗有一个每天都会踩的盲区。

## 为什么必须也判 `date`

客户端的 `WeatherService` 有**两把尺**，缺一不可：

1. 文件年龄 < `FRESH_WINDOW_MILLIS`（3 小时）—— 见 `dataAgeMillis()`；
2. **`date` 等于今天** —— 见 `cachedToday()` 里那句 `epochDay == today()`，
   判的是 `AppZone.todayEpochDay()`，也就是**北京时区的今天**。

一份 `date` 是昨天的文件，年龄再新也没用：它会在 `cachedToday()` 里被按
「不是今天」判掉，`refresh()` 拿到它同样判掉，界面最后显示的是「暂未取到」。

而只量年龄的看门狗**恰好在凌晨失明**：换日之后、下一次同步之前
（实测调度间隔平均 4.88 小时），文件的年龄只有几小时（够新），
`date` 却已经是昨天了。**那一段它判绿，而用户看到的是空的那一格。**

实测这份仓库的历史：覆盖 438.7 小时、98 个版本，其中 **40.5 小时**
处于「`date` 已经不是今天」的状态，共 **18 段**，最长一段 **4.0 小时**；
这 40.5 小时里有 **14.7 小时**文件年龄仍 < 3 小时 ——
也就是说这 14.7 小时里看门狗是绿的，而客户端照样显示「暂未取到」。
形态非常固定：`10-27 00:03 → 04:01`、`10-29 00:07 → 03:39`、`11-01 00:04 → 03:24`。

## 为什么用固定 +08，而不是 `ZoneInfo("Asia/Shanghai")`

runner 是 UTC：`date.today()` 在 16:00Z~24:00Z 之间比北京的日期**落后一天**，
拿它判「是不是今天」会每天误报 8 小时。
而 `ZoneInfo` 依赖系统 tzdata 是否装齐 —— 中国 1991 年后没有夏令时，
`+08:00` 与 `Asia/Shanghai` 在这件事上恒等，写死偏移既准确又少一个依赖。
（客户端那边用的确实是 `Asia/Shanghai`，两者等价。）

## 用法

    python scripts/check_weather_freshness.py [--max-age-hours 3] [路径]

退出码：0 = 又新又是今天的；1 = 太旧 / 不是今天 / 读不出来（几条理由会一起报出来）。
"""
import argparse
import datetime
import json
import os
import sys
import time

# 北京时区。**固定 +08 而不是 ZoneInfo**：中国 1991 年后没有夏令时，
# 两者等价，而固定偏移不依赖 runner 上装没装 tzdata（见模块 docstring）。
BEIJING = datetime.timezone(datetime.timedelta(hours=8))


def default_path() -> str:
    """仓库根目录下的 weather.json（脚本在 scripts/ 下，所以往上一级）。"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(script_dir), "weather.json")


def age_hours(last_updated, now_ms: float) -> float:
    """这份文件有多旧（小时）。读不出时间戳就抛异常，不猜。"""
    if not isinstance(last_updated, (int, float)) or isinstance(last_updated, bool):
        raise ValueError("没有可用的 lastUpdated")
    return (now_ms - float(last_updated)) / 1000.0 / 3600.0


def documented_date(value) -> datetime.date:
    """文件里的 `date`（"YYYY-MM-DD"）。解不出来就抛异常，不猜。"""
    if not isinstance(value, str):
        raise ValueError(f"date 不是字符串：{value!r}")
    return datetime.date.fromisoformat(value)


def main() -> int:
    parser = argparse.ArgumentParser(description="检查 weather.json 还能不能用")
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
        with open(path, encoding="utf-8") as f:
            payload = json.load(f)
    except Exception as e:  # 读不出来同样是「不能用」，不该当成「能用」放过去
        print(f"::error::读不出 {path}：{e}", file=sys.stderr)
        return 1

    now_ms = time.time() * 1000.0
    now_beijing = datetime.datetime.fromtimestamp(now_ms / 1000.0, BEIJING)

    # 两个理由分开收集、一起报：它们的修法不同 ——
    # 太旧是多跑一次同步就能好，日期不对往往说明上游的 daily[0] 还没翻页。
    problems = []

    # ① 年龄：这份数据有多旧。
    try:
        age = age_hours(payload.get("lastUpdated"), now_ms)
    except ValueError as e:
        problems.append(f"weather.json 里{e}")
    else:
        if age < 0:
            # 时间戳在未来 = 两端时钟不一致，或者文件被改坏了。
            # 这种情况下「有多旧」根本判不出来，不能当成「很新」放过去。
            problems.append(
                f"lastUpdated 在未来（{age:.2f} 小时），时钟不一致或文件异常"
            )
        elif age > args.max_age_hours:
            problems.append(
                f"这份天气已经 {age:.2f} 小时没更新了（上限 {args.max_age_hours} 小时）"
            )
        else:
            print(
                f"weather.json 距上次写入 {age:.2f} 小时"
                f"（上限 {args.max_age_hours} 小时）"
            )

    # ② 日期：这份数据是哪一天的。
    # **这一条才是客户端「能不能显示」的硬拦路** —— 少判它，看门狗就会在凌晨判绿。
    try:
        dated = documented_date(payload.get("date"))
    except Exception as e:
        problems.append(f"weather.json 里的 date 读不出来：{e}")
    else:
        if dated != now_beijing.date():
            problems.append(
                f"weather.json 里的 date 是 {dated}，今天（北京时区）是 "
                f"{now_beijing.date()} —— 客户端会按「不是今天」把它判掉，"
                f"天气块显示「暂未取到」"
            )
        else:
            print(f"weather.json 的 date 是 {dated}，是今天（北京时区）")

    for problem in problems:
        print(f"::error::{problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
