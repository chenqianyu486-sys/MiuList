package com.example.data.source.zju

import com.example.data.calendar.TweakKind
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.academicTermOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 校历配置的**解析**与**学期裁剪**。
 *
 * ## 这里不再测「联网 vs 内置怎么合并」
 *
 * 原来有两个 `mergeForTest` 用例，钉的是"上游那份比内置旧时不能覆盖内置"。
 * 那个场景在换掉真源之后**不再存在**：上游 zju-ical 的 `lastUpdated` 停在 20250213，
 * 只到 2024-2025 学年，比内置旧一整个学年，现在是完全的延迟来源。
 * 真源换成自托管后只剩一份，`merge` 随之删除 —— 留着那份测试就是给下一个人
 * 「顺手把合并加回来」的台阶。
 *
 * 合并掉的三层优先级（远程 → 缓存 → 内置）改由 `CalendarUpdateServiceTest` 覆盖。
 *
 * 这些用例全部**离线**，不碰网络。
 */
class ZjuCalendarSourceTest {

    /** 内置那份的内容，路径见 `res/assets/zju-calendar-fallback.json`。 */
    private val bundled = """
        {
          "lastUpdated": 20260917,
          "termConfigs": [
            { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:0", "2026-2027:1"],
          "examTerms": [],
          "tweaks": [
            { "TweakType": "Clear", "Description": "[国庆节] 10月1日停课", "From": 20261001, "To": 20261001 },
            { "TweakType": "Move", "Description": "[国庆节] 10月2日停课，补到10月17日", "From": 20261002, "To": 20261017 },
            { "TweakType": "Exchange", "Description": "[国庆节] 10月6日与9月20日对调", "From": 20261006, "To": 20260920 }
          ]
        }
    """.trimIndent()

    /**
     * 让每个远程 URL 都立刻失败，从而**真正**覆盖「拉不到远程」这条分支
     * （而不是顺带联网一下）。
     *
     * 手法是把 DNS 换成一个必然连接失败的地址（TEST-NET-3 保留网段），
     * 再关掉重试 —— 比「挂个死代理」可靠，也不依赖 okHttp 的内部 API。
     */
    private fun deadClient(): OkHttpClient {
        // 显式实现而不是 SAM 转换：本项目的 OkHttp 版本里 Dns 不是 Kotlin fun interface
        val deadDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                listOf(java.net.InetAddress.getByName("203.0.113.1"))
        }
        return OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .dns(deadDns)
            .build()
    }

    /**
     * 一个**根本不联网**的客户端：直接造一个 [code] / [body] 响应。
     *
     * [deadClient] 只能造出"连接层失败"，造不出 403/空响应那类**应用层**结果，
     * 而这两类在失败原因里长得完全不一样，必须分别钉住。
     */
    private fun stubClient(code: Int, body: String = bundled): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            .build()

    /**
     * 远程拉不到时返回 [RemoteFetch.Unavailable] 并**带上原因**，**不抛异常也不去碰内置**。
     *
     * 抛不抛、回落不回落是 [CalendarUpdateService] 的决定，不该在这一层。
     * 这一层只搬字节：它不知道内置的存在，也没有理由知道。
     *
     * 但「为什么没拉到」必须在这一层留下来 —— 到了上层就只剩一个 `null`，
     * 而域名解析不了 / TLS 被掐 / 403 签名过期的处置方式完全不同。
     */
    @Test
    fun `远程全部失败时给出原因而不是抛异常`() = runBlocking {
        val source = ZjuCalendarSource(
            client = deadClient(),
            bundledText = bundled,
            remoteUrls = listOf("https://example.invalid/config.json")
        )

        val result = source.fetchRemoteText()
        assertTrue(
            "拉不到必须是 Unavailable，让上层去决定回落，实际=$result",
            result is RemoteFetch.Unavailable
        )
        // 原因里要带得上网址，否则多镜像场景下不知道是哪一个挂了。
        assertTrue("原因应指明是哪个地址失败：${(result as RemoteFetch.Unavailable).reason}",
            result.reason.contains("example.invalid"))
        assertNull(source.fetchRemoteBundle())
    }

    /** `remoteUrls` 为空 = 完全不联网，同样是 Unavailable 而不是空 Bundle。 */
    @Test
    fun `没有配置任何远程地址时给出原因`() = runBlocking {
        val source = ZjuCalendarSource(bundledText = bundled, remoteUrls = emptyList())

        val result = source.fetchRemoteText()
        assertTrue(result is RemoteFetch.Unavailable)
        assertEquals(
            "一个地址都没配时要说清是配置问题，不是网络问题",
            "没有配置任何远程地址",
            (result as RemoteFetch.Unavailable).reason
        )
    }

    /**
     * 非 2xx 要带状态码：403（签名过期）和 404（文件没推上去）在界面上必须能一眼分开，
     * 而它们在 OkHttp 里都只是 `isSuccessful == false`。
     */
    @Test
    fun `非 2xx 的失败原因里带 HTTP 状态码`() = runBlocking {
        val source = ZjuCalendarSource(
            client = stubClient(code = 403),
            remoteUrls = listOf("https://example.com/config.json")
        )

        val result = source.fetchRemoteText()
        assertTrue(result is RemoteFetch.Unavailable)
        assertTrue(
            "状态码要出现在原因里：${(result as RemoteFetch.Unavailable).reason}",
            result.reason.contains("403")
        )
    }

    /** 2xx 但内容是空的也算失败，且要说清是空内容而不是网络问题。 */
    @Test
    fun `返回空内容时原因是空内容而不是网络`() = runBlocking {
        val source = ZjuCalendarSource(
            client = stubClient(code = 200, body = "   "),
            remoteUrls = listOf("https://example.com/config.json")
        )

        val result = source.fetchRemoteText()
        assertTrue(result is RemoteFetch.Unavailable)
        assertTrue(
            "要能分辨「空内容」和「网络不通」，实际：${(result as RemoteFetch.Unavailable).reason}",
            result.reason.contains("空内容")
        )
    }

    /** 注入文本时直接拿到 Got，**完全不联网**。 */
    @Test
    fun `注入的远程文本直接返回 Got`() = runBlocking {
        val source = ZjuCalendarSource(remoteText = bundled)

        assertEquals(RemoteFetch.Got(bundled), source.fetchRemoteText())
    }

    /** 内置那份能被 `parseBundled` 直接读出来（不联网）。 */
    @Test
    fun `parseBundled 直接读内置那份`() {
        val parsed = ZjuCalendarSource(bundledText = bundled).parseBundled()

        assertNotNull(parsed)
        assertEquals(2, parsed!!.termConfigs.size)
    }

    /** 内置文本没给也读不到 assets 时返回 null（而不是空 Bundle）。 */
    @Test
    fun `既没有内置文本也没有 assets 时 parseBundled 返回 null`() {
        assertNull(ZjuCalendarSource(bundledText = null, assets = null).parseBundled())
    }

    /**
     * 内置那份按上游的真实形态解析：一条 termConfigs = 一个 8 周半学期，
     * TweakType 是字符串。
     */
    @Test
    fun `内置校历解析出正确的学期与日期`() {
        val parsed = ZjuCalendarSource(bundledText = bundled).parse(bundled)

        assertEquals(2, parsed.termConfigs.size)
        val autumn = parsed.termConfigs.single { it.term == 0 }
        assertEquals("2026-2027", autumn.year)
        assertEquals(20260914, autumn.begin)
        assertEquals(20261108, autumn.end)
        val winter = parsed.termConfigs.single { it.term == 1 }
        assertEquals(20261109, winter.begin)
        assertEquals(20270103, winter.end)

        // 作息表由本项目自己补，不来自 config.json
        assertTrue("作息表不该为空", parsed.periods.isNotEmpty())
    }

    /**
     * 上游的 `TweakType` 写的是字符串。声明成 Int 时 Moshi 直接抛异常，
     * 而 `parse` 把反序列化包在 `runCatching` 里 —— 整份校历会解析失败。
     * 所以这里必须真的把字符串形态跑通。
     */
    @Test
    fun `字符串形态的 TweakType 要能解析出来`() {
        val parsed = ZjuCalendarSource(bundledText = bundled).parse(bundled)

        assertEquals(
            listOf(TweakKind.CLEAR, TweakKind.MOVE, TweakKind.EXCHANGE),
            parsed.tweaks.map { it.tweakType }
        )
        assertEquals(20261002, parsed.tweaks[1].from)
        assertEquals(20261017, parsed.tweaks[1].to)
    }

    /** 秋冬学期跨两段，折起来是 16 周。 */
    @Test
    fun `秋冬学期折起来是 16 周`() {
        val parsed = ZjuCalendarSource(bundledText = bundled).parse(bundled)
        val term = parsed.termConfigs.academicTermOf("2026-2027", 0)!!

        assertEquals("2026-2027学年 秋冬 学期", term.label)
        assertEquals(16, term.totalWeeks)
    }

    /**
     * `selectTerm` 留下的是**整个学年学期**，不是那一条。
     *
     * 秋冬学期由秋、冬两条 `termConfigs` 组成，只留一条就等于把后半学期丢掉：
     * 学期只剩 8 周，而课程周号已经按 16 周平移过，第 9–16 周的课整段不出现。
     */
    @Test
    fun `selectTerm 留下的是整个学年学期`() {
        val full = """
            {
              "lastUpdated": 20260917,
              "termConfigs": [
                { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 4, "Begin": 20270222, "End": 20270418, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 5, "Begin": 20270419, "End": 20270711, "FirstWeekNo": 1 }
              ],
              "classTerms": [], "examTerms": [],
              "tweaks": [
                { "TweakType": "Clear", "Description": "元旦停课", "From": 20270101, "To": 20270101 }
              ]
            }
        """.trimIndent()

        val source = ZjuCalendarSource(bundledText = bundled)
        val selected = source.selectTerm(source.parse(full), "2026-2027", 0)

        assertEquals("秋冬两段都要留下", listOf(0, 1), selected.termConfigs.map { it.term })
        assertEquals(
            "冬的元旦停课落在秋那条的 End 之后，按学年学期区间裁才留得住",
            1,
            selected.tweaks.size
        )

        val spring = source.selectTerm(source.parse(full), "2026-2027", 4)
        assertEquals("春夏也是两段", listOf(4, 5), spring.termConfigs.map { it.term })
        assertTrue("另一个学期的调休不该跟过来", spring.tweaks.isEmpty())
    }

    /**
     * 确认打进包里的那份 assets 文件存在、且是能被解析的合法 config。
     *
     * 注意这里**读文件本身**而不是走 `classLoader.getResourceAsStream`：
     * JVM 单测的 classpath 里没有 Android assets，所以那条路必然读不到 ——
     * 这正是为什么 [ZjuCalendarSource] 在 App 里必须用 `Context.assets`。
     * App 运行时能否读到，要靠 `connectedDebugAndroidTest` 验，不能在这儿假装验了。
     */
    @Test
    fun `内置 assets 文件存在且是合法校历 JSON`() {
        // 注意路径是 **src/main/assets**，不是 src/main/res/assets ——
    // 后者 AGP 根本不认：文件留在那儿能看见，构建也不报错，
    // 但 mergeDebugAssets 的输出是空的，APK 里没有它。
    // 这个「静默不打包」正是加这条断言的原因。
    val relative = "src/main/assets/$FALLBACK"
        // Gradle 把测试的工作目录设为 app 模块，但保险起见向上回溯几层，
        // 别让这条断言因为「在哪跑」而时好时坏。
        val file = generateSequence(java.io.File(".")) { it.parentFile }
            .take(6)
            .map { java.io.File(it, "app/$relative") }
            .firstOrNull { it.exists() }
            ?: generateSequence(java.io.File(".")) { it.parentFile }
                .take(6)
                .map { java.io.File(it, relative) }
                .firstOrNull { it.exists() }

        assertNotNull(
            "从当前目录向上都没找到 app/$relative（工作目录：${java.io.File(".").absolutePath}）",
            file
        )

        val text = file!!.readText(Charsets.UTF_8)
        val parsed = ZjuCalendarSource(bundledText = text).parse(text)

        assertEquals(2, parsed.termConfigs.size)
        val autumn = parsed.termConfigs.single { it.term == 0 }
        assertEquals("2026-2027", autumn.year)
        assertEquals(20260914, autumn.begin)
        assertEquals(20261108, autumn.end)
        val winter = parsed.termConfigs.single { it.term == 1 }
        assertEquals(20261109, winter.begin)
        assertEquals(20270103, winter.end)

        // 打进包里的那份调休必须是字符串形态且能解析：这份文件是网络不通时唯一的出路
        assertTrue("调休不该为空", parsed.tweaks.isNotEmpty())
        assertTrue(parsed.tweaks.none { it.tweakType == null })
        val term = parsed.termConfigs.academicTermOf("2026-2027", 0)!!
        assertEquals(16, term.totalWeeks)
    }

    private companion object {
        const val FALLBACK = ZjuCalendarSource.FALLBACK_ASSET
    }
}
