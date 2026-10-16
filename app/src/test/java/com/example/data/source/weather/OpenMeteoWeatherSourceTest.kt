package com.example.data.source.weather

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Open-Meteo 的取与解。**全部离线**：注入响应文本后完全不发网络请求。
 *
 * 这套用例当年是给心知写的，其中一半的告诫在换源之后依然成立
 * （错误要能从正文里读出来、残缺响应不能抛、温度缺测不能拖垮整份），
 * 换掉的是「签名」那一半 —— 现在这里没有任何秘密可测，
 * 这本身就是这次换源要达到的状态。
 */
class OpenMeteoWeatherSourceTest {

    private companion object {
        val HANGZHOU = WeatherLocation.DEFAULT

        /** 真机跑通过的那份响应（2026-10-16 杭州，坐标被吸附到最近的网格中心）。 */
        val OK_RESPONSE = """
            {"latitude":30.263618,"longitude":120.14051,
             "utc_offset_seconds":28800,"timezone":"Asia/Shanghai",
             "timezone_abbreviation":"GMT+8","elevation":10.0,
             "daily_units":{"time":"iso8601","weather_code":"wmo code",
               "temperature_2m_max":"°C","temperature_2m_min":"°C"},
             "daily":{"time":["2026-10-16"],"weather_code":[53],
               "temperature_2m_max":[26.0],"temperature_2m_min":[19.7]}}
        """.trimIndent()

        /** 参数写错时服务端回的东西。注意 HTTP 状态是 400，reason 里才是答案。 */
        val ERROR_RESPONSE =
            """{"error":true,"reason":"Latitude must be in range of -90 to 90°. Given: 300."}"""

        /** 空气质量的正常响应形状。64 = 良。 */
        val AIR_OK = """
            {"latitude":30.26,"longitude":120.14,"timezone":"Asia/Shanghai",
             "utc_offset_seconds":28800,
             "current_units":{"time":"iso8601","interval":"seconds","us_aqi":"USAQI"},
             "current":{"time":"2026-10-16T14:00","interval":3600,"us_aqi":64}}
        """.trimIndent()

        /** 空气端点报错时的形状：正文到手，但内容不能用。 */
        val AIR_BROKEN = """{"error":true,"reason":"bad air request"}"""
    }

    private fun source(text: String? = null, air: String? = null) =
        OpenMeteoWeatherSource(responseText = text, airResponseText = air)

    // ------------------------------------------------------------ 请求

    /**
     * `timezone` 漏了就错一天，而错一天的表现是**界面上拿昨天的温度冒充今天**，
     * 缓存判重也会跟着失效。这类错不会崩，只会安静地说错话，所以钉死。
     */
    @Test
    fun `地址带齐坐标时区与只取今天`() {
        val url = source().requestUrl(HANGZHOU)

        assertTrue("缺纬度：$url", url.contains("latitude=30.2741"))
        assertTrue("缺经度：$url", url.contains("longitude=120.1551"))
        assertTrue(
            "缺 daily 变量：$url",
            url.contains("daily=weather_code%2Ctemperature_2m_max%2Ctemperature_2m_min")
        )
        assertTrue("缺时区：$url", url.contains("timezone=Asia%2FShanghai"))
        assertTrue("不是只要今天：$url", url.contains("forecast_days=1"))
        assertTrue("温度单位要显式写死，否则换默认会悄悄改变显示值：$url",
            url.contains("temperature_unit=celsius"))
    }

    /** 地址里不许出现任何 key —— 换源的全部意义就在这一条。 */
    @Test
    fun `地址里没有任何凭据`() {
        val url = source().requestUrl(HANGZHOU).lowercase()

        listOf("key", "apikey", "sig", "token", "secret", "uid", "password")
            .forEach { assertFalse("地址里出现了 $it：$url", url.contains("=$it") || url.contains("&$it")) }
    }

    // ------------------------------------------------------------ 空气质量

    /** 空气走的是**另一个域名**，别把它当成 forecast 的一个参数写进同一个地址。 */
    @Test
    fun `空气质量打的是另一个端点`() {
        val url = source().airQualityUrl(HANGZHOU)

        assertTrue("域名不对：$url", url.startsWith("https://air-quality-api.open-meteo.com/"))
        assertTrue("路径不对：$url", url.contains("/v1/air-quality"))
        assertTrue("缺纬度：$url", url.contains("latitude=30.2741"))
        assertTrue("缺经度：$url", url.contains("longitude=120.1551"))
        assertTrue("缺时区：$url", url.contains("timezone=Asia%2FShanghai"))
    }

    /**
     * 只取 `us_aqi`。
     *
     * 多取一个变量不只是响应变大：界面上会出现第二个读者不会去看的数字，
     * 而它还让人开始拿别处的 µg/m³ 来对数，对不上时只会以为这个源不准。
     */
    @Test
    fun `只取 us_aqi 不取原始浓度`() {
        val url = source().airQualityUrl(HANGZHOU)

        assertTrue(url.contains("current=us_aqi"))
        assertFalse("不该顺带取 pm2_5：$url", url.contains("pm2_5"))
        assertFalse("不该顺带取 pm10：$url", url.contains("pm10"))
    }

    @Test
    fun `解析出空气质量指数`() {
        assertEquals(64, source(air = AIR_OK).parseAirQuality(AIR_OK))
    }

    /**
     * 指数四舍五入成整数。
     *
     * 上游给的是浮点（实测返回 `64` 或 `63.7` 都可能），而界面上只写「良 64」，
     * 显示一位小数会把 92.dp 那一行撑破。
     */
    @Test
    fun `指数四舍五入成整数`() {
        assertEquals(64, source(air = """{"current":{"us_aqi":63.7}}""").parseAirQuality("""{"current":{"us_aqi":63.7}}"""))
    }

    /**
     * 空气拉不到就是**没有**，不是 0、也不是沿用上一次的值。
     *
     * 用户照着它决定戴不戴口罩，显示一个假的「优」比什么都不显示坏得多。
     */
    @Test
    fun `空气读数缺失时返回 null 而不是 0`() {
        assertNull(source().parseAirQuality("{}"))
        assertNull(source().parseAirQuality("""{"current":{}}"""))
        assertNull(source().parseAirQuality("""{"current":{"us_aqi":null}}"""))
        assertNull(source().parseAirQuality("not json at all"))
    }

    // ------------------------------------------------------------ 解析

    @Test
    fun `解析出日期天气词与四舍五入后的高低温`() {
        val w = source().parseToday(OK_RESPONSE, HANGZHOU)!!

        assertEquals(LocalDate.of(2026, 10, 16).toEpochDay(), w.epochDay)
        assertEquals("杭州", w.cityName)
        assertEquals("毛毛雨", w.textDay)
        assertEquals(26, w.high)
        // 19.7 显示成 20：界面上没有半度，与其显示一位小数把那一行撑宽，
        // 不如四舍五入——它本来就是预报值，精度不到 0.5 度。
        assertEquals(20, w.low)
    }

    /**
     * **城市名来自我们声明的地点，不是响应。**
     *
     * 坐标接口不回传地名，所以显示什么只能由 [WeatherLocation.name] 决定。
     * 这条钉住「换个地点就换个名」，同时防止有人误以为界面上那个名字是服务端认证过的。
     */
    @Test
    fun `城市名随地点变而不是写死在解析里`() {
        val beijing = WeatherLocation("北京", 39.9042, 116.4074)

        assertEquals("北京", source().parseToday(OK_RESPONSE, beijing)!!.cityName)
        assertEquals("杭州", source().parseToday(OK_RESPONSE, HANGZHOU)!!.cityName)
    }

    /**
     * 逐日聚合不分昼夜，所以夜间值是**空**而不是把白天的词抄一遍。
     *
     * 抄一遍会让「白天夜间本来不同」这件事在数据里彻底看不出来，
     * 而空串正好走 [DailyWeather.textAt] 已测过的退路。
     */
    @Test
    fun `夜间值留空而不是抄白天的词`() {
        val w = source().parseToday(OK_RESPONSE, HANGZHOU)!!

        assertEquals("", w.textNight)
        assertEquals("任何钟点都退回白天的词", "毛毛雨", w.textAt(3))
        assertEquals("毛毛雨", w.textAt(23))
    }

    /** 温度缺测只该让那一个数字变 null，不该让整份响应作废。 */
    @Test
    fun `温度缺测不影响天气词与日期`() {
        val text = OK_RESPONSE
            .replace("\"temperature_2m_max\":[26.0]", "\"temperature_2m_max\":[null]")
            .replace("\"temperature_2m_min\":[19.7]", "\"temperature_2m_min\":[null]")
        val w = source().parseToday(text, HANGZHOU)!!

        assertNull(w.high)
        assertNull(w.low)
        assertEquals("毛毛雨", w.textDay)
        assertEquals(LocalDate.of(2026, 10, 16).toEpochDay(), w.epochDay)
    }

    /** 缺日期就没法判断是不是今天，不能放行 —— 那等于让昨天的数据冒充今天。 */
    @Test
    fun `残缺响应解析成 null 而不是抛`() {
        assertNull(source().parseToday("{}", HANGZHOU))
        assertNull(source().parseToday("""{"daily":{}}""", HANGZHOU))
        assertNull(source().parseToday("""{"daily":{"time":[null]}}""", HANGZHOU))
        assertNull(source().parseToday("""{"daily":{"time":["不是日期"]}}""", HANGZHOU))
        assertNull(source().parseToday("not json at all", HANGZHOU))
    }

    // ------------------------------------------------------------ 成功与失败

    @Test
    fun `正常响应被识别成成功`() = runTest {
        assertEquals(WeatherFetch.Got(OK_RESPONSE), source(OK_RESPONSE).fetchToday(HANGZHOU))
    }

    /**
     * 错误原因要**读正文**，不能只报「HTTP 400」。
     *
     * 服务端已经把答案写好了（哪个参数、给了什么值），只报状态码等于把它扔掉。
     */
    @Test
    fun `错误响应把 reason 当成失败原因`() = runTest {
        val result = source(ERROR_RESPONSE).fetchToday(HANGZHOU)

        assertTrue(result is WeatherFetch.Unavailable)
        assertTrue(
            "原因里要能看到服务端说了什么：$result",
            (result as WeatherFetch.Unavailable).reason.contains("Latitude")
        )
    }

    /** 成功响应没有 `error` 键 —— 把它当错误就是误判。 */
    @Test
    fun `没有 error 字段的成功响应不被当成错误`() = runTest {
        assertTrue(source(OK_RESPONSE).fetchToday(HANGZHOU) is WeatherFetch.Got)
    }

    @Test
    fun `空响应被识别成失败`() = runTest {
        val result = source("").fetchToday(HANGZHOU)

        assertTrue(result is WeatherFetch.Unavailable)
        assertTrue((result as WeatherFetch.Unavailable).reason.contains("空"))
    }

    // ------------------------------------------------------------ 失败类别

    /**
     * 类别按「答复有没有到手」切，不按故障的技术成因切。
     *
     * 这一条是整套分类的地基：正文到手的三种失败（空 / error / 非 2xx）
     * 归到一处，因为它们对用户是同一件事 —— 查网络没用，等下次进 App 再试。
     */
    @Test
    fun `正文到手的失败都不是网络问题`() = runTest {
        val empty = source("").fetchToday(HANGZHOU) as WeatherFetch.Unavailable
        val errored = source(ERROR_RESPONSE).fetchToday(HANGZHOU) as WeatherFetch.Unavailable

        assertEquals(WeatherFailureKind.REJECTED, empty.kind)
        assertEquals(WeatherFailureKind.REJECTED, errored.kind)
    }

    /**
     * 反过来，抛异常 = 一个字节都没拿到 → 网络问题。
     *
     * 用注入的拦截器制造异常，而不是等一次真实的 DNS 失败：
     * 后者会让这条用例随网络环境时红时绿。
     */
    @Test
    fun `请求发不出去归到网络问题`() = runTest {
        val failing = OpenMeteoWeatherSource(
            client = OkHttpClient.Builder()
                .addInterceptor { throw java.net.UnknownHostException("no dns in here") }
                .build(),
            airResponseText = AIR_OK
        )

        val result = failing.fetchToday(HANGZHOU) as WeatherFetch.Unavailable

        assertEquals(WeatherFailureKind.OFFLINE, result.kind)
        assertTrue("技术细节要留着：${result.reason}", result.reason.contains("解析"))
    }

    /** 空气走的是同一个判定，空气失败也一样分类 —— 免得以后只改天气那一半。 */
    @Test
    fun `空气拉不到时类别与天气一致`() = runTest {
        val brokenAir = OpenMeteoWeatherSource(responseText = OK_RESPONSE, airResponseText = AIR_BROKEN)
        // 注意这里**不能**再注入空气正文：注入了就等于服务端已经回过话，
        // 请求压根不会发出去，也就构造不出「没拿到答复」这一种。
        val failing = OpenMeteoWeatherSource(
            client = OkHttpClient.Builder()
                .addInterceptor { throw java.net.UnknownHostException("no dns in here") }
                .build()
        )

        assertEquals(
            WeatherFailureKind.REJECTED,
            (brokenAir.fetchAirQuality(HANGZHOU) as WeatherFetch.Unavailable).kind
        )
        assertEquals(
            WeatherFailureKind.OFFLINE,
            (failing.fetchAirQuality(HANGZHOU) as WeatherFetch.Unavailable).kind
        )
    }

    // ------------------------------------------------------------ WMO 码

    /**
     * 天气词直接决定界面画什么图标（`glyphFor()` 靠关键词挑），
     * 所以这张表钉的是「每个码都落到预期的那一档」。
     */
    @Test
    fun `WMO 码逐个映射到中文`() {
        assertEquals("晴", describeWeatherCode(0))
        assertEquals("阴", describeWeatherCode(3))
        assertEquals("雾", describeWeatherCode(45))
        assertEquals("小雨", describeWeatherCode(61))
        assertEquals("中雨", describeWeatherCode(63))
        assertEquals("大雨", describeWeatherCode(65))
        assertEquals("小雪", describeWeatherCode(71))
        assertEquals("大雪", describeWeatherCode(75))
        assertEquals("雷阵雨", describeWeatherCode(95))
        assertEquals("雷阵雨伴冰雹", describeWeatherCode(96))
    }

    /** 认不出来的码说「未知」，不许猜 —— 界面认不出就画中性图标。 */
    @Test
    fun `认不出来的码返回未知而不是猜一个`() {
        assertEquals("未知", describeWeatherCode(null))
        assertEquals("未知", describeWeatherCode(999))
    }

    /** 量级差别要留在词里：「下小雨」和「下大雨」该让人一眼看出区别。 */
    @Test
    fun `量级差别保留在措辞里`() {
        assertTrue(describeWeatherCode(61) != describeWeatherCode(65))
        assertTrue(describeWeatherCode(71) != describeWeatherCode(75))
    }

    // ------------------------------------------------------------ 错误消息

    /**
     * 异常消息会一路显示到界面上、会被截图转发，地址必须被抹掉。
     *
     * 换源之后那里已经没有密钥可泄，但一个几百字符的地址对用户毫无用处，
     * 抹掉之后剩下的部分仍然能定位问题。
     */
    @Test
    fun `错误消息抹掉地址但保留可诊断部分`() {
        val leaky = java.net.SocketTimeoutException(
            "timeout on https://api.open-meteo.com/v1/forecast?latitude=30.27&longitude=120.16"
        )

        val reason = leaky.describe()

        assertFalse("消息里漏了完整地址：$reason", reason.contains("https://"))
        assertFalse("消息里漏了坐标：$reason", reason.contains("120.16"))
        assertTrue("原因要还留着可诊断的部分：$reason", reason.contains("超时"))
    }
}
