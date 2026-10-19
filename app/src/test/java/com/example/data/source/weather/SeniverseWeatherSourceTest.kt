package com.example.data.source.weather

import com.example.data.calendar.AppZone
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
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

    // ------------------------------------------------------------ 镜像之间的新鲜度

    /**
     * **第一个镜像给的不是今天的，要继续试下一个。**
     *
     * 镜像之间的新鲜度不一致是常态，不是意外：Gitee 那份靠 CI 另推一次，
     * 而 CI 那一步的推送是 `|| true` 兜底的 —— token 没配或过期时它**静默失败**，
     * Gitee 上的 `weather.json` 就此停在昨天。实测撞到过：Gitee 是 10-18、
     * ghproxy 是 10-19。
     *
     * 旧做法是「第一个能通的就算」，于是一个陈旧镜像排在前面就能把一整天的
     * 天气变成「读不到」—— 而它自己是好的，换个镜像就有今天的。
     */
    @Test
    fun `前面的镜像不是今天的就继续试下一个`() = runTest {
        val today = AppZone.todayEpochDay()
        val yesterday = today - 1

        val client = clientReturning(
            "stale.example" to jsonFor(yesterday),
            "fresh.example" to jsonFor(today)
        )
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf("https://stale.example/weather.json", "https://fresh.example/weather.json")
        )

        val result = src.fetchToday(HANGZHOU)

        assertTrue("应该拿到今天的那份：$result", result is WeatherFetch.Got)
        val parsed = src.parseToday((result as WeatherFetch.Got).text, HANGZHOU)
        assertNotNull(parsed)
        assertEquals("拿到的必须是今天的文件", today, parsed!!.epochDay)
    }

    /**
     * 全都不通时仍然归到网络问题。
     *
     * 顺带钉住「陈旧的文件不算网络问题」：它是**答复到了但内容不新**，
     * 归错类会让界面劝用户去查网络，而他那台机器上什么都正常。
     */
    @Test
    fun `所有镜像都只给得出昨天的文件时交出那份旧的`() = runTest {
        val yesterday = AppZone.todayEpochDay() - 1
        val client = clientReturning("only.example" to jsonFor(yesterday))
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf("https://only.example/weather.json")
        )

        val result = src.fetchToday(HANGZHOU)

        // 交出去（而不是报 Unavailable）是为了让决策层说出「不是今天的数据」，
        // 那句话比一句笼统的「拉取失败」能定位问题。
        assertTrue("陈旧但可解析的文件该交上去：$result", result is WeatherFetch.Got)
    }

    /** 按 URL 里的主机名返回不同正文，模拟「几个镜像新鲜度不一致」。 */
    private fun clientReturning(vararg pairs: Pair<String, String>): OkHttpClient {
        val byHost = pairs.toMap()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val host = chain.request().url.host
                val body = byHost[host]
                okhttp3.Response.Builder()
                    .request(chain.request())
                    // protocol 与 message 不给的话，OkHttp 在 build() 那一步就会抛
                    // "protocol == null" / "message == null"，而那是两个跟被测行为
                    // 毫无关系的错误 —— 用例会红在一个它根本没在验的地方。
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .message("OK")
                    .code(if (body == null) 404 else 200)
                    .body((body ?: "").toResponseBody(null))
                    .build()
            }
            .build()
    }

    private fun jsonFor(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay).toString()
        return """{"date":"$date","textDay":"多云","textNight":"多云","high":26,"low":20,"aqi":69}"""
    }
}
