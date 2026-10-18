#!/usr/bin/env python3
"""
从心知天气 (Seniverse) 拉取指定城市的天气与空气质量，生成静态 weather.json。
用于 GitHub Actions 定时任务，通过 SENIVERSE_KEY 环境变量注入私钥，彻底避免将私钥打包进 App。

## 为什么空气失败必须留下痕迹

上一版把空气请求整个 except 掉、只往 stderr 打一行，然后照样把 `aqi: null`
写进 weather.json 提交上去 —— 于是 Actions 连续十次运行 conclusion 全是
success，**没有任何一个信号说「空气这一项已经连挂很久了」**，直到有人在
App 里发现城市名旁边少了一段。

所以现在两件事是硬要求：

1. **HTTP 错误要读正文。** urllib 的 `str(e)` 只有 `HTTP Error 4xx: ...`，
   而服务端早把原因写在正文里了。只报状态码，等于把答案扔掉。
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


def fetch_air_quality():
    """
    拉实时空气质量。**返回 (aqi, quality, error)**，三者最多有一个非空。

    失败一律降级成 error 字符串而不是抛异常：空气不是天气的前提，
    它挂了不应该让整份天气停止更新 —— 但**必须留下痕迹**。
    """
    air_url = (
        f"https://api.seniverse.com/v3/air/now.json?"
        f"key={urllib.parse.quote(SENIVERSE_KEY)}&location={urllib.parse.quote(LOCATION)}"
        f"&language=zh-Hans&scope=city"
    )
    print(f"正在拉取 {LOCATION} 的空气质量实况...")
    try:
        air_data = fetch_json(air_url)
    except FetchError as e:
        return None, None, str(e)

    # 下面这三种「拿到了但不是想要的东西」上一版是静默跳过的，
    # 现在一律当成失败报出来 —— 它们和「请求失败」一样会让 App 上少一段。
    results = air_data.get("results") or []
    if not results:
        return None, None, f"返回里没有 results：{json.dumps(air_data, ensure_ascii=False)[:300]}"
    city = (results[0].get("air") or {}).get("city") or {}
    aqi = parse_int(city.get("aqi"))
    if aqi is None:
        return None, None, f"结果里没有 aqi：{json.dumps(results[0], ensure_ascii=False)[:300]}"
    return aqi, city.get("quality"), None


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

    # 2. 抓取实时空气质量 (心知天气 v3 air/now.json) —— 失败只降级、留痕
    aqi, aqi_quality, aqi_error = fetch_air_quality()
    if aqi_error:
        print(f"警告：空气质量拉取失败（天气照常更新）：{aqi_error}", file=sys.stderr)

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
    #    aqiError 是给人和排查用的诊断字段，App 侧不认识它（Moshi 忽略未知键），
    #    所以留着不影响客户端，但一眼就能看出空气这一项到底为什么没有。
    payload = {
        "lastUpdated": int(time.time() * 1000),
        "cityName": resolved_city,
        "date": date_str,
        "textDay": text_day,
        "textNight": text_night,
        "high": high,
        "low": low,
        "aqi": aqi,
        "aqiQuality": aqi_quality,
        "aqiError": aqi_error
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
    print(f"小结：天气 OK，空气{'OK（aqi=' + str(aqi) + '）' if aqi is not None else '失败（' + str(aqi_error) + '）'}")


if __name__ == "__main__":
    main()
