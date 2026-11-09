package com.example.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备注类文字块的**分段**。
 *
 * Compose 的 `Text` 不认段落间距：几段写在一个 `Text` 里，中间的换行
 * 只值一个行高，两段读起来还是连着的。[NoteText] 把正文按回车换行拆开排，
 * 段与段之间空一个「行高 × [com.example.ui.theme.ParagraphGapFactor]」。
 *
 * 两种换行、两种间距（这是全 App 统一的定义）：
 *
 * - **回车换行**（作者敲的换行）= 另起一段 → 段间距；
 * - **被动换行**（被宽度折断）= 还是同一段 → 行高。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NoteTextTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `回车换行把正文拆成两段`() {
        composeTestRule.setContent {
            NoteText(text = "第一段。\n第二段。")
        }

        composeTestRule.onNodeWithText("第一段。").assertExists()
        composeTestRule.onNodeWithText("第二段。").assertExists()
        // 不再是「一整块」：两段之间要留段间距。
        composeTestRule.onNodeWithText("第一段。\n第二段。").assertDoesNotExist()
    }

    @Test
    fun `空行把正文拆成两段`() {
        composeTestRule.setContent {
            NoteText(text = "第一段。\n\n第二段。")
        }

        composeTestRule.onNodeWithText("第一段。").assertExists()
        composeTestRule.onNodeWithText("第二段。").assertExists()
    }

    @Test
    fun `整段只有一段时就只有一块`() {
        composeTestRule.setContent {
            NoteText(text = "只有一段。")
        }

        composeTestRule.onNodeWithText("只有一段。").assertExists()
    }

    @Test
    fun `空行不会多出一段`() {
        // "a\n\nb" 按回车切是三段，中间那段是空的 → 丢掉 → 两段，
        // 空行不能变成两截段间距。
        assertEquals(
            listOf("a", "b"),
            splitNoteParagraphs("a\n\nb")
        )
    }

    @Test
    fun `Windows 换行也算回车换行`() {
        assertEquals(
            listOf("第一行", "第二行"),
            splitNoteParagraphs("第一行\r\n第二行")
        )
    }

    @Test
    fun `空段会被丢掉`() {
        assertEquals(
            listOf("保留"),
            splitNoteParagraphs("\n\n  \n保留\n\n")
        )
    }
}
