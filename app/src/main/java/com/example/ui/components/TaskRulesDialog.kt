package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.generation.CourseRuleSpec
import com.example.data.generation.GenerationRuleResolver
import com.example.data.generation.TaskCadence
import com.example.data.local.CourseEntity
import com.example.data.model.TaskType
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.GlassEdgeBottom
import com.example.ui.theme.LimeGreenOnPrimary
import com.example.ui.theme.LimeGreenPrimary
import com.example.ui.theme.TextMuted

/**
 * 「课程 × 类型」的待办生成规则。
 *
 * 与类别规则（[CategoryRulesDialog]）分两层：那一层是默认值，这一层是具体到某门课的覆盖。
 * 文案一律两三个字一档 —— 节奏只写「每次课 / 每周」，截止只写「提前 5 分钟」「6 天后」。
 *
 * 开关（预习/作业/实验 开不开）**不在这里另存一列**，而是直接写穿
 * `CourseEntity.auto*`：设置页课程卡上本来就显示着这组开关，
 * 两处各存一份必然出现一处忘了同步。
 */
@Composable
fun TaskRulesDialog(
    courses: List<CourseEntity>,
    taskRules: Map<Long, Map<TaskType, CourseRuleSpec>>,
    onDismiss: () -> Unit,
    onSaveRule: (courseId: Long, taskType: TaskType, spec: CourseRuleSpec) -> Unit,
    onToggleEnabled: (courseId: Long, taskType: TaskType, enabled: Boolean) -> Unit
) {
    var selectedCourseId by remember(courses) {
        mutableStateOf(courses.firstOrNull()?.id ?: 0L)
    }
    val course = courses.firstOrNull { it.id == selectedCourseId }

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
                    text = "待办生成规则",
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
                // 课程选择：横向一排色点 chip，可左右滑动。
                // 这里必须能滚：普通 Row 装不下时右边的 chip 会被挤出弹窗宽度之外，
                // 既划不到也点不着；课程多到一屏放不下时同样取不到后面的。
                val courseListState = rememberLazyListState()
                LaunchedEffect(courses) {
                    val idx = courses.indexOfFirst { it.id == selectedCourseId }
                    if (idx > 0) courseListState.scrollToItem(idx)
                }
                LazyRow(
                    state = courseListState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 1.dp)
                ) {
                    items(courses, key = { it.id }) { c ->
                        val isSelected = c.id == selectedCourseId
                        val dot = parseColor(c.colorHex)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) LimeGreenPrimary else ChipSurfaceNeutral
                                )
                                .border(
                                    width = if (isSelected) 0.dp else 0.5.dp,
                                    color = if (isSelected) Color.Transparent else GlassEdgeBottom,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedCourseId = c.id }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dot)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = c.name,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) LimeGreenOnPrimary else TextMuted
                            )
                        }
                    }
                }

                if (course == null) {
                    Text(
                        text = "还没有课程，先去添加一门。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    GenerationRuleResolver.AUTO_TYPES.forEach { type ->
                        TaskRuleRow(
                            course = course,
                            type = type,
                            spec = taskRules[course.id]?.get(type),
                            onSave = { onSaveRule(course.id, type, it) },
                            onToggle = { onToggleEnabled(course.id, type, it) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("完成", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    )
}

/** 一行「某门课的某一类待办」：开关 + 节奏 + 截止。 */
@Composable
private fun TaskRuleRow(
    course: CourseEntity,
    type: TaskType,
    spec: CourseRuleSpec?,
    onSave: (CourseRuleSpec) -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val enabled = GenerationRuleResolver.isEnabled(course, type)
    // 显示的是**生效值**：课程级没配过就回落到类别 / 内置默认，
    // 让用户看见「它现在到底是怎么跑的」，而不是一格空白。
    val effective = GenerationRuleResolver.builtinFor(type)
    val cadence = spec?.let { TaskCadence.fromString(it.cadence) } ?: effective.cadence
    val dueOffsetDays = spec?.dueOffsetDays ?: effective.dueOffsetDays
    val dueBeforeMinutes = spec?.dueBeforeMinutes ?: effective.dueBeforeMinutes

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = type.label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = LimeGreenOnPrimary,
                    checkedTrackColor = LimeGreenPrimary
                )
            )
        }

        if (!enabled) return@Column

        Spacer(modifier = Modifier.height(8.dp))

        // 节奏
        Text(
            text = "节奏",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(ChipSurfaceNeutral)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            TaskCadence.entries.forEach { c ->
                val isSelected = c == cadence
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isSelected) LimeGreenPrimary else Color.Transparent)
                        .clickable {
                            onSave(
                                CourseRuleSpec(
                                    cadence = c.name,
                                    dueOffsetDays = dueOffsetDays,
                                    dueBeforeMinutes = dueBeforeMinutes
                                )
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = c.label,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) LimeGreenOnPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 截止：预习是「上课前 N 分钟」，作业与实验是「N 天后 23:59」。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (type == TaskType.PRE_STUDY) "上课前" else "上课后 N 天 23:59",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (type == TaskType.PRE_STUDY) {
                Stepper(
                    value = dueBeforeMinutes,
                    unit = "分钟",
                    onChange = {
                        onSave(
                            CourseRuleSpec(
                                cadence = cadence.name,
                                dueOffsetDays = dueOffsetDays,
                                dueBeforeMinutes = it
                            )
                        )
                    }
                )
            } else {
                Stepper(
                    value = dueOffsetDays,
                    unit = "天",
                    onChange = {
                        onSave(
                            CourseRuleSpec(
                                cadence = cadence.name,
                                dueOffsetDays = it,
                                dueBeforeMinutes = dueBeforeMinutes
                            )
                        )
                    }
                )
            }
        }
    }
}

/** `− 值 单位 +`。步进器比输入框更适合这种「小范围、离散」的参数。 */
@Composable
private fun Stepper(
    value: Int,
    unit: String,
    onChange: (Int) -> Unit,
    range: IntRange = 1..14
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton("减") { if (value - 1 in range) onChange(value - 1) }
        Text(
            text = "$value $unit",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        StepperButton("增") { if (value + 1 in range) onChange(value + 1) }
    }
}

@Composable
private fun StepperButton(desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(ChipSurfaceNeutral)
            .border(0.5.dp, GlassEdgeBottom, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (desc == "减") Icons.Outlined.Remove else Icons.Outlined.Add,
            contentDescription = if (desc == "减") "减少" else "增加",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp)
        )
    }
}
