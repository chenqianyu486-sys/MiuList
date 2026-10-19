package com.example.ui.components

import com.example.data.calendar.AppZone
import com.example.data.local.CourseSlotEntity
import com.example.data.timetable.PeriodSlot
import com.example.data.timetable.PeriodTable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class PeriodUtilsTest {

    private val periodTable = PeriodTable.from(
        listOf(
            // 第1节：08:00 - 08:45 (480 - 525)
            PeriodSlot(1, 480, 525, "上午"),
            // 第2节：08:50 - 09:35 (530 - 575)
            PeriodSlot(2, 530, 575, "上午"),
            // 第3节：10:00 - 10:45 (600 - 645)
            PeriodSlot(3, 600, 645, "上午"),
            // 第4节：10:50 - 11:35 (650 - 695)
            PeriodSlot(4, 650, 695, "上午")
        )
    )

    private fun makeCalendar(year: Int = 2026, month: Int = 9, day: Int = 15): Calendar {
        return AppZone.calendar().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun timeAt(cal: Calendar, hour: Int, minute: Int): Long {
        return (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `formatRemaining formats durations properly`() {
        assertEquals("0 分", formatRemaining(0L))
        assertEquals("45 分", formatRemaining(45 * 60_000L))
        assertEquals("1 小时", formatRemaining(60 * 60_000L))
        assertEquals("1 小时 30 分", formatRemaining(90 * 60_000L))
        assertEquals("2 小时", formatRemaining(120 * 60_000L))
        assertEquals("2 小时 15 分", formatRemaining(135 * 60_000L))
    }

    @Test
    fun `todayStatusLabel returns ALL_DAY_DONE_TEXT when slots are empty`() {
        val cal = makeCalendar()
        val now = timeAt(cal, 8, 0)
        val status = todayStatusLabel(cal, emptyList(), now, periodTable)
        assertEquals(ALL_DAY_DONE_TEXT, status)
    }

    @Test
    fun `todayStatusLabel returns 下节课还有 when before first course`() {
        val cal = makeCalendar()
        val slots = listOf(
            CourseSlotEntity(id = 1, courseId = 100, dayOfWeek = 4, startPeriod = 1, endPeriod = 2) // 08:00 - 09:35
        )
        // 07:30 -> 30 分 before 08:00
        val now = timeAt(cal, 7, 30)
        val status = todayStatusLabel(cal, slots, now, periodTable)
        assertEquals("下节课还有 30 分 开始", status)
    }

    @Test
    fun `todayStatusLabel returns 还有 X 下课 when during course`() {
        val cal = makeCalendar()
        val slots = listOf(
            CourseSlotEntity(id = 1, courseId = 100, dayOfWeek = 4, startPeriod = 1, endPeriod = 2) // 08:00 - 09:35
        )
        // 08:35 -> 1 hour (60 min) left before 09:35
        val now = timeAt(cal, 8, 35)
        val status = todayStatusLabel(cal, slots, now, periodTable)
        assertEquals("还有 1 小时 下课", status)
    }

    @Test
    fun `todayStatusLabel returns 下节课还有 when between courses`() {
        val cal = makeCalendar()
        val slots = listOf(
            CourseSlotEntity(id = 1, courseId = 100, dayOfWeek = 4, startPeriod = 1, endPeriod = 2), // 08:00 - 09:35
            CourseSlotEntity(id = 2, courseId = 101, dayOfWeek = 4, startPeriod = 3, endPeriod = 4)  // 10:00 - 11:35
        )
        // 09:40 -> 20 min before 10:00
        val now = timeAt(cal, 9, 40)
        val status = todayStatusLabel(cal, slots, now, periodTable)
        assertEquals("下节课还有 20 分 开始", status)
    }

    @Test
    fun `todayStatusLabel returns ALL_DAY_DONE_TEXT after all courses finished`() {
        val cal = makeCalendar()
        val slots = listOf(
            CourseSlotEntity(id = 1, courseId = 100, dayOfWeek = 4, startPeriod = 1, endPeriod = 2), // 08:00 - 09:35
            CourseSlotEntity(id = 2, courseId = 101, dayOfWeek = 4, startPeriod = 3, endPeriod = 4)  // 10:00 - 11:35
        )
        // 11:35 or later
        val now = timeAt(cal, 12, 0)
        val status = todayStatusLabel(cal, slots, now, periodTable)
        assertEquals(ALL_DAY_DONE_TEXT, status)
    }
}
