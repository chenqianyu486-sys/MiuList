package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「复制连接链接」按钮（2026-10-27 加的）。
 *
 * ## 为什么它重要
 *
 * 配对只有一条链路，地址只能靠复制粘贴搬过去。
 * 手机上那一下「复制链接」，复制出来的**必须正好是电脑端要的那个串**：
 * `http://<ip>:<端口>/`。
 *
 * 差一个字符的后果不是「连不上」这么客气：地址归一化会把
 * `192.168.1.5:8765` 补成 `http://192.168.1.5:8765`（能用），
 * 但把 `http://192.168.1.5:8765` 少抄一个数字，得到的就**是另一个地址**，
 * 表现为「地址没问题，就是连不上」。
 *
 * 所以这里钉住的是**剪贴板里的确切内容**，而不是「按钮在不在」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSyncCopyLinkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun dialog(running: Boolean, addresses: List<String>) {
        composeTestRule.setContent {
            WebSyncDialog(
                running = running,
                addresses = addresses,
                port = 8765,
                pairedDevices = emptyList(),
                pendingPairings = emptyList(),
                hasPasscode = false,
                localLatestEditAt = 0L,
                remoteLatestEditAt = 0L,
                onToggleService = {},
                onApprove = {},
                onDeny = {},
                onRevoke = {},
                onRequestSync = {},
                onSetPasscode = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun copy_link_button_copies_exactly_what_the_web_side_needs() {
        dialog(running = true, addresses = listOf("192.168.1.5"))

        clickCopyLink()

        assertEquals("http://192.168.1.5:8765/", clipboardText())
    }

    @Test
    fun copy_link_button_is_not_there_while_the_service_is_off() {
        // 服务没开就没有地址 —— 给一个「复制连接链接」按钮是骗人。
        dialog(running = false, addresses = emptyList())

        composeTestRule.onNodeWithTag("web_sync_copy_link").assertDoesNotExist()
    }

    @Test
    fun copy_link_button_copies_the_first_address_when_there_are_several() {
        // 虚拟网卡 / VPN 会让手机吐出好几个地址。
        // 电脑端连的是**第一个**（复制按钮用的就是这个地址），
        // 复制别的那个，用户就会拿着一个连不上的地址去粘贴。
        dialog(running = true, addresses = listOf("192.168.1.5", "10.0.2.16"))

        clickCopyLink()

        assertEquals("http://192.168.1.5:8765/", clipboardText())
    }

    /**
     * 触发按钮。
     *
     * 用**语义动作**而不是 `performClick`：后者是真的派一次触摸事件，
     * 事件落在节点中心 —— 节点在视口外时（地址多两行就把按钮顶下去了）
     * 这次触摸谁也收不到，于是断言挂在「剪贴板没变」上，
     * 看起来像是复制逻辑坏了，其实是点击没落到按钮上。
     */
    private fun clickCopyLink() {
        composeTestRule.onNodeWithTag("web_sync_copy_link")
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun clipboardText(): String? {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        val clip = manager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }
}
