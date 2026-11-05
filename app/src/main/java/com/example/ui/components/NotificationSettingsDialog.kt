package com.example.ui.components

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.timetable.formatMinute
import com.example.notification.NotificationSettings
import com.example.ui.theme.AdaptiveDialogContent
import com.example.ui.theme.TextSecondary

/**
 * 本地提醒的设置弹层。
 *
 * ## 这一层只回答三个问题
 *
 * 1. **要不要提醒**（总开关）；
 * 2. **哪几类要提醒**（三个分组开关）；
 * 3. **提前多久 / 几点**（每一行右侧的当前值，点开才选）。
 *
 * 提前量不再把所有选项摊在面板上：那一排 chips 既抢注意力，又会在窄屏上
 * 被裁掉半个（「12 小…」）。现在每一行只显示**当前值**，点它才展开候选；
 * 一眼扫下去，看到的是一份「现在怎么设」的读数，而不是一堵按钮墙。
 *
 * ## 层级靠字号和留白，不靠描边
 *
 * 总开关 16sp、分组 14sp、说明 11.5sp；分组之间只用一条很淡的分隔线。
 * 关掉总开关时，三个分组整块收起 —— 此刻只有一个决定要做。
 *
 * ## 权限是**如实报告**，不是自动弹
 *
 * 系统通知权限与精确闹钟权限都可能被用户拒绝。这里只在**未授予时**显示一行
 * 安静的说明和一个「去开启」，不主动跳系统页面，也不用报错色去吓人。
 * 已经给了权限的人完全看不到这些行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsDialog(
    settings: NotificationSettings,
    onDismiss: () -> Unit,
    onMasterEnabledChange: (Boolean) -> Unit,
    onDailyEnabledChange: (Boolean) -> Unit,
    onDailyMinuteChange: (Int) -> Unit,
    onClassEnabledChange: (Boolean) -> Unit,
    onClassLeadChange: (Int) -> Unit,
    onTodoEnabledChange: (Boolean) -> Unit,
    onTodoLeadChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsAllowed by remember { mutableStateOf(canPostNotifications(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    // 从系统设置回来时重新读一次权限：用户可能刚给了权限，而弹层还开着。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = canPostNotifications(context)
                exactAlarmsAllowed = canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showTimePicker by remember { mutableStateOf(false) }
    // 同时只展开一组的候选：展开另一组时上一组自动收起，注意力不会被拉成两处。
    var expandedLeadPicker by remember { mutableStateOf<String?>(null) }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "通知提醒",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("notification_settings_content")
            ) {
                MasterToggleRow(
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        onMasterEnabledChange(enabled)
                        if (!enabled) expandedLeadPicker = null
                    }
                )

                AnimatedVisibility(
                    visible = settings.enabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )

                        ReminderToggleRow(
                            title = "每日汇总",
                            hint = "每天早上看一眼今天的安排",
                            checked = settings.dailyEnabled,
                            onCheckedChange = onDailyEnabledChange,
                            switchTestTag = "notification_daily_switch",
                            value = {
                                ValuePill(
                                    label = formatMinute(settings.dailyMinuteOfDay),
                                    testTag = "notification_daily_time_button",
                                    onClick = { showTimePicker = true }
                                )
                            }
                        )

                        ReminderToggleRow(
                            title = "上课与考试",
                            hint = "上课前提醒，考试提前 30 分钟",
                            checked = settings.classEnabled,
                            onCheckedChange = { enabled ->
                                onClassEnabledChange(enabled)
                                if (!enabled) expandedLeadPicker = null
                            },
                            switchTestTag = "notification_class_switch",
                            value = {
                                LeadValuePicker(
                                    valueLabel = "提前 ${leadLabel(settings.classLeadMinutes)}",
                                    options = NotificationSettings.CLASS_LEAD_OPTIONS.map {
                                        it to leadLabel(it)
                                    },
                                    selected = settings.classLeadMinutes,
                                    onSelect = onClassLeadChange,
                                    testTag = "notification_class_lead_button",
                                    expanded = expandedLeadPicker == "class",
                                    onExpandedChange = { open ->
                                        expandedLeadPicker = if (open) "class" else null
                                    }
                                )
                            }
                        )

                        ReminderToggleRow(
                            title = "待办截止",
                            hint = "完成的待办不再提醒",
                            checked = settings.todoEnabled,
                            onCheckedChange = { enabled ->
                                onTodoEnabledChange(enabled)
                                if (!enabled) expandedLeadPicker = null
                            },
                            switchTestTag = "notification_todo_switch",
                            value = {
                                LeadValuePicker(
                                    valueLabel = "提前 ${leadLabel(settings.todoLeadMinutes)}",
                                    options = NotificationSettings.TODO_LEAD_OPTIONS.map {
                                        it to leadLabel(it)
                                    },
                                    selected = settings.todoLeadMinutes,
                                    onSelect = onTodoLeadChange,
                                    testTag = "notification_todo_lead_button",
                                    expanded = expandedLeadPicker == "todo",
                                    onExpandedChange = { open ->
                                        expandedLeadPicker = if (open) "todo" else null
                                    }
                                )
                            }
                        )
                    }
                }

                // 通知权限没给时，先解决它；这时再谈「精确」没有意义。
                if (settings.enabled && !notificationsAllowed) {
                    Spacer(modifier = Modifier.height(14.dp))
                    QuietHint(
                        text = "通知权限未开启",
                        actionLabel = "去开启",
                        onAction = { openAppNotificationSettings(context) },
                        testTag = "notification_system_permission_hint"
                    )
                } else if (settings.enabled && !exactAlarmsAllowed) {
                    Spacer(modifier = Modifier.height(14.dp))
                    QuietHint(
                        text = "提醒可能晚到几分钟",
                        actionLabel = "去调整",
                        onAction = { openExactAlarmSettings(context) },
                        testTag = "notification_exact_alarm_hint"
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    // 这句话是这一整套功能与「云端推送」的分界线：
                    // 用户可以放心地把它当作本地提醒，而不是又一个会上传数据的服务。
                    text = "只在本机提醒，不上传你的课程和待办。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("notification_settings_done_button")
            ) {
                Text(
                    "完成",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    )

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = settings.dailyMinuteOfDay / 60,
            initialMinute = settings.dailyMinuteOfDay % 60,
            is24Hour = true
        )
        // 和待办编辑里的时间选择器同一套做法：嵌套层必须是独立窗口，
        // 它自带返回栈与遮罩，不会和外层 GlassOverlayHost 抢点击/返回键。
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AlignDialogWindowScrim()
            AdaptiveDialogContent {
                Surface(
                    shape = GlassDialogShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .widthIn(max = 400.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "每日汇总时间",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            TimePicker(state = timePickerState)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showTimePicker = false }) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    onDailyMinuteChange(
                                        timePickerState.hour * 60 + timePickerState.minute
                                    )
                                    showTimePicker = false
                                },
                                modifier = Modifier.testTag("notification_time_confirm_button")
                            ) {
                                Text("确定")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 总开关。字号比下面三个分组大一档，让「要不要提醒」先于「怎么提醒」被读到。
 */
@Composable
private fun MasterToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "允许提醒",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "关掉后不再收到提醒",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.testTag("notification_master_switch")
        )
    }
}

/**
 * 一个分组：第一行是标题与开关，第二行只在开启时放**一个**当前值。
 *
 * 不把当前值和开关塞进同一行：弹层内容列在窄屏上只有 ~190dp，
 * 「标题 + 提前 15 分钟 + 开关」会先把标题挤成一条竖线。
 * 值放在标题下面一层，既读得全，也天然说明它是这个分组的下级设置。
 */
@Composable
private fun ReminderToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String,
    value: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.testTag(switchTestTag)
            )
        }
        if (checked && value != null) {
            Spacer(modifier = Modifier.height(6.dp))
            value()
        }
    }
}

/** 当前值的 pill。右侧的小箭头是「还能改」的唯一记号。 */
@Composable
private fun ValuePill(
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 提前量的选择：点 pill 就地展开候选，选中即收起。
 *
 * 用**就地展开**而不是弹菜单：候选只有 4–5 个，展开一排就能看全，
 * 不需要再叠一层浮层；同时只展开当前这一组，用户的注意力不会被拉成两处。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeadValuePicker(
    valueLabel: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    testTag: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Column {
        ValuePill(
            label = valueLabel,
            testTag = testTag,
            onClick = { onExpandedChange(!expanded) }
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { (value, label) ->
                    OptionChip(
                        label = label,
                        selected = value == selected,
                        onClick = {
                            onSelect(value)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

/** 展开后的一颗候选。选中项用主题绿，其余保持中性。 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/**
 * 权限提示。刻意不用报错色：这不是故障，只是系统里还有一道开关没打开。
 * 一行浅底 + 一个动作，位置在三个分组之后、隐私说明之前。
 */
@Composable
private fun QuietHint(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(start = 12.dp, end = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
        )
        TextButton(onClick = onAction) {
            Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** 「15 分钟」「6 小时」「1 天」——提前量的唯一写法。 */
private fun leadLabel(minutes: Int): String = when {
    minutes >= 1440 -> "${minutes / 1440} 天"
    minutes >= 60 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}

private fun canPostNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
}

private fun openAppNotificationSettings(context: Context) {
    val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (runCatching { context.startActivity(appIntent) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}
