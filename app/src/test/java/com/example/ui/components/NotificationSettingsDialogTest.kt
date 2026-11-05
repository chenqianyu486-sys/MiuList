package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.notification.NotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地提醒设置弹层。
 *
 * 要钉住的是**颗粒度**：三组提醒各有自己的开关与提前量，总开关一关，
 * 分组设置整块收起 —— 留着三个拨不动的开关只会让人以为可以单独开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSettingsDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `三组提醒与总开关都在`() {
        composeTestRule.setContent {
            NotificationSettingsDialog(
                settings = NotificationSettings(),
                onDismiss = {},
                onMasterEnabledChange = {},
                onDailyEnabledChange = {},
                onDailyMinuteChange = {},
                onClassEnabledChange = {},
                onClassLeadChange = {},
                onTodoEnabledChange = {},
                onTodoLeadChange = {}
            )
        }

        composeTestRule.onNodeWithTag("notification_master_switch").assertIsOn()
        composeTestRule.onNodeWithText("每日汇总").assertIsDisplayed()
        composeTestRule.onNodeWithText("上课与考试").assertIsDisplayed()
        composeTestRule.onNodeWithText("待办截止").assertIsDisplayed()
        composeTestRule.onNodeWithText("07:30").assertIsDisplayed()
    }

    @Test
    fun `关掉总开关后分组设置整块收起`() {
        var settings by mutableStateOf(NotificationSettings())

        composeTestRule.setContent {
            NotificationSettingsDialog(
                settings = settings,
                onDismiss = {},
                onMasterEnabledChange = { settings = settings.copy(enabled = it) },
                onDailyEnabledChange = {},
                onDailyMinuteChange = {},
                onClassEnabledChange = {},
                onClassLeadChange = {},
                onTodoEnabledChange = {},
                onTodoLeadChange = {}
            )
        }

        composeTestRule.onNodeWithTag("notification_master_switch").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("notification_master_switch").assertIsOff()
        composeTestRule.onNodeWithText("每日汇总").assertDoesNotExist()
        composeTestRule.onNodeWithText("上课与考试").assertDoesNotExist()
    }

    @Test
    fun `上课提前量可以单独改`() {
        var settings by mutableStateOf(NotificationSettings())
        var selected = 0

        composeTestRule.setContent {
            NotificationSettingsDialog(
                settings = settings,
                onDismiss = {},
                onMasterEnabledChange = {},
                onDailyEnabledChange = {},
                onDailyMinuteChange = {},
                onClassEnabledChange = {},
                onClassLeadChange = {
                    selected = it
                    settings = settings.copy(classLeadMinutes = it)
                },
                onTodoEnabledChange = {},
                onTodoLeadChange = {}
            )
        }

        // 候选不再常驻面板：没点开之前看不到「5 分钟」这些选项。
        composeTestRule.onNodeWithText("5 分钟").assertDoesNotExist()

        composeTestRule
            .onNodeWithTag("notification_settings_content", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("notification_class_lead_button"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("notification_class_lead_button").performClick()
        composeTestRule.waitForIdle()

        // 候选展开后可能落在视口下方，再滚一次到它。
        composeTestRule
            .onNodeWithTag("notification_settings_content", useUnmergedTree = true)
            .performScrollToNode(hasText("30 分钟"))
        composeTestRule.onNodeWithText("30 分钟").performClick()
        composeTestRule.waitForIdle()

        assertEquals(30, selected)
    }
}
