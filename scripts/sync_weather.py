#!/usr/bin/env python3
"""
从心知天气 (Seniverse) 拉取指定城市的天气与空气质量，生成静态 weather.json。
用于 GitHub Actions 定时任务，通过 SENIVERSE_KEY 环境变量注入私钥，彻底避免将私钥打包进 App。
"""
import os
import sys
import json
import time
import urllib.request
import urllib.parse

SENIVERSE_KEY = os.environ.get("SENIVERSE_KEY")
LOCATION = os.environ.get("WEATHER_LOCATION", "hangzhou")
CITY_NAME = os.environ.get("WEATHER_CITY_NAME", "杭州")


def fetch_json(url: str):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "MiuList-WeatherSync/1.0"}
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def parse_int(v):
    if v is None or v == "":
        return None
    try:
        return int(round(float(v)))
    except (ValueError, TypeError):
        return None


def main():
    if not SENIVERSE_KEY:
        print("Error: SENIVERSE_KEY 环境变量未设置！", file=sys.stderr)
        print("请在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中添加 SENIVERSE_KEY。", file=sys.stderr)
        sys.exit(1)

    # 1. 抓取逐日天气预报 (心知天气 v3 daily.json)
    daily_url = (
        f"https://api.seniverse.com/v3/weather/daily.json?"
        f"key={urllib.parse.quote(SENIVERSE_KEY)}&location={urllib.parse.quote(LOCATION)}"
        f"&language=zh-Hans&unit=c&start=0&days=1"
    )
    print(f"正在拉取 {LOCATION} 的逐日天气预报...")
    daily_data = fetch_json(daily_url)

    # 2. 抓取实时空气质量 (心知天气 v3 air/now.json)
    air_url = (
        f"https://api.seniverse.com/v3/air/now.json?"
        f"key={urllib.parse.quote(SENIVERSE_KEY)}&location={urllib.parse.quote(LOCATION)}"
        f"&language=zh-Hans"
    )
    print(f"正在拉取 {LOCATION} 的空气质量实况...")
    air_data = None
    try:
        air_data = fetch_json(air_url)
    except Exception as e:
        print(f"警告：拉取空气质量失败 (可降级跳过): {e}", file=sys.stderr)

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

    # 4. 解析空气质量
    aqi = None
    aqi_quality = None
    if air_data and "results" in air_data and air_data["results"]:
        air_city = air_data["results"][0].get("air", {}).get("city", {})
        aqi = parse_int(air_city.get("aqi"))
        aqi_quality = air_city.get("quality")

    # 5. 组装输出
    payload = {
        "lastUpdated": int(time.time() * 1000),
        "cityName": resolved_city,
        "date": date_str,
        "textDay": text_day,
        "textNight": text_night,
        "high": high,
        "low": low,
        "aqi": aqi,
        "aqiQuality": aqi_quality
    }

    # 输出路径：项目根目录下的 weather.json
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    out_file = os.path.join(project_root, "weather.json")

    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"成功写入 {out_file}:")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
