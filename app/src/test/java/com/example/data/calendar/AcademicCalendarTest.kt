package com.example.data.calendar

import com.example.data.local.CourseSlotEntity
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

    // ------------------------------------------------- 为什么没课（放假停课 vs 周末）

    /**
     * 周末是**软**理由：它说明「这天默认没课」，不说明「这天一定没课」。
     *
     * 所以周末不是硬闸门 —— 用户自己把课排在周六，那天就该上课。
     * 这条是「周末排的课要显示」与「放假停课要显示」能同时成立的前提。
     */
    @Test
    fun `a weekend is a soft reason and not a hard off day`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester())

        assertEquals(DayOffReason.WEEKEND, cal.dayOffReason(saturday))
        assertEquals(DayOffReason.WEEKEND, cal.dayOffReason(firstMonday + 6))
        assertFalse("周末不是硬闸门", cal.isOffDay(saturday))
    }

    @Test
    fun `a holiday is a hard off day and beats the weekend`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), noClassDays = setOf(saturday))

        assertEquals(DayOffReason.NO_CLASS, cal.dayOffReason(saturday))
        assertTrue(cal.isOffDay(saturday))
    }

    @Test
    fun `a vacated day is a hard off day but the make up day is not`() {
        val from = EpochDays.parse("2026-10-02")!!   // 周五，被搬走
        val to = EpochDays.parse("2026-10-17")!!     // 周六，补课
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))

        assertEquals(DayOffReason.VACATED, cal.dayOffReason(from))
        assertTrue(cal.isOffDay(from))
        assertNull("补课日不是休息日", cal.dayOffReason(to))
        assertFalse(cal.isOffDay(to))
    }

    @Test
    fun `a day outside the term is a hard off day`() {
        val cal = AcademicCalendar(semester(totalWeeks = 16))
        val past = firstMonday + 16 * 7

        assertEquals(DayOffReason.OUT_OF_TERM, cal.dayOffReason(past))
        assertTrue(cal.isOffDay(past))
    }

    @Test
    fun `a normal weekday has no reason to be empty`() {
        val cal = AcademicCalendar(semester())

        assertNull(cal.dayOffReason(firstMonday))
        assertNull(cal.dayOffReason(firstMonday + 4))
        assertFalse(cal.isOffDay(firstMonday))
    }

    /**
     * 未配置学期时，学期外这条判不出来（没有学期这个概念），
     * 但周末那条仍然成立 —— 周末与学期无关。
     */
    @Test
    fun `without a semester a weekend is still a weekend`() {
        val cal = AcademicCalendar.EMPTY

        assertEquals(DayOffReason.WEEKEND, cal.dayOffReason(firstMonday + 5))
        assertNull(cal.dayOffReason(firstMonday))
    }

    @Test
    fun `no class days win over everything including makeup`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(
            semester = semester(),
            noClassDays = setOf(saturday),
            makeupDays = mapOf(saturday to MakeupShift(firstMonday))
        )
        // 既标了补课又标了停课：停课优先，否则「临时取消补课」就表达不出来
        assertFalse(cal.isSchoolDay(saturday))
        assertTrue(cal.isNoClass(saturday))
        assertTrue(cal.isMakeup(saturday))
    }

    @Test
    fun `a makeup day makes a weekend a school day`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(firstMonday)))
        assertTrue("补课日即使是周六也要上课", cal.isSchoolDay(saturday))
        assertEquals(1, cal.makeupDayOfWeek(saturday))
        assertNull(cal.makeupDayOfWeek(saturday + 1))
    }

    /**
     * 来源日是唯一真源：补周几由它推出来，不需要、也不允许另存一份。
     *
     * 周六补的课要写「补周五」，来源日就得是一个周五 —— 这正是这条约束的形状：
     * 只要周几是算出来的，「写着补周五、来源日却是周三」这种矛盾就写不进去了。
     */
    @Test
    fun `the makeup weekday is derived from the source day`() {
        val friday = firstMonday + 4
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(friday)))

        assertEquals("补周几由来源日推出来", 5, cal.makeupDayOfWeek(saturday))
        assertEquals(6, EpochDays.dayOfWeek1To7(saturday))
    }

    @Test
    fun `effective day of week returns the makeup target, not the real weekday`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(firstMonday)))
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
            makeupDays = mapOf(to to MakeupShift(from))
        )

        assertEquals("来源日那周", 4, cal.teachingWeekOf(to))
        assertEquals("补课当天那周，只是不用于排课", 6, cal.weekOf(to))
        assertEquals(5, cal.effectiveDayOfWeek(to))
    }

    /**
     * 来源日未知的那行**整条作废**，而不是退回按补课当天算。
     *
     * 早先的兜底是「来源日未知就按补课当天的星期几与周次算」，看着向后兼容，
     * 实际是往课表里塞一条说不清的搬运：它不知道补的是哪一天的课，排出来的课
     * 也就落不进任何一周，而界面上看不出任何异常。现在来源日是必填的
     * （导入与手工填写两处都已拦下），老库残留的行宁可不认。
     */
    @Test
    fun `a shift without a known source day is not a shift at all`() {
        val saturday = EpochDays.parse("2026-10-17")!!
        val cal = AcademicCalendar(
            semester(),
            makeupDays = mapOf(saturday to MakeupShift(sourceEpochDay = 0L))
        )

        assertFalse(cal.isMakeup(saturday))
        assertNull(cal.shiftFor(saturday))
        assertNull(cal.makeupDayOfWeek(saturday))
        assertFalse("周六不会因为一条无效的搬运就变成上课日", cal.isSchoolDay(saturday))
    }

    @Test
    fun `a weekday erroneously marked as makeup for a weekend is ignored and treated as vacated`() {
        val sun = EpochDays.parse("2026-09-20")!! // Sunday
        val tue = EpochDays.parse("2026-10-06")!! // Tuesday
        // 模拟历史脏数据：老版本在处理对调时双向写入了 MakeupDayEntity
        val cal = AcademicCalendar(
            semester = semester(),
            makeupDays = mapOf(
                sun to MakeupShift(tue),
                tue to MakeupShift(sun)
            )
        )
        // 09-20（周日）是真正的补课日，补周二的课
        assertTrue(cal.isMakeup(sun))
        assertEquals(2, cal.effectiveDayOfWeek(sun))
        assertTrue(cal.isSchoolDay(sun))

        // 10-06（周二）是法定调休让出日，绝不能被当成补课日
        assertFalse("工作日不能补周末课，脏数据被丢弃", cal.isMakeup(tue))
        assertTrue("10-06 课被挪到 09-20，属于让出日", cal.isVacatedForShift(tue))
        assertFalse("让出日不上课", cal.isSchoolDay(tue))
    }

    @Test
    fun `mutual weekday exchange is preserved`() {
        val mon = EpochDays.parse("2027-01-04")!! // Monday
        val thu = EpochDays.parse("2026-12-31")!! // Thursday
        val cal = AcademicCalendar(
            semester = semester(),
            makeupDays = mapOf(
                mon to MakeupShift(thu)
            )
        )
        assertTrue(cal.isMakeup(mon))
        assertEquals(4, cal.effectiveDayOfWeek(mon))
    }

    /**
     * **学期外的补课日仍然是一个真实的上课日。**
     *
     * 真实的一条：冬季学期 2027-01-03 结束，而 12-31（周四）停课的课补到 01-04（周一）——
     * 校历里那条 [学生节] 的 Move 就是这么写的。01-04 落在学期范围之外，
     * 早先被判成 OUT_OF_TERM：那是**硬**闸门，[ScheduleResolver.slotsOn] 第一行就把它挡掉，
     * 于是那天的课在总览、列表、周课表里全部消失，而界面上看不出任何异常 ——
     * 那天看上去就是一个普通的学期外工作日。
     *
     * 补课是校历明确记着的「那天有课」，它比学期范围更具体，所以学期外不能盖过它。
     */
    @Test
    fun `a makeup day outside the term is still a class day`() {
        val thu = EpochDays.parse("2026-12-31")!! // 周四，停课
        val mon = EpochDays.parse("2027-01-04")!! // 周一，补课
        // 17 周的学期到 2027-01-03 结束：01-04 落在学期范围之外
        val cal = AcademicCalendar(
            semester = semester(totalWeeks = 17),
            makeupDays = mapOf(mon to MakeupShift(thu))
        )

        assertNull("学期外的补课日不是休息日", cal.dayOffReason(mon))
        assertFalse("补课日不能被硬闸门挡住", cal.isOffDay(mon))
        assertEquals("补的是周四的课", 4, cal.effectiveDayOfWeek(mon))

        // 周四上午第 1 节（08:00）：那天该出的就是这一节
        val slots = listOf(
            CourseSlotEntity(id = 1L, courseId = 1L, dayOfWeek = 4, startPeriod = 1, endPeriod = 2)
        )
        assertEquals(1, ScheduleResolver(cal).slotsOn(mon, slots).size)
    }
}
