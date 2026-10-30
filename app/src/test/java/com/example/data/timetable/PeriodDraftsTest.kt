package com.example.data.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作息草稿层：校历弹层里「每天 N 节 · 每节起止时刻」的可编辑数据。
 *
 * 纯 JUnit。测的重点是**改节数不能吃掉用户敲过的时间**、以及**保存时拦得住非法时刻**——
 * 这两条算错时界面上完全看不出异常：时间会静默存错，或改一次节数就清空一屏输入。
 */
class PeriodDraftsTest {

    // ------------------------------------------------------------ 时间解析

    @Test
    fun `parses the usual HH mm form`() {
        assertEquals(8 * 60, parseMinuteOfDay("08:00"))
        assertEquals(8 * 60 + 45, parseMinuteOfDay("08:45"))
        assertEquals(0, parseMinuteOfDay("00:00"))
        assertEquals(23 * 60 + 59, parseMinuteOfDay("23:59"))
    }

    @Test
    fun `tolerates the intermediate states a hand-typed value passes through`() {
        // 手输 08:45 会经过 "8" → "8:" → "8:4" → "8:45"，
        // 这些都必须原样留在输入框里由用户补完，不能当场判非法把字符吃掉
        assertEquals(8 * 60, parseMinuteOfDay("8:00"))
        assertEquals(8 * 60, parseMinuteOfDay("0800"))
        assertEquals(8 * 60 + 45, parseMinuteOfDay(" 08:45 "))
    }

    @Test
    fun `a bare hour is not read as that hour on the hour`() {
        // 放行 "8" 会把它当成 08:00，而用户八成是想接着补成 08:xx ——
        // 猜错一次就等于把这一节静默存成了早八
        assertNull(parseMinuteOfDay("8"))
        assertNull(parseMinuteOfDay("080"))
        assertNull(parseMinuteOfDay("845"))
    }

    @Test
    fun `rejects what cannot be a wall clock time`() {
        assertNull(parseMinuteOfDay(""))
        assertNull(parseMinuteOfDay("24:00"))
        assertNull(parseMinuteOfDay("08:60"))
        assertNull(parseMinuteOfDay("abc"))
        assertNull(parseMinuteOfDay("8:0:0"))
        assertNull(parseMinuteOfDay("-8:00"))
        assertNull(parseMinuteOfDay("080"))
    }

    @Test
    fun `rejects overflowing digits instead of throwing`() {
        // 粘贴进来的超长数字会溢出 Int，转换必须失败即返 null 而不是抛异常：
        // 这段解析在重组期就会被求值（isValid → startMinute），
        // 抛出来等于用户还没点保存，界面先崩了
        assertNull(parseMinuteOfDay("9999999999:00"))
        assertNull(parseMinuteOfDay("10:9999999999"))
        assertFalse(PeriodDraft(1, "9999999999", "11:00", "上午").isValid)
    }

    // ------------------------------------------------------------ 草稿合法性

    @Test
    fun `a row is only valid when both ends parse and start precedes end`() {
        val ok = PeriodDraft(1, "08:00", "08:45", "上午")
        assertTrue(ok.isValid)
        assertEquals(PeriodSlot(1, 8 * 60, 8 * 60 + 45, "上午"), ok.toPeriodSlot())

        // 结束早于开始：时间反了，存进去会让课程时长算成负数
        assertFalse(PeriodDraft(1, "08:45", "08:00", "上午").isValid)
        // 起止相同：零长度的课
        assertFalse(PeriodDraft(1, "08:00", "08:00", "上午").isValid)
        // 有一端没解析出来
        assertFalse(PeriodDraft(1, "", "08:45", "上午").isValid)
        assertFalse(PeriodDraft(1, "08:00", "8", "上午").isValid)
    }

    // ------------------------------------------------------------ 改节数

    @Test
    fun `growing the count appends after the last row and keeps what the user typed`() {
        val edited = listOf(
            PeriodDraft(1, "07:30", "08:15", "上午"),
            PeriodDraft(2, "08:25", "09:10", "上午")
        )
        val grown = resizePeriodDrafts(edited, 4)

        assertEquals(4, grown.size)
        // 前两行逐字段保留 —— 用户敲的时间不能在改节数时丢失
        assertEquals(edited[0], grown[0])
        assertEquals(edited[1], grown[1])
        // 新行从上一节结束后 10 分钟顺延，45 分钟一节
        assertEquals(PeriodDraft(3, "09:20", "10:05", "上午"), grown[2])
        assertEquals(PeriodDraft(4, "10:15", "11:00", "上午"), grown[3])
        // 列号连续，且全部合法（否则用户得自己补完才能保存）
        assertTrue(grown.all { it.isValid })
        assertEquals(listOf(1, 2, 3, 4), grown.map { it.period })
    }

    @Test
    fun `shrinking the count truncates the tail only`() {
        val full = PeriodTable.ZJU_PERIODS.toDrafts()
        val shrunk = resizePeriodDrafts(full, 6)

        assertEquals(6, shrunk.size)
        assertEquals(full.take(6), shrunk)
    }

    @Test
    fun `column numbers stay contiguous after repeated resizes`() {
        // 反复改节数时列号必须一直是 1..n，不能出现 1,2,5 之类
        var rows = PeriodTable.ZJU_PERIODS.toDrafts()
        listOf(3, 12, 7, 20, 1).forEach { n ->
            rows = resizePeriodDrafts(rows, n)
            assertEquals((1..rows.size).toList(), rows.map { it.period })
        }
    }

    @Test
    fun `count is clamped instead of producing unsavable rows`() {
        // 排 20 节又从早八顺延会算到 26 点多，产出 "26:20" 这种永远保存不了的时刻
        val rows = resizePeriodDrafts(PeriodTable.ZJU_PERIODS.toDrafts(), 999)
        assertEquals(MAX_PERIODS_PER_DAY, rows.size)
        assertTrue("所有行都得能保存", rows.all { it.isValid })

        // 0 节同样不是合法作息，至少留 1 节
        assertEquals(1, resizePeriodDrafts(PeriodTable.ZJU_PERIODS.toDrafts(), 0).size)
        assertEquals(1, resizePeriodDrafts(emptyList(), -5).size)
    }

    @Test
    fun `an empty starting table falls back to a sane first row`() {
        val rows = resizePeriodDrafts(emptyList(), 2)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.isValid })
        // 没有上一节可顺延时从 08:00 起，而不是从 00:00 或 1970 之类的垃圾值起
        assertEquals("08:00", rows[0].startText)
    }

    @Test
    fun `appending never produces a row that starts before the previous one ended`() {
        // 浙大作息 13 节排到 21:15 收工，再往 20 节加 7 行就撞到午夜了。
        // 把起点夹回 24:00 - 45 会让后面某一行「开始于 23:15」却夹在
        // 上一行「结束于 22:55」之前 —— 每行单独看都合法，整张表却是倒着排的。
        // 这种表存进去，课表上读出来的时间是乱的，而界面上看不出任何异常。
        val rows = resizePeriodDrafts(PeriodTable.ZJU_PERIODS.toDrafts(), 20)
        assertEquals(20, rows.size)
        assertTrue(rows.all { it.isValid })
        rows.zipWithNext { prev, next ->
            assertTrue(
                "第${prev.period}节 ${prev.endText} 之后不该是第${next.period}节 ${next.startText}",
                (next.startMinute ?: 0) >= (prev.endMinute ?: 0)
            )
        }
    }

    @Test
    fun `appended rows stay inside a single day`() {
        val rows = resizePeriodDrafts(PeriodTable.ZJU_PERIODS.toDrafts(), 20)
        rows.forEach {
            // HH:mm 的小时位只有 00–23，出现 24:00 就是一行永远存不下去的时间
            assertTrue("第${it.period}节 ${it.startText} 越界", it.startMinute!! in 0..1439)
            assertTrue("第${it.period}节 ${it.endText} 越界", it.endMinute!! in 0..1439)
        }
    }

    @Test
    fun `every appended row is individually savable`() {
        // 末节若被截成 start == end 就是零长度的课：整表看不出问题，
        // 只有逐行 toPeriodSlot() 返回 null 才会暴露，而那正是保存时被拦下的那一刻
        val rows = resizePeriodDrafts(PeriodTable.ZJU_PERIODS.toDrafts(), 20)
        rows.forEach {
            assertNotNull("第${it.period}节 ${it.startText}-${it.endText} 存不下去", it.toPeriodSlot())
        }
    }

    // ------------------------------------------------------------ 往返

    @Test
    fun `a table round trips through drafts without drifting`() {
        val zju = PeriodTable.ZJU
        val restored = PeriodTable.from(zju.toDrafts().mapNotNull { it.toPeriodSlot() })

        assertEquals(zju.periodsPerDay, restored.periodsPerDay)
        assertEquals(zju.periods, restored.periods)
    }

    @Test
    fun `section is inferred from the start time for newly appended rows`() {
        assertEquals("上午", sectionOfMinute(8 * 60))
        assertEquals("上午", sectionOfMinute(11 * 60 + 59))
        assertEquals("下午", sectionOfMinute(12 * 60))
        assertEquals("下午", sectionOfMinute(17 * 60 + 59))
        assertEquals("晚上", sectionOfMinute(18 * 60))
    }
}