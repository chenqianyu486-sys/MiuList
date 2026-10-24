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

    /**
     * 大字号下的短句：同样的时刻、更少的字。
     *
     * 这不是换文案，是**同一件事的紧凑写法**。长句 15 个字在系统字号 2.0 时
     * 会断成「…开 / 始」两行，最后一个字单独占一行；短句 11 个字能收回一行。
     * 默认文案不变，只有 `compact = true` 才走这条。
     */
    @Test
    fun `todayStatusLabel compact 文案更短且信息不丢`() {
        val cal = makeCalendar()
        val slots = listOf(
            CourseSlotEntity(id = 1, courseId = 100, dayOfWeek = 4, startPeriod = 1, endPeriod = 2)
        )
        val now = timeAt(cal, 7, 30)
        val status = todayStatusLabel(cal, slots, now, periodTable, compact = true)
        assertEquals("距下节课 30 分", status)
        // 正在上课 / 已上完两档不受影响。
        val during = todayStatusLabel(cal, slots, timeAt(cal, 8, 35), periodTable, compact = true)
        assertEquals("还有 1 小时 下课", during)
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

    /**
     * 考试与课在**同一条时间线上**，所以组头必须一起看。
     *
     * 只盯着课说「下节课还有 3 小时开始」，而考试两小时后就要考 —— 那是假话。
     */
    @Test
    fun `考试比课更早开始时组头说的是考试`() {
        val cal = makeCalendar()
        val slots = listOf(
            CourseSlotEntity(id = 1, courseId = 100, dayOfWeek = 4, startPeriod = 3, endPeriod = 4) // 10:00 - 11:35
        )
        val now = timeAt(cal, 8, 0)
        // 考试 09:00 - 10:00，比 10:00 的课更早
        val examWindow = timeAt(cal, 9, 0) to timeAt(cal, 10, 0)
        val status = todayStatusLabel(cal, slots, now, periodTable, examWindows = listOf(examWindow))
        assertEquals("考试还有 1 小时 开始", status)
    }

    /** 考试正在进行时，组头不能说「下课」—— 考试不是课。 */
    @Test
    fun `考试进行中组头说考试还有多久结束`() {
        val cal = makeCalendar()
        val now = timeAt(cal, 9, 30)
        val examWindow = timeAt(cal, 9, 0) to timeAt(cal, 10, 30)
        val status = todayStatusLabel(cal, emptyList(), now, periodTable, examWindows = listOf(examWindow))
        assertEquals("考试还有 1 小时 结束", status)
    }

    /** 今天只有考试、课都上完了：不能说「休息一下吧~」。 */
    @Test
    fun `今天只有考试时不说课都上完啦`() {
        val cal = makeCalendar()
        val now = timeAt(cal, 12, 0)
        val status = todayStatusLabel(cal, emptyList(), now, periodTable, examCountToday = 1)
        assertEquals("今天有 1 场考试", status)
    }

    /** 大字号下的短句：考试也走紧凑写法。 */
    @Test
    fun `考试开始前的大字号短句`() {
        val cal = makeCalendar()
        val now = timeAt(cal, 8, 0)
        val examWindow = timeAt(cal, 9, 0) to timeAt(cal, 10, 0)
        val status = todayStatusLabel(
            cal, emptyList(), now, periodTable, compact = true,
            examWindows = listOf(examWindow)
        )
        assertEquals("距考试 1 小时", status)
    }
}
