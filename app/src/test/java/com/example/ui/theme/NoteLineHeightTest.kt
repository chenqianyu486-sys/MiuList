package com.example.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 备注类文字块的两档行距：**行高 1.25 倍字号**，**段间距 0.7 个字（em）**。
 *
 * 这两条是纯算术，抽出来单测：它们一旦被改小，最先出问题的是大字号那一档，
 * 而大字号恰恰是最难靠肉眼在真机上确认的一档。
 *
 * 两种换行、两种间距：回车换行 = 另起一段 → 段间距；
 * 被动换行（被宽度折断）= 还是同一段 → 行高。
 */
class NoteLineHeightTest {

    @Test
    fun `行高是字号的125倍`() {
        val style = Typography.bodyMedium.withNoteLineHeight()
        assertEquals(14f, style.fontSize.value, 0.001f)
        assertEquals(17.5f, style.lineHeight.value, 0.001f)
    }

    @Test
    fun `段间距是零点七个字`() {
        // 14sp 的正文 → 视觉 14dp → 段间距 9.8dp（0.7 个字）
        assertEquals(9.8f, paragraphGapDp(fontSizeSp = 14f, fontScale = 1f).value, 0.001f)
    }

    @Test
    fun `字号放大时段间距跟着放大`() {
        // 系统字号 2.0：14sp 的字视觉上就是 28dp，段间距就是 19.6dp。
        assertEquals(19.6f, paragraphGapDp(fontSizeSp = 14f, fontScale = 2f).value, 0.001f)
    }

    @Test
    fun `小字号的段间距按同一个比例算`() {
        // 12sp 的课程备注 → 段间距 8.4dp
        assertEquals(8.4f, paragraphGapDp(fontSizeSp = 12f, fontScale = 1f).value, 0.001f)
    }
}
