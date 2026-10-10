package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.ui.theme.TeaColor
import com.example.ui.theme.isGreenishColor
import com.example.ui.theme.isLightColor

/**
 * Adjust color saturation using Android HSV conversion.
 * saturationFactor > 1.0 increases saturation (more vivid).
 * saturationFactor < 1.0 decreases saturation (more muted/pastel/greyed).
 */
fun adjustColorSaturation(color: Color, saturationFactor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
        hsv
    )
    hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
    val argb = android.graphics.Color.HSVToColor((color.alpha * 255).toInt(), hsv)
    return Color(argb)
}

/**
 * Visual badge dots indicating how many homework items are pending vs completed.
 * Lit (亮): Uncompleted homework task (solid bright accent dot).
 * Dim (灭): Completed homework task (subtle dimmed hollow ring).
 */
@Composable
fun TaskDotsBadge(
    linkedTasks: List<TaskEntity>,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    if (linkedTasks.isEmpty()) return

    val maxDisplayDots = 4
    val displayTasks = linkedTasks.take(maxDisplayDots)
    val remaining = linkedTasks.size - maxDisplayDots
    val isGreen = isGreenishColor(activeColor)
    val bg = if (isGreen) TeaColor else Color.White.copy(alpha = 0.88f)
    val dotColor = if (isGreen) MaterialTheme.colorScheme.primary else activeColor

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, if (isGreen) TeaColor else activeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 3.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        displayTasks.forEach { task ->
            if (!task.isCompleted) {
                // 亮: 待办未完成 - 高亮实心圆点
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            } else {
                // 灭: 待办已完成 - 熄灭空心圆点
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(1.dp, if (isGreen) Color(0xFF5E4F41) else activeColor.copy(alpha = 0.35f), CircleShape)
                )
            }
        }
        if (remaining > 0) {
            Text(
                text = "+$remaining",
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                color = dotColor
            )
        }
    }
}

/**
 * Modern Minimalist Restrained Course Timetable Cell (现代极简美观克制课程色块).
 * 设计特点：
 * 1. 现代极简透亮底色：柔和克制的粉彩洗底（alpha 0.16f），绝不泛油反光，0.5dp 极细轻柔边框；
 * 2. 雅致左侧微圆角强调条：2.5dp 纤细条，两端精致微圆角，与边框自然呼吸留白；
 * 3. 高对比墨炭标题与石墨地点：字迹清爽锐利、层次分明；
 * 4. 绿作前景茶色作底：当有待办任务时，采用典雅浓茶色 (#2E261F) 微胶囊底色 + 抹茶嫩绿 (#BCE472) 指示圆点与数字，
 *    对比度 > 11:1，典雅纯粹美观克制，彻底消除浅色绿在浅白底上的模糊问题；
 * 5. 状态已结清时：采用极简淡雅轻标，静谧无侵扰。
 */
@Composable
fun CourseTimetableCell(
    course: CourseEntity,
    slot: CourseSlotEntity,
    linkedTasks: List<TaskEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val courseBaseColor = parseColor(course.colorHex)
    val hasLinkedTasks = linkedTasks.isNotEmpty()
    val pendingTasksCount = linkedTasks.count { !it.isCompleted }
    val isAllCompleted = hasLinkedTasks && pendingTasksCount == 0
    val hasPending = pendingTasksCount > 0
    val room = slot.classroom.ifBlank { course.classroom }
    val span = (slot.endPeriod - slot.startPeriod + 1).coerceAtLeast(1)

    // 现代极简美观克制：抹茶嫩绿作底色，前景色为黑色
    // 柔和粉彩满铺底色，去掉冗余边框与左侧指示条
    val containerBg = when {
        isAllCompleted -> courseBaseColor.copy(alpha = 0.4f)
        hasPending -> courseBaseColor.copy(alpha = 0.9f)
        else -> courseBaseColor.copy(alpha = 0.65f)
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .testTag("course_slot_${course.id}_${slot.id}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerBg
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Course Name & Room
            Column {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 12.5.sp
                    ),
                    // 墨曜深炭黑，无论在何种粉彩底色上均清晰极高对比度
                    color = Color(0xFF18181B),
                    maxLines = if (span >= 3) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.5.sp
                )

                if (room.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.5.dp))
                    Text(
                        text = if (room.startsWith("@")) room else "@$room",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF3F3F46), // 稍深的灰色以保证在粉彩底色上的对比度
                        fontSize = 8.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Bottom: 现代极简克制作业状态徽标
            val isGreen = isGreenishColor(courseBaseColor)
            if (hasPending) {
                // 绿作前景时白底就看不清了，让 ui 元素出现绿作前景时该元素背景色用茶色
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isGreen) TeaColor else Color.White.copy(alpha = 0.85f))
                        .padding(horizontal = 3.5.dp, vertical = 1.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                    ) {
                        val badgeFg = if (isGreen) MaterialTheme.colorScheme.primary else courseBaseColor
                        Box(
                            modifier = Modifier
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(badgeFg)
                        )
                        Text(
                            text = "$pendingTasksCount 待办",
                            color = badgeFg,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            fontSize = 7.5.sp
                        )
                    }
                }
            } else if (isAllCompleted) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.6f))
                        .padding(horizontal = 3.dp, vertical = 0.5.dp)
                ) {
                    Text(
                        text = "✓ 已结清",
                        color = Color(0xFF52525B),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        fontSize = 7.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseLinkageSheet(
    courseWithSlots: CourseWithSlots,
    linkedTasks: List<TaskEntity>,
    onDismiss: () -> Unit,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onAddNewTaskForCourse: () -> Unit,
    onEditCourse: () -> Unit,
    onDeleteCourse: () -> Unit
) {
    val course = courseWithSlots.course
    val slots = courseWithSlots.slots
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val courseColor = parseColor(course.colorHex)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(courseColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                val isGreen = isGreenishColor(courseColor)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isGreen) TeaColor else courseColor.copy(alpha = 0.15f))
                        .border(0.5.dp, if (isGreen) TeaColor else courseColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = course.category,
                        color = if (isGreen) MaterialTheme.colorScheme.primary else courseColor,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Course Info & Multi-Slot schedule Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(text = "任课教师: ${course.teacher}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(text = "主教室: ${course.classroom}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "本周排课时段安排 (${slots.size} 次/周):",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    slots.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod })).forEach { s ->
                        val (sTime, eTime) = getPeriodTimeRange(s.startPeriod, s.endPeriod)
                        val room = s.classroom.ifBlank { course.classroom }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${getDayName(s.dayOfWeek)} ${formatPeriodRange(s.startPeriod, s.endPeriod)} ($sTime-$eTime) @ $room",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Linked Tasks Title + Add Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Assignment,
                        contentDescription = null,
                        tint = courseColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "本课程关联待办 (${linkedTasks.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (linkedTasks.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TaskDotsBadge(linkedTasks = linkedTasks, activeColor = courseColor)
                    }
                }

                val btnContentColor = if (isLightColor(courseColor)) Color(0xFF1E2D0E) else Color.White
                Button(
                    onClick = onAddNewTaskForCourse,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = courseColor,
                        contentColor = btnContentColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("add_task_for_course_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = btnContentColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新增待办", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = btnContentColor)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tasks List
            if (linkedTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "该课程暂无关联待办，可点击上方「新增待办」或通过今日课表自动生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(linkedTasks, key = { it.id }) { task ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (task.isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (task.isCompleted) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline
                            ),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleTaskComplete(task) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isTaskGreen = task.isCompleted || isGreenishColor(courseColor)
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(if (isTaskGreen) TeaColor else courseColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (task.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
                                        contentDescription = null,
                                        tint = if (isTaskGreen) MaterialTheme.colorScheme.primary else courseColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TaskTypeBadge(taskTypeStr = task.taskType)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = task.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "截止: ${task.dueDateString} · 预计${task.estimatedMinutes}分钟",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onEditCourse,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑课程与时段", color = MaterialTheme.colorScheme.onSurface)
                }
                OutlinedButton(
                    onClick = onDeleteCourse,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除课程")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
