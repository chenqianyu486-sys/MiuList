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

        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE

        val OK_STATIC_JSON = """
            {
              "lastUpdated": 1792195200000,
              "cityName": "杭州",
              "date": "2026-10-17",
              "textDay": "多云",
              "textNight": "阴",
              "high": 24,
              "low": 15,
              "aqi": 30,
              "aqiQuality": "优",
              "usAqi": 45,
              "usAqiQuality": "优",
              "aqiStandard": "CN"
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
        // 两套数各在各的字段里，**不互相覆盖**。
        assertEquals(30, weather.aqi)
        assertEquals(45, weather.usAqi)
        // 等级必须跟着一起解析出来：它和数值是同一份数据里的，
        // 丢了才退回本地那张表（见 aqiQuality 优先那条用例）。
        assertEquals("优", weather.aqiQuality)
        assertEquals("优", weather.usAqiQuality)
    }

    /**
     * **等级要和数值一起从托管文件里出来。**
     *
     * 新文件里 `aqi` 是国标、`usAqi` 是美国值，各带各的等级。上游给了
     * `aqiQuality` 就必须用它，不能只取数值、把等级交给本地那张表 ——
     * 尤其是美国值：它和国标的等级名一样，数值却不同。
     */
    @Test
    fun `国标等级跟着国标数值一起解析`() {
        val json = """
            {"date":"2026-10-17","textDay":"阴","textNight":"阴","high":24,"low":15,
             "aqi":132,"aqiQuality":"轻度污染",
             "usAqi":161,"usAqiQuality":"中度污染","aqiStandard":"CN"}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)!!

        assertEquals(132, weather.aqi)
        assertEquals("轻度污染", weather.aqiQuality)
        assertEquals(161, weather.usAqi)
        assertEquals("中度污染", weather.usAqiQuality)
        assertEquals("空气轻度污染 132", weather.airQualityLabel())
        assertEquals("空气中度污染 161", weather.airQualityLabel(AqiStandard.US))
    }

    /**
     * **旧格式：只有 `aqi`，而那个 `aqi` 其实是美国 `us_aqi`。**
     *
     * 2026-11 之前同步脚本只取了 Open-Meteo 的 `us_aqi`，却写在 `aqi` 键上，
     * 客户端再按国标等级表翻译 —— 这是这次要修的错的根源。
     * 新客户端遇到这种文件时，把它搬到 `usAqi`，国标留空：
     * 宁可国标模式暂时不显示，也不把美国数当国标。
     */
    @Test
    fun `旧文件的 aqi 按美国口径解析`() {
        val json = """
            {"lastUpdated":1792324861294,"cityName":"杭州","date":"2026-10-18",
             "textDay":"阴","textNight":"多云","high":32,"low":21,
             "aqi":90,"aqiQuality":null,"aqiError":null}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)!!

        assertNull("旧 aqi 不能当国标", weather.aqi)
        assertNull(weather.aqiQuality)
        assertEquals(90, weather.usAqi)
        assertNull(weather.airQualityLabel(AqiStandard.CN))
        assertEquals("空气良 90", weather.airQualityLabel(AqiStandard.US))
    }

    /**
     * 新文件没有上游等级时，国标模式才用本地那张表算等级。
     *
     * 这张表只在「指数 → 等级」这一步用；浓度 → 指数已经在服务端做完了。
     */
    @Test
    fun `新文件没有上游等级时由本地表算出等级`() {
        val json = """
            {"lastUpdated":1792324861294,"cityName":"杭州","date":"2026-10-18",
             "textDay":"阴","textNight":"多云","high":32,"low":21,
             "aqi":60,"aqiQuality":null,
             "usAqi":100,"usAqiQuality":null,"aqiStandard":"CN"}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)!!

        assertEquals(60, weather.aqi)
        assertNull(weather.aqiQuality)
        assertEquals("空气良 60", weather.airQualityLabel())
    }

    /** 文件明确标了 `"US"` 时，`aqi` 仍然是美国值，不能倒过来当国标。 */
    @Test
    fun `明确标记美国口径时 aqi 按美国解析`() {
        val json = """
            {"date":"2026-10-17","textDay":"晴","textNight":"晴","high":20,"low":10,
             "aqi":120,"aqiQuality":"轻度污染","aqiStandard":"US"}
        """.trimIndent()

        val weather = source().parseToday(json, HANGZHOU)!!

        assertNull(weather.aqi)
        assertEquals(120, weather.usAqi)
        assertEquals("空气轻度污染 120", weather.airQualityLabel(AqiStandard.US))
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
    fun `parseAirQuality 能正确提取内嵌国标 AQI`() {
        // 空气已内嵌在天气文件里，这条独立入口只剩兼容用途；
        // 它取的是 `aqi`（国标），不是 `usAqi`。
        assertEquals(30, source().parseAirQuality(OK_STATIC_JSON))
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
     * 而 CI 那一步的推送会失败（实测 2026-11-02 那次就失败了，exit 128），
     * Gitee 上的 `weather.json` 于是停在旧的一份上。
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
     * **同一天里也要比新旧。**
     *
     * `date` 只分得出「今天 / 不是今天」。同一个今天里可以藏着差 8 小时的两份：
     * Gitee 那次推送失败，GitHub 上已经是新的，它还停在 8 小时前 —— 实测
     * 2026-11-02 就这样。旧做法是「第一个 date 是今天的就收手」，
     * 于是那份旧的被当成现状显示，而后面那个镜像上更新的那份**根本读不到**。
     */
    @Test
    fun `同一天里取最新的那份而不是第一个`() = runTest {
        val today = AppZone.todayEpochDay()
        val now = 1_792_131_086_000L
        val client = clientReturning(
            "old.example" to jsonFor(today, updatedAt = now - 8 * HOUR, high = 26),
            "new.example" to jsonFor(today, updatedAt = now - 10 * MINUTE, high = 99)
        )
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf("https://old.example/weather.json", "https://new.example/weather.json"),
            nowMillis = { now }
        )

        val result = src.fetchToday(HANGZHOU)

        assertTrue("应该拿到文件：$result", result is WeatherFetch.Got)
        val parsed = src.parseToday((result as WeatherFetch.Got).text, HANGZHOU)
        assertNotNull(parsed)
        assertEquals("必须取更新的那份（high=99）", 99, parsed!!.high)
    }

    /**
     * **够新就收手**，不再白等后面的镜像。
     *
     * 三个镜像各 5 秒超时，逐个试就是 15 秒；而健康状态下第一个镜像
     * 0.4 秒就拿到了最新的文件 —— 这时再去问后两个纯属浪费。
     */
    @Test
    fun `第一份够新就不再问后面的镜像`() = runTest {
        val today = AppZone.todayEpochDay()
        val now = 1_792_131_086_000L
        val hits = mutableListOf<String>()
        val client = clientReturning(
            "first.example" to jsonFor(today, updatedAt = now - 10 * MINUTE, high = 88),
            hits = hits
        )
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf(
                "https://first.example/weather.json",
                "https://second.example/weather.json",
                "https://third.example/weather.json"
            ),
            nowMillis = { now }
        )

        val result = src.fetchToday(HANGZHOU)

        assertTrue(result is WeatherFetch.Got)
        assertEquals("只该问第一个镜像", listOf("first.example"), hits)
        val parsed = src.parseToday((result as WeatherFetch.Got).text, HANGZHOU)
        assertEquals(88, parsed!!.high)
    }

    /**
     * **全都够旧时，取 `lastUpdated` 最大的那份**，
     * 而不是第一个（第一个有可能最旧）。
     */
    @Test
    fun `都不够新时取写入时刻最大的那份`() = runTest {
        val today = AppZone.todayEpochDay()
        val now = 1_792_131_086_000L
        val client = clientReturning(
            "a.example" to jsonFor(today, updatedAt = now - 9 * HOUR, high = 11),
            "b.example" to jsonFor(today, updatedAt = now - 6 * HOUR, high = 22),
            "c.example" to jsonFor(today, updatedAt = now - 7 * HOUR, high = 33)
        )
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf(
                "https://a.example/weather.json",
                "https://b.example/weather.json",
                "https://c.example/weather.json"
            ),
            nowMillis = { now }
        )

        val result = src.fetchToday(HANGZHOU)

        val parsed = src.parseToday((result as WeatherFetch.Got).text, HANGZHOU)
        assertEquals("要取写入时刻最大的那份（high=22）", 22, parsed!!.high)
    }

    /**
     * **老格式（没有 `lastUpdated`）也能用。**
     *
     * 判不出年龄就等于判不出新旧，但那份文件仍然是今天的、仍然能显示 ——
     * 不能因为少一个可选字段就让整天的天气作废。
     */
    @Test
    fun `没有写入时刻的老格式文件照样能用`() = runTest {
        val today = AppZone.todayEpochDay()
        val now = 1_792_131_086_000L
        val client = clientReturning("old.example" to jsonFor(today, updatedAt = null))
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf("https://old.example/weather.json"),
            nowMillis = { now }
        )

        val result = src.fetchToday(HANGZHOU)

        assertTrue("老格式不该被判成拉不到：$result", result is WeatherFetch.Got)
        val parsed = src.parseToday((result as WeatherFetch.Got).text, HANGZHOU)
        assertNotNull(parsed)
        assertEquals(today, parsed!!.epochDay)
        assertNull("解析不出写入时刻就当没有", parsed.updatedAtMillis)
    }

    /**
     * **找更新的预算用完了就收手。**
     *
     * 客户端只有在「自己那份已经旧了」时才联网，而镜像推的是同一份文件 ——
     * 所以每次联网，几乎必然要去问第二、第三个镜像。没有预算的话，
     * 一次冷启动要把三个镜像挨个走完（实测能到 20 秒以上）。
     *
     * 这里用一个「每调一次就往前跳 10 秒」的时钟把预算耗掉，
     * 断言后面的镜像根本没被问。
     */
    @Test
    fun `找更新的时间预算用完就不再问后面的镜像`() = runTest {
        val today = AppZone.todayEpochDay()
        val now = 1_792_131_086_000L
        val hits = mutableListOf<String>()
        val client = clientReturning(
            "first.example" to jsonFor(today, updatedAt = now - 8 * HOUR, high = 26),
            "second.example" to jsonFor(today, updatedAt = now - 8 * HOUR, high = 77),
            hits = hits
        )
        var clockCalls = 0
        val src = SeniverseWeatherSource(
            client = client,
            remoteUrls = listOf(
                "https://first.example/weather.json",
                "https://second.example/weather.json"
            ),
            nowMillis = { clockCalls++; now + clockCalls * 10_000L }
        )

        val result = src.fetchToday(HANGZHOU)

        assertTrue("预算用完也要交出手上那份：$result", result is WeatherFetch.Got)
        assertEquals("预算用完就不该再问第二个镜像", listOf("first.example"), hits)
        val parsed = src.parseToday((result as WeatherFetch.Got).text, HANGZHOU)
        assertEquals("交出的应该是手上那一份", 26, parsed!!.high)
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
    private fun clientReturning(
        vararg pairs: Pair<String, String>,
        hits: MutableList<String> = mutableListOf()
    ): OkHttpClient {
        val byHost = pairs.toMap()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val host = chain.request().url.host
                hits += host
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

    /**
     * 造一份托管文件。
     *
     * @param updatedAt 文件自己的写入时刻（`lastUpdated`）。
     *   **null = 老格式没有这个字段** —— 那时客户端判不出新旧，
     *   只能退回「第一个 date 是今天的就算」，这条退路也要有用例钉住。
     * @param high 高低温，用来分辨「拿到的是哪一份」。
     */
    private fun jsonFor(epochDay: Long, updatedAt: Long? = null, high: Int = 26): String {
        val date = LocalDate.ofEpochDay(epochDay).toString()
        val stamp = updatedAt?.let { "\"lastUpdated\":$it," } ?: ""
        // 用拼接而不是原始字符串：原始字符串里 "$stamp"date" 这种写法会让
        // 引号配对错位，后面的 $date 掉到字符串外面，变成字面量 "$date"。
        return "{$stamp\"date\":\"$date\",\"textDay\":\"多云\",\"textNight\":\"多云\"," +
            "\"high\":$high,\"low\":20,\"aqi\":69}"
    }
}
