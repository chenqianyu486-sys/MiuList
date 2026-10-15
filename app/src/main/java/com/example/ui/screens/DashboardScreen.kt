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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.CalendarMonth
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
import com.example.ui.theme.cardRevealAt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.CourseTintVivid
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.glassSurface
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: MainUiState,
    onGenerateTodayTasks: () -> Unit = {},
    onPreviewGenerateTasks: () -> Unit = {},
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onToggleTaskStar: (TaskEntity) -> Unit,
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

    val cardRevealProgress = LocalCardRevealProgress.current

    // 入场序号是**整页**一条，从上往下数：学期卡 → 今日课程 → 待办。
    // 每段的起点由前面段落的条数推出来，而不是三个各从 0 开始——各从 0 开始的话
    // 「今日没课」的空态卡和学期卡同时亮，五张待办卡又和学期卡同时亮，整页就变成
    // 三批一起亮，看不出是从上往下的。
    val courseOrderBase = 1
    val taskOrderBase = courseOrderBase + todaySlots.size

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PageHInset),
        verticalArrangement = Arrangement.spacedBy(CardBlockGap),
        // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
        contentPadding = PaddingValues(bottom = IslandBottomInset)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Semester Overview & Progress Hero Card (Clean & Airy Modern)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .cardRevealAt(cardRevealProgress, 0)
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
                                // 学期名取自校历，和设置页、顶栏同一个来源。
                                // 原来这里写死成「2026 秋季学期」：换个学期、或者用户
                                // 自己改了校历，这张卡和别处就会显示两个不同的学期名。
                                Text(
                                    text = state.semester?.name?.ifBlank { null } ?: "本学期",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
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
                                color = TextSecondary
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
                                    color = TextSecondary,
                                    fontSize = 8.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Today's Classes & Linked Tasks
        // 组头只剩标题：原来右侧那颗「全周课表 >」是跳课表页的按钮，已撤。
        // 切页由底部浮岛承担，组头里再放一个跳转入口就是同一个动作的第二个落点。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
        }

        if (todaySlots.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .cardRevealAt(cardRevealProgress, courseOrderBase)
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
                            color = TextSecondary
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
            itemsIndexed(todaySlots, key = { _, slot -> "slot_${slot.id}" }) { slotIndex, slot ->
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
                            .cardRevealAt(cardRevealProgress, courseOrderBase + slotIndex)
                            .clickable { onInspectCourse(cWithSlots) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassSurface(shape = RoundedCornerShape(18.dp), tint = courseColor, tintAlpha = CourseTintAlpha, vividness = CourseTintVivid)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧课程色竖条（一度删掉又按用户要求恢复）：
                            // 卡面染色只有 20% 的粉彩，扫视时不足以一眼认出课程；
                            // 这根实色竖条是更强的识别线索，不是同一信息的重复表达。
                            Box(
                                modifier = Modifier
                                    .size(4.dp, 44.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(courseColor)
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                // weight 挂在**课名 Text** 上，而且必须是这个 RowScope 里的。
                                //
                                // 两个坑连着：
                                // ① 挂到外面那个 Course Row 上不行——它在 `Column` 里，
                                //    ColumnScope 的 weight 是**纵向**权重，会把这一行撑成竖向
                                //    满格甚至直接量不出高度（实测课名和教室整行消失）。
                                // ② 能不能生效取决于外层结构：这张卡的 Column 自己带 weight(1f)，
                                //    宽度在进入这里之前就已经被定成「卡片减色条、待办 chip」的余量，
                                //    所以里面的 chip 先按这个上限量、课名再拿剩下的——顺序才对。
                                //    （课表列表页那种「包课名的 Row 本身是非弹性直接子项」的结构，
                                //    weight 必须挂在那个 Row 上，否则反过来被 chip 截。）
                                // 让**课程名**去吸收宽度不足，教室保持完整：课名截断只是信息不全，
                                // 而教室被折行会让整张卡比邻居高出一截。
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = course.name,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd) · ${course.teacher}",
                                    style = MaterialTheme.typography.labelSmall,
                                    // 这行是这张卡里最该被扫到的时间信息，用 TextSecondary 而不是
                                    // 全局的 onSurfaceVariant（= TextMuted #78827B）：本卡压的是
                                    // **这门课的粉彩**，实测 TextMuted 在四种卡面上只有
                                    // 2.2~3.3:1，10~11sp 的小字过不了 AA。TextSecondary #4A524D
                                    // 在同一批卡面上是 6.1~6.6:1。设置页的功能卡早就在用同一个理由换过。
                                    color = TextSecondary
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
                                        color = TextSecondary,
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
        // 同上：右侧「清单 >」跳转按钮已撤，这一组只剩标题。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(TeaColor)
                        .border(0.5.dp, TeaColorBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ListAlt,
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
        }

        if (pendingTasks.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .cardRevealAt(cardRevealProgress, taskOrderBase)
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
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                pendingTasks.take(5),
                key = { _, task -> "task_${task.id}" }
            ) { taskIndex, task ->
                TaskItemCard(
                    task = task,
                    customTypes = state.customTaskTypes,
                    onToggleComplete = { onToggleTaskComplete(task) },
                    onToggleStar = { onToggleTaskStar(task) },
                    onOpenDetail = { onInspectTask(task) },
                    modifier = Modifier.cardRevealAt(
                        cardRevealProgress,
                        taskOrderBase + taskIndex
                    )
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
