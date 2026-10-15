package com.example.data.calendar

import com.example.data.codec.CalendarBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TweakBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * zju-ical 校历配置 → 本项目内部模型的映射。
 *
 * 用的是上游 `configs/config.json` 的**真实片段**，不是编出来的：
 * `2024-2025:4`（春夏学期）Begin=20250414、End=20250608、FirstWeekNo=1。
 */
class ZjuCalendarAdapterTest {

    private fun epoch(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test
    fun `a Monday first week no shift means firstMonday is the first day of term`() {
        // 2025-04-14 是周一，FirstWeekNo = 1 → 第一周周一就是开学那天
        val begin = epoch(2025, 4, 14)
        assertEquals(begin, ZjuCalendarAdapter.firstMondayOf(begin, 1))
    }

    @Test
    fun `firstMonday rewinds to the Monday of that week when term starts mid week`() {
        // 2025-04-17 是周四；上游公式是 Begin − (周几 − 1) − 7×(FirstWeekNo − 1)
        val begin = epoch(2025, 4, 17)
        assertEquals(epoch(2025, 4, 14), ZjuCalendarAdapter.firstMondayOf(begin, 1))
    }

    @Test
    fun `firstWeekNo two rewinds a whole extra week`() {
        val begin = epoch(2025, 4, 14)
        assertEquals(epoch(2025, 4, 7), ZjuCalendarAdapter.firstMondayOf(begin, 2))
    }

    @Test
    fun `the real spring term config yields the expected total weeks`() {
        // 上游 config.json 里 2024-2025:4 的真实取值
        val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(20250414)
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(20250608)
        assertEquals(8, ZjuCalendarAdapter.totalWeeks(begin!!, end!!, 1))
    }

    @Test
    fun `yyyymmdd parses and formats back`() {
        val day = ZjuCalendarAdapter.yyyymmddToEpochDay(20250608)
        assertEquals(epoch(2025, 6, 8), day)
        assertEquals(20250608, ZjuCalendarAdapter.epochDayToYyyymmdd(day!!))
    }

    @Test
    fun `a malformed date is rejected instead of silently becoming some other day`() {
        assertEquals(null, ZjuCalendarAdapter.yyyymmddToEpochDay(null))
        assertEquals(null, ZjuCalendarAdapter.yyyymmddToEpochDay(202506))       // 位数不对
        assertEquals(null, ZjuCalendarAdapter.yyyymmddToEpochDay(20251332))      // 13 月
    }

    @Test
    fun `term numbers map to the same labels the upstream project uses`() {
        assertEquals("秋学期", ZjuCalendarAdapter.termLabel(0))
        assertEquals("冬学期", ZjuCalendarAdapter.termLabel(1))
        assertEquals("春学期", ZjuCalendarAdapter.termLabel(4))
        assertEquals("夏学期", ZjuCalendarAdapter.termLabel(5))
        assertEquals("", ZjuCalendarAdapter.termLabel(99))
        assertEquals("", ZjuCalendarAdapter.termLabel(null))
    }

    @Test
    fun `a term config built from the app side reproduces the same week numbering`() {
        // 导出时我们把 Begin 写成第一周周一；导回来必须还原出同一个 totalWeeks。
        val begin = epoch(2025, 4, 14)
        val end = epoch(2025, 6, 8)
        val original = ZjuCalendarAdapter.totalWeeks(begin, end, 1)

        val exported = ZjuCalendarAdapter.epochDayToYyyymmdd(begin)
        val reimportedBegin = ZjuCalendarAdapter.yyyymmddToEpochDay(exported)!!
        val reimportedEnd = ZjuCalendarAdapter.yyyymmddToEpochDay(20250608)!!
        assertEquals(original, ZjuCalendarAdapter.totalWeeks(reimportedBegin, reimportedEnd, 1))
    }

    @Test
    fun `tweaks and the term window live on the same date scale`() {
        // 停课区间的两端和学期起止**都是 YYYYMMDD 整数**，拿 epochDay 去比会得到
        // 恒真的结论（或恒假），而「越界忽略」就再也不会触发 —— 静默地放国庆假进来。
        val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(20250414)!!
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(20250608)!!

        val springBreak = TweakBundle(TweakKind.CLEAR, "劳动节", 20250502, 20250505)
        val breakTo = ZjuCalendarAdapter.yyyymmddToEpochDay(springBreak.to!!)!!
        assertTrue(
            "学期内的停课区间换算后应当落在学期内",
            breakTo >= begin && breakTo <= end
        )

        val nationalDay = TweakBundle(TweakKind.CLEAR, "国庆", 20251001, 20251007)
        assertTrue(
            "学期外的停课区间换算后应当落在学期之后，由展开阶段忽略",
            ZjuCalendarAdapter.yyyymmddToEpochDay(nationalDay.to!!)!! > end
        )
    }

    @Test
    fun `calendar bundle keeps the term it was built with`() {
        val cal = CalendarBundle(termConfigs = listOf(TermConfigBundle("2024-2025", 4, 20250414, 20250608, 1)))
        assertEquals("2024-2025:4", cal.termConfigs.first().key)
    }
}