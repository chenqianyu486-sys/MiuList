package com.example.data.calendar

import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「应用官方校历」的差异计算。
 *
 * ## 这里最要紧的一条
 *
 * 应用是**全量替换**：用户自己敲的停课 / 补课会被官方那份换掉。
 * 所以差异计算错一个方向，用户就会**无声地**丢掉自己加的东西 ——
 * 而且是在他点了"确认应用"之后。所以新增、移除、修改三态都要单独钉死。
 *
 * 纯函数，无 Android 依赖。
 */
class CalendarDiffTest {

    private fun sem(
        name: String = "2026-2027学年 秋冬 学期",
        start: Long = 20708L,
        monday: Long = 20708L,
        end: Long = 20830L,
        weeks: Int = 16
    ) = CalendarSnapshot.Semester(name, start, monday, end, weeks)

    private fun snap(
        semester: CalendarSnapshot.Semester? = sem(),
        noClass: Set<Long> = emptySet(),
        makeup: Map<Long, CalendarSnapshot.Makeup> = emptyMap()
    ) = CalendarSnapshot(semester, noClass, makeup)

    private fun mk(day: Long, dow: Int, source: Long = 0L) =
        CalendarSnapshot.Makeup(day, dow, source)

    // ---------------- 完全一致 ----------------

    /**
     * 两边一模一样时必须是空的。
     *
     * 不空的话界面上会弹一个"确认应用"，而按下去什么都不会变 ——
     * 用户会以为是应用失败了。
     */
    @Test
    fun `完全一致时没有差异`() {
        val a = snap(noClass = setOf(20722L), makeup = mapOf(20738L to mk(20738L, 5, 20723L)))
        val diff = CalendarDiffCalculator.compute(a, a)

        assertTrue("不该有差异，实际：$diff", diff.isEmpty)
    }

    // ---------------- 首次应用 ----------------

    /**
     * 本机压根没配过学期（最常见的首次应用）。
     *
     * 此时**不该**把学期五项全列成"新增"刷屏 —— 一条"学期：未设置 → 2026-2027 秋冬 · 16 周"
     * 就够了，而且停课/补课全是纯新增，没有"移除"。
     */
    @Test
    fun `本机没配过学期时算首次应用`() {
        val current = snap(semester = null, noClass = emptySet(), makeup = emptyMap())
        val incoming = snap(noClass = setOf(20722L), makeup = mapOf(20738L to mk(20738L, 5, 20723L)))

        val diff = CalendarDiffCalculator.compute(current, incoming)

        assertEquals("学期应该只报一条总览", 1, diff.semesterChanges.size)
        assertEquals("学期", diff.semesterChanges[0].label)
        assertEquals("未设置", diff.semesterChanges[0].before)
        assertTrue(diff.noClassAdded.contains(20722L))
        assertTrue("首次不该有移除", diff.noClassRemoved.isEmpty())
        assertTrue("首次不该有移除", diff.makeupRemoved.isEmpty())
        assertEquals(1, diff.makeupAdded.size)
        assertFalse(diff.isEmpty)
    }

    // ---------------- 学期 ----------------

    @Test
    fun `学期起止变化逐项列出`() {
        val current = snap(semester = sem(start = 20708L, end = 20830L, weeks = 16))
        val incoming = snap(semester = sem(start = 20715L, end = 20837L, weeks = 17))

        val labels = CalendarDiffCalculator.compute(current, incoming).semesterChanges.map { it.label }

        assertEquals(
            listOf("学期开始日", "学期结束日", "总周数"),
            labels
        )
    }

    /** 改了名但日期没变，也要报出来 —— 学期名会写进导出文档。 */
    @Test
    fun `只有学期名变了也要报`() {
        val current = snap(semester = sem(name = "2026-2027学年 秋冬 学期"))
        val incoming = snap(semester = sem(name = "2026-2027学年 秋冬 学期 （官方）"))

        val changes = CalendarDiffCalculator.compute(current, incoming).semesterChanges

        assertEquals(1, changes.size)
        assertEquals("学期名称", changes[0].label)
    }

    // ---------------- 停课 ----------------

    @Test
    fun `停课的新增与移除要分开列`() {
        val current = snap(noClass = setOf(20722L, 20723L))
        val incoming = snap(noClass = setOf(20722L, 20724L))

        val diff = CalendarDiffCalculator.compute(current, incoming)

        assertEquals(listOf(20724L), diff.noClassAdded)
        assertEquals(
            "用户自己加的停课被移除了，必须报出来",
            listOf(20723L),
            diff.noClassRemoved
        )
    }

    // ---------------- 补课 ----------------

    @Test
    fun `补课的新增与移除要分开列`() {
        val current = snap(makeup = mapOf(20738L to mk(20738L, 5, 20723L), 20739L to mk(20739L, 4)))
        val incoming = snap(makeup = mapOf(20738L to mk(20738L, 5, 20723L), 20740L to mk(20740L, 2)))

        val diff = CalendarDiffCalculator.compute(current, incoming)

        assertEquals(listOf(20740L), diff.makeupAdded.map { it.epochDay })
        assertEquals(listOf(20739L), diff.makeupRemoved.map { it.epochDay })
        assertTrue("内容没变的补课不该进 changed", diff.makeupChanged.isEmpty())
    }

    /** 同一天补周几变了 —— 是"修改"不是"移除+新增"，界面上该能一眼看出。 */
    @Test
    fun `补课补周几变了算修改`() {
        val current = snap(makeup = mapOf(20738L to mk(20738L, 5, 20723L)))
        val incoming = snap(makeup = mapOf(20738L to mk(20738L, 3, 20723L)))

        val diff = CalendarDiffCalculator.compute(current, incoming)

        assertEquals(1, diff.makeupChanged.size)
        assertEquals(5, diff.makeupChanged[0].before.makeupForDayOfWeek)
        assertEquals(3, diff.makeupChanged[0].after.makeupForDayOfWeek)
        assertTrue("不是新增", diff.makeupAdded.isEmpty())
        assertTrue("不是移除", diff.makeupRemoved.isEmpty())
    }

    /**
     * 来源日变了同样算修改。
     *
     * 来源日决定「这段课算第几周」，改错了会让单双周课集体判错 ——
     * 所以它必须出现在差异里。
     */
    @Test
    fun `补课来源日变了算修改`() {
        val current = snap(makeup = mapOf(20738L to mk(20738L, 5, 20723L)))
        val incoming = snap(makeup = mapOf(20738L to mk(20738L, 5, 20730L)))

        val diff = CalendarDiffCalculator.compute(current, incoming)

        assertEquals(1, diff.makeupChanged.size)
        assertEquals(20723L, diff.makeupChanged[0].before.sourceEpochDay)
        assertEquals(20730L, diff.makeupChanged[0].after.sourceEpochDay)
    }

    /**
     * 手填的补课（`sourceEpochDay = 0`，来源日未知）也要能正常比对。
     *
     * 校历弹层里手工加的行就是这样，它们不是坏数据，
     * 只是退回「按补课当天算周次」的兼容路径。
     */
    @Test
    fun `来源日未知的手填补课不误报`() {
        val handFilled = snap(makeup = mapOf(20738L to mk(20738L, 5, 0L)))

        val same = CalendarDiffCalculator.compute(handFilled, handFilled)
        assertTrue("一样的两份不该算有差异", same.isEmpty)

        val changedDow = CalendarDiffCalculator.compute(handFilled, snap(makeup = mapOf(20738L to mk(20738L, 2, 0L))))
        assertEquals(1, changedDow.makeupChanged.size)
    }

    // ---------------- 从实体构造 ----------------

    /**
     * Room 实体 → 快照的转换要保号。
     *
     * epochDay 是主键，转错了会把"同一天"算成"两天"，于是一条补课
     * 同时出现在新增和移除里。
     */
    @Test
    fun `从 Room 实体构造快照`() {
        val snapshot = CalendarSnapshot.of(
            semester = SemesterEntity(
                name = "2026-2027学年 秋冬 学期",
                yearSpan = "2026-2027",
                term = "秋冬",
                startEpochDay = 20708L,
                firstMondayEpochDay = 20708L,
                endEpochDay = 20830L,
                totalWeeks = 16
            ),
            noClassDays = listOf(NoClassDayEntity(20722L, "国庆"), NoClassDayEntity(20723L, "")),
            makeupDays = listOf(MakeupDayEntity(20738L, 5, "国庆调休", 20723L))
        )

        assertEquals(setOf(20722L, 20723L), snapshot.noClassDays)
        assertEquals(1, snapshot.makeupDays.size)
        assertEquals(5, snapshot.makeupDays.getValue(20738L).makeupForDayOfWeek)
        assertEquals(20723L, snapshot.makeupDays.getValue(20738L).sourceEpochDay)
        assertEquals(16, snapshot.semester!!.totalWeeks)

        // 自己跟自己比必须干净，否则界面上会一直弹"确认应用"
        assertTrue(CalendarDiffCalculator.compute(snapshot, snapshot).isEmpty)
    }
}
