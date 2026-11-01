package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页里那个「检查更新」入口。
 *
 * 三条：
 *
 * - **它在设置页里**，用户自己决定什么时候去问；
 * - **点了就触发检查**（只有一个落点：按钮与整张卡是同一个动作）；
 * - **检查中按钮禁用**：不然连点两下会发两次请求，
 *   而先回来的那次未必是新的那次。
 *
 * **先滚过去再断言**：这一项排在设置页中段，而设置页是一个 LazyColumn ——
 * 视口外的项根本不会被组合，节点压根不存在（不是「存在但不可见」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenUpdateCheckTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** 把「检查更新」那一项滚进视口，否则它的节点还不存在。 */
    private fun scrollToUpdateCard() {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("检查更新"))
        composeTestRule.waitForIdle()
    }

    @Test
    fun `入口摆在设置页里`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {}
            )
        }

        scrollToUpdateCard()
        composeTestRule.onNodeWithText("检查更新").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_check_update_button").assertIsDisplayed()
    }

    @Test
    fun `点一下就发起检查`() {
        var checked = 0

        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                onCheckAppUpdate = { checked++ }
            )
        }

        scrollToUpdateCard()
        composeTestRule.onNodeWithTag("open_check_update_button").performClick()

        assertEquals(1, checked)
    }

    @Test
    fun `检查中按钮禁用`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                isCheckingAppUpdate = true
            )
        }

        scrollToUpdateCard()
        composeTestRule.onNodeWithTag("open_check_update_button").assertIsNotEnabled()
    }

    @Test
    fun `没在检查时按钮可点`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {}
            )
        }

        scrollToUpdateCard()
        composeTestRule.onNodeWithTag("open_check_update_button").assertIsEnabled()
    }
}
