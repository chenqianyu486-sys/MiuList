package com.example.data.calendar

import com.example.data.local.CourseSlotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排课判定唯一入口。纯 JUnit。
 *
 * 改造前「这天有没有课」的筛选散在四个地方（仪表盘今日课程、周课表每列、
 * 列表页分组、Repository 的 getSlotsByDaySync），加进开课周次、停课、补课后
 * 会被同时改坏。这里把四种情形一次钉死。
 */
class ScheduleResolverTest {

    private val firstMonday = EpochDays.parse("2026-09-07")!!

    private fun semester(totalWeeks: Int = 20) = Semester(
        name = "2026-2027学年 秋冬学期",
        yearSpan = "2026-2027",
        term = "秋冬",
        startEpochDay = firstMonday,
        firstMondayEpochDay = firstMonday,
        endEpochDay = firstMonday + (totalWeeks - 1) * 7L + 6,
        totalWeeks = totalWeeks,
        periodsPerDay = 13
    )

    private fun slot(id: Long, dayOfWeek: Int, start: Int, end: Int = start) =
        CourseSlotEntity(
            id = id,
            courseId = 1L,
            dayOfWeek = dayOfWeek,
            startPeriod = start,
            endPeriod = end
        )

    // ---------------------------------------------------------------- 未配置学期

    @Test
    fun `without a semester the opening weeks are ignored entirely`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        val slots = listOf(slot(1, 3, 1), slot(2, 3, 3))
        val weeks = mapOf(1L to setOf(1, 3, 5), 2L to setOf(2, 4))

        // 周三。两个时段的开课周次都不含当前周，但未配置学期时必须全出
        val today = firstMonday + 2
        assertEquals(2, resolver.slotsOn(today, slots, weeks).size)
    }

    @Test
    fun `without a semester weekends still have no classes`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        val slots = listOf(slot(1, 6, 1))
        assertTrue(resolver.slotsOn(firstMonday + 5, slots).isEmpty())
    }

    // ---------------------------------------------------------------- 开课周次

    @Test
    fun `a slot only shows up in the weeks it actually opens`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester()))
        val oddOnly = slot(1, 1, 1)
        val weeks = mapOf(1L to setOf(1, 3, 5))

        assertEquals(1, resolver.slotsOn(firstMonday, listOf(oddOnly), weeks).size)          // 第 1 周
        assertEquals(0, resolver.slotsOn(firstMonday + 7, listOf(oddOnly), weeks).size)      // 第 2 周
        assertEquals(1, resolver.slotsOn(firstMonday + 14, listOf(oddOnly), weeks).size)     // 第 3 周
    }

    @Test
    fun `a missing or empty week set means every week`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester()))
        val s = slot(1, 1, 1)
        // 缺 key = 未指定 = 每周，与改造前一致
        assertEquals(1, resolver.slotsOn(firstMonday + 7, listOf(s), emptyMap()).size)
        assertEquals(1, resolver.slotsOn(firstMonday + 7, listOf(s), mapOf(1L to emptySet())).size)
    }

    @Test
    fun `slots come back ordered by start period`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        val slots = listOf(slot(1, 1, 7), slot(2, 1, 1), slot(3, 1, 4))
        assertEquals(listOf(1, 4, 7), resolver.slotsOn(firstMonday, slots).map { it.startPeriod })
    }

    // ---------------------------------------------------------------- 停课 / 补课

    @Test
    fun `a no class day has no classes even though the weekday matches`() {
        val day = firstMonday
        val cal = AcademicCalendar(semester(), noClassDays = setOf(day))
        val resolver = ScheduleResolver(cal)
        assertFalse(resolver.isSchoolDay(day))
        assertTrue(resolver.slotsOn(day, listOf(slot(1, 1, 1))).isEmpty())
    }

    @Test
    fun `a makeup day pulls in the classes of the day it substitutes for`() {
        val saturday = firstMonday + 5      // 周六
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(1)))
        val resolver = ScheduleResolver(cal)
        val monday = slot(1, 1, 1)
        val saturdaySlot = slot(2, 6, 1)

        // 补的是周一，所以出周一的课，不出周六原本的课
        val result = resolver.slotsOn(saturday, listOf(monday, saturdaySlot))
        assertEquals(1, result.size)
        assertEquals(1L, result.first().id)
    }

    @Test
    fun `a makeup day on a weekday overrides that weekday`() {
        // 周三补周五的课：当天出周五的课，不出周三自己的课
        val wednesday = firstMonday + 2
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(wednesday to MakeupShift(5)))
        val resolver = ScheduleResolver(cal)
        val wed = slot(1, 3, 1)
        val fri = slot(2, 5, 1)
        val result = resolver.slotsOn(wednesday, listOf(wed, fri))
        assertEquals(1, result.size)
        assertEquals(2L, result.first().id)
    }

    @Test
    fun `a makeup day still respects the opening weeks of the substituted day`() {
        val saturday = firstMonday + 5      // 第 1 周的周六
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(1)))
        val resolver = ScheduleResolver(cal)
        val oddOnly = slot(1, 1, 1)
        // 第 1 周是单周 → 开课；补课日不豁免开课周次判定
        assertEquals(1, resolver.slotsOn(saturday, listOf(oddOnly), mapOf(1L to setOf(1, 3))).size)
        assertEquals(0, resolver.slotsOn(saturday, listOf(oddOnly), mapOf(1L to setOf(2, 4))).size)
    }

    /**
     * 补课日按**来源日**的开课周次筛课，不按补课当天。
     *
     * 这就是「调休是具体到哪一天调到哪一天」在排课上的落点：
     * 隔了两周补上来的那份课，开课周次要拿来源日那一周去比。
     * 学期从 [firstMonday]（2026-09-07）起算，所以来源日 10-02 落在第 4 周、
     * 补课日 10-17 落在第 6 周；只在第 4 周开的课必须出现，只在第 6 周开的必须不出现。
     */
    @Test
    fun `a makeup day filters by the source day's week, not its own`() {
        val from = EpochDays.parse("2026-10-02")!!
        val to = EpochDays.parse("2026-10-17")!!
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(5, from)))
        val resolver = ScheduleResolver(cal)
        val friday = slot(1, 5, 1)

        assertEquals(
            "来源日在第 4 周，只在第 4 周开的课要出来",
            1,
            resolver.slotsOn(to, listOf(friday), mapOf(1L to setOf(4))).size
        )
        assertEquals(
            "第 6 周是补课当天，不该用它的周次",
            0,
            resolver.slotsOn(to, listOf(friday), mapOf(1L to setOf(6))).size
        )
    }

    // ---------------------------------------------------------------- 周课表

    @Test
    fun `the weekly grid keeps its shape and is not affected by makeup days`() {
        val saturday = firstMonday + 5
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(1)))
        val resolver = ScheduleResolver(cal)
        val monday = slot(1, 1, 1)
        val saturdaySlot = slot(2, 6, 2)

        val grid = resolver.slotsInWeek(1, listOf(monday, saturdaySlot))
        // 周课表是「这一周的结构」：周一还是周一，周六还是周六
        assertEquals(setOf(1, 6), grid.keys)
        assertEquals(1L, grid[1]!!.first().id)
        assertEquals(2L, grid[6]!!.first().id)
    }

    @Test
    fun `the weekly grid drops days whose only slots do not open this week`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester()))
        val oddOnly = slot(1, 1, 1)
        val weeks = mapOf(1L to setOf(1, 3, 5))
        assertEquals(setOf(1), resolver.slotsInWeek(1, listOf(oddOnly), weeks).keys)
        assertTrue(resolver.slotsInWeek(2, listOf(oddOnly), weeks).isEmpty())
    }

    @Test
    fun `the weekly grid without a semester shows every slot`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        val slots = listOf(slot(1, 1, 1), slot(2, 6, 1))
        // 未配置学期时周课表也要保留周六，否则「周六有课」这类课表会整列消失
        assertEquals(setOf(1, 6), resolver.slotsInWeek(1, slots).keys)
    }

    // ---------------------------------------------------------------- 周次与边界

    @Test
    fun `occursInWeek rejects a week below one`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        assertFalse(resolver.occursInWeek(1L, 0, emptyMap()))
        assertFalse(resolver.occursInWeek(1L, -3, emptyMap()))
    }

    @Test
    fun `current week falls back to the legacy default when the date is outside the semester`() {
        val configured = ScheduleResolver(AcademicCalendar(semester()))
        assertEquals(1, configured.currentWeek(firstMonday))
        assertEquals(3, configured.currentWeek(firstMonday + 14))

        val empty = ScheduleResolver(AcademicCalendar.EMPTY)
        assertEquals(ScheduleResolver.FALLBACK_CURRENT_WEEK, empty.currentWeek(firstMonday))
        assertEquals(6, ScheduleResolver.FALLBACK_CURRENT_WEEK)
    }

    @Test
    fun `a day past the last week of the semester has no classes`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester(totalWeeks = 16)))
        val monday = slot(1, 1, 1)
        assertEquals(1, resolver.slotsOn(firstMonday + 15 * 7, listOf(monday)).size)
        assertTrue(resolver.slotsOn(firstMonday + 16 * 7, listOf(monday)).isEmpty())
    }
}
