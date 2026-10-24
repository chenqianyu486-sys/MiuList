package com.example.ui.components

import com.example.data.calendar.AppZone
import com.example.data.model.ExamEntry
import com.example.data.model.ExamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 考试卡上的文案分档。
 *
 * ## 为什么这些纯函数要单独钉住
 *
 * 它们决定的是**用户看到的事实**：「还有几天」「几点考」「哪一天」，
 * 每一个都能算错，而且算错之后界面上看不出任何异常。
 *
 * 尤其「日期没公布」那一档：那时候没有绝对日期，
 * 只能显示学校给的那句标签 —— 要是自己编一个日期出来，
 * 用户会照着去考场，那就不是显示问题而是害人了。
 */
class ExamLabelsTest {

    private val now: Long = at(2026, 10, 24, 12 * 60)

    private fun at(year: Int, month: Int, day: Int, minute: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(AppZone.ZONE).toInstant().toEpochMilli() +
            minute * 60_000L

    private fun entry(
        startAt: Long = 0L,
        endAt: Long = 0L,
        startMinute: Int = 0,
        endMinute: Int = 0,
        dateLabel: String = "",
        classroom: String = "",
        seat: String = ""
    ) = ExamEntry(
        id = 1L,
        courseName = "高等数学(甲)II",
        kind = ExamKind.FINAL,
        startAt = startAt,
        endAt = endAt,
        startMinute = startMinute,
        endMinute = endMinute,
        dateLabel = dateLabel,
        classroom = classroom,
        seat = seat,
        credit = 5.0,
        termLabel = "2026-2027 秋冬",
        courseId = null,
        courseColorHex = "#93C5FD"
    )

    @Test
    fun `时刻区间照着起止分钟写出来`() {
        assertEquals(
            "09:00-11:00",
            examClockRange(entry(startMinute = 9 * 60, endMinute = 11 * 60))
        )
    }

    /** 只给了开始时刻时不该编一个结束时刻出来。 */
    @Test
    fun `只有开始时刻时只写开始`() {
        assertEquals("09:00", examClockRange(entry(startMinute = 9 * 60)))
    }

    /** 连时刻都没有 → 空串。编一个占位时间只会被当成真的。 */
    @Test
    fun `没有时刻时区间是空串`() {
        assertEquals("", examClockRange(entry()))
    }

    @Test
    fun `近三天用今天明天后天更远的用月日`() {
        assertEquals("今天", examDateLabel(entry(startAt = at(2026, 10, 24, 9 * 60)), now))
        assertEquals("明天", examDateLabel(entry(startAt = at(2026, 10, 25, 9 * 60)), now))
        assertEquals("后天", examDateLabel(entry(startAt = at(2026, 10, 26, 9 * 60)), now))
        assertEquals("10月27日", examDateLabel(entry(startAt = at(2026, 10, 27, 9 * 60)), now))
    }

    /** 日期没公布时用学校给的那句标签，一个字都不改。 */
    @Test
    fun `日期没公布时原样显示学校的标签`() {
        assertEquals(
            "考试周第 3 天",
            examDateLabel(entry(dateLabel = "考试周第 3 天"), now)
        )
    }

    /** 连标签都没有（学校连时间都没给）时才用兜底文案。 */
    @Test
    fun `标签也没有时用日期待定`() {
        assertEquals("日期待定", examDateLabel(entry(), now))
    }

    @Test
    fun `倒计时三天起才出现近三天的日期行已经说清楚了`() {
        assertNull(examCountdownLabel(entry(startAt = at(2026, 10, 24, 9 * 60)), now))
        assertNull(examCountdownLabel(entry(startAt = at(2026, 10, 25, 9 * 60)), now))
        assertNull(examCountdownLabel(entry(startAt = at(2026, 10, 26, 9 * 60)), now))
        assertEquals("3 天后", examCountdownLabel(entry(startAt = at(2026, 10, 27, 9 * 60)), now))
        assertEquals("30 天后", examCountdownLabel(entry(startAt = at(2026, 11, 23, 9 * 60)), now))
    }

    /** 超过一个月用周 —— 「43 天后」不好读，「6 周后」好读。 */
    @Test
    fun `超过一个月的倒计时用周`() {
        assertEquals("6 周后", examCountdownLabel(entry(startAt = at(2026, 12, 5, 9 * 60)), now))
    }

    /** 日期未知就没有可倒计时的东西。 */
    @Test
    fun `日期未知时没有倒计时`() {
        assertNull(examCountdownLabel(entry(dateLabel = "考试周第 3 天"), now))
    }

    /** 已经过去的考试不该显示倒计时（显示负数天是荒谬的）。 */
    @Test
    fun `已经过去的考试没有倒计时`() {
        assertNull(examCountdownLabel(entry(startAt = at(2026, 10, 1, 9 * 60)), now))
    }

    @Test
    fun `今天有没有考试按当天判`() {
        assertTrue(isExamToday(entry(startAt = at(2026, 10, 24, 9 * 60)), now))
        assertFalse(isExamToday(entry(startAt = at(2026, 10, 25, 9 * 60)), now))
        assertFalse("日期未知的考试不属于任何一天", isExamToday(entry(dateLabel = "考试周第 3 天"), now))
    }

    /**
     * 「考场与座位的待安排状态」分得开。
     */
    @Test
    fun `考场与座位的待安排状态分得开`() {
        val none = entry(classroom = "", seat = "")
        assertTrue("两样都没定", none.locationPending)
        assertFalse(none.seatPending)

        val roomOnly = entry(classroom = "紫金港东2-201", seat = "")
        assertFalse("考场定了就不算全待安排", roomOnly.locationPending)
        assertTrue("座位还没定", roomOnly.seatPending)

        val full = entry(classroom = "紫金港东2-201", seat = "12-3-15")
        assertFalse(full.locationPending)
        assertFalse(full.seatPending)
    }

    /**
     * 今天有考试时，组头**不能**说「课都上完啦，休息一下吧~」。
     *
     * 那句话是在说「今天没事了」。今天有考试时它就是**说假话**，
     * 而用户恰恰最需要被提醒的就是这件事。
     */
    @Test
    fun `今天有考试时组头改口说考试`() {
        assertEquals("今天有 1 场考试", dayDoneLabel(examCountToday = 1))
        assertEquals("今天有 3 场考试", dayDoneLabel(examCountToday = 3))
        assertEquals("课都上完啦，休息一下吧~", dayDoneLabel(examCountToday = 0))
    }

    /**
     * 本地改考试时刻的输入解析。
     *
     * 解析不出来就**不让保存** —— 考试时间错了，用户会按错的时间出门。
     */
    @Test
    fun `时刻文本解析成当天第几分钟`() {
        assertEquals(9 * 60, parseClockText("09:00"))
        assertEquals(9 * 60, parseClockText("9:00"))
        assertEquals(9 * 60 + 5, parseClockText("09:05"))
        assertEquals(0, parseClockText("00:00"))
        assertEquals(23 * 60 + 59, parseClockText("23:59"))
    }

    @Test
    fun `时刻文本解析不出来时返回空`() {
        assertEquals(null, parseClockText(""))
        assertEquals(null, parseClockText("abc"))
        assertEquals(null, parseClockText("9"))
        assertEquals(null, parseClockText("24:00"))
        assertEquals(null, parseClockText("09:60"))
    }
}
