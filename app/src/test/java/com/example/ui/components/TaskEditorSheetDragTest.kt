package com.example.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「弹层内容整体在顶」的判定。
 *
 * 这条规则是 2026-11-08 修「展开关联课程后弹层突然变透」时抽出来的。
 *
 * 背景一句话：弹层在 `onPostScroll` 里接手的剩余位移，在「内容滚到头」
 * 和「内容本来就滚不动」两种情形下长得一模一样；分不出来就一律拖动弹层，
 * 弹层于是被拽下去并一路淡到 20% 不透明度 —— 看着就是「弹层突然变透」。
 *
 * 所以这条判定必须是**全局**的：表单和课程滚筒是两个独立的滚动区域，
 * 滚筒在顶但表单不在顶时，下拉该滚表单，不该拖动弹层。
 */
class TaskEditorSheetDragTest {

    @Test
    fun `表单与滚筒都在顶时 判定为在顶`() {
        assertTrue(sheetContentAtTop(formScrollOffset = 0, wheelFirstVisibleIndex = 0, wheelFirstVisibleOffset = 0))
    }

    @Test
    fun `表单滚下去之后 不算在顶`() {
        // 这一条就是 bug 本身：展开课程滚筒后表单变长，用户滚到表单底部，
        // 再往下拉时表单已经没法继续下滚，剩余位移被弹层当成「把弹层往下拖」。
        assertFalse(sheetContentAtTop(formScrollOffset = 240, wheelFirstVisibleIndex = 0, wheelFirstVisibleOffset = 0))
    }

    @Test
    fun `滚筒滑走之后 不算在顶`() {
        assertFalse(sheetContentAtTop(formScrollOffset = 0, wheelFirstVisibleIndex = 2, wheelFirstVisibleOffset = 0))
    }

    @Test
    fun `滚筒还差几个像素没回到顶 也不算在顶`() {
        assertFalse(sheetContentAtTop(formScrollOffset = 0, wheelFirstVisibleIndex = 0, wheelFirstVisibleOffset = 7))
    }

    @Test
    fun `负偏移也算在顶`() {
        // 橡皮筋回弹时表单的偏移可能短暂为负，仍视为在顶。
        assertTrue(sheetContentAtTop(formScrollOffset = -12, wheelFirstVisibleIndex = 0, wheelFirstVisibleOffset = 0))
    }
}
