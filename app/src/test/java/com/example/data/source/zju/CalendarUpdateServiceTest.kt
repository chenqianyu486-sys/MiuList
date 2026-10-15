package com.example.data.source.zju

import com.example.data.calendar.CalendarCache
import com.example.data.calendar.CalendarOrigin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 校历的三层来源：远程 → 缓存 → 内置。
 *
 * ## 这里钉住的是两件事
 *
 * 1. **回落顺序**：缓存优先于内置 —— 缓存可能是用户上次拉到的新版，
 *    内置只保证"发版时是对的"。
 * 2. **「拉不到」绝不等于「已是最新」** —— 见
 *    [`远程拉不到时是 Failed 而不是 UpToDate`]。
 *
 * 全部离线：远程靠 [ZjuCalendarSource.remoteText] 注入（等价于"这次真的拉到了"），
 * 拉不到靠 `remoteUrls = emptyList()`，本地靠 TemporaryFolder。
 */
class CalendarUpdateServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 内置那份：2026-2027 秋冬，版本 20260917，1 天停课。 */
    private val bundledJson = """
        {
          "lastUpdated": 20260917,
          "termConfigs": [
            { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:0", "2026-2027:1"],
          "examTerms": [],
          "tweaks": [
            { "TweakType": "Clear", "Description": "国庆停课", "From": 20261001, "To": 20261001 }
          ]
        }
    """.trimIndent()

    /**
     * 远程托管那份。版本号可换，停课条数跟着变 ——
     * 这样"确实是更新的内容"和"只是版本号不同"能分开验。
     */
    private fun remoteJson(version: Int?, extraClear: Boolean) = """
        {
          ${version?.let { "\"lastUpdated\": $it," } ?: ""}
          "termConfigs": [
            { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:0", "2026-2027:1"],
          "examTerms": [],
          "tweaks": [
            { "TweakType": "Clear", "Description": "国庆停课", "From": 20261001, "To": 20261001 }
            ${if (extraClear) ",{ \"TweakType\": \"Clear\", \"Description\": \"元旦停课\", \"From\": 20270101, \"To\": 20270101 }" else ""}
          ]
        }
    """.trimIndent()

    private fun cacheFile(): File = File(temp.newFolder(), CalendarCache.FILE_NAME)

    /**
     * @param remote 这次"远程"返回的内容；null = 拉不到（真的走一次空地址表）。
     */
    private fun service(
        cache: CalendarCache,
        bundled: String? = bundledJson,
        remote: String? = null
    ) = CalendarUpdateService(
        source = ZjuCalendarSource(
            client = OkHttpClient.Builder()
                .connectTimeout(200, TimeUnit.MILLISECONDS)
                .readTimeout(200, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build(),
            bundledText = bundled,
            remoteUrls = if (remote == null) listOf("https://example.invalid/config.json") else emptyList(),
            remoteText = remote
        ),
        cache = cache
    )

    // ---------------- loadOffline：不联网的回落顺序 ----------------

    /** 有缓存就用缓存 —— 缓存可能是用户上次拉到的新版。 */
    @Test
    fun `有缓存时用缓存而不是内置`() = runBlocking {
        val cache = CalendarCache(cacheFile())
        cache.write(remoteJson(20261120, extraClear = true))

        val loaded = service(cache).loadOffline()!!

        assertEquals(CalendarOrigin.CACHED, loaded.origin)
        assertEquals(20261120, loaded.bundle.lastUpdated)
    }

    /** 没有缓存才退内置。 */
    @Test
    fun `没有缓存时退内置`() = runBlocking {
        val loaded = service(CalendarCache(cacheFile())).loadOffline()!!

        assertEquals(CalendarOrigin.BUNDLED, loaded.origin)
        assertEquals(20260917, loaded.bundle.lastUpdated)
    }

    /** 两层都没有就是 null，让界面说"读不到"，而不是摆一份空的。 */
    @Test
    fun `缓存与内置都没有时返回 null`() = runBlocking {
        assertNull(service(CalendarCache(cacheFile()), bundled = null).loadOffline())
    }

    /**
     * 缓存文件坏了要能自愈成内置，而不是整个校历读不出来。
     *
     * 坏缓存是真实会发生的：写到一半被杀、托管方改坏过内容。
     * 不能因为它坏了就把用户挡在"没有校历"之外 —— 内置那份明明还在。
     */
    @Test
    fun `缓存内容坏掉时退回内置`() = runBlocking {
        val file = cacheFile()
        file.writeText("{ 这不是 JSON", Charsets.UTF_8)

        val loaded = service(CalendarCache(file)).loadOffline()!!

        assertEquals("坏缓存不该挡住内置那份", CalendarOrigin.BUNDLED, loaded.origin)
    }

    /**
     * `loadOffline` **不联网**。
     *
     * 弹层打开走这条：它必须秒开无网可用。地址表里给的是必然失败的地址，
     * 若这条路径偷偷联网，用例会因超时而变慢 —— 这里直接断言它瞬间返回。
     */
    @Test
    fun `loadOffline 完全不联网`() = runBlocking {
        val started = System.currentTimeMillis()
        service(CalendarCache(cacheFile()), remote = remoteJson(20990101, true)).loadOffline()
        val elapsed = System.currentTimeMillis() - started

        assertTrue(
            "loadOffline 似乎联网了（耗时 ${elapsed}ms），弹层会等满 15s 超时",
            elapsed < 1000
        )
    }

    /** 导入路径上两层都没有必须抛，且归到 SERVER 而不是 NETWORK —— 矛头不该指向用户网络。 */
    @Test
    fun `导入时两层都没有就报错`() = runBlocking {
        val error = runCatching {
            service(CalendarCache(cacheFile()), bundled = null).loadForImport()
        }.exceptionOrNull()

        assertTrue("应该抛 ZjuException，实际：$error", error is ZjuException)
        assertEquals(ZjuFailureKind.SERVER, (error as ZjuException).kind)
    }

    // ---------------- checkForUpdate：三种结果互不重叠 ----------------

    /**
     * 拉不到必须是 Failed，**绝不能是 UpToDate**。
     *
     * 整个方案里最容易写错、后果也最重的一处：把"拉不到"渲染成"已是最新"
     * 等于骗用户，而那恰恰是网络受限时最需要说实话的时候 ——
     * 用户以为校历是最新的，于是照着错的日子去上课。
     */
    @Test
    fun `远程拉不到时是 Failed 而不是 UpToDate`() = runBlocking {
        val outcome = service(CalendarCache(cacheFile()), remote = null).checkForUpdate()

        assertTrue(
            "拉不到绝不能报成『已是最新』，实际：$outcome",
            outcome is CalendarUpdateService.UpdateOutcome.Failed
        )
    }

    /**
     * [CalendarUpdateService.UpdateOutcome.Failed] 的原因要**是这一次真的原因**，
     * 不是一句写死的「连不上校历源」。
     *
     * 钉的是「原因可追溯」这件事本身：域名解析不了、TLS 被掐、403 签名过期、
     * 一个地址都没配 —— 处置方式各不相同，压成同一句话就等于把排查成本推给用户，
     * 而托管方也无从知道自己推的文件是不是被 CDN 拒了。
     */
    @Test
    fun `Failed 的原因来自拉取层而不是固定文案`() = runBlocking {
        val outcome = service(CalendarCache(cacheFile()), remote = null).checkForUpdate()

        assertTrue(outcome is CalendarUpdateService.UpdateOutcome.Failed)
        val reason = (outcome as CalendarUpdateService.UpdateOutcome.Failed).reason
        assertTrue(
            "原因要指得出是哪个地址出的问题，而不是一句通用文案，实际：$reason",
            reason.contains("example.invalid")
        )
        assertTrue(
            "原因要说出失败类型（超时/解析/状态码），实际：$reason",
            reason.contains("超时") || reason.contains("解析") || reason.contains("HTTP")
        )
    }

    /** 拉到内容但格式坏了，同样是 Failed —— 上层照样拿兜底那份顶上。 */
    @Test
    fun `远程内容解析不了时是 Failed`() = runBlocking {
        val outcome = service(CalendarCache(cacheFile()), remote = "{ 这不是 JSON").checkForUpdate()

        assertTrue(
            "坏内容不该报成『已是最新』，实际：$outcome",
            outcome is CalendarUpdateService.UpdateOutcome.Failed
        )
    }

    /** 拉到内容但三份都没有学期，parse 会过、但下层拿不到学期 —— 这里仍算成功拉到。 */
    @Test
    fun `远程比缓存新时写缓存并返回 Updated`() = runBlocking {
        val cache = CalendarCache(cacheFile())
        // 先放一份旧缓存 —— "新"是相对它判断的
        cache.write(remoteJson(20261001, extraClear = false))
        val svc = service(cache, remote = remoteJson(20261120, extraClear = true))

        val outcome = svc.checkForUpdate()

        assertTrue("实际：$outcome", outcome is CalendarUpdateService.UpdateOutcome.Updated)
        val updated = outcome as CalendarUpdateService.UpdateOutcome.Updated
        assertEquals(20261120, updated.version)
        assertNotNull(updated.bundle.termConfigs)
        // 缓存也要跟上，否则下次开弹层看到的还是旧的
        assertTrue("缓存没写进去", cache.read()!!.contains("20261120"))
    }

    /**
     * 没有缓存时，远程那份直接算"新"。
     *
     * 这正是新装用户第一次点"检查更新"的路径：没得比，拿到就是赚到。
     */
    @Test
    fun `没有缓存时远程那份算 Updated`() = runBlocking {
        val cache = CalendarCache(cacheFile())

        val outcome = service(cache, remote = remoteJson(20261120, extraClear = true)).checkForUpdate()

        assertTrue("实际：$outcome", outcome is CalendarUpdateService.UpdateOutcome.Updated)
        assertEquals(20261120, (outcome as CalendarUpdateService.UpdateOutcome.Updated).version)
    }

    /**
     * 远程不比缓存新时是 UpToDate，**且不动缓存**。
     *
     * 覆盖式重写会把用户正在用的那份冲掉 —— 即使内容其实一样，
     * 也不该在用户没确认的情况下动它。
     */
    @Test
    fun `远程不比缓存新时是 UpToDate 且不动缓存`() = runBlocking {
        val cache = CalendarCache(cacheFile())
        cache.write(remoteJson(20261120, extraClear = true))
        val before = cache.read()

        val outcome = service(cache, remote = remoteJson(20260901, extraClear = false)).checkForUpdate()

        assertTrue("实际：$outcome", outcome is CalendarUpdateService.UpdateOutcome.UpToDate)
        assertEquals(20261120, (outcome as CalendarUpdateService.UpdateOutcome.UpToDate).version)
        assertEquals("缓存被覆盖了", before, cache.read())
    }

    /** 版本号相同（内容也相同）同样是 UpToDate —— 不能每次点都喊有更新。 */
    @Test
    fun `版本号相同时是 UpToDate`() = runBlocking {
        val cache = CalendarCache(cacheFile())
        cache.write(remoteJson(20261120, extraClear = true))

        val outcome = service(cache, remote = remoteJson(20261120, extraClear = true)).checkForUpdate()

        assertTrue("实际：$outcome", outcome is CalendarUpdateService.UpdateOutcome.UpToDate)
    }

    /**
     * 远程 `lastUpdated` 缺失时**不覆盖**缓存。
     *
     * 没法证明它新，就不该覆盖用户正在用的东西。代价是托管方忘了写版本号时
     * 用户永远看不到更新提示（表现为"检查了但没反应"）——
     * 那比覆盖掉正确数据安全得多。
     */
    @Test
    fun `远程没有 lastUpdated 时不覆盖缓存`() = runBlocking {
        val cache = CalendarCache(cacheFile())
        cache.write(remoteJson(20261120, extraClear = true))
        val before = cache.read()

        val outcome = service(cache, remote = remoteJson(version = null, extraClear = false)).checkForUpdate()

        assertTrue("实际：$outcome", outcome is CalendarUpdateService.UpdateOutcome.UpToDate)
        assertEquals("版本号都没有就覆盖缓存，等于放弃用户的现有数据", before, cache.read())
    }

    /**
     * 检查更新**只写缓存，不碰用户正在用的校历**。
     *
     * `loadOffline` 在检查之后仍返回缓存那份（内容已换成新版），
     * 但落库要等用户在差异预览里点确认 —— 那是另一条路径。
     */
    @Test
    fun `检查更新只写缓存 不动内置兜底`() = runBlocking {
        val cache = CalendarCache(cacheFile())
        val svc = service(cache, remote = remoteJson(20261120, extraClear = true))

        svc.checkForUpdate()

        // 内置那份仍是最后一道兜底，版本号没被动过
        val offline = service(CalendarCache(cacheFile())).loadOffline()!!
        assertEquals(CalendarOrigin.BUNDLED, offline.origin)
        assertEquals(20260917, offline.bundle.lastUpdated)
        // 而缓存里已经是新版 —— 下次开弹层看到的就是它
        assertEquals(20261120, svc.loadOffline()!!.bundle.lastUpdated)
    }
}
