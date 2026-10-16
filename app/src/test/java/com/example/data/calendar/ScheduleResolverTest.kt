package com.example.data.calendar

import com.example.data.local.CourseSlotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(firstMonday)))
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
        // 周三补周五的课：当天出周五的课，不出周三自己的课。
        // 「补周五」不是手写的 —— 来源日取 [friday]（周五），周几由它推出来。
        val wednesday = firstMonday + 2
        val friday = firstMonday + 4
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(wednesday to MakeupShift(friday)))
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
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(saturday to MakeupShift(firstMonday)))
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
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))
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

    // ---------------------------------------------------------------- 周课表（按真实日期）

    /**
     * 2026 国庆那一次调休，钉死周课表上的样子。
     *
     * 学期从 [firstMonday]（周一）起算：第 3 周周五（09-25）的课挪到第 5 周周六（10-10）。
     * 于是**两处**要同时对，第 5 周那一处最容易漏：
     * - 第 3 周的周五（09-25）整列空，且标出它是**让出课**，不是「我周五没课」；
     * - 第 5 周的周六（10-10）出周五的课，列头写「补周五」；
     * - 这节补上来的课仍按**来源日那一周**（第 3 周）的开课周次筛。
     *
     * 这一条是整个改动要解决的问题：改造前周课表按星期几分列，
     * 周六那一列永远是空的，用户看到的是「周六没课」。
     */
    @Test
    fun `the weekly grid puts the makeup on the day it actually falls on`() {
        val from = firstMonday + 18      // 第 3 周周五 09-25
        val to = firstMonday + 33        // 第 5 周周六 10-10
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))
        val resolver = ScheduleResolver(cal)
        val friday = slot(1, 5, 1)

        val vacated = resolver.weekPlan(3, listOf(friday))[4]
        assertEquals(5, vacated.dayOfWeek)
        assertEquals(DayOffReason.VACATED, vacated.offReason)
        assertTrue("让出课那天一节都不上", vacated.slots.isEmpty())

        val makeupSaturday = resolver.weekPlan(5, listOf(friday))[5]
        assertEquals("固定 7 列，周六排第 6 列", 6, makeupSaturday.dayOfWeek)
        assertEquals("补的是周五的课", 5, makeupSaturday.makeupDayOfWeek)
        assertNull("补课日不是休息日", makeupSaturday.offReason)
        assertEquals(listOf(1L), makeupSaturday.slots.map { it.id })
    }

    @Test
    fun `the makeup on the grid is filtered by the source week not the browsed week`() {
        val from = firstMonday + 18      // 第 3 周
        val to = firstMonday + 33        // 第 5 周
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))
        val resolver = ScheduleResolver(cal)
        val friday = slot(1, 5, 1)
        val saturdayColumn = { weeks: Map<Long, Set<Int>> ->
            resolver.weekPlan(5, listOf(friday), weeks)[5].slots.size
        }

        assertEquals("只在来源日那一周开的课要出来", 1, saturdayColumn(mapOf(1L to setOf(3))))
        assertEquals("第 5 周不是这节课的身份", 0, saturdayColumn(mapOf(1L to setOf(5))))
    }

    @Test
    fun `a no class day is empty and says which kind of empty it is`() {
        val wednesday = firstMonday + 9   // 第 2 周周三
        val cal = AcademicCalendar(semester(), noClassDays = setOf(wednesday))
        val plan = ScheduleResolver(cal).weekPlan(2, listOf(slot(1, 3, 1)))

        assertEquals(DayOffReason.NO_CLASS, plan[2].offReason)
        assertTrue(plan[2].slots.isEmpty())
    }

    @Test
    fun `a plain weekend is a rest day and not a holiday`() {
        val plan = ScheduleResolver(AcademicCalendar(semester())).weekPlan(1, emptyList())

        assertEquals(DayOffReason.WEEKEND, plan[5].offReason)
        assertEquals(DayOffReason.WEEKEND, plan[6].offReason)
        // 工作日不挂任何「不上课」的原因，否则列头会给每一列都挂上东西
        assertNull(plan[0].offReason)
    }

    @Test
    fun `a week past the end of the semester is marked out of term`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester(totalWeeks = 16)))
        val monday = slot(1, 1, 1)

        // 整周七列都算学期外——连周末也是。学期外的周六周日不是「休息日」，
        // 说成休息日会和周次条上的「不在学期范围内」自相矛盾。
        assertTrue(
            resolver.weekPlan(17, listOf(monday)).all { it.offReason == DayOffReason.OUT_OF_TERM }
        )
        assertEquals("第 16 周仍在学期内", null, resolver.weekPlan(16, listOf(monday))[0].offReason)
    }

    @Test
    fun `the weekly grid drops days whose only slots do not open this week`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester()))
        val oddOnly = slot(1, 1, 1)
        val weeks = mapOf(1L to setOf(1, 3, 5))

        assertEquals(1, resolver.weekPlan(1, listOf(oddOnly), weeks)[0].slots.size)
        assertTrue(resolver.weekPlan(2, listOf(oddOnly), weeks)[0].slots.isEmpty())
    }

    @Test
    fun `the weekly grid keeps saturday classes that are really on the timetable`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester()))
        val saturdayClass = slot(2, 6, 2)

        // 周末默认不上课，但排进了课表就算数——不能因为「周末」两个字把整列吃掉
        val saturday = resolver.weekPlan(1, listOf(saturdayClass))[5]
        assertEquals(DayOffReason.WEEKEND, saturday.offReason)
        assertEquals(listOf(2L), saturday.slots.map { it.id })
    }

    @Test
    fun `without a semester the week is anchored to the current week`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        val today = EpochDays.parse("2026-10-08")!!     // 周四

        assertEquals(EpochDays.mondayOf(today), resolver.firstMondayOfWeek(1, today))
        assertEquals(EpochDays.mondayOf(today) + 14, resolver.firstMondayOfWeek(3, today))

        val slots = listOf(slot(1, 1, 1), slot(2, 6, 1))
        assertEquals(2, resolver.weekPlan(1, slots).count { it.slots.isNotEmpty() })
        // 没有学期就没有开课周次这回事，两条都得留着
        assertEquals(1, resolver.weekPlan(1, slots).first().slots.size)
        assertEquals(1, resolver.weekPlan(1, slots)[5].slots.size)
    }

    // ------------------------------------------------- 教学周投影（总览课时 / 按周生成）

    /**
     * [slotsInWeek] 是**教学周**投影，回答「第 N 周开课的时段有哪些」，
     * 与课被调到了周几无关。总览的本周课时和按周生成待办都走它。
     *
     * 它和 [weekPlan]（日历视图，回答「这一周这七天分别上什么课」）是两件事：
     * 周五的课被挪到周六之后，栅格里第 5 周的周五是空的，这里第 5 周的周五仍然算数。
     */
    @Test
    fun `the teaching week projection ignores the calendar entirely`() {
        val from = firstMonday + 18
        val to = firstMonday + 33
        val cal = AcademicCalendar(semester(), makeupDays = mapOf(to to MakeupShift(from)))
        val resolver = ScheduleResolver(cal)
        val friday = slot(1, 5, 1)

        val projection = resolver.slotsInWeek(5, listOf(friday))
        assertEquals(setOf(5), projection.keys)
        assertEquals(1L, projection[5]!!.first().id)
    }

    @Test
    fun `the teaching week projection drops slots that do not open this week`() {
        val resolver = ScheduleResolver(AcademicCalendar(semester()))
        val oddOnly = slot(1, 1, 1)
        val weeks = mapOf(1L to setOf(1, 3, 5))
        assertEquals(setOf(1), resolver.slotsInWeek(1, listOf(oddOnly), weeks).keys)
        assertTrue(resolver.slotsInWeek(2, listOf(oddOnly), weeks).isEmpty())
    }

    @Test
    fun `the teaching week projection without a semester shows every slot`() {
        val resolver = ScheduleResolver(AcademicCalendar.EMPTY)
        val slots = listOf(slot(1, 1, 1), slot(2, 6, 1))
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
