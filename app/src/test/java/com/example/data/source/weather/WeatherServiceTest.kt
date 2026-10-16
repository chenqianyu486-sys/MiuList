package com.example.data.source.weather

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.UnknownHostException
import java.time.LocalDate

/**
 * 天气取数的**决策层**：3 小时窗口、当天校验、三态语义、空气并入。
 *
 * 全部离线：响应文本注入 [OpenMeteoWeatherSource]，缓存落在 [TemporaryFolder]，
 * 时钟也钉死，所以「距上次拉取多久」是确定的值而不是碰运气。
 *
 * 这一层**不该因为换了数据源、或改了窗口长度而改**：它只认「拿到的这份
 * 是不是今天」与「它有多新」，与那份数据是谁给的无关。底下换源、上面改窗口，
 * 用例的形状要跟着动，但它们要验的那条逻辑一个都不许变。
 */
class WeatherServiceTest {

    private companion object {
        val TODAY = LocalDate.of(2026, 10, 16).toEpochDay()
        val HANGZHOU = WeatherLocation.DEFAULT

        /** 基准时刻（毫秒）。 */
        const val T0 = 1_792_131_086_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE

        /** 3 小时窗口（用户 2026-10-16 从 20 分钟改过来的）。 */
        const val WINDOW = WeatherService.FRESH_WINDOW_MILLIS

        /** 上游报错时的形状：正文里带 error 与 reason。 */
        val BROKEN = """{"error":true,"reason":"boom"}"""

        /** 61 = 小雨。 */
        fun response(date: String, high: Double = 25.0, low: Double = 19.0) = """
            {"daily":{"time":["$date"],"weather_code":[61],
              "temperature_2m_max":[$high],"temperature_2m_min":[$low]}}
        """.trimIndent()

        /** 空气正常：64 = 良。 */
        const val AIR_OK = """{"current":{"time":"2026-10-16T14:00","interval":3600,"us_aqi":64}}"""
    }

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheIn() = WeatherCache(File(temp.newFolder(), WeatherCache.FILE_NAME))

    private fun service(
        text: String? = null,
        /**
         * 空气质量正文。**默认给一份固定的成功响应，而不是 null。**
         *
         * 因为 [OpenMeteoWeatherSource] 把 `null` 理解成「没注入，去真联网」——
         * 写测试时漏传一个参数，于是这条用例会真的发请求出去。
         * 实测过一次：断言里写着 `usAqi=null`，实际拿回来的却是真实读数 150，
         * 用例红在一个与它要验的东西毫无关系的地方。
         *
         * 所以这里的默认值必须是「一份确定的正文」。要模拟空气拉不到，
         * 显式传 [BROKEN] 或 `"""{"current":{}}"""`，不要靠 null。
         */
        air: String = AIR_OK,
        cache: WeatherCache = cacheIn(),
        today: Long = TODAY,
        now: Long = T0,
        /**
         * 整份数据源覆盖。
         *
         * 只在要验「网络层失败」时用：那是唯一无法靠注入正文造出来的分支
         * （正文注入了就等于服务端已经回过话）。做法是给 client 挂一个
         * 抛异常的拦截器 —— **注入异常，而不是等它真的发生**，
         * 否则换台机器、换个网络，这条用例会红在一个与它无关的地方。
         */
        source: OpenMeteoWeatherSource =
            OpenMeteoWeatherSource(responseText = text, airResponseText = air)
    ) = WeatherService(
        source = source,
        cache = cache,
        today = { today },
        nowMillis = { now }
    )

    /** 一个「请求压根没发出去」的源：拦截器直接抛异常。 */
    private fun offlineSource() = OpenMeteoWeatherSource(
        client = OkHttpClient.Builder()
            .addInterceptor { throw UnknownHostException("no dns in here") }
            .build(),
        airResponseText = AIR_OK
    )

    /**
     * 用**真实的写入路径**把一份缓存种进去，而不是手拼外壳。
     *
     * 这么写顺带把「写入时确实记下了拉取时刻」也一起验了 ——
     * 如果 `refresh()` 忘了写 `fetchedAt`，下面所有窗口用例都会失败。
     */
    private fun seededCache(
        body: String,
        fetchedAt: Long,
        today: Long = TODAY,
        air: String = AIR_OK
    ): WeatherCache {
        val cache = cacheIn()
        // runBlocking 而不是把整个 helper 变成 suspend：下面不少用例本身不是
        // suspend 的（它们只读 cachedToday()），那样写会传染一片。
        kotlinx.coroutines.runBlocking {
            service(text = body, air = air, cache = cache, today = today, now = fetchedAt).refresh()
        }
        return cache
    }

    // ------------------------------------------------------------ 缓存

    @Test
    fun `拉到成功就写缓存并记下拉取时刻`() = runTest {
        val cache = cacheIn()
        val state = service(text = response("2026-10-16"), cache = cache, now = T0).refresh()

        assertTrue(state is WeatherService.WeatherState.Available)
        assertNotNull("成功后必须落缓存", cache.read())
        assertEquals("缓存必须记住本机拉取它的那一刻", T0, service(cache = cache).cachedToday()?.fetchedAtMillis)
    }

    @Test
    fun `缓存是今天的就能立刻用上`() {
        val cache = seededCache(response("2026-10-16"), T0)

        val cached = service(cache = cache, now = T0 + MINUTE).cachedToday()

        assertNotNull(cached)
        assertEquals("杭州", cached!!.weather.cityName)
        assertEquals(25, cached.weather.high)
    }

    /**
     * **核心判据**：缓存里昨天的那份不能当今天显示。
     *
     * 用户会照着这个温度决定穿不穿外套。拿昨天的冒充今天，
     * 比干脆显示「读不到」坏得多。
     */
    @Test
    fun `缓存不是今天的就不给用`() {
        val cache = seededCache(response("2026-10-15"), T0, today = TODAY)

        assertNull(service(cache = cache, now = T0).cachedToday())
    }

    /** 缓存里的坏内容不能让整个模块崩，退化成「没有缓存」。 */
    @Test
    fun `缓存内容坏了就当没有`() {
        val cache = cacheIn()
        cache.write("{ this is not json")

        assertNull(service(cache = cache, now = T0).cachedToday())
    }

    /**
     * 老版本存的是**裸响应**，套上外壳后解不出来 —— 必须当「没有缓存」重新拉，
     * 而不是把一份裸 JSON 误认成外壳（`body` 为 null）或干脆崩掉。
     *
     * 换源之后这条更重要：磁盘上可能还留着心知那版写的缓存，
     * 那份正文换了解析器同样解不出来，走的就是这条路。
     */
    @Test
    fun `老格式的裸响应当没有缓存`() {
        val cache = cacheIn()
        cache.write(response("2026-10-16"))

        assertNull(service(cache = cache, now = T0).cachedToday())
    }

    /** 换源前存进去的那种响应，换了解析器之后必须自然作废，而不是显示错的东西。 */
    @Test
    fun `换源前的旧响应不会被新解析器误认`() {
        val cache = cacheIn()
        cache.write(
            """
            {"results":[{"location":{"name":"杭州"},
              "daily":[{"date":"2026-10-16","text_day":"小雨","high":"25","low":"19"}]}]}
            """.trimIndent()
        )

        assertNull(service(cache = cache, now = T0).cachedToday())
    }

    /** 拉取失败时**不能**动缓存 —— 那等于让用户连最后那份都失去。 */
    @Test
    fun `拉取失败时不动缓存`() = runTest {
        val cache = seededCache(response("2026-10-16"), T0)
        val before = cache.read()

        val state = service(text = BROKEN, cache = cache, now = T0 + MINUTE).refresh()

        assertTrue(state is WeatherService.WeatherState.Unavailable)
        assertEquals("失败后缓存里应仍是原来那份", before, cache.read())
    }

    @Test
    fun `没配缓存位置时退回纯联网`() = runTest {
        val state = service(text = response("2026-10-16"), cache = WeatherCache(null), now = T0).refresh()

        assertTrue(state is WeatherService.WeatherState.Available)
    }

    // ------------------------------------------------------------ 20 分钟窗口

    /**
     * **窗口内直接读缓存，一次网络都不发。**
     *
     * 证法不是「结果来自缓存」这么含糊，而是让**网络会返回另一个温度**：
     * 缓存里是 25，注入的响应是 99。拿到 25 就说明压根没去问网络。
     * 只断言「值等于缓存」的话，网络刚好失败时也会同样通过，等于没测。
     */
    @Test
    fun `距上次拉取不足 3 小时就直接用缓存`() = runTest {
        val cache = seededCache(response("2026-10-16", high = 25.0), T0)

        val state = service(text = response("2026-10-16", high = 99.0), cache = cache, now = T0 + WINDOW - MINUTE)
            .loadForEntry()

        val available = state as WeatherService.WeatherState.Available
        assertEquals("窗口内不该联网，看到的必须是缓存那份 25", 25, available.weather.high)
        assertTrue("命中缓存时要标出来", available.fromCache)
    }

    /** 刚好卡在边界上：满 3 小时就算过期。 */
    @Test
    fun `正好满 3 小时就过期`() = runTest {
        val cache = seededCache(response("2026-10-16", high = 25.0), T0)

        val state = service(text = response("2026-10-16", high = 99.0), cache = cache, now = T0 + WINDOW)
            .loadForEntry()

        assertEquals(99, (state as WeatherService.WeatherState.Available).weather.high)
    }

    @Test
    fun `超过 3 小时就重新拉`() = runTest {
        val cache = seededCache(response("2026-10-16", high = 25.0), T0)

        val state = service(text = response("2026-10-16", high = 99.0), cache = cache, now = T0 + WINDOW + MINUTE)
            .loadForEntry()

        assertEquals(99, (state as WeatherService.WeatherState.Available).weather.high)
    }

    /**
     * 时钟被往回调过（`fetchedAt` 在「现在」之后）时**算过期**。
     *
     * 那种情况下 `age` 是负数，若按「小于窗口就算新」处理，
     * 一份可能已经放了一整天的数据会一直不过期 —— 直到时钟追上来为止。
     */
    @Test
    fun `时钟回拨导致时间戳在未来时算过期`() = runTest {
        val cache = seededCache(response("2026-10-16", high = 25.0), T0 + HOUR)

        val state = service(text = response("2026-10-16", high = 99.0), cache = cache, now = T0)
            .loadForEntry()

        assertEquals(99, (state as WeatherService.WeatherState.Available).weather.high)
    }

    @Test
    fun `isFresh 的边界`() {
        val svc = service(now = T0)

        assertTrue(svc.isFresh(T0, WINDOW))
        assertTrue(svc.isFresh(T0 - WINDOW + MINUTE, WINDOW))
        assertFalse(svc.isFresh(T0 - WINDOW, WINDOW))
        assertFalse(svc.isFresh(T0 - WINDOW - MINUTE, WINDOW))
        assertFalse(svc.isFresh(T0 + 1, WINDOW))
        assertFalse("窗口为 0 时永不命中", svc.isFresh(T0, 0))
    }

    /** 窗口内但缓存是昨天的：一样要去拉，不能拿旧的冒充今天。 */
    @Test
    fun `窗口内但缓存不是今天的仍然要联网`() = runTest {
        val cache = seededCache(response("2026-10-15", high = 25.0), T0)

        val state = service(text = response("2026-10-16", high = 99.0), cache = cache, now = T0 + MINUTE)
            .loadForEntry()

        assertEquals(99, (state as WeatherService.WeatherState.Available).weather.high)
    }

    /**
     * 过期 + 拉不到 = 退回缓存。用户不该因为一次断网就看到天气消失。
     *
     * 时刻必须**跨过 3 小时窗口**：停在窗口内的话，`loadForEntry()` 会直接
     * 命中缓存返回，压根不调用刷新 —— 用例照样绿，却把「拉不到要退回缓存」
     * 这条路径一次都没走到。窗口从 20 分钟改成 3 小时时最容易踩这个坑。
     */
    @Test
    fun `过期且拉不到时退回今天的缓存`() = runTest {
        val cache = seededCache(response("2026-10-16", high = 25.0), T0)

        val state = service(text = BROKEN, cache = cache, now = T0 + WINDOW + HOUR)
            .loadForEntry()

        val available = state as WeatherService.WeatherState.Available
        assertEquals(25, available.weather.high)
        assertTrue(available.fromCache)
    }

    // ------------------------------------------------------------ 三态

    @Test
    fun `联网成功是 Available 且 fromCache 为假`() = runTest {
        val state = service(text = response("2026-10-16"), now = T0).refresh()

        assertEquals(
            WeatherService.WeatherState.Available(
                // 夜间值是空的：逐日聚合不分昼夜，详见 DailyWeather.textAt。
                // 空气默认注入一份 64，所以它也在这条断言里。
                DailyWeather("杭州", TODAY, "小雨", "", 25, 19, usAqi = 64),
                fromCache = false
            ),
            state
        )
    }

    @Test
    fun `拉不到且无缓存是 Unavailable 且带真实原因`() = runTest {
        val state = service(text = BROKEN, now = T0).loadForEntry()

        assertTrue(state is WeatherService.WeatherState.Unavailable)
        assertTrue(
            "原因要能看出是上游报了什么，而不是一句『获取失败』",
            (state as WeatherService.WeatherState.Unavailable).reason.contains("boom")
        )
    }

    /** 拿到了但解不出来，是服务端改了格式 —— 不能说成「今天没天气」。 */
    @Test
    fun `拿到但解析不了是失败`() = runTest {
        val state = service(text = """{"daily":{}}""", now = T0).refresh()

        assertTrue(state is WeatherService.WeatherState.Unavailable)
        assertTrue((state as WeatherService.WeatherState.Unavailable).reason.contains("无法解析"))
    }

    /** 拿到别的日期（时区传错）时如实说，不冒充今天。 */
    @Test
    fun `返回的不是今天就如实报出来`() = runTest {
        val state = service(text = response("2026-10-15"), now = T0).refresh()

        assertTrue(state is WeatherService.WeatherState.Unavailable)
        assertTrue((state as WeatherService.WeatherState.Unavailable).reason.contains("不是今天"))
    }

    // ------------------------------------------------------------ 空气质量

    @Test
    fun `空气读数并进天气里一起显示`() = runTest {
        val state = service(text = response("2026-10-16"), air = AIR_OK, now = T0).refresh()

        val w = (state as WeatherService.WeatherState.Available).weather
        assertEquals(64, w.usAqi)
        assertEquals("空气良 64", w.airQualityLabel())
    }

    /**
     * **空气挂掉不许把天气也拖下水。**
     *
     * 它是另一个端点，为它单独发一次请求。为了一次副标题级的读数
     * 把当天的温度也变成「读不到」，那是明显的坏交易。
     */
    @Test
    fun `空气失败时天气照常显示只是少一段`() = runTest {
        val state = service(
            text = response("2026-10-16", high = 25.0),
            air = BROKEN,
            now = T0
        ).refresh()

        assertTrue("空气失败不该让整块变成不可用：$state", state is WeatherService.WeatherState.Available)
        val w = (state as WeatherService.WeatherState.Available).weather
        assertEquals(25, w.high)
        assertNull("空气读数就该是空而不是 0", w.usAqi)
        assertNull(w.airQualityLabel())
    }

    /** 空气拉回来但里面没指数，同样按「没有」处理，不显示「空气未知」。 */
    @Test
    fun `空气响应里没指数时不显示这一段`() = runTest {
        val state = service(text = response("2026-10-16"), air = """{"current":{}}""", now = T0).refresh()

        val w = (state as WeatherService.WeatherState.Available).weather
        assertNull(w.usAqi)
        assertNull(w.airQualityLabel())
    }

    /** 缓存要把空气一起带回来，否则冷启动那一下空气是空的。 */
    @Test
    fun `缓存里的空气读数也一起回来`() {
        val cache = seededCache(response("2026-10-16"), T0, air = AIR_OK)

        val cached = service(cache = cache, now = T0 + MINUTE).cachedToday()

        assertNotNull(cached)
        assertEquals(64, cached!!.weather.usAqi)
    }

    /**
     * 换源之前写的缓存里没有空气这一项，**不该让天气一起作废**。
     *
     * 少一个可选字段就丢一整天天气，是把可选当成了必需。
     * 传 BROKEN 是为了造出「拉到了空气但没有正文」→ 缓存里 `airBody` 为空
     * 的那份；直接传 null 会变成真的发一次网络请求（见 service 的注释）。
     */
    @Test
    fun `没有空气的旧缓存照样显示天气`() {
        val cache = seededCache(response("2026-10-16"), T0, air = BROKEN)

        val cached = service(cache = cache, now = T0 + MINUTE).cachedToday()

        assertNotNull("旧缓存不该被作废：$cached", cached)
        assertEquals(25, cached!!.weather.high)
        assertNull(cached.weather.usAqi)
    }

    @Test
    fun `三个状态互不重叠`() {
        val loading: WeatherService.WeatherState = WeatherService.WeatherState.Loading
        val available: WeatherService.WeatherState =
            WeatherService.WeatherState.Available(DailyWeather("杭州", TODAY, "晴", "晴", 20, 10), false)
        val unavailable: WeatherService.WeatherState =
            WeatherService.WeatherState.Unavailable("断网", WeatherFailureKind.OFFLINE)

        assertEquals(3, setOf(loading, available, unavailable).size)
        assertFalse(available == unavailable)
    }

    // ------------------------------------------------------------ 失败类别

    /**
     * 一个字节的答复都没拿到 → **网络问题**。
     *
     * 这一条最容易归错：归成「答复不可用」的话，界面上会让用户去查网络，
     * 而实际上他那台机器上什么都正常。
     */
    @Test
    fun `连不上时归到网络问题`() = runTest {
        val state = service(source = offlineSource()).refresh()

        assertEquals(WeatherFailureKind.OFFLINE, (state as WeatherService.WeatherState.Unavailable).kind)
    }

    /** 服务端回了正文但说不行 —— 查网络没有帮助，不能归到 OFFLINE。 */
    @Test
    fun `答复不能用时不是网络问题`() = runTest {
        val state = service(text = BROKEN).refresh()

        assertEquals(WeatherFailureKind.REJECTED, (state as WeatherService.WeatherState.Unavailable).kind)
    }

    /** 拿到了但解不出来：正文到手了，同样不是网络问题。 */
    @Test
    fun `解析失败归到答复不可用`() = runTest {
        val state = service(text = "这不是 JSON").refresh()

        assertEquals(WeatherFailureKind.REJECTED, (state as WeatherService.WeatherState.Unavailable).kind)
    }

    /** 日期对不上（时区传错之类）也是答复本身的问题，不是网络。 */
    @Test
    fun `不是今天的数据归到答复不可用`() = runTest {
        val yesterday = LocalDate.ofEpochDay(TODAY).minusDays(1).toString()
        val state = service(text = response(yesterday.toString())).refresh()

        assertEquals(WeatherFailureKind.REJECTED, (state as WeatherService.WeatherState.Unavailable).kind)
    }

    /**
     * 类别决定界面上那句短标签，但**技术细节不能跟着丢**。
     *
     * 界面上那一格只有 92.dp 宽、9sp，显示不下「域名解析不了（…）」，
     * 所以那一层只读 kind —— 但排查问题的人还要看得到它。
     */
    @Test
    fun `技术细节仍然留在状态里`() = runTest {
        val state = service(text = BROKEN).refresh()

        assertEquals("boom", (state as WeatherService.WeatherState.Unavailable).reason)
    }
}

/**
 * 「什么时候显示夜间的天气文字」——白天的多云和夜里的小雨不该是同一句话。
 *
 * 这一组不依赖数据源：它测的是 [DailyWeather.textAt] 自己的规则，
 * 换源不换规则。当前 Open-Meteo 给的夜间值是空的，于是
 * 「夜间没有单独的值时退回白天」那条正是线上走的那条。
 */
class DailyWeatherTextTest {

    private val day = DailyWeather("杭州", 0L, "小雨", "多云", 25, 19)

    @Test
    fun `白天用白天的词`() {
        assertEquals("小雨", day.textAt(12))
        assertEquals("小雨", day.textAt(9))
    }

    @Test
    fun `入夜和凌晨用夜间的词`() {
        assertEquals("多云", day.textAt(18))
        assertEquals("多云", day.textAt(23))
        assertEquals("多云", day.textAt(0))
        assertEquals("多云", day.textAt(5))
    }

    /** Open-Meteo 现在走的就是这一条：任何钟点都退回白天的词。 */
    @Test
    fun `夜间没有单独的值时退回白天`() {
        val same = DailyWeather("杭州", 0L, "晴", "", 20, 10)

        assertEquals("晴", same.textAt(22))
        assertEquals("晴", same.textAt(12))
    }

    @Test
    fun `温度范围两端都在才画波浪号`() {
        assertEquals("19° ~ 25°", day.temperatureRange())
        assertEquals("25°", day.copy(low = null).temperatureRange())
        assertEquals("19°", day.copy(high = null).temperatureRange())
        assertEquals("25°", day.copy(low = 25).temperatureRange())
        assertNull(day.copy(high = null, low = null).temperatureRange())
    }
}

/**
 * 空气质量的分级与文案。
 *
 * 分段抄的是 EPA 那套（0-50 优 / 51-100 良 / …）而不是欧洲那套 ——
 * 界面上写「良」时用户对的就是这套。选错一套，同一个 64 会一个叫「良」
 * 一个叫「较差」，那就不是翻译问题而是撒谎了。
 */
class AirQualityLabelTest {

    @Test
    fun `各段落到对应的等级`() {
        assertEquals("优", describeAqi(0))
        assertEquals("优", describeAqi(50))
        assertEquals("良", describeAqi(51))
        assertEquals("良", describeAqi(100))
        assertEquals("轻度污染", describeAqi(101))
        assertEquals("轻度污染", describeAqi(150))
        assertEquals("中度污染", describeAqi(151))
        assertEquals("中度污染", describeAqi(200))
        assertEquals("重度污染", describeAqi(201))
        assertEquals("重度污染", describeAqi(300))
        assertEquals("严重污染", describeAqi(301))
        assertEquals("严重污染", describeAqi(500))
    }

    @Test
    fun `文案是等级加指数`() {
        assertEquals("空气良 64", DailyWeather("杭州", 0L, "晴", "", 20, 10, usAqi = 64).airQualityLabel())
        assertEquals("空气优 12", DailyWeather("杭州", 0L, "晴", "", 20, 10, usAqi = 12).airQualityLabel())
    }

    /** 拉不到就不画这一段 —— 不写「空气未知」，也不写「空气 0」。 */
    @Test
    fun `没有读数时不产出文案`() {
        assertNull(DailyWeather("杭州", 0L, "晴", "", 20, 10).airQualityLabel())
    }
}
