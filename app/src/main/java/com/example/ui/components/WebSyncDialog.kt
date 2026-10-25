package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.sync.web.PairingStore
import com.example.data.sync.web.QrCodes
import com.example.data.sync.web.SyncDirection

/**
 * 「网页同步」弹层。
 *
 * ## 这一屏要回答三个问题
 *
 * 1. **服务开着吗？** —— 一个开关，开着的时候下面才有地址和二维码。
 * 2. **网页端在哪个地址？** —— 局域网地址 + 扫码直接打开。
 * 3. **谁连上了？** —— 已配对的设备列表，可以随时撤销。
 *
 * ## 为什么二维码是「打开网页」而不是「配对」
 *
 * 配对是**网页端发起**的（它先拿一个配对码，再把配对码画成二维码让手机扫），
 * 所以手机这边看到的二维码是**网页地址**。用户拿另一台设备扫它，
 * 就能打开网页端；网页端再把配对请求发回来，手机确认之后就连上了。
 *
 * 这和「手机显示配对二维码」的直觉是反的，但反过来的话，
 * **扫的那台设备就是发起方**——网页端没法主动去扫，
 * 而让用户在电脑上装一个扫码器显然更麻烦。
 */
@Composable
fun WebSyncDialog(
    running: Boolean,
    addresses: List<String>,
    port: Int,
    pairedDevices: List<PairingStore.PairedDevice>,
    /** 有一台设备正在等确认。 */
    pendingPairing: Boolean,
    /** 设了同步密码没有。 */
    hasPasscode: Boolean,
    /** 本机最新的一次编辑时间。 */
    localLatestEditAt: Long,
    /** 网页端最近一次同步过来的快照的最新编辑时间。 */
    remoteLatestEditAt: Long,
    onToggleService: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onRevoke: (String) -> Unit,
    /** 让网页端按指定方向同步一次。 */
    onRequestSync: (SyncDirection) -> Unit,
    /** 设 / 改同步密码。空串 = 取消。 */
    onSetPasscode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passcodeInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val primaryUrl = remember(addresses, port) {
        addresses.firstOrNull()?.let { "http://$it:$port/" }
    }
    val qrBitmap = remember(primaryUrl) {
        primaryUrl?.let { QrCodes.bitmap(it, 360) }
    }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = { Text("网页同步", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── 开关 ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "局域网服务",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (running) "已开启，其他设备可以连进来" else "已关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = onToggleService,
                        modifier = Modifier.testTag("web_sync_switch"),
                    )
                }

                if (pendingPairing) {
                    PendingPairingCard(onApprove = onApprove, onDeny = onDeny)
                }

                if (running) {
                    // ── 地址 + 二维码（放最前面） ────────────────────
                    //
                    // 顺序是有讲究的：**二维码是这一屏最该被看见的东西**，
                    // 放最后就永远在折叠线以下，用户得先滚一屏才找得到。
                    if (addresses.isEmpty()) {
                        Text(
                            "没找到局域网地址。请确认手机连着 Wi-Fi，或者先关掉 VPN / 代理再试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        SectionLabel("在同一个 Wi-Fi 下打开")
                        addresses.forEach { address ->
                            val url = "http://$address:$port/"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    url,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "复制地址",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .testTag("web_sync_copy")
                                        .clickable { clipboard.setText(AnnotatedString(url)) },
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        qrBitmap?.let { bmp ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White)
                                        .padding(8.dp),
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "网页端二维码",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "扫码打开网页端",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ── 两端各自改到什么时候 ──────────────────────────
                // 自动同步只在「没有冲突」时进行；一旦有冲突，
                // 用户要靠这两个时间判断该往哪边同步 —— 所以它们摆在一起。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TimeCard(
                        label = "本机最后编辑",
                        value = formatEditTime(localLatestEditAt),
                        modifier = Modifier.weight(1f),
                    )
                    TimeCard(
                        label = "网页端最后编辑",
                        value = if (remoteLatestEditAt > 0) formatEditTime(remoteLatestEditAt) else "还没同步过",
                        modifier = Modifier.weight(1f),
                    )
                }

                if (running) {
                    // ── 方向选择 ──────────────────────────────────────
                    SectionLabel("手动同步")
                    Text(
                        "两端都在线且没有冲突时会自动同步；下面三个是手动指定方向。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DirectionButton(
                        title = "把手机上的推给网页端",
                        desc = "网页端那份以手机为准",
                        onClick = { onRequestSync(SyncDirection.PULL) },
                    )
                    Spacer(Modifier.height(6.dp))
                    DirectionButton(
                        title = "从网页端拉过来",
                        desc = "手机这份以网页端为准",
                        onClick = { onRequestSync(SyncDirection.PUSH) },
                    )
                    Spacer(Modifier.height(6.dp))
                    DirectionButton(
                        title = "合并两边",
                        desc = "两边的改动都保留",
                        onClick = { onRequestSync(SyncDirection.MERGE) },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (running) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "网页端第一次打开会显示一张配对码，扫一下就自动连上；之后两边的改动会实时同步。" +
                            "配对过的设备不用再扫。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 同步密码 ────────────────────────────────────────
                // 它会混进配对密钥里：**二维码被别人拍到，光有码也解不开**。
                // 配对时网页端要填同一个密码；改了密码，已配对的设备要重新配。
                SectionLabel("同步密码")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = passcodeInput,
                        // **只收 4 位数字**：好敲好念。
                        // 代价是 10⁴ 种 —— 拿到配对密钥的人能离线爆破它，
                        // 它挡的是「旁边有人瞄到屏幕」，不是密码学第二因子。
                        onValueChange = { raw ->
                            passcodeInput = raw.filter { it.isDigit() }.take(4)
                        },
                        label = { Text(if (hasPasscode) "已设置（留空则不改）" else "未设置（4 位数字）") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onSetPasscode(passcodeInput) },
                        enabled = passcodeInput.isNotBlank(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (hasPasscode) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "网页端配对时要填同一个密码。改密码后已配对的设备需要重新扫码。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 已配对设备 ─────────────────────────────────────
                if (pairedDevices.isNotEmpty()) {
                    SectionLabel("已配对的设备")
                    pairedDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {                            Icon(
                                imageVector = Icons.Outlined.QrCode2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.deviceName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "配对于 " + formatPairedAt(device.pairedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = { onRevoke(device.deviceId) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 0.dp,
                                ),
                            ) {
                                Text("移除", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.testTag("web_sync_done"),
            ) {
                Text("完成")
            }
        },
    )
}

/** 有设备在等确认时，插在最上面的一块。 */
@Composable
private fun PendingPairingCard(onApprove: () -> Unit, onDeny: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
    ) {
        Text(
            "有一台设备请求连接",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "确认之后它就能看到你的待办、课程和考试，并且能改待办。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApprove, modifier = Modifier.testTag("web_sync_approve")) {
                Text("允许")
            }
            OutlinedButton(onClick = onDeny, modifier = Modifier.testTag("web_sync_deny")) {
                Text("拒绝")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 「本机最后编辑 / 12-25 21:13」这样的一格。 */
@Composable
private fun TimeCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一个同步方向。 */
@Composable
private fun DirectionButton(title: String, desc: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // **内容色改成 onSurface**：OutlinedButton 默认用 primary，
        // 三个按钮的标题全变成链接绿 —— 读起来像「点了会跳走」，
        // 而它们其实是三个并列的动作。
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 14.dp,
            vertical = 10.dp,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「2026-10-25 20:31」。用 App 时区 —— 配对记录是校园里的时间事实。 */
private fun formatPairedAt(millis: Long): String =
    com.example.data.calendar.AppZone.format("yyyy-MM-dd HH:mm", millis)

/**
 * 「最后编辑」用短一点的时间格式。
 *
 * 那一格只有约 180dp 宽，写全了「2025-09-23 12:00」会被截成「2025-09-23 …」——
 * **而截掉的正是分钟那一头**，那才是用户要看的东西。
 *
 * 当年��只写「MM-DD HH:mm」，跨年才补上年份。
 */
private fun formatEditTime(millis: Long): String {
    if (millis <= 0) return "—"
    val zone = com.example.data.calendar.AppZone
    val thisYear = zone.format("yyyy", System.currentTimeMillis())
    val year = zone.format("yyyy", millis)
    return if (year == thisYear) zone.format("MM-dd HH:mm", millis) else zone.format("yyyy-MM-dd HH:mm", millis)
}
