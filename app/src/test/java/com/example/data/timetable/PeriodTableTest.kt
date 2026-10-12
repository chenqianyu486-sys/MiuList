package com.example.data.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作息表。纯 JUnit。
 *
 * 这次改造把「全校作息」从 `PeriodUtils.ALL_PERIODS` 这个全局常量搬进了数据，
 * 所以这里的重点不是「值对不对」，而是**搬完之后行为有没有变**：
 * 大节分隔线、越界处理、默认值。
 */
class PeriodTableTest {

    @Test
    fun `the built-in default is still the 13 period legacy grid`() {
        val t = PeriodTable.DEFAULT
        assertEquals(13, t.periodsPerDay)
        assertEquals(13, t.periods.size)
        // 改造前 PeriodUtils.ALL_PERIODS 的首尾，逐值相同
        assertEquals("08:00" to "08:45", t.timeRange(1, 1))
        assertEquals("22:05" to "22:50", t.timeRange(13, 13))
        assertEquals("08:00", t.slotOf(1)?.startTime)
        assertEquals("09:40", t.slotOf(2)?.endTime)
    }

    @Test
    fun `section boundaries are derived, not hardcoded to the old 5 and 10`() {
        val t = PeriodTable.DEFAULT
        // 改造前写死的是 `period == 5 || period == 10`。推导结果必须逐一致，
        // 否则课表栅格上的大节分隔线会整体挪位。
        val derived = (1..13).filter { t.isSectionEnd(it) }
        assertEquals(listOf(5, 10), derived)
    }

    @Test
    fun `section boundaries follow the data when the data changes`() {
        // 换一套作息（上午 1-4、下午 5-8、晚上 9-10），分隔线要跟着走
        val custom = PeriodTable.from(
            listOf(
                PeriodSlot(1, 8 * 60, 8 * 60 + 45, "上午"),
                PeriodSlot(2, 9 * 60, 9 * 60 + 45, "上午"),
                PeriodSlot(3, 10 * 60, 10 * 60 + 45, "上午"),
                PeriodSlot(4, 11 * 60, 11 * 60 + 45, "上午"),
                PeriodSlot(5, 14 * 60, 14 * 60 + 45, "下午"),
                PeriodSlot(6, 15 * 60, 15 * 60 + 45, "下午"),
                PeriodSlot(7, 16 * 60, 16 * 60 + 45, "下午"),
                PeriodSlot(8, 17 * 60, 17 * 60 + 45, "下午"),
                PeriodSlot(9, 19 * 60, 19 * 60 + 45, "晚上"),
                PeriodSlot(10, 20 * 60, 20 * 60 + 45, "晚上")
            )
        )
        assertEquals(10, custom.periodsPerDay)
        assertEquals(listOf(4, 8), (1..10).filter { custom.isSectionEnd(it) })
    }

    @Test
    fun `a single-section day has no separator at all`() {
        val t = PeriodTable.from(
            listOf(
                PeriodSlot(1, 480, 525, "上午"),
                PeriodSlot(2, 535, 580, "上午"),
                PeriodSlot(3, 600, 645, "上午")
            )
        )
        assertEquals("全天同段就不该出现任何分隔线", emptyList<Int>(), (1..3).filter { t.isSectionEnd(it) })
    }

    @Test
    fun `the last period is never a section end`() {
        // 末节后面没有下一节，画分隔线会贴着栅格底边，看起来像多出来一条
        assertFalse(PeriodTable.DEFAULT.isSectionEnd(13))
        assertFalse(PeriodTable.DEFAULT.isSectionEnd(99))
        assertFalse(PeriodTable.DEFAULT.isSectionEnd(0))
    }

    @Test
    fun `out-of-range lookups return null instead of a plausible wrong time`() {
        val t = PeriodTable.DEFAULT
        // 改造前 getPeriodTimeRange 越界时兜底到 "08:00" / "22:50"，
        // 正好是「改了作息但调用点漏改」的藏身处
        assertNull(t.timeRange(0, 1))
        assertNull(t.timeRange(1, 14))
        assertNull(t.timeRange(99, 99))
        assertNull(t.slotOf(14))
        assertNull(t.startMinuteOf(0))
        assertNull(t.endMinuteOf(14))
        assertEquals("", t.sectionOf(14))
    }

    @Test
    fun `timeRangeText shows a placeholder rather than a fabricated clock time`() {
        assertEquals("08:00 ~ 09:40", PeriodTable.DEFAULT.timeRangeText(1, 2))
        assertEquals("时间待定", PeriodTable.DEFAULT.timeRangeText(1, 99))
        assertEquals("时间待定", PeriodTable.DEFAULT.timeRangeText(0, 1))
    }

    @Test
    fun `from sorts unsorted input and derives the count from it`() {
        val t = PeriodTable.from(
            listOf(
                PeriodSlot(3, 600, 645, "上午"),
                PeriodSlot(1, 480, 525, "上午"),
                PeriodSlot(2, 535, 580, "上午")
            )
        )
        assertEquals(listOf(1, 2, 3), t.periods.map { it.period })
        assertEquals(3, t.periodsPerDay)
        assertEquals("08:00", t.slotOf(1)?.startTime)
    }

    @Test
    fun `an empty table is reported as empty instead of pretending to have periods`() {
        val t = PeriodTable.from(emptyList())
        assertTrue(t.isEmpty)
        assertEquals(0, t.periodsPerDay)
        assertNull(t.timeRange(1, 1))
        assertFalse(t.isSectionEnd(1))
    }

    @Test
    fun `minute formatting pads both fields`() {
        assertEquals("08:00", formatMinute(480))
        assertEquals("09:05", formatMinute(545))
        assertEquals("00:00", formatMinute(0))
        assertEquals("23:59", formatMinute(23 * 60 + 59))
    }

    @Test
    fun `the ZJU timetable is the 15 period grid and keeps the upstream times`() {
        val t = PeriodTable.ZJU
        assertEquals(15, t.periodsPerDay)
        assertEquals(15, t.periods.size)
        assertEquals((1..15).toList(), t.periods.map { it.period })
        // 逐值对齐 zju-ical 的 NewClassPeriod。浙大是压缩作息：第 5 节 12:25 就结课、
        // 第 6 节 13:25 才上课（中间那 1 小时是午休），跟上面那份通用 13 节表不一样。
        // 这里错一个数，整个学期的上课时间就全错，而且错得看起来还挺合理。
        assertEquals("08:00" to "08:45", t.timeRange(1, 1))
        assertEquals("11:40" to "12:25", t.timeRange(5, 5))
        assertEquals("13:25" to "14:10", t.timeRange(6, 6))
        assertEquals("22:10" to "22:55", t.timeRange(15, 15))
    }

    @Test
    fun `the ZJU timetable splits into the same five ten fifteen sections as the default`() {
        // 分段是本项目自己的（上游没有这个概念），但要跟 DEFAULT 的切法保持一致：
        // 否则同一份 UI 代码在两套作息下会画出数量不同的分隔线。
        assertEquals(listOf(5, 10), (1..15).filter { PeriodTable.ZJU.isSectionEnd(it) })
        assertFalse(PeriodTable.ZJU.isSectionEnd(15))
        assertNull(PeriodTable.ZJU.slotOf(16))
        assertNull(PeriodTable.ZJU.timeRange(1, 16))
    }

    @Test
    fun `adding the ZJU grid did not touch the frozen legacy default`() {
        // 两条作息并行存在，任何一边被复用/覆盖都会把另一边的栅格弄变形。
        // DEFAULT_PERIODS 是 MIGRATION_3_4 写给升级用户的种子值，一个字节都不能改。
        assertEquals(13, PeriodTable.DEFAULT.periodsPerDay)
        assertEquals(13, PeriodTable.DEFAULT_PERIODS.size)
        assertNotSame(PeriodTable.DEFAULT_PERIODS, PeriodTable.ZJU_PERIODS)
        // 浙大第 13 节是 20:30，通用表第 13 节是 22:05 —— 两者确实不是同一份数据
        assertEquals("20:30", PeriodTable.ZJU.slotOf(13)?.startTime)
        assertEquals("22:05", PeriodTable.DEFAULT.slotOf(13)?.startTime)
    }

    @Test
    fun `durationMinutesOf sums the real clock span including the gap between classes`() {
        // 总览的「本周 x.x 小时」靠它求和，所以口径必须是**真实经过的时间**，
        // 不是「节数 × 45」—— 两节课之间那 5–10 分钟间隔也是上课要占用的时间。
        val t = PeriodTable.ZJU
        assertEquals(45, t.durationMinutesOf(1, 1))
        // 第 2 节 08:50 上课、09:35 下课，所以 1–2 节跨 95 分钟（50 分钟间隔 + 45 分钟课）
        assertEquals(95, t.durationMinutesOf(1, 2))
        // 第 5 节 11:40 上课、第 6 节 13:25 下课：中间整段午休都算在里面
        assertEquals("13:25 − 11:40 = 150", 150, t.durationMinutesOf(5, 6))
        assertEquals(100, PeriodTable.DEFAULT.durationMinutesOf(1, 2))
    }

    @Test
    fun `durationMinutesOf refuses to fabricate a duration for out-of-range periods`() {
        val t = PeriodTable.ZJU
        // 越界返回 0 而不是兜底成 45：总览会把 0 原样加进去，露出真实的问题，
        // 悄悄给个假值会让这个数字永远错一点，且没人看得出错在哪。
        assertEquals(0, t.durationMinutesOf(0, 1))
        assertEquals(0, t.durationMinutesOf(1, 16))
        assertEquals(0, t.durationMinutesOf(16, 16))
        // 倒序（end 在 start 之前）同样是数据错误，不返回负数把总和拉低
        assertEquals(0, t.durationMinutesOf(6, 5))
    }

    @Test
    fun `the legacy morning-afternoon labels are preserved verbatim`() {
        // 历史数据里第 5 节（14:00）标的是「上午」。看着别扭，但它是改造前
        // ALL_PERIODS 的原值，而迁徙的全部意义就是让老用户升级后看见同一张课表。
        // section 只参与 isSectionEnd 推导，界面上不显示，所以不改。
        assertEquals("上午", PeriodTable.DEFAULT.sectionOf(5))
        assertEquals("下午", PeriodTable.DEFAULT.sectionOf(6))
        assertEquals("晚上", PeriodTable.DEFAULT.sectionOf(11))
    }
}
