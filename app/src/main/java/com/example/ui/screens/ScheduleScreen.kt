package com.example.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.ui.components.CourseTimetableCell
import com.example.ui.components.TaskItemCard
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.getDayName
import com.example.ui.components.getPeriodTimeRange
import com.example.ui.components.parseColor
import com.example.ui.theme.TeaColor
import com.example.ui.theme.isGreenishColor
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.ScheduleViewMode

@Composable
fun ScheduleScreen(
    state: MainUiState,
    onSelectDay: (Int) -> Unit,
    onSelectViewMode: (ScheduleViewMode) -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onEditTask: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onAddNewTask: (Long?) -> Unit,
    onGenerateTodayTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // Top Switcher + Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Modern Segmented Control
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val isWeekGrid = state.scheduleViewMode == ScheduleViewMode.WEEK_GRID
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isWeekGrid) TeaColor else Color.Transparent)
                        .clickable { onSelectViewMode(ScheduleViewMode.WEEK_GRID) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("schedule_tab_week_grid"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ViewWeek,
                            contentDescription = null,
                            tint = if (isWeekGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "周课表(1-13节)",
                            fontSize = 11.5.sp,
                            fontWeight = if (isWeekGrid) FontWeight.Bold else FontWeight.Medium,
                            color = if (isWeekGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val isDayTimeline = state.scheduleViewMode == ScheduleViewMode.DAY_TIMELINE
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isDayTimeline) TeaColor else Color.Transparent)
                        .clickable { onSelectViewMode(ScheduleViewMode.DAY_TIMELINE) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("schedule_tab_day_timeline"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ViewDay,
                            contentDescription = null,
                            tint = if (isDayTimeline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "今日日程联动",
                            fontSize = 11.5.sp,
                            fontWeight = if (isDayTimeline) FontWeight.Bold else FontWeight.Medium,
                            color = if (isDayTimeline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = onGenerateTodayTasks,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag("schedule_generate_tasks_button")
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(4.dp))
                Text("联动生成", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.scheduleViewMode == ScheduleViewMode.WEEK_GRID) {
            // 可视化图例说明条：色块饱和度区分完成/未完成 + 角标小圆点亮灭提示待办
            TimetableStatusLegendBar(modifier = Modifier.padding(bottom = 6.dp))

            WeekTimetableGrid13(
                coursesWithSlots = state.coursesWithSlots,
                slots = state.courseSlots,
                tasks = state.tasks,
                selectedDay = state.selectedScheduleDay,
                onInspectCourse = onInspectCourse,
                modifier = Modifier.weight(1f)
            )
        } else {
            DayTimelineView13(
                coursesWithSlots = state.coursesWithSlots,
                slots = state.courseSlots,
                tasks = state.tasks,
                selectedDay = state.selectedScheduleDay,
                onSelectDay = onSelectDay,
                onInspectCourse = onInspectCourse,
                onToggleTaskComplete = onToggleTaskComplete,
                onEditTask = onEditTask,
                onDeleteTask = onDeleteTask,
                onAddNewTask = onAddNewTask,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Timetable Status Legend Bar
 * 帮助用户快速识别：
 * 1. 色块饱和度：高饱和=有未完成作业，低饱和=作业已结清
 * 2. 角标小圆点：亮点=未完成待办，灭点=已完成待办
 */
@Composable
fun TimetableStatusLegendBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(TeaColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = "待办",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text("茶底绿标:有待办", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                        .border(0.5.dp, Color(0xFFD4D4D8), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text("✓", fontSize = 8.sp, color = Color(0xFF71717A), fontWeight = FontWeight.Bold)
                }
                Text("淡灰标:已结清", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(
            text = "极简克制 · 1-13节连堂排课",
            fontSize = 9.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * Weekly Timetable Grid supporting full 1-13 periods and multi-period spanned cards (连堂课铺满格子).
 * 节省空间但层次分明：周一至周日平分全宽，增加按节次的方格背景（交替微浅灰质感与极细分界线），
 * 格子背景内置微小节次标号（1..13），使无课格子整齐美观有秩序，有课色块层次分明、字迹清晰。
 */
@Composable
fun WeekTimetableGrid13(
    coursesWithSlots: List<CourseWithSlots>,
    slots: List<CourseSlotEntity>,
    tasks: List<TaskEntity>,
    selectedDay: Int,
    onInspectCourse: (CourseWithSlots) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val courseMap = coursesWithSlots.associateBy { it.course.id }
    val periodHeight = 62.dp
    val totalHeight = periodHeight * 13

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        // Sticky Day Header Row (周一至周日平分宽度，高亮当日)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dayLabels.forEachIndexed { index, day ->
                val dayNum = index + 1
                val isToday = dayNum == selectedDay
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isToday) TeaColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(
                            0.5.dp,
                            if (isToday) TeaColor else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "周$day",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold
                            ),
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }

        // Scrollable 1-13 Periods Body with Beautiful Period Grid Background (按节方格背景)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
            // 7 Day Columns
            for (dayNum in 1..7) {
                val isToday = dayNum == selectedDay
                val daySlots = slots.filter { it.dayOfWeek == dayNum }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(totalHeight)
                        .padding(horizontal = 1.dp)
                ) {
                    // 1. Beautiful Checkered Period Grid Background (美观按节方格背景)
                    Column(modifier = Modifier.fillMaxSize()) {
                        for (period in 1..13) {
                            // 大节区分：1-4节(上午), 5-8节(下午), 9-13节(晚上)
                            val isEvenPeriod = period % 2 == 0
                            val cellBg = when {
                                isToday && isEvenPeriod -> Color(0xFFF1F5F9).copy(alpha = 0.7f)
                                isToday -> Color(0xFFF8FAFC)
                                isEvenPeriod -> Color(0xFFF8FAFC)
                                else -> Color(0xFFFFFFFF)
                            }
                            val isSectionEnd = period == 4 || period == 8

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(periodHeight)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(cellBg)
                                    .border(
                                        width = if (isSectionEnd) 1.dp else 0.5.dp,
                                        color = if (isSectionEnd) Color(0xFFCBD5E1) else Color(0xFFE2E8F0).copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(2.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                // 淡淡的节次数字角标水印，提供清晰的空间方位感且绝不喧宾夺主
                                Text(
                                    text = "$period",
                                    color = Color(0xFF94A3B8).copy(alpha = 0.45f),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // 2. Multi-Period Spanned Course Cards (连堂课色块精准覆盖对应节次)
                    daySlots.forEach { slot ->
                        val cWithSlots = courseMap[slot.courseId]
                        val course = cWithSlots?.course
                        if (course != null) {
                            val start = slot.startPeriod.coerceIn(1, 13)
                            val end = slot.endPeriod.coerceIn(start, 13)
                            val span = (end - start + 1).coerceAtLeast(1)

                            val topOffset = periodHeight * (start - 1)
                            val cardHeight = periodHeight * span - 2.dp

                            val linkedTasks = tasks.filter { it.courseId == course.id }

                            Box(
                                modifier = Modifier
                                    .offset(y = topOffset)
                                    .height(cardHeight)
                                    .fillMaxWidth()
                                    .padding(1.dp)
                            ) {
                                CourseTimetableCell(
                                    course = course,
                                    slot = slot,
                                    linkedTasks = linkedTasks,
                                    onClick = { onInspectCourse(cWithSlots) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Daily Timeline View showing classes in chronological order alongside paired homework todos
 */
@Composable
fun DayTimelineView13(
    coursesWithSlots: List<CourseWithSlots>,
    slots: List<CourseSlotEntity>,
    tasks: List<TaskEntity>,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onEditTask: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onAddNewTask: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val courseMap = coursesWithSlots.associateBy { it.course.id }

    val todaySlots = slots.filter { it.dayOfWeek == selectedDay }.sortedBy { it.startPeriod }

    Column(modifier = modifier.fillMaxSize()) {
        // Modern Segmented Day Picker (周一至周日)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            dayNames.forEachIndexed { index, name ->
                val dayNum = index + 1
                val isSelected = selectedDay == dayNum
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) TeaColor else Color.Transparent)
                        .border(
                            width = 0.5.dp,
                            color = if (isSelected) TeaColor else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectDay(dayNum) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.5.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (todaySlots.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎉 今日无排课，可点击上方其他星期切换查看日程与待办联动！",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(todaySlots, key = { it.id }) { slot ->
                    val cWithSlots = courseMap[slot.courseId]
                    val course = cWithSlots?.course
                    if (course != null) {
                        val linkedTasks = tasks.filter { it.courseId == course.id }
                        val courseColor = parseColor(course.colorHex)
                        val (timeStart, timeEnd) = getPeriodTimeRange(slot.startPeriod, slot.endPeriod)
                        val room = slot.classroom.ifBlank { course.classroom }

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            elevation = CardDefaults.cardElevation(0.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                // Header: Period Range (e.g. 第3-5节) and Time
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onInspectCourse(cWithSlots) },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .height(16.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(courseColor)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = course.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    val isGreen = isGreenishColor(courseColor)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isGreen) TeaColor else courseColor.copy(alpha = 0.15f))
                                            .border(0.5.dp, if (isGreen) TeaColor else courseColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd)",
                                            color = if (isGreen) MaterialTheme.colorScheme.primary else courseColor,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "@$room · ${course.teacher} · ${course.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Linked Tasks
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "联动待办任务 (${linkedTasks.size})",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = courseColor
                                    )

                                    Text(
                                        text = "+ 为本课添待办",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onAddNewTask(course.id) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                if (linkedTasks.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "暂无关联作业，可点击上方「+」添加或通过今日课表智能生成",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        linkedTasks.forEach { task ->
                                            TaskItemCard(
                                                task = task,
                                                onToggleComplete = { onToggleTaskComplete(task) },
                                                onEdit = { onEditTask(task) },
                                                onDelete = { onDeleteTask(task) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
