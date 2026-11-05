#!/usr/bin/env python3
"""
生成静态 weather.json，由 GitHub Actions 定时提交，App 只拉这个文件。

**两个数据源，各管一半：**

| 字段 | 来源 | 为什么 |
|---|---|---|
| 天气（温度、天气现象） | 心知天气 v3 `weather/daily.json` | 地面观测，用户更信 |
| 空气质量（AQI） | Open-Meteo `air-quality` | **心知那把 key 没有空气接口权限** |

空气质量为什么不用心知：官方错误码表里 `AP010002` = 「没有权限访问这个 API 接口」，
而 V3 手册把「天气类」和「空气类」列成**两个并列的产品分类** ——
只开通天气类的 key 调 `air/now.json` 一律 403。实测就是这个码。
Open-Meteo 的空气端点不要 key、不要注册，配额也不绑定账号。

## 两套 AQI，两个字段

Open-Meteo 原生只给 `us_aqi`（美国 EPA 口径）。它和国标 HJ 633-2012
**不是同一把尺子**：六档的边界数字虽然都是 50/100/150/200/300，
但同一浓度换算出的指数不同 —— PM2.5 35 µg/m³ 在国标是 50（优/良边界），
在美国是 99（良边缘）；75 µg/m³ 国标是 100，美国约 161。

所以这里做两件事，**不把两者混成一个数**：

- `aqi` / `aqiQuality`：用 Open-Meteo 的六污染物 hourly 按 HJ 633-2012
  算出的**中国国标** AQI（PM/NO2/SO2/CO 用 24h 均值，O3 用 8h 均值）。
- `usAqi` / `usAqiQuality`：Open-Meteo 原生的 `us_aqi`，原样保留。

客户端设置里二选一。`aqi` 继续放在原来的键上，是为了让还没升级的
旧客户端至少能显示国标数 —— 旧版本那张等级表本来就是国标分段。

脚本跑在 GitHub Actions 上（美国机房），访问 Open-Meteo 没有可达性问题。

## 空气失败必须留下痕迹

上一版把空气请求整个 except 掉、只往 stderr 打一行，然后照样把 `aqi: null`
写进 weather.json 提交上去 —— 于是 Actions 连续十次运行 conclusion 全是
success，**没有任何一个信号说「空气这一项已经连挂很久了」**，直到有人在
App 里发现城市名旁边少了一段。

所以现在两件事是硬要求：

1. **HTTP 错误要读正文。** urllib 的 `str(e)` 只有 `HTTP Error 4xx: ...`，
   而服务端早把原因写在正文里了。只报状态码，等于把答案扔掉
   （权限不足 / 参数写错 / 额度用尽，三种在日志里长得一模一样）。
2. **失败原因写进 weather.json 的 `aqiError`。** 写进日志还不够 ——
   日志要翻 Actions 页面，而这个文件 `open` 一下就能看到。
"""
import os
import sys
import json
import time
import urllib.request
import urllib.parse
import urllib.error

SENIVERSE_KEY = os.environ.get("SENIVERSE_KEY")
LOCATION = os.environ.get("WEATHER_LOCATION", "hangzhou")
CITY_NAME = os.environ.get("WEATHER_CITY_NAME", "杭州")

# 空气端点只认经纬度，不认地名。
# **必须与客户端 WeatherLocation.DEFAULT 一致**（30.2741, 120.1551）：
# 天气查杭州、空气查别处，界面上会并排显示两个地方的数据。
LATITUDE = float(os.environ.get("WEATHER_LATITUDE", "30.2741"))
LONGITUDE = float(os.environ.get("WEATHER_LONGITUDE", "120.1551"))

# 与客户端 AppZone（app/src/main/java/com/example/data/calendar/AppZone.kt）一致。
# `current` 本身不分日，但两端时区不一致时排查问题会很难受。
TIMEZONE = "Asia/Shanghai"


class FetchError(Exception):
    """一句话就能定位问题的失败原因。已经带上了服务端正文。"""


def fetch_json(url: str):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "MiuList-WeatherSync/1.0"}
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # **正文才是答案。** 少了这一段，权限不足、参数写错、额度用尽
        # 三种完全不同的情况在日志里长得一模一样。
        body = e.read().decode("utf-8", errors="replace")[:300].strip()
        raise FetchError(f"HTTP {e.code} · {body}") from e
    except urllib.error.URLError as e:
        raise FetchError(f"网络不可达：{e.reason}") from e
    except TimeoutError as e:
        raise FetchError(f"超时：{e}") from e
    except json.JSONDecodeError as e:
        raise FetchError(f"响应不是合法 JSON：{e}") from e


def parse_int(v):
    if v is None or v == "":
        return None
    try:
        return int(round(float(v)))
    except (ValueError, TypeError):
        return None


# ------------------------------------------------------------------ 国标 AQI
#
# HJ 633-2012 表 1 的 IAQI 分段。每项是
# (浓度下限, 浓度上限, IAQI 下限, IAQI 上限)。
#
# 浓度单位：PM2.5 / PM10 / SO2 / NO2 / O3 为 µg/m³，CO 为 mg/m³。
# 这些数字是国标原文，别按美国 EPA 的断点改 —— 两套断点在低浓度段
# 差得很大（PM2.5 35 在国标是 50，在美国是 99）。
CN_IAQI_BREAKPOINTS = {
    "pm2_5": (
        (0, 35, 0, 50),
        (35, 75, 50, 100),
        (75, 115, 100, 150),
        (115, 150, 150, 200),
        (150, 250, 200, 300),
        (250, 350, 300, 400),
        (350, 500, 400, 500),
    ),
    "pm10": (
        (0, 50, 0, 50),
        (50, 150, 50, 100),
        (150, 250, 100, 150),
        (250, 350, 150, 200),
        (350, 420, 200, 300),
        (420, 500, 300, 400),
        (500, 600, 400, 500),
    ),
    "so2": (
        (0, 50, 0, 50),
        (50, 150, 50, 100),
        (150, 475, 100, 150),
        (475, 800, 150, 200),
        (800, 1600, 200, 300),
        (1600, 2100, 300, 400),
        (2100, 2620, 400, 500),
    ),
    "no2": (
        (0, 40, 0, 50),
        (40, 80, 50, 100),
        (80, 180, 100, 150),
        (180, 280, 150, 200),
        (280, 565, 200, 300),
        (565, 750, 300, 400),
        (750, 940, 400, 500),
    ),
    "co": (
        (0, 2, 0, 50),
        (2, 4, 50, 100),
        (4, 14, 100, 150),
        (14, 24, 150, 200),
        (24, 36, 200, 300),
        (36, 48, 300, 400),
        (48, 60, 400, 500),
    ),
    # O3 用 8 小时滑动平均；国标这张表到 300 就封顶。
    "o3": (
        (0, 100, 0, 50),
        (100, 160, 50, 100),
        (160, 215, 100, 150),
        (215, 265, 150, 200),
        (265, 800, 200, 300),
    ),
}

CN_POLLUTANT_LABELS = {
    "pm2_5": "PM2.5",
    "pm10": "PM10",
    "so2": "SO2",
    "no2": "NO2",
    "co": "CO",
    "o3": "O3",
}


def describe_aqi(aqi):
    """AQI → 六档中文等级。国标与 EPA 在这六个**指数**分界上一致。"""
    if aqi <= 50:
        return "优"
    if aqi <= 100:
        return "良"
    if aqi <= 150:
        return "轻度污染"
    if aqi <= 200:
        return "中度污染"
    if aqi <= 300:
        return "重度污染"
    return "严重污染"


def iaqi(value, breakpoints):
    """
    一项污染物浓度 → IAQI。线性插值，四舍五入取整。

    超过表尾就按最高一档封顶：真出现 >500 说明这一片已经不能呼吸了，
    给它一个单独的名字没有意义（与客户端 describeAqi 的口径一致）。
    """
    if value is None:
        return None
    try:
        value = float(value)
    except (TypeError, ValueError):
        return None
    if value < 0:
        return None

    for lo, hi, ilo, ihi in breakpoints:
        if value <= hi:
            if hi == lo:
                return ihi
            return int(ilo + (value - lo) / (hi - lo) * (ihi - ilo) + 0.5)
    return breakpoints[-1][3]


def trailing_average(values, end_index, window):
    """
    [end-window+1, end] 的算术平均。**不足 window 个有效值就返回 None**。

    宁可少一项污染物，也不要用半截窗口算一个看起来像 24h 均值的数 ——
    那个数会被当成合规读数写进 weather.json，比缺项更难发现。
    """
    if not isinstance(values, list) or end_index < 0:
        return None
    start = end_index - window + 1
    if start < 0 or end_index >= len(values):
        return None
    chunk = values[start:end_index + 1]
    if len(chunk) != window or any(v is None for v in chunk):
        return None
    return sum(float(v) for v in chunk) / window


def compute_china_aqi(hourly, current_time):
    """
    用 Open-Meteo 的 hourly 六污染物算**当前小时的国标 AQI**。

    - PM2.5 / PM10 / SO2 / NO2 / CO：截至当前小时的 24h 滑动平均；
    - O3：截至当前小时的 8h 滑动平均；
    - AQI = 六项 IAQI 的最大值；>50 时最大项就是首要污染物。
    """
    times = hourly.get("time") or []
    if current_time in times:
        end = times.index(current_time)
    else:
        # current 与 hourly 的时间戳偶尔会差几分钟/一小时；取不晚于
        # current_time 的最后一个小时，而不是直接放弃整份空气。
        candidates = [i for i, t in enumerate(times) if t <= current_time]
        if not candidates:
            raise ValueError(f"hourly 里找不到 {current_time} 对应的时刻")
        end = candidates[-1]

    concentrations = {
        "pm2_5": trailing_average(hourly.get("pm2_5"), end, 24),
        "pm10": trailing_average(hourly.get("pm10"), end, 24),
        "so2": trailing_average(hourly.get("sulphur_dioxide"), end, 24),
        "no2": trailing_average(hourly.get("nitrogen_dioxide"), end, 24),
        "co": trailing_average(hourly.get("carbon_monoxide"), end, 24),
        "o3": trailing_average(hourly.get("ozone"), end, 8),
    }
    if concentrations["co"] is not None:
        # Open-Meteo 的 CO 是 µg/m³，国标表用 mg/m³。
        concentrations["co"] /= 1000.0

    iaqis = {
        key: iaqi(concentrations[key], CN_IAQI_BREAKPOINTS[key])
        for key in concentrations
    }
    valid = {key: value for key, value in iaqis.items() if value is not None}
    if not valid:
        raise ValueError("六项污染物都没有足够的 24h/8h 数据")

    aqi = max(valid.values())
    primary_key = max(valid, key=valid.get) if aqi > 50 else None
    return {
        "aqi": aqi,
        "quality": describe_aqi(aqi),
        "primary": CN_POLLUTANT_LABELS.get(primary_key),
        "iaqi": {
            CN_POLLUTANT_LABELS[key]: value for key, value in valid.items()
        },
        "concentrations": {
            CN_POLLUTANT_LABELS[key]: (
                round(value, 2) if value is not None else None
            )
            for key, value in concentrations.items()
        },
        "time": times[end],
    }


def compute_us_aqi(current):
    """
    Open-Meteo 原生的美国 EPA AQI。**不做任何换算**。

    它的 `us_aqi` 是按 PM2.5 的 24h 均值算的（用 hourly 序列能复算出来），
    不是“当前小时浓度”的指数。这一点也要如实保留 —— 换成国标字段时
    不能假装两边只是换了个名字。
    """
    aqi = parse_int(current.get("us_aqi"))
    if aqi is None:
        raise ValueError(
            f"current 里没有 us_aqi：{json.dumps(current, ensure_ascii=False)[:300]}"
        )

    per_pollutant = {}
    for key, label in (
        ("us_aqi_pm2_5", "PM2.5"),
        ("us_aqi_pm10", "PM10"),
        ("us_aqi_ozone", "O3"),
        ("us_aqi_nitrogen_dioxide", "NO2"),
        ("us_aqi_sulphur_dioxide", "SO2"),
        ("us_aqi_carbon_monoxide", "CO"),
    ):
        value = parse_int(current.get(key))
        if value is not None:
            per_pollutant[label] = value

    primary = max(per_pollutant, key=per_pollutant.get) if aqi > 50 and per_pollutant else None
    return {
        "aqi": aqi,
        "quality": describe_aqi(aqi),
        "primary": primary,
        "iaqi": per_pollutant,
    }


def fetch_air_quality():
    """
    从 Open-Meteo 取空气质量，一次请求拿两套口径。**永远不抛**。

    返回：
      {
        "cn": {"aqi", "quality", "primary", "iaqi", "concentrations", "time"} | None,
        "us": {"aqi", "quality", "primary", "iaqi"} | None,
        "time": 上游 current 的时刻 | None,
        "cnError": 国标算不出来的原因 | None,
        "usError": 美国 AQI 拿不到的原因 | None,
      }

    空气不是天气的前提：失败只降级成错误字符串，天气照常更新；
    但错误必须写进 weather.json（`aqiError` / `usAqiError`），
    不能只打一行 stderr 就当没发生。
    """
    air_url = (
        f"https://air-quality-api.open-meteo.com/v1/air-quality?"
        f"latitude={LATITUDE}&longitude={LONGITUDE}"
        f"&current=us_aqi,us_aqi_pm2_5,us_aqi_pm10,us_aqi_ozone,"
        f"us_aqi_nitrogen_dioxide,us_aqi_sulphur_dioxide,us_aqi_carbon_monoxide"
        f"&hourly=pm2_5,pm10,ozone,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide"
        f"&past_days=1&forecast_days=1"
        f"&timezone={urllib.parse.quote(TIMEZONE)}"
    )
    print(f"正在拉取 {LATITUDE},{LONGITUDE} 的空气质量（Open-Meteo）...")
    try:
        air_data = fetch_json(air_url)
    except FetchError as e:
        return {
            "cn": None,
            "us": None,
            "time": None,
            "cnError": str(e),
            "usError": str(e),
        }

    if air_data.get("error"):
        message = f"接口报错：{air_data.get('reason')}"
        return {
            "cn": None,
            "us": None,
            "time": None,
            "cnError": message,
            "usError": message,
        }

    current = air_data.get("current")
    if not isinstance(current, dict):
        message = f"返回里没有 current：{json.dumps(air_data, ensure_ascii=False)[:300]}"
        return {
            "cn": None,
            "us": None,
            "time": None,
            "cnError": message,
            "usError": message,
        }

    current_time = current.get("time")
    cn = None
    cn_error = None
    hourly = air_data.get("hourly")
    if not isinstance(hourly, dict):
        cn_error = f"返回里没有 hourly：{json.dumps(air_data, ensure_ascii=False)[:300]}"
    else:
        try:
            cn = compute_china_aqi(hourly, current_time)
        except Exception as e:  # noqa: BLE001 —— 空气失败一律降级，不拖垮天气
            cn_error = str(e)

    us = None
    us_error = None
    try:
        us = compute_us_aqi(current)
    except Exception as e:  # noqa: BLE001
        us_error = str(e)

    return {
        "cn": cn,
        "us": us,
        "time": current_time,
        "cnError": cn_error,
        "usError": us_error,
    }


def main():
    if not SENIVERSE_KEY:
        print("Error: SENIVERSE_KEY 环境变量未设置！", file=sys.stderr)
        print("请在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中添加 SENIVERSE_KEY。", file=sys.stderr)
        sys.exit(1)

    # 1. 抓取逐日天气预报 (心知天气 v3 daily.json)
    #    天气是主项：这里失败就整个脚本失败（exit 1），CI 会标红。
    daily_url = (
        f"https://api.seniverse.com/v3/weather/daily.json?"
        f"key={urllib.parse.quote(SENIVERSE_KEY)}&location={urllib.parse.quote(LOCATION)}"
        f"&language=zh-Hans&unit=c&start=0&days=1"
    )
    print(f"正在拉取 {LOCATION} 的逐日天气预报...")
    try:
        daily_data = fetch_json(daily_url)
    except FetchError as e:
        # 不吞：天气是这一趟唯一必须成功的东西，失败了就不该提交。
        print(f"Error: 逐日天气拉取失败：{e}", file=sys.stderr)
        sys.exit(1)

    # 2. 抓取空气质量 (Open-Meteo) —— 失败只降级、留痕
    air = fetch_air_quality()
    cn = air["cn"] or {}
    us = air["us"] or {}
    if air["cnError"]:
        print(f"警告：国标 AQI 失败（天气照常更新）：{air['cnError']}", file=sys.stderr)
    if air["usError"]:
        print(f"警告：美国 AQI 失败（天气照常更新）：{air['usError']}", file=sys.stderr)

    # 3. 校验并提取逐日预报
    results = daily_data.get("results", [])
    if not results:
        raise ValueError(f"心知天气返回数据异常: {daily_data}")

    first_result = results[0]
    daily_list = first_result.get("daily", [])
    if not daily_list:
        raise ValueError(f"未能解析出 daily 预报列表: {first_result}")

    today = daily_list[0]
    date_str = today.get("date")  # "YYYY-MM-DD"
    text_day = today.get("text_day", "")
    text_night = today.get("text_night", "")
    high = parse_int(today.get("high"))
    low = parse_int(today.get("low"))
    resolved_city = first_result.get("location", {}).get("name") or CITY_NAME

    # 4. 组装输出
    #
    # aqi 是**中国国标**（HJ 633-2012），usAqi 是 Open-Meteo 原生的美国 EPA 值。
    # 两个都在文件里，客户端设置里二选一；旧客户端不认识 usAqi，会继续读 aqi，
    # 而 aqi 现在也是国标 —— 对旧客户端来说反而修好了原来的口径错。
    #
    # aqiError / usAqiError 是给人和排查用的诊断字段，App 侧不认识它们
    # （Moshi 忽略未知键），但一眼就能看出哪一套为什么没有。
    payload = {
        "lastUpdated": int(time.time() * 1000),
        "cityName": resolved_city,
        "date": date_str,
        "textDay": text_day,
        "textNight": text_night,
        "high": high,
        "low": low,
        "aqi": cn.get("aqi"),
        "aqiQuality": cn.get("quality"),
        "aqiPrimary": cn.get("primary"),
        "usAqi": us.get("aqi"),
        "usAqiQuality": us.get("quality"),
        "usAqiPrimary": us.get("primary"),
        # 上游实际用到的那个小时（不是本机同步时刻）。空气是逐小时读数，
        # 与文件自己的 lastUpdated 分开记，排查时才知道这个数对应哪一小时。
        "aqiTime": cn.get("time") or air["time"],
        # 明确标出 aqi 的语义。旧文件没有这个键，客户端按「aqi 是旧版美国值」处理。
        "aqiStandard": "CN",
        "aqiError": air["cnError"],
        "usAqiError": air["usError"],
    }

    # 输出路径：项目根目录下的 weather.json
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    out_file = os.path.join(project_root, "weather.json")

    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"成功写入 {out_file}:")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    # 汇总到最后一行：翻日志时第一眼找这一行就行。
    cn_text = (
        f"{cn.get('aqi')}（{cn.get('quality')}）"
        if cn.get("aqi") is not None
        else f"失败（{air['cnError']}）"
    )
    us_text = (
        f"{us.get('aqi')}（{us.get('quality')}）"
        if us.get("aqi") is not None
        else f"失败（{air['usError']}）"
    )
    print(f"小结：天气 OK，空气国标 {cn_text}；美国 {us_text}")


if __name__ == "__main__":
    main()
