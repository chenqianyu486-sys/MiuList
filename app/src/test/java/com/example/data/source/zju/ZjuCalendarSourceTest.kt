package com.example.data.source.zju

import com.example.data.calendar.ZjuCalendarAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 校历来源的**兜底分支**。
 *
 * 加内置兜底的起因是一次真实的失败：2026-10-13 在这台开发机上，GitHub 系的
 * 全部镜像（gh-proxy.com / jsdelivr / raw.githubusercontent）都在 TLS 握手阶段
 * 被掐断，`fetchPublicCalendar` 整条挂掉 —— 于是「课表能抓到，校历却拉不到」，
 * 导入管线走不完。
 *
 * 这些用例全部**离线**，不碰网络。
 */
class ZjuCalendarSourceTest {

    /** 内置那份的内容，路径见 `res/assets/zju-calendar-fallback.json`。 */
    private val bundled = """
        {
          "lastUpdated": 20261013,
          "termConfigs": [
            { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20270104, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:0"],
          "examTerms": [],
          "tweaks": []
        }
    """.trimIndent()

    /**
     * 让 [ZjuCalendarSource.fetchText] 的每个 URL 都立刻失败，
     * 从而**真正**覆盖「联网全挂 → 落到内置」这条分支（而不是顺带联网一下）。
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

    @Test
    fun `联网全失败时退回内置校历`() = runBlocking {
        val source = ZjuCalendarSource(client = deadClient(), bundledText = bundled)

        val bundle = source.fetch() // 不抛异常就是兜底生效了

        val term = bundle.termConfigs.single()
        assertEquals("2026-2027", term.year)
        assertEquals(0, term.term)
        assertTrue("作息表不该为空", bundle.periods.isNotEmpty())
    }

    @Test
    fun `既没有网络也没有内置时才报错`() = runBlocking {
        val source = ZjuCalendarSource(client = deadClient(), bundledText = null, assets = null)

        val error = runCatching { source.fetch() }.exceptionOrNull()

        assertTrue("应该抛 ZjuException，实际：$error", error is ZjuException)
        assertEquals("校历配置拉取失败，可改用文件导入", error?.message)
    }

    /**
     * 联网那份**比内置的旧**时，当前学期必须仍然出现在下拉里。
     *
     * 这个 bug 是 2026-10-13 在模拟器上真跑出来的：宿主代理配好后校历走通了联网源，
     * 而上游 zju-ical 的 config.json 只更到 2024-2025（仓库 2023-11 后基本停更），
     * 于是「联网优先」把内置的 2026-2027 整个盖掉，学期下拉里只剩
     * `2024-2025 春学期 / 夏学期` —— 用户会选到一个根本不存在的当前学期，
     * 后面抓回来的课表自然是空的，**而且没有任何报错**。
     *
     * 没有这条用例，改回「联网优先」不会有任何测试失败。
     */
    @Test
    fun `联网那份比内置旧时 当前学期不能被盖掉`() {
        // 上游那份的形状取自实抓：只有 2023-2024 / 2024-2025，没有 2026-2027。
        val staleRemote = """
            {
              "lastUpdated": 20240501,
              "termConfigs": [
                { "Year": "2023-2024", "Term": 4, "Begin": 20240226, "End": 20240707, "FirstWeekNo": 1 },
                { "Year": "2024-2025", "Term": 4, "Begin": 20250224, "End": 20240707, "FirstWeekNo": 1 }
              ],
              "classTerms": ["2023-2024:4", "2024-2025:4"],
              "examTerms": [],
              "tweaks": [
                { "TweakType": 2, "Description": "[国庆节] 调休", "From": 20241001, "To": 20241007 }
              ]
            }
        """.trimIndent()

        val merged = ZjuCalendarSource().mergeForTest(
            remote = ZjuCalendarSource().parse(staleRemote),
            bundled = ZjuCalendarSource().parse(bundled)
        )

        assertTrue(
            "下拉里必须有 2026-2027 秋学期，实际：${merged.termConfigs.map { it.year to it.term }}",
            merged.termConfigs.any { it.year == "2026-2027" && it.term == 0 }
        )
        // 联网那份的旧学期也要保留，否则历史学期会消失。
        assertTrue(
            "联网那份的学期不该被丢",
            merged.termConfigs.any { it.year == "2024-2025" && it.term == 4 }
        )
        assertTrue("联网那份的调休不该被丢", merged.tweaks.any { it.from == 20241001 })
    }

    @Test
    fun `联网与内置都有同一学期时 以联网的日期为准`() {
        val sameTermDifferentDates = """
            {
              "lastUpdated": 20270101,
              "termConfigs": [
                { "Year": "2026-2027", "Term": 0, "Begin": 20260921, "End": 20270115, "FirstWeekNo": 2 }
              ],
              "classTerms": [], "examTerms": [], "tweaks": []
            }
        """.trimIndent()

        val merged = ZjuCalendarSource().mergeForTest(
            remote = ZjuCalendarSource().parse(sameTermDifferentDates),
            bundled = ZjuCalendarSource().parse(bundled)
        )

        val term = merged.termConfigs.single { it.year == "2026-2027" && it.term == 0 }
        assertEquals("联网优先：日期应取联网那份", 20260921, term.begin)
        assertEquals(20270115, term.end)
        assertEquals(2, term.firstWeekNo)
    }

    @Test
    fun `内置校历解析出正确的学期与日期`() {
        val parsed = ZjuCalendarSource(bundledText = bundled).parse(bundled)

        val term = parsed.termConfigs.single()
        assertEquals("2026-2027", term.year)
        assertEquals(0, term.term)
        assertEquals(20260914, term.begin)
        assertEquals(20270104, term.end)
        assertEquals(1, term.firstWeekNo)

        // 作息表由本项目自己补，不来自 config.json
        assertTrue("作息表不该为空", parsed.periods.isNotEmpty())
    }

    @Test
    fun `秋冬学期的起止日期能换算成 epochDay 与总周数`() {
        val parsed = ZjuCalendarSource(bundledText = bundled).parse(bundled)
        val term = parsed.termConfigs.single()

        val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(term.begin)
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(term.end)
        assertNotNull(begin)
        assertNotNull(end)

        val weeks = ZjuCalendarAdapter.totalWeeks(begin!!, end!!, term.firstWeekNo ?: 1)
        // 2026-09-14 ~ 2027-01-04 约 16 周半，断言一个合理区间而不是精确值：
        // 单双周展开完全依赖这个数，算错会让课在错误的周次出现。
        assertTrue("总周数不合理：$weeks", weeks in 14..20)
    }

    @Test
    fun `selectTerm 只保留目标学期`() {
        val full = """
            {
              "lastUpdated": 20261013,
              "termConfigs": [
                { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20270104, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 4, "Begin": 20270222, "End": 20270704, "FirstWeekNo": 1 }
              ],
              "classTerms": [],
              "examTerms": [],
              "tweaks": []
            }
        """.trimIndent()

        val source = ZjuCalendarSource(bundledText = bundled)
        val selected = source.selectTerm(source.parse(full), "2026-2027", 0)

        assertEquals(1, selected.termConfigs.size)
        assertEquals(0, selected.termConfigs.first().term)
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

        val term = parsed.termConfigs.single()
        assertEquals("2026-2027", term.year)
        assertEquals(0, term.term)
        assertEquals(20260914, term.begin)
        assertEquals(20270104, term.end)
    }

    private companion object {
        const val FALLBACK = ZjuCalendarSource.FALLBACK_ASSET
    }
}