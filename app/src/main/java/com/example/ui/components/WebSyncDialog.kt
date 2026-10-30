package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.sync.web.PairingStore
import com.example.data.sync.web.SyncDirection
import com.example.data.sync.web.WebSyncService
import kotlinx.coroutines.delay

/**
 * 「网页同步」弹层。
 *
 * ## 配对只有一条链路
 *
 * 手机开开关 → 复制连接链接 → 电脑粘贴地址点「连接」→ 电脑发一条**带 DH 公钥**
 * 的配对请求 → 手机弹确认卡 → 核对 6 位码点「允许」。
 *
 * 不再有第二条路：手机不再生成地址图片，电脑也不用摄像头。
 * 每条旁路都要求用户在另一台设备上再做一次「对准 / 找角度 / 授摄像头权限」，
 * 而真正决定安全性的从来不是「码怎么搬过去」。
 *
 * ## 6 位配对码和来源地址为什么仍然重要
 *
 * - **6 位码**（`H(A‖B)`）绑定的是**双方公钥**：中间人换掉任何一把公钥，
 *   两端算出来的码就对不上。它是用户唯一能亲手做的一次端到端校验。
 * - **来源地址**（`ip:port`）是服务端从 TCP 连接上读出来的，客户端伪造不了；
 *   设备名谁都能写成「我的电脑」，地址不能。
 *
 * ## 为什么手机不再生成地址图片
 *
 * 图片方案要求电脑有摄像头、用户点一次摄像头权限，并且把「配对」和
 * 「搬地址」两件事混在一起。复制粘贴两个动作就够了，而且从 v3.2 起
 * 电脑本来就要主动发请求 —— 一张地址图片只会多出一条需要维护的旁路。
 */
@Composable
fun WebSyncDialog(
    running: Boolean,
    addresses: List<String>,
    port: Int,
    pairedDevices: List<PairingStore.PairedDevice>,
    /**
     * **正在等确认的配对请求**：可能不止一条，**一条一张卡**。
     *
     * 每条都带**服务端观察到的来源地址** —— 那是客户端伪造不了的，
     * 比它自己报的设备名可靠。
     */
    pendingPairings: List<WebSyncService.PendingPairing> = emptyList(),
    /** 设了同步密码没有。 */
    hasPasscode: Boolean,
    /** 本机最新的一次编辑时间。 */
    localLatestEditAt: Long,
    /** 网页端最近一次同步过来的快照的最新编辑时间。 */
    remoteLatestEditAt: Long,
    onToggleService: (Boolean) -> Unit,
    /** 批准某一条（参数是它的 requestId）。 */
    onApprove: (String) -> Unit,
    /** 拒绝某一条（参数是它的 requestId）。 */
    onDeny: (String) -> Unit,
    onRevoke: (String) -> Unit,
    /** 让网页端按指定方向同步一次。 */
    onRequestSync: (SyncDirection) -> Unit,
    /** 设 / 改同步密码。空串 = 取消。 */
    onSetPasscode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passcodeInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val primaryUrl = remember(addresses, port) {
        addresses.firstOrNull()?.let { "http://$it:$port/" }
    }

    // 「已复制」只亮 1.6 秒 —— 够用户看见，又不至于一直占着按钮文案。
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
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
                            "允许其他设备连接",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (running) "已开启" else "已关闭",
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

                if (pendingPairings.isNotEmpty()) {
                    // ── 待确认的配对请求：一条一张卡 ────────────────
                    //
                    // **不用 LazyColumn**：外层已经是可滚动的 Column，
                    // 里面再套一个滚动容器，两层滚动手势会互相打架。
                    //
                    // 一条一条列出来是有意为之：后来的请求**不再顶掉**先来的，
                    // 真电脑的和陌生设备会同时出现，用户才看得见「怎么来了两个」。
                    Column(modifier = Modifier.fillMaxWidth()) {
                        pendingPairings.forEachIndexed { index, pending ->
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            PendingPairingCard(
                                pending = pending,
                                index = index,
                                onApprove = onApprove,
                                onDeny = onDeny,
                            )
                        }
                    }
                }

                if (running) {
                    // ── 连接电脑 ──────────────────────────────────────
                    SectionLabel("连接电脑")
                    if (addresses.isEmpty()) {
                        Text(
                            "没找到可用地址，请确认手机已连接 Wi-Fi 后重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        // 主地址：只读展示第一个，不点它。
                        primaryUrl?.let { url ->
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
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(url))
                                    copied = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("web_sync_copy_link"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (copied) "已复制" else "复制连接链接")
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "在电脑上打开 MiuList 网页端，把链接粘贴到「手机地址」；电脑发出请求后，这里会出现确认卡。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (addresses.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "其他地址：",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                addresses.drop(1).forEachIndexed { offset, address ->
                                    val url = "http://$address:$port/"
                                    Text(
                                        text = address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .testTag("web_sync_copy_alt_${offset + 1}")
                                            .clickable {
                                                clipboard.setText(AnnotatedString(url))
                                                copied = true
                                            },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                }

                // ── 已连接的设备 ─────────────────────────────────────
                if (pairedDevices.isNotEmpty()) {
                    SectionLabel("已连接的设备")
                    pairedDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Computer,
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

                if (running && pairedDevices.isNotEmpty()) {
                    // ── 两端各自改到什么时候 ──────────────────────────
                    // 自动同步只在「没有冲突」时进行；一旦有冲突，
                    // 用户要靠这两个时间判断该往哪边同步 —— 所以它们摆在一起。
                    SectionLabel("同步")
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
                    Spacer(Modifier.height(6.dp))
                    DirectionButton(
                        title = "以手机为准",
                        desc = "网页端的待办和课表都会被手机上的覆盖",
                        onClick = { onRequestSync(SyncDirection.PULL) },
                    )
                    Spacer(Modifier.height(6.dp))
                    DirectionButton(
                        title = "以网页端为准",
                        desc = "待办会被网页端的覆盖；课表仍以手机为准",
                        onClick = { onRequestSync(SyncDirection.PUSH) },
                    )
                    Spacer(Modifier.height(6.dp))
                    DirectionButton(
                        title = "合并两边",
                        desc = "两边的待办改动都保留",
                        onClick = { onRequestSync(SyncDirection.MERGE) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "没冲突时会自动同步；也可以指定方向。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 同步密码 ────────────────────────────────────────
                // 它会混进配对密钥里：**光有共享密钥也解不开**。
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
                        "配对时网页端要填同一个密码；改过密码的设备要重新配对。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/**
 * 一条待批准的配对请求 —— **一条一张卡**。
 *
 * ## 这张卡上先看哪里
 *
 * 1. **来源地址**（主标识）：手机自己从网络上观察到的地址，**对面伪造不了**。
 *    设备名谁都能自报成「我的电脑」，这个地址不能 —— 所以它排在最上面。
 * 2. **配对码**：跟电脑上显示的那 6 位一致才点允许；对不上就是有人中间换过公钥。
 * 3. **设备名**（副信息）：对方自报的，**只能当参考**。
 */
@Composable
private fun PendingPairingCard(
    pending: WebSyncService.PendingPairing,
    /** 这张卡是列表里的第几张 —— 用来给 testTag 加后缀。 */
    index: Int,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    // **第一张卡沿用原来的 tag**（`web_sync_approve` / `web_sync_deny`），
    // 其余的加序号后缀 —— 语义树里不能出现两个一样的 tag。
    val tagSuffix = if (index == 0) "" else "_$index"
    val source = pending.sourceAddress.trim()
    val sourceKnown = source.isNotEmpty()
    val deviceName = pending.deviceName.trim()
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
        // 标题**不带设备名** —— 它是对方自报的，让它当标题等于把不可信的东西摆最显眼的位置。
        Text(
            "有一台设备请求连接",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "允许后，它能查看并修改你的待办、课程与考试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 来源地址（主标识） ──────────────────────────────────
        Spacer(Modifier.height(10.dp))
        Text(
            "来源地址",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (sourceKnown) source else "未知地址",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("web_sync_source$tagSuffix"),
        )
        Text(
            if (sourceKnown) "确认这是你眼前的设备。" else "如果不是你发起的，请拒绝。",
            style = MaterialTheme.typography.labelSmall,
            color = if (sourceKnown) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        // ── 配对码 ─────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "配对码",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                pending.pairCode,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier.testTag("web_sync_pair_code$tagSuffix"),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // 这个码是双方公钥的哈希（见 [com.example.data.sync.web.WebDh.pairCode]）——
                // 有人在中间把公钥换掉的话，两边显示的码就对不上，用户一眼能看出来。
                "和电脑上显示的 6 位码一致再点允许。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 设备名（副信息，不可信） ───────────────────────────
        Spacer(Modifier.height(10.dp))
        Text(
            deviceName.ifBlank { "对方没有提供设备名" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "设备名由对方填写，只能参考。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onApprove(pending.requestId) },
                modifier = Modifier.testTag("web_sync_approve$tagSuffix"),
            ) {
                Text("允许")
            }
            // 「拒绝」是次要动作，但它**必须看得清** ——
            // 默认的 OutlinedButton 用主题绿写字，落在浅绿卡片上几乎读不出来。
            // 这里换成次级墨色，对比度够了才谈得上「用户看清了再决定」。
            OutlinedButton(
                onClick = { onDeny(pending.requestId) },
                modifier = Modifier.testTag("web_sync_deny$tagSuffix"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
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
 * 当年只写「MM-DD HH:mm」，跨年才补上年份。
 */
private fun formatEditTime(millis: Long): String {
    if (millis <= 0) return "—"
    val zone = com.example.data.calendar.AppZone
    val thisYear = zone.format("yyyy", System.currentTimeMillis())
    val year = zone.format("yyyy", millis)
    return if (year == thisYear) zone.format("MM-dd HH:mm", millis) else zone.format("yyyy-MM-dd HH:mm", millis)
}
