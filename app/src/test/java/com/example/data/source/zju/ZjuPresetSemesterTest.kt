package com.example.data.source.zju

import com.example.data.calendar.CalendarOrigin
import com.example.data.calendar.EpochDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「官方校历」要与**导入时真正落库的那一份**逐字一致。
 *
 * 弹层上摆着 8 天停课，用户点「应用到本机校历」，界面上却成了 7 天停课 ——
 * 这类漂移两边都「看起来对」，只有把显示与落库钉在同一份校验结果上才躲得过。
 *
 * ## 为什么改成喂显式的 bundle
 *
 * 原来这个类走 `bundledPreset()`，那个方法**自己去读内置 assets**。
 * 于是弹层显示的那份与导入时用的那份是两条独立链路 —— 界面上写着"官方校历"，
 * 落库的却可能是另一份（远程的、或更旧的），而两边都"看起来对"。
 * 现在改成 [ZjuImportService.presetFrom]，**读哪一份由调用方决定**，
 * 这条分叉才真的被合上。
 *
 * 全部离线：`ZjuCalendarSource` 的 `bundledText` 可注入，不必出网也不必碰 assets。
 */
class ZjuPresetSemesterTest {

    private val calendar = """
        {
          "lastUpdated": 20260917,
          "termConfigs": [
            { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:0", "2026-2027:1"],
          "examTerms": [],
          "tweaks": [
            { "TweakType": "Clear", "From": 20260925, "To": 20260927 },
            { "TweakType": "Clear", "From": 20261001, "To": 20261001 },
            { "TweakType": "Clear", "From": 20261003, "To": 20261005 },
            { "TweakType": "Move", "From": 20261002, "To": 20261017 },
            { "TweakType": "Exchange", "From": 20261006, "To": 20260920 },
            { "TweakType": "Exchange", "From": 20261007, "To": 20261010 },
            { "TweakType": "Move", "From": 20261231, "To": 20270104 },
            { "TweakType": "Clear", "From": 20270101, "To": 20270101 }
          ]
        }
    """.trimIndent()

    /** 同一份文件，外加一期的春夏，测「要哪一期」。 */
    private val twoYears = """
        {
          "lastUpdated": 20260917,
          "termConfigs": [
            { "Year": "2025-2026", "Term": 0, "Begin": 20250915, "End": 20251109, "FirstWeekNo": 1 },
            { "Year": "2025-2026", "Term": 1, "Begin": 20251110, "End": 20260104, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": [],
          "examTerms": [],
          "tweaks": []
        }
    """.trimIndent()

    /**
     * 只读校历文件、不碰教务网，所以 CAS 用原实现即可。
     *
     * [origin] 是调用方传进来的：这个类**不决定**读的是哪一份，
     * 它只负责把一份 bundle 折成显示模型。
     */
    private fun preset(
        text: String = calendar,
        prefer: String? = null,
        season: String? = null,
        origin: CalendarOrigin = CalendarOrigin.BUNDLED
    ) = runBlocking {
        val source = ZjuCalendarSource(bundledText = text)
        ZjuImportService(cas = ZjuCasClient(), calendarSource = source)
            .presetFrom(
                bundle = source.parse(text),
                prefer = prefer?.let { ZjuImportService.PreferredTerm(it, season) },
                origin = origin
            )
    }

    /**
     * 同一学年里有秋冬**和**春夏时，必须选中用户当前所在的那一个。
     *
     * 这是「添加新学期」最容易踩的地方：一个学年有 16 周的学期两个（秋冬 / 春夏），
     * 而 `yearSpan` 两边都是 `2026-2027`。只按学年选的话，用户明明在用春夏，
     * 弹层却摆着秋冬 —— 点「应用」就把正在用的学期整个换成了另一个。
     *
     * 所以偏好必须带**季节**，不能只有学年。
     */
    @Test
    fun `同学年有秋冬与春夏时按当前学期选而不是第一个`() {
        val both = """
            {
              "lastUpdated": 20270218,
              "termConfigs": [
                { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 4, "Begin": 20270222, "End": 20270418, "FirstWeekNo": 1 },
                { "Year": "2026-2027", "Term": 5, "Begin": 20270419, "End": 20270711, "FirstWeekNo": 1 }
              ],
              "classTerms": [],
              "examTerms": [],
              "tweaks": []
            }
        """.trimIndent()

        assertEquals("2026-2027学年 春夏 学期", preset(both, "2026-2027", "春夏")!!.label)
        assertEquals("2026-2027学年 秋冬 学期", preset(both, "2026-2027", "秋冬")!!.label)
    }

    /**
     * 偏好带了季节但配置里那一期是**短学期**（`season` 为 null，只有半截）时，
     * 仍要退回按学年匹配，而不是什么都选不到。
     */
    @Test
    fun `短学期没有季节时退回按学年匹配`() {
        // 只有秋一条 → 折不出「秋冬」（两半都在场才敢这么叫）
        val shortTerm = """
            {
              "lastUpdated": 20270218,
              "termConfigs": [
                { "Year": "2027-2028", "Term": 4, "Begin": 20270222, "End": 20270418, "FirstWeekNo": 1 }
              ],
              "classTerms": [], "examTerms": [], "tweaks": []
            }
        """.trimIndent()

        val p = preset(shortTerm, "2027-2028", "春夏")

        assertNotNull("短学期不该选不到", p)
        assertEquals("2027-2028", p!!.yearSpan)
    }

    /**
     * 秋 + 冬两条要折成**一个 16 周的学年学期**。
     *
     * 只取一条的话学期就成了 8 周，弹层上写出来的总周数会与导入后不符。
     */
    @Test
    fun `预置校历折成一个 16 周的秋冬学期`() {
        val p = preset()!!

        assertEquals("2026-2027", p.yearSpan)
        assertEquals("2026-2027学年 秋冬 学期", p.label)
        assertEquals("2026-09-14", EpochDays.format(p.startEpochDay))
        assertEquals("2027-01-03", EpochDays.format(p.endEpochDay))
        assertEquals(16, p.totalWeeks)
        assertEquals("界面上要的是人读的日期，不是上游那个 20260917", "2026-09-17", p.versionLabel)
    }

    /**
     * 来源与版本号要透传到界面上。
     *
     * 用户凭什么相信弹层顶部那一块「官方校历」，唯一的依据就是它能说清自己读的是
     * 远程、缓存还是内置。这两个字段一但对不上，界面上那句「来源 远程」就成了空话。
     */
    @Test
    fun `来源与版本号透传给界面`() {
        val bundled = checkNotNull(preset())
        assertEquals(CalendarOrigin.BUNDLED, bundled.origin)
        assertEquals("内置", bundled.originLabel)
        assertEquals(20260917, bundled.lastUpdated)

        val remote = checkNotNull(preset(origin = CalendarOrigin.REMOTE))
        assertEquals(CalendarOrigin.REMOTE, remote.origin)
        assertEquals("远程", remote.originLabel)
    }

    /** `lastUpdated` 缺失时版本号是 null，界面上不能打印一串 "null"。 */
    @Test
    fun `没有 lastUpdated 时版本号为 null`() {
        val noVersion = calendar.replace("\"lastUpdated\": 20260917,", "")
        assertNull(preset(text = noVersion)?.versionLabel)
    }

    /**
     * 停课 / 补课要按 tweaks 展开，且**补课必须带来源日**。
     *
     * 少了来源日，弹层点「填入」会丢掉最要紧的那一半：这段课算第 3 周还是第 5 周
     * 由来源日决定（10-02 在第 3 周、10-17 在第 5 周）。
     *
     * `Exchange` 的两端也各算一个补课日 —— 09-20 与 10-06 的课是对调的，
     * 那两天都要上课，只算一头就等于把另一头停了。
     */
    @Test
    fun `停课与补课按 tweaks 展开且补课带来源日`() {
        val p = preset()!!

        val noClass = p.noClassDays.map { EpochDays.format(it.epochDay) }
        assertEquals(
            "中秋 3 天 + 国庆 1+3 天 + 元旦 1 天 = 8 天",
            listOf("2026-09-25", "2026-09-26", "2026-09-27", "2026-10-01",
                "2026-10-03", "2026-10-04", "2026-10-05", "2027-01-01"),
            noClass
        )

        val makeup = p.makeupDays.associateBy { it.epochDay }
        assertEquals(
            "两次 Move + 两次 Exchange（各两端）= 6 天",
            setOf("2026-09-20", "2026-10-06", "2026-10-07", "2026-10-10", "2026-10-17", "2027-01-04"),
            makeup.keys.map { EpochDays.format(it) }.toSet()
        )

        val moved = makeup.getValue(EpochDays.parse("2026-10-17")!!)
        assertEquals("10-17 补 10-02 的课", "2026-10-02", EpochDays.format(moved.sourceEpochDay))
        assertEquals("10-02 是周五", 5, moved.makeupForDayOfWeek)

        val newYear = makeup.getValue(EpochDays.parse("2027-01-04")!!)
        assertEquals("2027-01-04 补 12-31 的课", "2026-12-31", EpochDays.format(newYear.sourceEpochDay))

        // 两次 Exchange：两天的来源日互为对方
        val a = makeup.getValue(EpochDays.parse("2026-09-20")!!)
        val b = makeup.getValue(EpochDays.parse("2026-10-06")!!)
        assertEquals("Exchange 两端互换", a.sourceEpochDay, b.epochDay)
        assertEquals("Exchange 两端互换", b.sourceEpochDay, a.epochDay)
        assertTrue("补课要记周几", p.makeupDays.all { it.makeupForDayOfWeek in 1..7 })
    }

    /**
     * `Move` 的来源日**不进停课表**。
     *
     * 10-02 那天确实停课，但它已经由「这是 10-17 那次补课的来源日」推出来了；
     * 两处都存的话，导出再导入会凭空多出一天停课。
     */
    @Test
    fun `Move 的来源日不算停课日`() {
        val p = preset()!!

        assertFalse(
            "10-02 是让出课，不是停课",
            p.noClassDays.any { EpochDays.format(it.epochDay) == "2026-10-02" }
        )
    }

    /**
     * 当前学年有那一期就显示那一期，没有就给**最新**的一期。
     *
     * 后者不是兜底凑数：内置文件更新得比用户换学年慢是常态，
     * 「官方现在是哪一期」仍然值得摆出来。
     */
    @Test
    fun `优先显示当前学年否则给最新的一期`() {
        assertEquals("2025-2026", preset(twoYears, prefer = "2025-2026")!!.yearSpan)
        assertEquals("2026-2027", preset(twoYears, prefer = "2099-2100")!!.yearSpan)
        assertEquals("2026-2027", preset(twoYears, prefer = null)!!.yearSpan)
    }

    /**
     * 配置里一个学年学期都折不出来时返回 null。
     *
     * 注意这与"内容是坏的"**不是同一件事**：坏内容在 `parse` 就抛了，
     * 由更上层（`CalendarUpdateService`）决定回落或报失败 ——
     * `presetFrom` 只接收已经解析好的 bundle，不负责读、更不负责判格式。
     * 这个分工正是"读哪一份由调用方决定"的一部分。
     */
    @Test
    fun `配置里没有可用学期时返回 null`() {
        val empty = """
            {
              "lastUpdated": 20260917,
              "termConfigs": [],
              "classTerms": [], "examTerms": [], "tweaks": []
            }
        """.trimIndent()

        assertNull(preset(text = empty))
    }

    /** 坏内容在解析层就抛，不会走到折算这里。 */
    @Test(expected = ZjuException::class)
    fun `内容根本解析不了时在 parse 层就抛`() {
        preset(text = "不是 JSON")
    }
}