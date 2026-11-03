package com.example.data.source.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 天气查询地点在**两边各写了一份**，这里把它们钉在一起。
 *
 * ## 为什么这件事会出错
 *
 * 空气这一项查的是**经纬度**（Open-Meteo 的空气端点只认坐标，不认地名），
 * 而这个坐标写在两个地方：
 *
 * - `scripts/sync_weather.py` 的 `LATITUDE` / `LONGITUDE`（CI 抓空气时用）；
 * - `WeatherLocation.DEFAULT`（客户端显示与兜底用）。
 *
 * 两份各改各的，**编译得过、App 照跑、界面上看不出任何异常** ——
 * 只是天气来自杭州、空气来自另一个城市，两个地方的数据并排显示。
 * 空气是「抓的那一刻」的读数，两个城市的读数没有可比性，
 * 而用户只会看到一句「杭州 · 空气良 64」，看不出空气其实是别处的。
 *
 * 所以这里不是「提醒一下」，是**一条断言**：两份不一致就让测试红。
 */
class WeatherLocationConsistencyTest {

    /** 从模块目录往上找资源文件，兼容不同的测试工作目录。 */
    private fun locate(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到资源文件：$relativePath")
    }

    private fun script(): String = locate("scripts/sync_weather.py").readText()

    /** 从 python 源码里抠出 `NAME = float(os.environ.get("X", "值"))` 里的默认值。 */
    private fun pythonDefault(source: String, name: String): String? {
        val pattern = Regex("""$name\s*=\s*float\(os\.environ\.get\([^,]+,\s*"([^"]+)"\)\)""")
        return pattern.find(source)?.groupValues?.get(1)
    }

    @Test
    fun `同步脚本与客户端查的是同一个坐标`() {
        val source = script()

        val lat = pythonDefault(source, "LATITUDE")
        val lon = pythonDefault(source, "LONGITUDE")
        assertTrue("脚本里没找到 LATITUDE 的默认值", lat != null)
        assertTrue("脚本里没找到 LONGITUDE 的默认值", lon != null)

        val expected = WeatherLocation.DEFAULT
        assertEquals(
            "同步脚本抓空气用的坐标与客户端显示的城市对不上 —— " +
                "天气与空气会来自两个不同的地方，界面上看不出来。",
            expected.latitude,
            lat!!.toDouble(),
            1e-9
        )
        assertEquals(
            "同步脚本抓空气用的坐标与客户端显示的城市对不上 —— " +
                "天气与空气会来自两个不同的地方，界面上看不出来。",
            expected.longitude,
            lon!!.toDouble(),
            1e-9
        )
    }

    /**
     * 时区也要一致。
     *
     * 天气是**按天**成立的事实：日期判错一整天，界面上就会拿昨天的
     * 温度冒充今天。而脚本里写的时区与客户端的 `AppZone` 不是同一个真相时，
     * 跨日的那几个小时会错得很安静。
     */
    @Test
    fun `同步脚本与客户端用同一个时区`() {
        val source = script()
        assertTrue(
            "脚本里的时区必须是 Asia/Shanghai，与客户端 AppZone 一致",
            source.contains("""TIMEZONE = "Asia/Shanghai"""")
        )
    }
}
