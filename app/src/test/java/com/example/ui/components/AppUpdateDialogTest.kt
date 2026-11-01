package com.example.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「有新版本了」的提示弹层。
 *
 * 三件事要钉住：
 *
 * - **两个版本号都摆出来**：新的是哪一版、你现在用的是哪一版。
 *   只说「有新版本」等于让用户自己回忆自己装的是哪一版。
 * - **「去发布页」真的去发布页**：下载要走浏览器，App 自己装不了自己。
 * - **「稍后」只是这一次**：它收起弹层，不当成「以后别提醒」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `弹层里摆出两个版本号`() {
        composeTestRule.setContent {
            AppUpdateDialog(
                latestVersion = "1.2.0",
                currentVersion = "1.1.0",
                onOpenReleasePage = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("有新版本了").assertIsDisplayed()
        composeTestRule.onNodeWithText("已经发布 v1.2.0，你现在用的是 v1.1.0。").assertIsDisplayed()
    }

    @Test
    fun `按去发布页就走发布页`() {
        var opened = false

        composeTestRule.setContent {
            AppUpdateDialog(
                latestVersion = "1.2.0",
                currentVersion = "1.1.0",
                onOpenReleasePage = { opened = true },
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithTag("app_update_open_release_page_button").performClick()

        assertTrue("按「去发布页」要真的去发布页", opened)
    }

    @Test
    fun `按稍后只收起弹层`() {
        var opened = false
        var dismissed = false

        composeTestRule.setContent {
            AppUpdateDialog(
                latestVersion = "1.2.0",
                currentVersion = "1.1.0",
                onOpenReleasePage = { opened = true },
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.onNodeWithTag("app_update_later_button").performClick()

        assertTrue("按「稍后」要收起弹层", dismissed)
        assertFalse("按「稍后」不该顺手开了发布页", opened)
    }
}
