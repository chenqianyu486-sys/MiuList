package com.example.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校历判定。纯 JUnit。
 *
 * 这些日期都挑过：2026-09-07 是周一，+5 是周六，+6 是周日。
 * 用真实日历而不是造一个假的 dayOfWeek，才能同时验到 `EpochDays` 那层换算。
 */
class AcademicCalendarTest {

    private val firstMonday = EpochDays.parse("2026-09-07")!!

    private fun semester(totalWeeks: Int = 20) = Semester(
        name = "2026-2027学年 秋冬学期",
        yearSpan = "2026-2027",
        term = "秋冬",
        startEpochDay = EpochDays.parse("2026-09-09")!!,   // 周三开学
        firstMondayEpochDay = firstMonday,
        endEpochDay = firstMonday + (totalWeeks - 1) * 7L + 6,
        totalWeeks = totalWeeks,
        periodsPerDay = 13
    )

    @Test
    fun `week numbering counts from the stored first monday, not from the start date`() {
        val cal = AcademicCalendar(semester())
        assertEquals(1, cal.weekOf(firstMonday))
        // 周三开学，但它和当周周日同属第 1 周
        assertEquals(1, cal.weekOf(firstMonday + 2))
        assertEquals(1, cal.weekOf(firstMonday + 6))
        assertEquals(2, cal.weekOf(firstMonday + 7))
        assertEquals(20, cal.weekOf(firstMonday + 19 * 7))
    }

    @Test
    fun `days outside the semester report week zero`() {
        val cal = AcademicCalendar(semester())
        assertEquals(0, cal.weekOf(firstMonday - 1))
        assertEquals(0, cal.weekOf(firstMonday - 7))
        assertEquals(0, cal.weekOf(firstMonday + 20 * 7))
    }

    @Test
    fun `without a semester every weekday is a school day and weekends are not`() {
        val cal = AcademicCalendar.EMPTY
        assertFalse(cal.hasSemester)
        assertEquals(25, cal.totalWeeks)          // 与改造前 coerceIn(1, 25) 同界
        assertEquals(0, cal.weekOf(firstMonday))  // 0 = 「无周次概念」

        assertTrue(cal.isSchoolDay(firstMonday))       // 周一
        assertTrue(cal.isSchoolDay(firstMonday + 4))   // 周五
        assertFalse("周六不上课是常识，与有没有学期无关", cal.isSchoolDay(firstMonday + 5))
        assertFalse(cal.isSchoolDay(firstMonday + 6))
    }

    @Test
    fun `no class days win over everything including makeup`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(
            semester = semester(),
            noClassDays = setOf(saturday),
            makeupDays = mapOf(saturday to MakeupShift(1))
        )
        // 既标了补课又标了停课：停课优先，否则「临时取消补课」就表达不出来
        assertFalse(cal.isSchoolDay(saturday))
        assertTrue(cal.isNoClass(saturday))
        assertTrue(cal.isMakeup(saturday))
    }

    @Test
    fun `a makeup day makes a weekend a school day`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(1)))
        assertTrue("补课日即使是周六也要上课", cal.isSchoolDay(saturday))
        assertEquals(1, cal.makeupFor(saturday))
        assertNull(cal.makeupFor(saturday + 1))
    }

    @Test
    fun `effective day of week returns the makeup target, not the real weekday`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(1)))
        // 10 月 10 日（周六）补周一的课 —— 课表上就该出周一的课
        assertEquals(1, cal.effectiveDayOfWeek(saturday))
        assertEquals(6, EpochDays.dayOfWeek1To7(saturday))
        // 没标补课的日子仍是真实星期
        assertEquals(7, cal.effectiveDayOfWeek(firstMonday + 6))
    }

    @Test
    fun `weekend detection follows the real calendar`() {
        assertEquals(1, EpochDays.dayOfWeek1To7(firstMonday))
        assertEquals(5, EpochDays.dayOfWeek1To7(firstMonday + 4))
        assertEquals(6, EpochDays.dayOfWeek1To7(firstMonday + 5))
        assertEquals(7, EpochDays.dayOfWeek1To7(firstMonday + 6))
        assertFalse(EpochDays.isWeekend(firstMonday + 4))
        assertTrue(EpochDays.isWeekend(firstMonday + 5))
        assertTrue(EpochDays.isWeekend(firstMonday + 6))
    }

    @Test
    fun `mondayOf lands on the monday of the containing week`() {
        assertEquals(firstMonday, EpochDays.mondayOf(firstMonday))
        assertEquals(firstMonday, EpochDays.mondayOf(firstMonday + 3))
        assertEquals(firstMonday, EpochDays.mondayOf(firstMonday + 6))
        assertEquals(firstMonday + 7, EpochDays.mondayOf(firstMonday + 7))
    }

    @Test
    fun `dateRangeOfWeek is inclusive and bounded by the semester`() {
        val s = semester()
        assertEquals(firstMonday to (firstMonday + 6), s.dateRangeOfWeek(1))
        assertEquals((firstMonday + 7) to (firstMonday + 13), s.dateRangeOfWeek(2))
        assertNull(s.dateRangeOfWeek(0))
        assertNull(s.dateRangeOfWeek(21))
    }

    @Test
    fun `a semester shorter than the fallback still bounds the school days`() {
        val cal = AcademicCalendar(semester(totalWeeks = 16))
        assertTrue(cal.isSchoolDay(firstMonday + 15 * 7))
        assertFalse("第 17 周已在学期外", cal.isSchoolDay(firstMonday + 16 * 7))
        assertEquals(16, cal.totalWeeks)
    }

    @Test
    fun `epoch day parsing rejects garbage instead of guessing`() {
        assertNull(EpochDays.parse(""))
        assertNull(EpochDays.parse("2026/09/07"))
        assertNull(EpochDays.parse("9月7日"))
        assertEquals("2026-09-07", EpochDays.format(firstMonday))
    }

    @Test
    fun `expandRange guards against an accidentally huge span`() {
        assertTrue(EpochDays.expandRange(10, 9).isEmpty())
        assertEquals(listOf(10L, 11L, 12L), EpochDays.expandRange(10, 12))
        // 手滑写成 1970–9999 时不能把内存吃光
        assertTrue(EpochDays.expandRange(0, 999999).size <= 400)
    }

    @Test
    fun `formatCn drops the year and keeps month and day`() {
        assertEquals("9月7日", EpochDays.formatCn(firstMonday))
        assertEquals("10月1日", EpochDays.formatCn(EpochDays.parse("2026-10-01")!!))
    }

    // ------------------------------------------------- 补课归属哪一周

    /**
     * 补课归属**来源日**那一周，不是补课当天那一周。
     *
     * 2026 国庆的真实一条：10-02（周五）的课挪到 10-17（周六）补。
     * 本用例的学期从 09-07 起算，于是 10-02 是第 4 周、10-17 是第 6 周。
     * 按 6 去筛开课周次，这场补上来的周五课会拿第 6 周去比 ——
     * 单双周课要么凭空多出一次，要么一次都不出，而界面上看不出哪里不对。
     */
    @Test
    fun `a makeup day counts towards the week of the day it substitutes for`() {
        val from = EpochDays.parse("2026-10-02")!!   // 周五
        val to = EpochDays.parse("2026-10-17")!!     // 周六
        val cal = AcademicCalendar(
            semester = semester(),
            makeupDays = mapOf(to to MakeupShift(5, from))
        )

        assertEquals("来源日那周", 4, cal.teachingWeekOf(to))
        assertEquals("补课当天那周，只是不用于排课", 6, cal.weekOf(to))
        assertEquals(5, cal.effectiveDayOfWeek(to))
    }

    /**
     * 不知道来源日（v1 旧文档、弹层手工填的行）时退回按补课当天算。
     *
     * 这条是兼容路径：宁可沿用旧行为，也不能因为新字段缺失就让课全都不出现。
     */
    @Test
    fun `an unknown source day falls back to the makeup day itself`() {
        val to = EpochDays.parse("2026-10-17")!!
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(5)))

        assertEquals(0L, cal.shiftFor(to)!!.sourceEpochDay)
        assertEquals(cal.weekOf(to), cal.teachingWeekOf(to))
    }
}
