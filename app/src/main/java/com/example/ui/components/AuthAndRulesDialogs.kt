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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CategoryRule

@Composable
fun WeekPickerDialog(
    /** 今天所在的周。格子上附淡绿色底色，让用户一眼找到现在。 */
    currentWeek: Int,
    /** 正在浏览的周。高亮边框指向它——翻到第 3 周时高亮不该还留在第 5 周。 */
    selectedWeek: Int,
    /** 学期总周数。写死 20 会让 16 周的学期凭空多出 4 个空格子。 */
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onSelectWeek: (Int) -> Unit
) {
    AlertDialog(
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
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "切换周次",
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "课表与待办会同步更新：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                (1..totalWeeks.coerceAtLeast(1)).chunked(4).forEach { rowWeeks ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowWeeks.forEach { w ->
                            val isSelected = selectedWeek == w
                            val isCurrent = w == currentWeek

                            // 本周附淡绿色（primaryContainer 为主题极淡抹茶绿）
                            val backgroundColor = when {
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                isSelected -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            }
                            val borderColor = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.outline
                            }
                            val textColor = when {
                                isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                                isSelected -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            val textWeight = if (isSelected || isCurrent) FontWeight.Bold else FontWeight.Medium

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(backgroundColor)
                                    .border(
                                        if (isSelected) 1.5.dp else 1.dp,
                                        borderColor,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        onSelectWeek(w)
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "第${w}周",
                                    fontSize = 12.sp,
                                    fontWeight = textWeight,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("关闭", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    )
}

/**
 * 「课程联动规则」= **按类别批量设置**该类别下所有课程的待办开关。
 *
 * 改动先落在本地暂存上，点「应用」才落库，点「取消」全部丢弃。
 * 早期版本是点一下勾就存，但那时保存只推「相对上一版变了的那几格」；
 * 改成全量刷之后（每个类别对其下所有课程都有权威）即时落库就意味着
 * 「勾第一格就把 9 门课的三个开关全盖掉」，所以暂存与取消是这条语义的前置条件，
 * 不是额外的贴心设计。
 *
 * 每张类别卡上标出它影响几门课：批量设置改的是别人已经配好的课，
 * 不显示波及范围，用户是在不知情的情况下改动整批设置。
 */
@Composable
fun CategoryRulesDialog(
    rules: List<CategoryRule>,
    /** 类别 → 该类别下的课程门数，用来显示批量设置的波及范围。 */
    courseCountByCategory: Map<String, Int> = emptyMap(),
    onDismiss: () -> Unit,
    onApply: (List<CategoryRule>) -> Unit
) {
    val scrollState = rememberScrollState()
    // 弹层每次打开都是新的组合（调用方按 isCategoryRulesDialogOpen 条件渲染），
    // 所以这里记得的就是本次打开时的原始值，不需要额外的重置逻辑。
    var staged by remember { mutableStateOf(rules) }

    val changed = staged.filter { next ->
        rules.firstOrNull { it.category == next.category } != next
    }
    val affectedCourses = changed.sumOf { courseCountByCategory[it.category] ?: 0 }

    fun toggle(category: String, apply: (CategoryRule) -> CategoryRule) {
        staged = staged.map { if (it.category == category) apply(it) else it }
    }

    AlertDialog(
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
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "课程联动规则",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "按类别批量设置该类别下所有课程的待办开关：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                staged.forEach { rule ->
                    val courseCount = courseCountByCategory[rule.category] ?: 0
                    val dirty = changed.any { it.category == rule.category }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                if (dirty) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rule.category,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // 波及范围写在类别名旁边：这是「批量设置」，改的不是这一行，
                            // 而是这一类下的每一门课（含用户已单独调过的那几门）。
                            Text(
                                text = "$courseCount 门课程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        SwitchRow(
                            label = "课前预习",
                            checked = rule.autoPreStudy,
                            onChange = { on -> toggle(rule.category) { it.copy(autoPreStudy = on) } }
                        )
                        SwitchRow(
                            label = "课后作业",
                            checked = rule.autoPostHomework,
                            onChange = { on -> toggle(rule.category) { it.copy(autoPostHomework = on) } }
                        )
                        SwitchRow(
                            label = "实验报告",
                            checked = rule.autoLabReport,
                            onChange = { on -> toggle(rule.category) { it.copy(autoLabReport = on) } }
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                // 没改动时不让点：让「应用」永远代表一次真实写入，
                // 也免得用户以为按了没反应。
                enabled = changed.isNotEmpty(),
                onClick = {
                    onApply(changed)
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (affectedCourses > 0) "应用到 $affectedCourses 门课程" else "应用",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    )
}

/** 一行「标签 + 勾选框」，整行是落点。三个开关行长得一样，抽出来免得写三遍。 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
