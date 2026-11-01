package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页里那个「待办完成确认」开关。
 *
 * 两条：
 *
 * - **它在设置页里**：用户要能自己决定「勾掉待办要不要多问一句」。
 * - **它只有开关这一个落点**：整行不可点 —— 整行也可点会多出第二个落点
 *   （按标题算「改了」，按空白处又像没反应）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenCompleteConfirmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `开关摆在那里且反映当前状态`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                taskCompleteConfirmEnabled = true
            )
        }

        composeTestRule.onNodeWithText("待办完成确认").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_task_complete_confirm_switch").assertIsOn()
    }

    @Test
    fun `关掉之后开关显示为关`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                taskCompleteConfirmEnabled = false
            )
        }

        composeTestRule.onNodeWithTag("settings_task_complete_confirm_switch").assertIsOff()
    }

    @Test
    fun `拨开关把新值交出去`() {
        var changed: Boolean? = null

        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                taskCompleteConfirmEnabled = true,
                onTaskCompleteConfirmChange = { changed = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_task_complete_confirm_switch").performClick()

        assertEquals(false, changed)
    }
}
