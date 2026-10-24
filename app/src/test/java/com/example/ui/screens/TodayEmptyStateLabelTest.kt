package com.example.ui.screens

import com.example.data.calendar.DayOffReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 总览空态卡的**文案分档**。
 *
 * ## 为什么这条值得单独钉住
 *
 * 「放假停课」和「周末」在界面上必须说不同的话。
 *
 * 以前总览判断「今天停课」用的是 `!calendar.isSchoolDay(today)`，而 `isSchoolDay`
 * 把**周末**也判成「不上课」—— 于是每个周末的总览都写着「今天停课」，
 * 是在宣称一件根本没发生的事。用户原话：「放假停课与周末还是有区别的」。
 *
 * 文案是纯函数，所以能直接单测，不必靠截图去看。
 */
class TodayEmptyStateLabelTest {

    @Test
    fun `放假停课才叫停课`() {
        assertEquals("今天停课", todayEmptyStateLabel(DayOffReason.NO_CLASS))
    }

    @Test
    fun `周末不是停课`() {
        assertEquals("今天周末", todayEmptyStateLabel(DayOffReason.WEEKEND))
    }

    /**
     * 让出课那天，课没消失、是被搬走了。说「今天停课」会让人以为白放了一天，
     * 说「今天没课」又太轻 —— 那天本来是有课的。
     */
    @Test
    fun `让出课说的是课被搬走了`() {
        assertEquals("今天的课调到别的日子了", todayEmptyStateLabel(DayOffReason.VACATED))
    }

    @Test
    fun `学期外与普通工作日没课都只说没课`() {
        assertEquals("今天没课", todayEmptyStateLabel(DayOffReason.OUT_OF_TERM))
        assertEquals("今天没课", todayEmptyStateLabel(null))
    }

    /**
     * 三个原因三句话，**互不相同**。
     *
     * 单条断言挡不住「全都返回同一句」这种退化 —— 那样每条用例都过，
     * 但界面还是把放假和周末混成一件事。
     */
    @Test
    fun `三种原因说出三句不同的话`() {
        val labels = listOf(
            DayOffReason.NO_CLASS,
            DayOffReason.VACATED,
            DayOffReason.WEEKEND
        ).map { todayEmptyStateLabel(it) }

        assertEquals("三句话必须互不相同", 3, labels.toSet().size)
    }
}
