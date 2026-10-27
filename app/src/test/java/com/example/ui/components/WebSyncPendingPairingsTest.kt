package com.example.ui.components

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.example.data.sync.web.WebSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 配对确认卡（协议 v3.2 的授权模型）。
 *
 * ## 为什么这些用例重要
 *
 * 授权发生在**手机上**：局域网里任何设备都能构造出一条配对请求，
 * 所以「谁在请求连接」这件事必须由**手机自己**来回答，
 * 而且回答它的依据必须是**客户端伪造不了**的东西。
 *
 * 原来那张卡上写的是设备名 —— 谁都能自报成「我的电脑」，
 * 用户拿它认不出对面是谁，于是这张卡形同虚设。
 *
 * v3.2 改成：卡上最上面是**服务端观察到的来源地址**，
 * 设备名降级成「只能当参考」的副信息。
 *
 * 还有一条：**后来的请求不能顶掉先来的**。
 * 单槽的时候，攻击者发一条深链就能把你电脑上那条真的确认卡换掉，
 * 手机上只剩一张卡，用户根本看不出换过。
 *
 * ## 为什么用 `useUnmergedTree = true`
 *
 * 弹层面板的根节点是 `mergeDescendants = true` 的，
 * 于是面板里那些**不是**合并节点的 `Text` 会被吸收进根节点，
 * 而 `testTag` **不参与合并** —— 合并树里按 tag 找不到它们。
 *
 * 这不是无障碍缺陷：这些文本仍然在根节点的 `Text` 属性里，
 * 读屏用户照样听得到（「读屏仍能听到来源地址」那条用例钉的就是这件事）。
 * 但测试要按 tag 精确找到某个 `Text`，就只能走非合并树。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSyncPendingPairingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun pending(
        requestId: String,
        deviceName: String = "我的电脑",
        pairCode: String = "GPP7WE",
        sourceAddress: String = "192.168.1.5:51234",
    ) = WebSyncService.PendingPairing(
        requestId = requestId,
        secretBase64 = null,
        deviceName = deviceName,
        pairCode = pairCode,
        sourceAddress = sourceAddress,
    )

    private fun dialog(
        pendingList: List<WebSyncService.PendingPairing>,
        onApprove: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            WebSyncDialog(
                running = true,
                addresses = listOf("192.168.1.5"),
                port = 8765,
                pairedDevices = emptyList(),
                pendingPairings = pendingList,
                hasPasscode = false,
                localLatestEditAt = 0L,
                remoteLatestEditAt = 0L,
                onToggleService = {},
                onApprove = onApprove,
                onDeny = {},
                onRevoke = {},
                onRequestSync = {},
                onSetPasscode = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `两条待确认的请求会显示两张卡 - 后来的不能顶掉先来的`() {
        dialog(listOf(pending("req-a"), pending("req-b")))

        composeTestRule.onNodeWithTag("web_sync_source", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("web_sync_source_1", useUnmergedTree = true).assertExists()
        // 两张卡各有各的按钮 —— 语义树里不能出现两个一样的 tag。
        composeTestRule.onNodeWithTag("web_sync_approve", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("web_sync_approve_1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `来源地址显示在最上面 - 设备名只是参考`() {
        dialog(listOf(pending("req-a", deviceName = "我的电脑", sourceAddress = "192.168.1.5:51234")))

        composeTestRule.onNodeWithTag("web_sync_source", useUnmergedTree = true)
            .assertTextEquals("192.168.1.5:51234")
        // 设备名不再被当成「是谁」的答案。
        composeTestRule.onNodeWithText("只能当参考", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `来源未知时要给出警告 - 扫码深链那条路拿不到地址`() {
        dialog(listOf(pending("req-a", sourceAddress = "")))

        composeTestRule.onNodeWithTag("web_sync_source", useUnmergedTree = true)
            .assertTextEquals("来源未知")
        composeTestRule.onNodeWithText("没带来源地址", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `读屏仍然听得到来源地址 - 面板会合并语义但文本没丢`() {
        dialog(listOf(pending("req-a", sourceAddress = "192.168.1.5:51234")))

        // **合并树**（useUnmergedTree = false，默认）才是读屏用户走的那棵树：
        // 面板根节点合并了所有 Text，所以来源地址与配对码都在它的 Text 列表里。
        assertTrue(
            "读屏用户必须能听到来源地址",
            composeTestRule.onAllNodesWithText("192.168.1.5:51234")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "配对码也要读得到",
            composeTestRule.onAllNodesWithText("GPP7WE")
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `点允许时带的是那一条的 requestId - 不是「当前唯一那条」`() {
        var approved: String? = null
        dialog(listOf(pending("req-a"), pending("req-b")), onApprove = { approved = it })

        composeTestRule.onNodeWithTag("web_sync_approve_1", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals("req-b", approved)
    }
}
