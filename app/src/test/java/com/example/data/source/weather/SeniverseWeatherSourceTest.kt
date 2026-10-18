package com.example.data.source.weather

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.time.LocalDate

/**
 * 静态托管的心知天气取与解单元测试。全部离线。
 */
class SeniverseWeatherSourceTest {

    private companion object {
        val HANGZHOU = WeatherLocation.DEFAULT

        val OK_STATIC_JSON = """
            {
              "lastUpdated": 1792195200000,
              "cityName": "杭州",
              "date": "2026-10-17",
              "textDay": "多云",
              "textNight": "阴",
              "high": 24,
              "low": 15,
              "aqi": 45,
              "aqiQuality": "优"
            }
        """.trimIndent()

        val BROKEN_JSON = """{"error": "not found"}"""
    }

    private fun source(text: String? = null, urls: List<String> = SeniverseWeatherSource.REMOTE_URLS) =
        SeniverseWeatherSource(responseText = text, remoteUrls = urls)

    @Test
    fun `正常解析完整字段`() {
        val weather = source().parseToday(OK_STATIC_JSON, HANGZHOU)

        assertNotNull(weather)
        assertEquals("杭州", weather!!.cityName)
        assertEquals(LocalDate.of(2026, 10, 17).toEpochDay(), weather.epochDay)
        assertEquals("多云", weather.textDay)
        assertEquals("阴", weather.textNight)
        assertEquals(24, weather.high)
        assertEquals(15, weather.low)
        assertEquals(45, weather.aqi)
        // 等级必须跟着一起解析出来：它和数值是同一份数据里的，
        // 丢了就得靠 EPA 表去猜一个（见 aqiQuality 优先那条用例）。
        assertEquals("优", weather.aqiQuality)
    }

    /**
     * **等级要和数值一起从托管文件里出来。**
     *
     * 托管文件里的 `aqi` 是**心知口径**，而界面上那张等级表是 EPA 的分段 ——
     * 同一片空气两边能差出一整个等级。所以上游给了 `aqiQuality` 就必须用它，
     * 不能只取数值、把等级交给本地那张猜出来的表。
     */
    @Test
    fun `托管文件里的等级跟着数值一起解析`() {
        val json = """
            {"date":"2026-10-17","textDay":"阴","textNight":"阴","high":24,"low":15,
             "aqi":132,"aqiQuality":"轻度污染"}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)!!

        assertEquals(132, weather.aqi)
        assertEquals("轻度污染", weather.aqiQuality)
        // 132 在那张兜底表里也是「轻度污染」，但那是巧合 —— 这里钉的是「用上游的」。
        assertEquals("空气轻度污染 132", weather.airQualityLabel())
    }

    /**
     * **当前真实的托管文件形态：只有 `aqi`，`aqiQuality` 是 null。**
     *
     * 空气这一项 2026-10-18 起换成了 Open-Meteo，它只给 `us_aqi`、不给中文等级，
     * 所以同步脚本写进去的 `aqiQuality` 就是 null。于是等级**必须**由客户端
     * 那张表算出来 —— 少了这一步，界面上会直接不显示这一段。
     *
     * 这条用的是线上真实拿到的形状，别把它简化成「顺手带上 quality」。
     */
    @Test
    fun `没有上游等级时由本地表算出等级`() {
        val json = """
            {"lastUpdated":1792324861294,"cityName":"杭州","date":"2026-10-18",
             "textDay":"阴","textNight":"多云","high":32,"low":21,
             "aqi":90,"aqiQuality":null,"aqiError":null}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)!!

        assertEquals(90, weather.aqi)
        assertNull(weather.aqiQuality)
        assertEquals("空气良 90", weather.airQualityLabel())
    }

    /**
     * `aqiError` 是同步脚本写下的诊断字段（空气为什么没取到）。
     *
     * App 侧**不认识它**（Moshi 忽略未知键），这是有意的：它是给人看的，
     * 不该让一份诊断信息参与业务解析，更不该因此解析失败把整份天气丢掉。
     */
    @Test
    fun `诊断字段不影响解析`() {
        val json = """
            {"date":"2026-10-18","textDay":"晴","textNight":"晴","high":26,"low":16,
             "aqi":null,"aqiQuality":null,
             "aqiError":"HTTP 403 · {\"status\":\"You do not have access to this API.\"}"}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)

        assertNotNull("空气取不到不该让天气一起作废：$json", weather)
        assertEquals(26, weather!!.high)
        assertNull("空气取不到就不画这一段", weather.airQualityLabel())
    }

    @Test
    fun `缺城市名时退回传入的 Location 名称`() {
        val json = """
            {
              "date": "2026-10-17",
              "textDay": "晴",
              "textNight": "晴",
              "high": 26,
              "low": 16
            }
        """.trimIndent()
        val weather = source().parseToday(json, HANGZHOU)

        assertNotNull(weather)
        assertEquals("杭州", weather!!.cityName)
        assertNull(weather.aqi)
    }

    @Test
    fun `日期缺失或非法时拒绝解析`() {
        val invalidDates = listOf(
            """{"textDay":"晴"}""",
            """{"date":"","textDay":"晴"}""",
            """{"date":"not-a-date","textDay":"晴"}"""
        )
        for (json in invalidDates) {
            val weather = source().parseToday(json, HANGZHOU)
            assertNull("非法日期应拒绝解析：$json", weather)
        }
    }

    @Test
    fun `parseAirQuality 能正确提取内嵌 AQI`() {
        assertEquals(45, source().parseAirQuality(OK_STATIC_JSON))
        assertNull(source().parseAirQuality("""{"textDay":"晴"}"""))
        assertNull(source().parseAirQuality("invalid json"))
    }

    @Test
    fun `请求地址里没有任何密钥凭据`() {
        SeniverseWeatherSource.REMOTE_URLS.forEach { url ->
            val lower = url.lowercase()
            listOf("key", "apikey", "sig", "token", "secret", "password").forEach { credential ->
                assertFalse("地址不许含 $credential：$url", lower.contains("=$credential") || lower.contains("&$credential"))
            }
        }
    }

    @Test
    fun `离线时返回 OFFLINE 失败类别`() = runTest {
        val offlineClient = OkHttpClient.Builder()
            .addInterceptor { throw UnknownHostException("dns failed") }
            .build()
        val offlineSource = SeniverseWeatherSource(client = offlineClient)

        val result = offlineSource.fetchToday(HANGZHOU)
        assertTrue(result is WeatherFetch.Unavailable)
        assertEquals(WeatherFailureKind.OFFLINE, (result as WeatherFetch.Unavailable).kind)
    }

    @Test
    fun `注入空正文时返回 REJECTED 失败类别`() = runTest {
        val result = source(text = "").fetchToday(HANGZHOU)
        assertTrue(result is WeatherFetch.Unavailable)
        assertEquals(WeatherFailureKind.REJECTED, (result as WeatherFetch.Unavailable).kind)
    }
}
