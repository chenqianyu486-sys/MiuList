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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.TaskEntity
import com.example.data.model.TaskPriority
import com.example.data.model.TaskType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun TaskEditDialog(
    initialTask: TaskEntity?,
    courses: List<CourseEntity>,
    preselectedCourseId: Long?,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit
) {
    val defaultCourse = courses.find { it.id == preselectedCourseId }
        ?: initialTask?.let { task -> courses.find { it.id == task.courseId } }
        ?: courses.firstOrNull()

    var selectedCourse by remember { mutableStateOf(defaultCourse) }
    var title by remember { mutableStateOf(initialTask?.title ?: "") }
    var description by remember { mutableStateOf(initialTask?.description ?: "") }
    var selectedType by remember {
        mutableStateOf(initialTask?.let { TaskType.fromString(it.taskType) } ?: TaskType.POST_CLASS)
    }
    var selectedPriority by remember {
        mutableStateOf(initialTask?.let { TaskPriority.fromString(it.priority) } ?: TaskPriority.MEDIUM)
    }
    var estimatedMinutes by remember {
        mutableIntStateOf(initialTask?.estimatedMinutes ?: 45)
    }
    var dueDaysOffset by remember { mutableIntStateOf(3) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialTask == null) "新建作业/待办" else "编辑作业待办",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Select Course
                Text(
                    text = "关联课程",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    courses.forEach { course ->
                        val isSelected = selectedCourse?.id == course.id
                        val color = parseColor(course.colorHex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    selectedCourse = course
                                    if (title.isBlank() || title.startsWith("【")) {
                                        title = when (selectedType) {
                                            TaskType.PRE_STUDY -> "【课前预习】${course.name} 预习任务"
                                            TaskType.POST_CLASS -> "【课后作业】${course.name} 作业习题"
                                            TaskType.LAB_REPORT -> "【实验报告】${course.name} 实验报告"
                                            TaskType.REVIEW -> "【复习总结】${course.name} 考前复习"
                                            TaskType.CUSTOM -> "【${course.name}】"
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(color)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = course.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) color else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = course.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Task Type
                Text(
                    text = "待办类型",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskType.entries.forEach { type ->
                        val isSelected = selectedType == type
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedType = type
                                selectedCourse?.let { c ->
                                    title = when (type) {
                                        TaskType.PRE_STUDY -> "【课前预习】${c.name} 预备知识"
                                        TaskType.POST_CLASS -> "【课后作业】${c.name} 课后作业"
                                        TaskType.LAB_REPORT -> "【实验报告】${c.name} 实验报告"
                                        TaskType.REVIEW -> "【复习总结】${c.name} 核心考点"
                                        TaskType.CUSTOM -> "【自定义】${c.name}"
                                    }
                                }
                            },
                            label = { Text(type.label, fontSize = 11.sp) }
                        )
                    }
                }

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("待办名称") },
                    placeholder = { Text("例如：【课后作业】数据结构 树遍历习题") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_title_input"),
                    singleLine = true
                )

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("作业要求与备注 (选填)") },
                    placeholder = { Text("例如：教材第58页第3题，周五前上传学习通") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // Priority
                Text(
                    text = "优先级",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TaskPriority.entries.forEach { priority ->
                        FilterChip(
                            selected = selectedPriority == priority,
                            onClick = { selectedPriority = priority },
                            label = { Text("${priority.label}优先级") }
                        )
                    }
                }

                // Estimated duration
                Text(
                    text = "预估用时: $estimatedMinutes 分钟",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(20, 30, 45, 60, 90, 120).forEach { mins ->
                        FilterChip(
                            selected = estimatedMinutes == mins,
                            onClick = { estimatedMinutes = mins },
                            label = { Text("${mins}m") }
                        )
                    }
                }

                // Deadline selection
                Text(
                    text = "截止时间",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Pair(0, "今天"),
                        Pair(1, "明天"),
                        Pair(3, "3天后"),
                        Pair(5, "5天后"),
                        Pair(7, "下周")
                    ).forEach { (offset, label) ->
                        FilterChip(
                            selected = dueDaysOffset == offset,
                            onClick = { dueDaysOffset = offset },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetCourse = selectedCourse ?: return@Button
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, dueDaysOffset)
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 0)
                    }
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                    val startTimeTs = initialTask?.startTimeTimestamp?.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val startTimeStr = initialTask?.startTimeString?.ifBlank { "本周排期 (${targetCourse.name})" } ?: "本周排期 (${targetCourse.name})"

                    val taskToSave = initialTask?.copy(
                        courseId = targetCourse.id,
                        courseName = targetCourse.name,
                        courseCategory = targetCourse.category,
                        courseColorHex = targetCourse.colorHex,
                        title = title.ifBlank { "【${selectedType.label}】${targetCourse.name}" },
                        description = description,
                        taskType = selectedType.name,
                        startTimeTimestamp = startTimeTs,
                        startTimeString = startTimeStr,
                        priority = selectedPriority.name,
                        estimatedMinutes = estimatedMinutes,
                        dueDateTimestamp = cal.timeInMillis,
                        dueDateString = sdf.format(cal.time)
                    ) ?: TaskEntity(
                        courseId = targetCourse.id,
                        courseName = targetCourse.name,
                        courseCategory = targetCourse.category,
                        courseColorHex = targetCourse.colorHex,
                        title = title.ifBlank { "【${selectedType.label}】${targetCourse.name}" },
                        description = description,
                        taskType = selectedType.name,
                        startTimeTimestamp = startTimeTs,
                        startTimeString = startTimeStr,
                        priority = selectedPriority.name,
                        estimatedMinutes = estimatedMinutes,
                        dueDateTimestamp = cal.timeInMillis,
                        dueDateString = sdf.format(cal.time),
                        isAutoGenerated = false
                    )

                    onSave(taskToSave)
                },
                modifier = Modifier.testTag("save_task_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("保存待办", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
