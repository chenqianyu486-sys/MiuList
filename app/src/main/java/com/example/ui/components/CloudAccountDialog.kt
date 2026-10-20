package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.calendar.AppZone
import com.example.data.source.zju.ZjuFailureKind
import com.example.data.sync.SyncFailure
import com.example.ui.theme.AlertRedText
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.ZjuTermOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudAccountDialog(
    boundStudentId: String?,
    isRememberCredentials: Boolean,
    lastSyncTimestamp: Long?,
    lastSyncAttemptTime: Long?,
    lastSyncFailureKind: ZjuFailureKind?,
    isSyncing: Boolean,
    zjuTermOptions: List<ZjuTermOption>,
    syncFailure: SyncFailure?,
    errorMessage: String?,
    onLoadTerms: () -> Unit,
    onSyncWithCredentials: (studentId: String, password: String, option: ZjuTermOption, remember: Boolean) -> Unit,
    onQuickSync: () -> Unit,
    onClearAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        onLoadTerms()
    }

    var isEditing by remember(boundStudentId) {
        mutableStateOf(boundStudentId.isNullOrBlank())
    }
    var studentIdInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var rememberCredentials by remember { mutableStateOf(true) }
    var selectedTermKey by remember { mutableStateOf("") }

    // 凭据类的失败直接把表单推到眼前：用户已经停在这个页面上了，
    // 还要自己再点一次「修改账号/学期」才能修，等于把「给出解法」与「提供入口」拆成了两步。
    LaunchedEffect(syncFailure?.kind) {
        if (syncFailure?.kind?.needsCredentials == true) isEditing = true
    }

    val selectedOption = zjuTermOptions.firstOrNull { it.key == selectedTermKey }
        ?: zjuTermOptions.firstOrNull { it.term == 0 }
        ?: zjuTermOptions.firstOrNull()

    // 同步时间是给人看的墙上时间，钉北京时区（见 AppZone）。
    val timeFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            timeZone = AppZone.TIME_ZONE
        }
    }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "教务云端同步与本地缓存",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isSyncing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                            .padding(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "正在连接浙大教务系统拉取最新课表与校历…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                syncFailure?.let { failure ->
                    SyncFailureCard(
                        failure = failure,
                        onRetry = onQuickSync,
                        onEditCredentials = { isEditing = true },
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // 导入源那条链路（文件 / ZJU 源）的老式裸红字。与同步失败卡并存，
                // 因为两者互斥触发，但不该共用一个「错误色文本」的位置。
                errorMessage?.takeIf { syncFailure == null }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 状态卡片
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (boundStudentId != null) Icons.Outlined.CloudDone else Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            tint = if (boundStudentId != null) MaterialTheme.colorScheme.primary else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (boundStudentId != null) "已绑定学号：$boundStudentId" else "尚未绑定教务账号",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "当前状态：优先读取本地缓存（断网立即可用，持久保存）",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    lastSyncTimestamp?.let { ts ->
                        Text(
                            text = "上次成功同步：${timeFormat.format(Date(ts))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }

                    // 失败的那次要单独占一行。
                    // 只留上面那条「上次成功」的话，刚失败过的用户看到的是一条
                    // 看上去很健康的旧时间戳，完全看不出「刚才那次没成」——
                    // 于是在他眼里同步这件事仍然是正常的。
                    val failedAt = lastSyncAttemptTime
                    if (lastSyncFailureKind != null && failedAt != null) {
                        Text(
                            text = "最近一次尝试（${timeFormat.format(Date(failedAt))}）失败：" +
                                lastSyncFailureKind.title,
                            style = MaterialTheme.typography.labelSmall,
                            // 文字档红，不是图标档的 AlertRed：这一行只有 11sp，
                            // 实测 #EF4444 压在这里只有 3.4:1，过不了 AA（详见 AlertRedText）。
                            color = AlertRedText,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (!isEditing && boundStudentId != null) {
                    // 已绑定展示操作
                    Button(
                        onClick = onQuickSync,
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag("quick_cloud_sync_button")
                    ) {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSyncing) "同步中…" else "立即从云端同步更新",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isEditing = true },
                            enabled = !isSyncing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("修改账号/学期", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onClearAccount,
                            enabled = !isSyncing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("清除已存凭据", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    // 输入表单
                    OutlinedTextField(
                        value = studentIdInput,
                        onValueChange = { studentIdInput = it },
                        label = { Text("学号", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth().testTag("cloud_student_id_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("密码", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !isSyncing,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("cloud_password_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text(
                        text = "选择同步学期",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(zjuTermOptions) { option ->
                            val isSelected = option == selectedOption
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else ChipSurfaceNeutral
                                    )
                                    .clickable { selectedTermKey = option.key }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = option.label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberCredentials = !rememberCredentials }
                    ) {
                        Checkbox(
                            checked = rememberCredentials,
                            onCheckedChange = { rememberCredentials = it },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "安全记住学号密码",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                // 原来写「不泄露到代码库」：代码库是开发者的事，
                                // 用户要听的是「这台手机上会不会留明文」。
                                text = "采用 Android KeyStore 硬件 AES-256 加密存储，本机不留明文",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    Button(
                        onClick = {
                            selectedOption?.let {
                                onSyncWithCredentials(studentIdInput, passwordInput, it, rememberCredentials)
                            }
                        },
                        enabled = !isSyncing && selectedOption != null &&
                            studentIdInput.isNotBlank() && passwordInput.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag("cloud_fetch_and_cache_button")
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSyncing) "正在拉取并落盘缓存…" else "拉取云端数据并落盘本地缓存",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (boundStudentId != null) {
                        TextButton(
                            onClick = { isEditing = false },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("返回绑定状态")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
