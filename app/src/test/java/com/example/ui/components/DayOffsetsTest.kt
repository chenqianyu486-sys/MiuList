package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 近程时间线取哪几天。
 *
 * ## 这条规则为什么值得钉住
 *
 * 列表页是**近程时间线**：有日程的今天 + 后面两个有日程的日子。
 * 考试是一两周之后的事，把它塞进这个列表会让这一页越拉越长；
 * 而完全不显示又等于「考试从列表里消失了」。
 *
 * 折中就是这条规则：**考试算「有日程」**（所以它能出现），
 * 但**只取三天**（所以这一页不会越拉越长）。
 *
 * 今天自己没日程时不出今天这一段 —— 于是那天只剩两天，
 * 与改造前「今天没课就不出今天这一段」是同一条。
 */
class DayOffsetsTest {

    @Test
    fun `今天有日程时取今天加后面两天`() {
        val everyDay = { _: Int -> true }
        assertEquals(listOf(0, 1, 2), pickDayOffsets(everyDay, extra = 2, horizon = 14))
    }

    @Test
    fun `今天没日程时就不出今天这一段只剩两天`() {
        // 第 0 天没课，第 1 天起有课 → 取 1、2（今天不算额外名额）。
        val has = { offset: Int -> offset >= 1 }
        assertEquals(listOf(1, 2), pickDayOffsets(has, extra = 2, horizon = 14))
    }

    @Test
    fun `只有考试的那天也算有日程`() {
        // 第 2、3、4 天有日程（第 2 天只有一场考试）→ 今天没日程，取 2、3。
        val has = { offset: Int -> offset in listOf(2, 3, 4) }
        assertEquals(listOf(2, 3), pickDayOffsets(has, extra = 2, horizon = 14))
    }

    @Test
    fun `窗口内凑不满就只取到有的那天`() {
        val has = { offset: Int -> offset == 5 }
        assertEquals(listOf(5), pickDayOffsets(has, extra = 2, horizon = 14))
    }

    @Test
    fun `窗口内一个都没有就返回空`() {
        assertEquals(emptyList<Int>(), pickDayOffsets({ false }, extra = 2, horizon = 14))
    }
}
