#!/usr/bin/env python3
"""
`sync_weather.py` 里 AQI 换算的单元测试。**不联网**。

Run:
    python scripts/test_sync_weather.py

为什么单独钉这张表：国标 HJ 633-2012 和美国 EPA 的**六档指数边界**
一模一样，但浓度断点差得很远（PM2.5 35 → 国标 50 / 美国 99）。
只要有人“顺手”把断点改成另一套，界面上就会出现一整个等级的偏差，
而编译、同步、客户端解析全都会是绿的。
"""
import os
import statistics
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sync_weather as sw  # noqa: E402


def hourly(
    *,
    pm2_5=0,
    pm10=0,
    so2=0,
    no2=0,
    co=0,
    o3=0,
    hours=30,
):
    """造一份恒定的 hourly；当前时刻就取最后一个。"""
    times = [f"h{i:02d}" for i in range(hours)]
    return {
        "time": times,
        "pm2_5": [pm2_5] * hours,
        "pm10": [pm10] * hours,
        "sulphur_dioxide": [so2] * hours,
        "nitrogen_dioxide": [no2] * hours,
        "carbon_monoxide": [co] * hours,
        "ozone": [o3] * hours,
    }, times[-1]


class IaqiBreakpointTest(unittest.TestCase):
    """每一项的 0/50/100/…/500 边界。浓度断点抄自 HJ 633-2012 表 1。"""

    def assert_iaqi(self, pollutant, cases):
        table = sw.CN_IAQI_BREAKPOINTS[pollutant]
        for concentration, expected in cases:
            self.assertEqual(
                expected,
                sw.iaqi(concentration, table),
                f"{pollutant} 浓度 {concentration} 应得 IAQI {expected}",
            )

    def test_pm2_5(self):
        self.assert_iaqi("pm2_5", [
            (0, 0),
            (35, 50),
            (75, 100),
            (115, 150),
            (150, 200),
            (250, 300),
            (350, 400),
            (500, 500),
            (600, 500),  # 超上限封顶
        ])

    def test_pm10(self):
        self.assert_iaqi("pm10", [
            (0, 0),
            (50, 50),
            (150, 100),
            (250, 150),
            (350, 200),
            (420, 300),
            (500, 400),
            (600, 500),
        ])

    def test_so2(self):
        self.assert_iaqi("so2", [
            (0, 0),
            (50, 50),
            (150, 100),
            (475, 150),
            (800, 200),
            (1600, 300),
            (2100, 400),
            (2620, 500),
        ])

    def test_no2(self):
        self.assert_iaqi("no2", [
            (0, 0),
            (40, 50),
            (80, 100),
            (180, 150),
            (280, 200),
            (565, 300),
            (750, 400),
            (940, 500),
        ])

    def test_co_uses_mg_per_m3(self):
        self.assert_iaqi("co", [
            (0, 0),
            (2, 50),
            (4, 100),
            (14, 150),
            (24, 200),
            (36, 300),
            (48, 400),
            (60, 500),
        ])

    def test_o3_8h(self):
        self.assert_iaqi("o3", [
            (0, 0),
            (100, 50),
            (160, 100),
            (215, 150),
            (265, 200),
            (800, 300),
            (900, 300),  # 国标 O3 表到 300 封顶
        ])

    def test_invalid_values(self):
        self.assertIsNone(sw.iaqi(None, sw.CN_IAQI_BREAKPOINTS["pm2_5"]))
        self.assertIsNone(sw.iaqi(-1, sw.CN_IAQI_BREAKPOINTS["pm2_5"]))
        self.assertIsNone(sw.iaqi("not-a-number", sw.CN_IAQI_BREAKPOINTS["pm2_5"]))


class ChinaAqiTest(unittest.TestCase):

    def test_all_clean(self):
        data, now = hourly()
        result = sw.compute_china_aqi(data, now)

        self.assertEqual(0, result["aqi"])
        self.assertEqual("优", result["quality"])
        self.assertIsNone(result["primary"])
        self.assertEqual(now, result["time"])

    def test_pm2_5_drives_the_index(self):
        data, now = hourly(pm2_5=75)
        result = sw.compute_china_aqi(data, now)

        self.assertEqual(100, result["aqi"])
        self.assertEqual("良", result["quality"])
        self.assertEqual("PM2.5", result["primary"])
        self.assertEqual(100, result["iaqi"]["PM2.5"])

    def test_co_is_converted_from_ug_to_mg(self):
        # Open-Meteo 的 CO 是 µg/m³；4000 µg/m³ = 4 mg/m³ → IAQI 100。
        data, now = hourly(co=4000)
        result = sw.compute_china_aqi(data, now)

        self.assertEqual(100, result["aqi"])
        self.assertEqual("CO", result["primary"])
        self.assertAlmostEqual(4.0, result["concentrations"]["CO"])

    def test_o3_uses_8h_average(self):
        data, now = hourly(o3=160)
        result = sw.compute_china_aqi(data, now)

        self.assertEqual(100, result["aqi"])
        self.assertEqual("O3", result["primary"])

    def test_current_time_can_be_slightly_after_the_last_hour(self):
        data, now = hourly(pm2_5=35)
        result = sw.compute_china_aqi(data, now + ":30")

        # 取不晚于 current 的最后一个小时，仍然能算。
        self.assertEqual(50, result["aqi"])

    def test_missing_full_window_is_rejected(self):
        # 5 小时：既不够 PM 的 24h，也不够 O3 的 8h，六项全无 → 拒绝。
        data, now = hourly(pm2_5=75, hours=5)
        with self.assertRaises(ValueError):
            sw.compute_china_aqi(data, now)

    def test_missing_pollutant_does_not_block_the_others(self):
        data, now = hourly(pm2_5=75)
        # 故意让 PM10 少于 24 个有效值：它缺席，PM2.5 仍然能出结果。
        data["pm10"] = [10] * 5
        result = sw.compute_china_aqi(data, now)

        self.assertEqual(100, result["aqi"])
        self.assertNotIn("PM10", result["iaqi"])

    def test_trailing_average_needs_the_full_window(self):
        values = [10.0] * 24
        self.assertEqual(10.0, sw.trailing_average(values, 23, 24))
        self.assertIsNone(sw.trailing_average(values, 22, 24))
        values[5] = None
        self.assertIsNone(sw.trailing_average(values, 23, 24))

    def test_average_is_actually_an_average(self):
        values = [0.0] * 24
        values[-1] = 240.0
        self.assertEqual(
            statistics.mean(values),
            sw.trailing_average(values, 23, 24),
        )


class UsAqiTest(unittest.TestCase):

    def test_keeps_upstream_value_and_computes_quality(self):
        current = {
            "time": "2026-11-05T15:00",
            "us_aqi": 121,
            "us_aqi_pm2_5": 121,
            "us_aqi_pm10": 41,
            "us_aqi_ozone": 23,
            "us_aqi_nitrogen_dioxide": 11,
            "us_aqi_sulphur_dioxide": 2,
            "us_aqi_carbon_monoxide": 4,
        }
        result = sw.compute_us_aqi(current)

        self.assertEqual(121, result["aqi"])
        self.assertEqual("轻度污染", result["quality"])
        self.assertEqual("PM2.5", result["primary"])

    def test_clean_has_no_primary_pollutant(self):
        result = sw.compute_us_aqi({"us_aqi": 50, "us_aqi_pm2_5": 50})
        self.assertEqual("优", result["quality"])
        self.assertIsNone(result["primary"])

    def test_missing_us_aqi_is_an_error(self):
        with self.assertRaises(ValueError):
            sw.compute_us_aqi({"time": "2026-11-05T15:00"})


class DescribeAqiTest(unittest.TestCase):

    def test_six_levels(self):
        cases = {
            0: "优",
            50: "优",
            51: "良",
            100: "良",
            101: "轻度污染",
            150: "轻度污染",
            151: "中度污染",
            200: "中度污染",
            201: "重度污染",
            300: "重度污染",
            301: "严重污染",
            500: "严重污染",
        }
        for aqi, label in cases.items():
            self.assertEqual(label, sw.describe_aqi(aqi))


if __name__ == "__main__":
    unittest.main()
