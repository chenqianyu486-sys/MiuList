package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 总览页空态卡第二行的文案。
 *
 * ## 为什么空态卡不必提考试
 *
 * 空态卡只在**今天一个条目都没有**时出现（没课、也没考试）。
 * 有考试时那张卡根本不出现 —— 上面摆着一张「今天没课」、
 * 下面摆着一张考试卡，是自相矛盾的。
 */
class ExamSectionLabelsTest {

    @Test
    fun `空态卡只讲待办`() {
        assertEquals("还有 3 条待办", todayEmptyStateSummary(pendingCount = 3))
        assertEquals("今天没有待办", todayEmptyStateSummary(pendingCount = 0))
    }
}
