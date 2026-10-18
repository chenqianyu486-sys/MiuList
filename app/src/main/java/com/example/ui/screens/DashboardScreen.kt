package com.example.ui.screens

import android.graphics.RectF
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.cardRevealAt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.model.TaskType
import com.example.data.source.zju.CoursesTodoItem
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.TaskItemCard
import com.example.ui.components.WeatherBlock
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.todayCalendar
import com.example.ui.components.todayStatusLabel
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.ui.components.parseColor
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.CardBaseCoatAlpha
import com.example.ui.theme.accentBarColor
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.cardTint
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.glassSurface
import com.example.ui.theme.wallpaperBlur
import com.example.ui.theme.WallpaperCardFaceTint
import com.example.ui.theme.WallpaperCardFaceAlpha
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val HeroCardHeight = 140.dp

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
    onRefreshCoursesTodo: () -> Unit = {},
    onOpenQuickLoginDialog: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val todayCalendar = remember { todayCalendar() }
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

            var isHeroFlipped by rememberSaveable { mutableStateOf(false) }
            val heroFlipRotation by animateFloatAsState(
                targetValue = if (isHeroFlipped) 180f else 0f,
                animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                label = "hero_flip_rotation"
            )

            // Semester Overview & Progress Hero Card (3D Flip: 正面=日期天气，反面=学在浙大待交作业)
            // 固定 HeroCardHeight = 140.dp，确保卡片翻转过程中与正反两面高度绝对一致，防止下方列表发生跳变。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HeroCardHeight)
                    .cardRevealAt(cardRevealProgress, 0)
                    .testTag("dashboard_semester_card")
                    .graphicsLayer {
                        rotationY = heroFlipRotation
                        cameraDistance = 14f * density
                    },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                if (heroFlipRotation <= 90f) {
                    // 正面：日期与天气卡片（点击卡片主体翻转至背面学在浙大）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .wallpaperBlur()
                            .glassSurface(
                                shape = RoundedCornerShape(22.dp),
                                tint = WallpaperCardFaceTint,
                                tintAlpha = WallpaperCardFaceAlpha,
                                blurRadius = 0.dp,
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                isHeroFlipped = true
                            }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        contentAlignment = Alignment.CenterStart
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

                            WeatherBlock(
                                state = state.weather,
                                hourOfDay = LocalTime.now().hour
                            )
                        }
                    }
                } else {
                    // 背面：学在浙大待交作业卡片（抵消 180° 水平翻转，恢复正向文字渲染）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .wallpaperBlur()
                            .glassSurface(
                                shape = RoundedCornerShape(22.dp),
                                tint = WallpaperCardFaceTint,
                                tintAlpha = WallpaperCardFaceAlpha,
                                blurRadius = 0.dp,
                            )
                            .graphicsLayer {
                                rotationY = 180f
                            }
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                isHeroFlipped = false
                            }
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 第一行：标题栏（点击本行任意空白翻回天气卡片）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(TeaColor)
                                            .border(0.5.dp, TeaColorBorder, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.School,
                                            contentDescription = null,
                                            tint = TeaOnColor,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "学在浙大",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val todoCount = state.coursesTodoList.size
                                    val badgeText = if (!state.hasZjuCredentials) "未绑定"
                                        else if (state.isCoursesTodoLoading) "刷新中..."
                                        else if (!state.coursesTodoError.isNullOrBlank()) "同步失败"
                                        else if (todoCount > 0) "$todoCount 篇待交"
                                        else "已交清"
                                    val badgeColor = if (!state.hasZjuCredentials) TextSecondary
                                        else if (state.isCoursesTodoLoading) MaterialTheme.colorScheme.primary
                                        else if (!state.coursesTodoError.isNullOrBlank()) MaterialTheme.colorScheme.error
                                        else if (todoCount > 0) MaterialTheme.colorScheme.primary
                                        else TextSecondary
                                    val badgeBg = if (!state.hasZjuCredentials) MaterialTheme.colorScheme.surfaceVariant
                                        else if (state.isCoursesTodoLoading) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else if (!state.coursesTodoError.isNullOrBlank()) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                        else if (todoCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = badgeColor,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val updateText = remember(state.coursesTodoLastUpdated) {
                                        formatLastUpdatedTime(state.coursesTodoLastUpdated)
                                    }
                                    if (updateText.isNotBlank()) {
                                        Text(
                                            text = updateText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            fontSize = 10.5.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }

                                    // 一键刷新按钮（带加载旋转动效）
                                    val infiniteTransition = rememberInfiniteTransition(label = "courses_todo_spin")
                                    val spinAngle by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 360f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(900, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        ),
                                        label = "courses_todo_spin_angle"
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) {
                                                onRefreshCoursesTodo()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = "一键刷新",
                                            tint = if (state.isCoursesTodoLoading) MaterialTheme.colorScheme.primary else TextSecondary,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .graphicsLayer {
                                                    if (state.isCoursesTodoLoading) {
                                                        rotationZ = spinAngle
                                                    }
                                                }
                                        )
                                    }
                                }
                            }

                            // 第二行：横向待办小卡片栏 (按 DDL 顺序从左往右滑动)
                            if (!state.hasZjuCredentials) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "尚未绑定浙大账号，无法同步作业",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = onOpenQuickLoginDialog,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("填写学号密码", fontSize = 11.5.sp)
                                    }
                                }
                            } else if (state.coursesTodoList.isEmpty()) {
                                val errorMsg = state.coursesTodoError
                                if (!errorMsg.isNullOrBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(64.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = errorMsg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(64.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "✨ 当前没有未交作业，所有任务已结清",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                            } else {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(state.coursesTodoList, key = { it.id }) { item ->
                                        CoursesMiniCard(
                                            item = item,
                                            matchingCourse = courseMap.values.find {
                                                it.course.name.trim() == item.courseName.trim() ||
                                                    it.course.name.contains(item.courseName) ||
                                                    item.courseName.contains(it.course.name)
                                            }?.course
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Today's Classes & Linked Tasks
        // 组头只剩标题：原来右侧那颗「全周课表 >」是跳课表页的按钮，已撤；
        // 标题左侧那颗圆形学士帽图标同轮撤掉——它和茶色圆底把「这是标题」
        // 说成「这是个可点的入口」，与组头不再承担跳转的事实相矛盾。
        item {
            val now = System.currentTimeMillis()
            val coursesHeader = todayStatusLabel(todayCalendar, todaySlots, now, state.periodTable)
            Text(
                text = coursesHeader,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                            // 空态卡同样铺白衬底：它和课程卡、待办卡排在同一屏里，
                            // 不铺的话换深色壁纸时它会先变成一块发灰的板。
                            .glassSurface(shape = RoundedCornerShape(18.dp), baseCoatAlpha = CardBaseCoatAlpha)
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
                                // 白衬底让这张卡的明度主要由自己决定，换壁纸不会连着变暗；
                                // 染色传 cardTint 解出来的颜色，八门课因此等重——两条理由
                                // 分别见 CardBaseCoatAlpha 和 Color.kt 的「卡片染色」。
                                .glassSurface(shape = RoundedCornerShape(18.dp), tint = cardTint(courseColor, CourseTintAlpha), tintAlpha = CourseTintAlpha, baseCoatAlpha = CardBaseCoatAlpha)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧课程色竖条（一度删掉又按用户要求恢复）：
                            // 卡面染色很淡，扫视时不足以一眼认出课程；
                            // 这根实色竖条是更强的识别线索，不是同一信息的重复表达。
                            //
                            // 颜色不是课程原色，而是 accentBarColor 解出来的那一支：
                            // 八门课的课程色明度跨得很开（黄的 L≈0.86、青的 L≈0.55），
                            // 直接画原色时黄色的竖条压在黄卡面上等于消失、青的又压得很重。
                            // 现在它和卡面同一个色相、同一套等重建，只是深一档——
                            // 竖条指的是「这门课」，而不是「这门课原来是哪个色号」。
                            Box(
                                modifier = Modifier
                                    .size(4.dp, 44.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(accentBarColor(courseColor))
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

                            // 右侧状态徽标：跟周课表那一页同一套说法——
                            // 有未完成待办只给「圆点 + 数字」，全部完成只给一枚 ✓。
                            //
                            // 「待办」两个字去掉是有理由的：圆点已经是「还有几件事没做」
                            // 的形状语言，补两个字就成了一个短语，而短语要在这点
                            // 余量里塞下，字号就得跟着别的小字降一级。
                            // 「已结清」换成 ✓ 是同一个道理：勾本身就是完成的形状。
                            //
                            // 从没挂过待办的课程**完全不挂徽标**（周课表也是这条规矩）：
                            // 一枚 ✓ 会把「一件待办都没有」说成「都做完了」，
                            // 这两件事不是一回事，不能共用一个记号。
                            val pendingCount = linked.count { !it.isCompleted }
                            val hasLinked = linked.isNotEmpty()
                            if (pendingCount > 0) {
                                Box(
                                    modifier = Modifier
                                        // 底与字跟周课表格子里那枚徽标**同一套**：
                                        // 磨砂白玻璃面 + 墨黑的点和数字，一枚 1.dp 描边都不要。
                                        //
                                        // 之前这里是 secondaryContainer 实色 + secondary 描边和字，
                                        // 八门课就长出八种底色，而它偏偏是这张卡上最该统一的元件——
                                        // 课程色已经由左侧竖条和整张卡面表达了，徽标再带一遍颜色
                                        // 只是把「这门课」重复说三遍，还顺手把数字压成灰绿。
                                        //
                                        // blurRadius 传 0，理由和课表那枚一样：背后是这张染色课程卡
                                        // 而不是壁纸，卡面本身已接近纯白，糊进来等于没糊；
                                        // 而且 18/28 的核会先把这枚 23dp 高的小徽标自己的边界吃掉，
                                        // 读出来是一块斑而不是一枚徽标。白面 + 半透已经足够立住。
                                        .glassSurface(
                                            shape = RoundedCornerShape(7.dp),
                                            tint = Color.White,
                                            tintAlpha = 0.82f,
                                            blurRadius = 0.dp
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
                                                .background(TextPrimary)
                                        )
                                        Text(
                                            // 只剩数字：小圆点已经把「还有几件待办」说完了，
                                            // 后缀「待办」在这张卡右侧是纯噪声。
                                            text = "$pendingCount",
                                            color = TextPrimary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else if (hasLinked) {
                                // 全部完成：一枚正圆里一个 ✓，和周课表那枚同一套底与字。
                                // 从胶囊改成正圆——里面只剩一个字符时，胶囊会在字符两侧
                                // 空出一段档，看着像多出来的一颗点而不像一枚徽标。
                                Box(
                                    modifier = Modifier
                                        .glassSurface(
                                            shape = CircleShape,
                                            tint = Color.White,
                                            tintAlpha = 0.82f,
                                            blurRadius = 0.dp
                                        )
                                        .size(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓",
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        fontSize = 11.sp,
                                        maxLines = 1
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
            Text(
                text = "待办清单 · ${pendingTasks.size}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
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
                            // 同上：待办区的空态卡与待办卡同屏，衬底保持一致。
                            .glassSurface(shape = RoundedCornerShape(18.dp), baseCoatAlpha = CardBaseCoatAlpha)
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

@Composable
private fun CoursesMiniCard(
    item: CoursesTodoItem,
    matchingCourse: CourseEntity?,
    modifier: Modifier = Modifier
) {
    val courseColor = remember(matchingCourse) {
        matchingCourse?.colorHex?.let { parseColor(it) } ?: Color(0xFF8E9BAE)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = modifier
            .width(156.dp)
            .height(64.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .glassSurface(
                    shape = RoundedCornerShape(14.dp),
                    tint = cardTint(courseColor, 0.08f),
                    tintAlpha = 0.08f,
                    baseCoatAlpha = CardBaseCoatAlpha
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧课程色细条
            Box(
                modifier = Modifier
                    .size(3.5.dp, 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentBarColor(courseColor))
            )
            Spacer(modifier = Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 主行：作业名称
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))

                // 备注行：DDL（紧急状态突出显示）· 课程名
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.dueFormatted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (item.isUrgent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 10.sp
                        ),
                        color = if (item.isUrgent) Color(0xFFC84238) else TextSecondary,
                        maxLines = 1
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

private fun formatLastUpdatedTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "${minutes}分钟前"
        else -> {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            "${sdf.format(Date(timestamp))} 更新"
        }
    }
}

