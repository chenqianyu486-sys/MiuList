package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.model.TaskType
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.TaskItemCard
import com.example.ui.components.formatPeriodRange
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.ui.components.parseColor
import com.example.ui.theme.PageHInset
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.CourseTintVivid
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.glassSurface
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.NavigationTab
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: MainUiState,
    onNavigateTab: (NavigationTab) -> Unit,
    onGenerateTodayTasks: () -> Unit = {},
    onPreviewGenerateTasks: () -> Unit = {},
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onInspectTask: (TaskEntity) -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    onOpenWeekPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completedCount = state.tasks.count { it.isCompleted }
    val totalCount = state.tasks.size
    val completionRatio = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f

    val todayCalendar = remember { Calendar.getInstance() }
    val todayDateStr = remember {
        val sdf = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINESE)
        sdf.format(Date())
    }

    // 今日课程：判定权交给 resolver（开课周次 / 停课 / 补课都在它里面）。
    // 停课日这里会得到空列表，界面据此显示「今天停课」而不是一片空白。
    val resolver = remember(state.calendar) { ScheduleResolver(state.calendar) }
    val todaySlots = remember(state.courseSlots, state.slotWeeks, state.calendar) {
        resolver.slotsOn(EpochDays.today(), state.courseSlots, state.slotWeeks)
    }
    val todayIsNoClassDay = remember(state.calendar, state.noClassDayCount) {
        !state.calendar.isSchoolDay(EpochDays.today())
    }

    val courseMap = remember(state.coursesWithSlots) {
        state.coursesWithSlots.associateBy { it.course.id }
    }

    val pendingTasks = remember(state.tasks) {
        state.tasks.filter { !it.isCompleted }
    }

    // 本周课时。
    //
    // 走 slotsInWeek 而不是「全部时段 × 每节时长」，是为了让单双周（开课周次）过滤生效——
    // 否则这周只上单周课时，副标题却按全量算出 12 小时，和周课表上看到的对不上。
    //
    // 只跟着课表与作息重算；currentWeek 由校历派生，跨过周末边界时会自己变。
    val weekHoursText = remember(
        state.courseSlots,
        state.slotWeeks,
        state.currentWeek,
        state.periodTable,
        state.calendar
    ) {
        val weekSlots = resolver.slotsInWeek(
            state.currentWeek,
            state.courseSlots,
            state.slotWeeks
        ).values.flatten()
        val minutes = weekSlots.sumOf {
            state.periodTable.durationMinutesOf(it.startPeriod, it.endPeriod)
        }
        String.format(Locale.getDefault(), "%.1f 小时", minutes / 60.0)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PageHInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
        contentPadding = PaddingValues(bottom = IslandBottomInset)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Semester Overview & Progress Hero Card (Clean & Airy Modern)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_semester_card"),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(22.dp))
                        // 这里原来压了一条抹茶绿渐变做「主卡识别」，是米白页面底时代的做法。
                        // 页面底换成纯白、面改成半透明冷灰之后，这条渐变只剩两个坏处：
                        // 一是渐变本身就是一道明暗轴，和这版「面里不藏光影」的扁平原则冲突，
                        // 二是它把主卡染绿了，整屏唯一的绿本该留给按钮和选中态这些小面积强调。
                        // 主卡靠 22dp 大圆角和更大的体量已经足够认出来。
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .clickable { onOpenWeekPicker() }
                                        .padding(horizontal = 9.dp, vertical = 3.5.dp)
                                ) {
                                    Text(
                                        text = "第 ${state.currentWeek} 周",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        fontSize = 11.5.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "2026 秋季学期",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = todayDateStr,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.3).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "${state.courses.size} 门课 · ${weekHoursText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Modern Circular Progress Ring
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { completionRatio },
                                modifier = Modifier.size(56.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outline,
                                strokeWidth = 5.dp
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${(completionRatio * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "完成率",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 8.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Today's Classes & Linked Tasks
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(TeaColor)
                            .border(0.5.dp, TeaColorBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = null,
                            tint = TeaOnColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "今日课程 · ${todaySlots.size} 堂",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TeaColor)
                        .border(0.5.dp, TeaColorBorder, RoundedCornerShape(12.dp))
                        .clickable { onNavigateTab(NavigationTab.SCHEDULE) }
                        .padding(horizontal = 9.dp, vertical = 3.5.dp)
                ) {
                    Text(
                        text = "全周课表 >",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TeaOnColor,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        if (todaySlots.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(shape = RoundedCornerShape(18.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎉 今天没课，适合把作业一次清空",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // key 必须带前缀：下面那段「近期待办」用的是另一张表的 id，两段在**同一个**
            // LazyColumn 里，裸 `it.id` 会让时段 id 和待办 id 撞进同一个 key 命名空间，
            // 抛 `Key "N" was already used` 直接闪退。
            // 这个撞车平时看不出来——只有「今天有课」且「有待办」时才同时存在这两段，
            // 所以在周日（没排课）怎么测都复现不出来。
            items(todaySlots, key = { "slot_${it.id}" }) { slot ->
                val cWithSlots = courseMap[slot.courseId]
                val course = cWithSlots?.course
                if (course != null) {
                    val linked = state.tasks.filter { it.courseId == course.id }
                    val courseColor = parseColor(course.colorHex)
                    val (timeStart, timeEnd) = state.periodTable.timeRange(slot.startPeriod, slot.endPeriod)
                        ?: ("--" to "--")
                    val room = slot.classroom.ifBlank { course.classroom }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onInspectCourse(cWithSlots) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassSurface(shape = RoundedCornerShape(18.dp), tint = courseColor, tintAlpha = CourseTintAlpha, vividness = CourseTintVivid)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp, 44.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(courseColor)
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = course.name,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "@$room",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd) · ${course.teacher}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val pendingCount = linked.count { !it.isCompleted }
                            if (pendingCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 9.dp, vertical = 4.5.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondary)
                                        )
                                        Text(
                                            text = "$pendingCount 待办",
                                            color = MaterialTheme.colorScheme.secondary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 9.dp, vertical = 4.5.dp)
                                ) {
                                    Text(
                                        text = "✓ 已结清",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Urgent / Today's Pending Tasks
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(TeaColor)
                            .border(0.5.dp, TeaColorBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ListAlt,
                            contentDescription = null,
                            tint = TeaOnColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "待办清单 · ${pendingTasks.size}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TeaColor)
                        .border(0.5.dp, TeaColorBorder, RoundedCornerShape(12.dp))
                        .clickable { onNavigateTab(NavigationTab.CHECKLIST) }
                        .padding(horizontal = 9.dp, vertical = 3.5.dp)
                ) {
                    Text(
                        text = "清单 >",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TeaOnColor,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        if (pendingTasks.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(shape = RoundedCornerShape(18.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✨ 全部完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(pendingTasks.take(5), key = { "task_${it.id}" }) { task ->
                TaskItemCard(
                    task = task,
                    customTypes = state.customTaskTypes,
                    onToggleComplete = { onToggleTaskComplete(task) },
                    onOpenDetail = { onInspectTask(task) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
