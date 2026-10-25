package com.example.ui.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.components.CloudAccountDialog
import com.example.ui.components.QuickZjuLoginDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 防截屏（`FLAG_SECURE`）这一层的行为。
 *
 * ## 为什么这条要测
 *
 * `FLAG_SECURE` 是**窗口属性**，而这个 App 的弹层为了做毛玻璃是画在
 * Activity 同一个窗口里的 —— 也就是说「给窗口加标志」这件事必须真的落到
 * Activity 那个窗口上，否则就是一句看起来很安全的空话：
 * 密码框照样能被系统录屏、任务管理器缩略图照样能拍到明文。
 *
 * 这里钉的是三件事：
 * 1. 机制本身开/关都生效；
 * 2. 两个**含密码**的弹层真的开了它（漏一个就是一处泄露）；
 * 3. 关掉之后窗口恢复可截屏 —— 否则「给课表截图」这个正常需求会被误伤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SecureWindowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun hostWindowHasSecureFlag(): Boolean =
        composeTestRule.activity.window.attributes.flags and
            WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `开启时所在窗口禁止截屏`() {
        composeTestRule.setContent { SecureWindowEffect(enabled = true) }
        composeTestRule.waitForIdle()

        assertTrue("窗口应当带上 FLAG_SECURE", hostWindowHasSecureFlag())
    }

    @Test
    fun `关闭时所在窗口恢复可截屏`() {
        composeTestRule.setContent { SecureWindowEffect(enabled = false) }
        composeTestRule.waitForIdle()

        assertFalse("不该拦截图", hostWindowHasSecureFlag())
    }

    @Test
    fun `绑定浙大账号的弹层挂着时禁止截屏`() {
        composeTestRule.setContent {
            QuickZjuLoginDialog(
                onConfirm = { _, _ -> },
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "这个弹层里有密码框，挂着期间必须禁止截屏",
            hostWindowHasSecureFlag()
        )
    }

    @Test
    fun `教务云端账号弹层挂着时禁止截屏`() {
        composeTestRule.setContent {
            CloudAccountDialog(
                boundStudentId = null,
                isRememberCredentials = false,
                lastSyncTimestamp = null,
                lastSyncAttemptTime = null,
                lastSyncFailureKind = null,
                isSyncing = false,
                zjuTermOptions = emptyList(),
                syncFailure = null,
                errorMessage = null,
                onLoadTerms = {},
                onSyncWithCredentials = { _, _, _, _ -> },
                onQuickSync = {},
                onClearAccount = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "这个弹层里有密码框，挂着期间必须禁止截屏",
            hostWindowHasSecureFlag()
        )
    }
}
