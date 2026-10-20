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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.cardRevealAt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.source.zju.CoursesTodoItem
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.TaskItemCard
import com.example.ui.components.WeatherBlock
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.formatRemaining
import com.example.ui.components.periodStartMillis
import com.example.ui.components.todayStatusLabel
import com.example.ui.components.todayCalendar
import com.example.data.calendar.AppZone
import com.example.data.calendar.EpochDays
import com.example.data.timetable.formatMinute
import com.example.data.calendar.ScheduleResolver
import com.example.ui.components.parseColor
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.MiuSurface
import com.example.ui.theme.MiuType
import com.example.ui.theme.accentBarColor
import com.example.ui.theme.accentOnCourse
import com.example.ui.theme.cardTint
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TeaOnColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.glassSurface
import com.example.ui.theme.wallpaperBlur
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.theme.LocalWallpaperIsCustom
import com.example.ui.viewmodel.MainUiState
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

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
        // 顶部的「2026年10月19日 星期一」是**北京**的日期。
        // 无参 SimpleDateFormat 走设备时区，GMT 设备在北京凌晨会显示昨天的星期几。
        AppZone.format("yyyy年M月d日 EEEE", System.currentTimeMillis(), Locale.CHINESE)
    }

    // 课程状态每 30 秒自己走一次，不能等用户碰一下屏幕才更新。
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    // 不做深色模式：两套都是浅色。预设壁纸保留原版的通透；
    // 用户照片的细节不可控，换一档更厚的玻璃，并把主卡垫起来。
    val customWallpaper = LocalWallpaperIsCustom.current
    val contentBase = if (customWallpaper) 0.72f else 0.55f
    val courseTint = if (customWallpaper) 0.26f else 0.30f
    val heroBase = if (customWallpaper) 0.55f else 0f

    // 今日课程：判定权交给 resolver（开课周次 / 停课 / 补课都在它里面）。
    // 停课日这里会得到空列表，界面据此显示「今天停课」而不是一片空白。
    val resolver = remember(state.calendar) { ScheduleResolver(state.calendar) }
    val todaySlots = remember(state.courseSlots, state.slotWeeks, state.calendar) {
        resolver.slotsOn(EpochDays.today(), state.courseSlots, state.slotWeeks)
    }
    val todayIsNoClassDay = remember(state.calendar, state.noClassDayCount) {
        !state.calendar.isSchoolDay(EpochDays.today())
    }

    // 当前/下一节：用来在原位置把其中一张卡加高。
    // 同一时间有多节重叠时取开始更早的那节；不改变列表顺序（用户选的是方案 A）。
    val nowMinute = remember(nowMillis) {
        AppZone.calendar(nowMillis).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    }
    val slotWindows = remember(todaySlots, state.periodTable) {
        todaySlots.mapNotNull { slot ->
            val start = state.periodTable.startMinuteOf(slot.startPeriod)
                ?: return@mapNotNull null
            val end = state.periodTable.endMinuteOf(slot.endPeriod)
                ?: return@mapNotNull null
            SlotWindow(slot, start, end)
        }
    }
    val currentWindow = slotWindows.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
    val nextWindow = slotWindows.firstOrNull { it.startMinute > nowMinute }
    val featuredWindow = currentWindow ?: nextWindow
    val featuredSlotId = featuredWindow?.slot?.id
    val featuredIsCurrent = currentWindow != null
    // 下一节的倒计时和组头共用同一个 nowMillis，避免组头 53 分、卡片 54 分。
    val featuredStartMillis = featuredWindow?.let {
        periodStartMillis(todayCalendar, it.slot, state.periodTable)
    } ?: 0L
    val featuredCountdown = if (featuredWindow != null && !featuredIsCurrent) {
        // 只说「还有 51 分」，不接「开始」：组头已经说过「下节课还有 51 分 开始」，
        // 卡片右栏那枚「开课」标签又把「开始」说了一遍——同一个词在一屏里出现三次，
        // 就得砍到只剩最该说的那处。卡片这一行接上右栏正好读成
        //「还有 51 分 · 开课 13:25」。
        "还有 ${formatRemaining((featuredStartMillis - nowMillis).coerceAtLeast(0L))}"
    } else {
        ""
    }

    val courseMap = remember(state.coursesWithSlots) {
        state.coursesWithSlots.associateBy { it.course.id }
    }

    val pendingTasks = remember(state.tasks) {
        state.tasks.filter { !it.isCompleted }
    }

    // 本周课时：走 slotsInWeek 让单双周过滤生效，否则周课表上只上单周，
    // 副标题却按全量算出一个对不上的小时数。
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

    // 组头文案沿用原来的 todayStatusLabel：
    // 正在上课 →「还有 X 分 下课」；还没到下一节 →「下节课还有 X 开始」；
    // 全部上完 / 今天没课 →「课都上完啦，休息一下吧~」。
    val coursesHeader = todayStatusLabel(todayCalendar, todaySlots, nowMillis, state.periodTable)

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
                shape = MiuSurface.HeroShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                if (heroFlipRotation <= 90f) {
                    // 正面：日期与天气卡片（点击卡片主体翻转至背面学在浙大）。
                    // 浅色壁纸不铺白衬底，只保留原来那层 10% 白玻璃；
                    // 深色壁纸才按亮度把衬底推到 0.90，保证日期和正文读得出来。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MiuSurface.HeroShape)
                            .wallpaperBlur()
                            .glassSurface(
                                shape = MiuSurface.HeroShape,
                                tint = Color.White,
                                tintAlpha = 0.10f,
                                baseCoatAlpha = heroBase,
                                blurRadius = 0.dp
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
                                    text = "${state.courses.size} 门课 · $weekHoursText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            WeatherBlock(
                                state = state.weather,
                                hourOfDay = AppZone.hourOfDayNow()
                            )
                        }
                    }
                } else {
                    // 背面：学在浙大待交作业卡片（抵消 180° 水平翻转，恢复正向文字渲染）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MiuSurface.HeroShape)
                            .wallpaperBlur()
                            .glassSurface(
                                shape = MiuSurface.HeroShape,
                                tint = Color.White,
                                tintAlpha = 0.10f,
                                baseCoatAlpha = heroBase,
                                blurRadius = 0.dp
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
                                            // 原来写「当前没有未交作业，所有任务已结清」：
                                            // 前半句和后半句说的是同一件事，而「任务」
                                            // 是这个 App 里第四个称呼（待办 / 作业 /
                                            // 课程 / 任务），指的却是同一批东西。
                                            // 这一栏是「学在浙大」拉回来的作业，就只说作业。
                                            text = "✨ 暂无未交作业",
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
        item {
            DashboardSectionHeader(
                title = coursesHeader,
                titleSize = 18.sp
            )
        }

        if (todaySlots.isEmpty()) {
            item {
                Card(
                    shape = MiuSurface.CardShape,
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
                            .glassSurface(shape = MiuSurface.CardShape, baseCoatAlpha = contentBase)
                            .padding(horizontal = 18.dp, vertical = 20.dp)
                    ) {
                        Column {
                            Text(
                                text = if (todayIsNoClassDay) "今天停课" else "今天没课",
                                style = MiuType.CourseTitle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pendingTasksSummary(pendingTasks.size),
                                style = MiuType.CourseMeta,
                                color = TextSecondary
                            )
                        }
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
                    val pendingCount = linked.count { !it.isCompleted }
                    val hasLinked = linked.isNotEmpty()
                    val courseColor = parseColor(course.colorHex)
                    val (timeStart, timeEnd) = state.periodTable.timeRange(slot.startPeriod, slot.endPeriod)
                        ?: ("--" to "--")
                    val room = slot.classroom.ifBlank { course.classroom }
                    // 白胶囊是玻璃的一部分：不做深色模式，所有壁纸都走同一档。
                    val isFeatured = slot.id == featuredSlotId

                    if (isFeatured && featuredWindow != null) {
                        FeaturedCourseCard(
                            course = course,
                            slot = slot,
                            pendingCount = pendingCount,
                            hasLinked = hasLinked,
                            room = room,
                            timeStart = timeStart,
                            timeEnd = timeEnd,
                            courseColor = courseColor,
                            isCurrent = featuredIsCurrent,
                            startMinute = featuredWindow.startMinute,
                            endMinute = featuredWindow.endMinute,
                            nowMinute = nowMinute,
                            countdownText = featuredCountdown,
                            contentBase = contentBase,
                            courseTint = if (customWallpaper) 0.28f else 0.32f,
                            modifier = Modifier
                                .cardRevealAt(cardRevealProgress, courseOrderBase + slotIndex)
                                .clickable { onInspectCourse(cWithSlots) }
                        )
                    } else {
                    Card(
                        shape = MiuSurface.CardShape,
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
                                .glassSurface(
                                    shape = MiuSurface.CardShape,
                                    tint = cardTint(courseColor, courseTint),
                                    tintAlpha = courseTint,
                                    baseCoatAlpha = contentBase,
                                )
                                .courseCardWash(
                                    courseColor = courseColor,
                                    shape = MiuSurface.CardShape,
                                    // 不描边：白边已取消，边界交给卡面本身。
                                    washAlpha = 0.10f,
                                    sheenAlpha = 0.11f
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp, 44.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                accentBarColor(courseColor),
                                                accentBarColor(courseColor).copy(alpha = 0.55f)
                                            )
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = course.name,
                                        style = MiuType.CourseTitle.copy(fontSize = 14.5.sp),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (room.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val roomShape = RoundedCornerShape(6.dp)
                                        Box(
                                            modifier = Modifier
                                                .clip(roomShape)
                                                .background(courseColor.copy(alpha = 0.16f))
                                                .border(0.5.dp, courseColor.copy(alpha = 0.28f), roomShape)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                // 不带「@」：设计笔记的现状诊断第 6 条写着
                                                // 「@ 教室胶囊的『@』是程序员语法，不是学生语言」，
                                                // 加高主卡早就照这条改掉了，这一枚小胶囊
                                                // 却还留着。同一个 App 里同一件事实用两种写法，
                                                // 改掉一处就等于把另一处也标成了待办。
                                                text = room,
                                                color = accentOnCourse(courseColor),
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd) · ${course.teacher}",
                                    style = MiuType.CourseMeta.copy(fontSize = 10.5.sp),
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            CoursePendingBadge(
                                pendingCount = pendingCount,
                                hasLinked = hasLinked,
                                courseColor = courseColor
                            )
                        }
                    }
                    }
                }
            }
        }

        // Section: Urgent / Today's Pending Tasks
        item {
            DashboardSectionHeader(
                title = "待办清单 · ${pendingTasks.size}",
                titleSize = 16.sp
            )
        }

        if (pendingTasks.isEmpty()) {
            item {
                Card(
                    shape = MiuSurface.CardShape,
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
                            .glassSurface(shape = MiuSurface.CardShape, baseCoatAlpha = contentBase)
                            .padding(horizontal = 18.dp, vertical = 20.dp)
                    ) {
                        Column {
                            Text(
                                text = "全部完成",
                                style = MiuType.CourseTitle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "这一周可以轻松一点了",
                                style = MiuType.CourseMeta,
                                color = TextSecondary
                            )
                        }
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
    val contentBase = if (LocalWallpaperIsCustom.current) 0.80f else 0.55f

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
                    baseCoatAlpha = contentBase
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
        else -> "${AppZone.format("HH:mm", timestamp)} 更新"
    }
}

/**
 * 组头：就是一行纯文字，回到原版的样子。
 *
 * 不做深色模式，也不给壁纸加自动磨砂底；深色照片需要保护时，
 * 用户自己在壁纸设置里拉「文字保护遮罩」。
 */
/** 主卡的当前/下一节时间窗：起止分钟一次算好。 */
private data class SlotWindow(
    val slot: CourseSlotEntity,
    val startMinute: Int,
    val endMinute: Int
)

/**
 * 课程卡的“色带玻璃”：课程色从左侧晕开、顶部一道很轻的高光。
 * 叠在 glassSurface 之上，不改变卡片尺寸和交互。
 *
 * **不描边**：白边已经取消——白边在浅色壁纸上只是勉强算材质，在深色/花纹壁纸上
 * 是一圈硬邦邦的亮线，而卡片边界本来就由「面更实 + 更大的模糊半径」这两件事撑着
 * （见 Mica.kt）。主卡那圈 1dp 课程色描边也跟着撤了：它原本是拿来当「这是主卡」
 * 的层级信号，现在主卡已经靠**加高 + 状态标记 + 右栏读数**站出来了，不必再描一圈。
 * 描边给出去就收回来，别留一个没人用的旋钮。
 */
private fun Modifier.courseCardWash(
    courseColor: Color,
    shape: RoundedCornerShape,
    washAlpha: Float = 0.10f,
    sheenAlpha: Float = 0.11f
): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to accentBarColor(courseColor).copy(alpha = washAlpha),
                0.62f to Color.Transparent
            )
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheenAlpha),
                0.5f to Color.Transparent
            )
        )
    }

/** 课程卡右侧的待办小签：同色系描边，不再用一块高白胶囊。 */
@Composable
private fun CoursePendingBadge(
    pendingCount: Int,
    hasLinked: Boolean,
    courseColor: Color,
    modifier: Modifier = Modifier
) {
    if (pendingCount > 0) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f))
                .border(0.75.dp, courseColor.copy(alpha = 0.38f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentOnCourse(courseColor))
                )
                Text(
                    text = "$pendingCount",
                    color = accentOnCourse(courseColor),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else if (hasLinked) {
        Box(
            modifier = modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f))
                .border(0.75.dp, courseColor.copy(alpha = 0.38f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                color = accentOnCourse(courseColor),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * 主卡上那个状态标记。
 *
 * 「现在」是**主题绿实心**，「下一节」是**磨砂白面 + 墨黑字**。
 *
 * 为什么不再用两档绿：旧版「下一节」那枚淡绿胶囊压在粉彩卡上，是全卡对比度
 * 最高的元素，比课名还响——可绿色跟「这门课」没有任何关系，纯粹是拿了一个
 * 不相干的颜色占了最贵的对比度（设计笔记的规矩：颜色是线索，不是装饰；
 * 紧迫度负责「现在看哪条」）。
 *
 * 现在两态靠**实心 / 磨砂**分轻重：「现在」是正在发生，主题绿是语义色，
 * 值得占一个实心块；「下一节」还没发生，给一枚中性标签就够——
 * 中性的好处是它落在任何一门课的粉彩卡面上都不撞色。
 */
@Composable
private fun FeaturedStateChip(
    isCurrent: Boolean,
    modifier: Modifier = Modifier
) {
    val background = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val foreground = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isCurrent) "现在" else "下一节",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = foreground,
            maxLines = 1
        )
    }
}

/**
 * 加高的主卡：当前课 / 下一节课。
 *
 * 排版按**信息成组**来：左边是这门课的事实（课名 / 教室 / 节次 / 教师），
 * 右边是一个时间读数，中间一根发丝线分开，底部那行按这一态有没有百分比来给。
 *
 * 相对旧版改了三处，都是为了把「响」让给该响的东西：
 *
 * 1. **状态标记不再跟课程色打架**（见 [FeaturedStateChip]）。
 * 2. **右栏改成「标签在上、读数在下」**。旧版把「开始」压在 13:25 底下，
 *    而这个词组头已经说过一次（组头：「下节课还有 51 分 开始」），
 *    卡里再说就是第三次。现在右栏只留一个标签说明右边那个数字是什么，
 *    文案也改成「开课」，跟组头的「开始」不撞词。
 * 3. **左轨满高，底部各归各位**。旧版左轨只有 56dp，在 112dp 的卡里像半截
 *    没画完。现在左轨贯通内容区。底部那行按**这一态到底有没有百分比**来给：
 *    「现在」有——进度条就是「这节课上了多少」的量表，所以画轨道 +「已上 92%」；
 *    「下一节」没有——还没开课，没有百分比可言，硬画一条空轨道只是拿一根
 *    没有含义的线占位置，所以那里只留倒计时本身。
 * 4. **教室升成速查项**（见下）。
 * 5. **不描边**。主卡那圈 1dp 课程色描边撤掉了：它原本是「这是主卡」的层级信号，
 *    现在主卡已经靠**加高 + 状态标记 + 右栏读数**站出来了，不必再描一圈。
 *
 * ### 教室为什么单独占一行
 *
 * 用户原话：「教室信息对用户来说是需要速查的信息，要明显一点」。
 * 旧版把教室塞成 meta 行里一枚 9.5sp 的小胶囊，压在 11sp 的节次/时间/教师旁边——
 * 那是整张卡上**最小**的一枚字，却是出门前最要紧的一个数。速查的东西不该
 * 藏在最细的一行里。
 *
 * 现在教室自己占一行：位置图标 + 14sp SemiBold + 主文字墨色，夹在课名和
 * meta 行之间，字号只比课名小一档。顺带把「@」去掉了——课程详情弹层里
 * 教室本来就用 [Icons.Outlined.LocationOn] 表示（见 CourseTimetableComponents），
 * 图标已经说明了「这是地点」，再挂一个「@」是重复；设计笔记的现状诊断也写着
 * 「@ 教室胶囊的『@』是程序员语法，不是学生语言」。
 *
 * 教室名写**全名**（带「紫金港」这样的校区前缀），不砍前缀——理由见
 * 下面 `room` 变量上方那段注释。
 *
 * ### 行高必须显式写
 *
 * Compose 的 `Text` 只改 `fontSize` 不改 `lineHeight` 时，会**继承默认主题的
 * lineHeight**（本项目是 24sp）——11sp 的小字和 17sp 的课名因此占着一样高的
 * 行框，24dp 一行是白白浪费的。这张卡要同时塞下四行事实，就把这几行的
 * lineHeight 逐条写死，行高立刻省出 20dp 上下，教室这一行才塞得进去而
 * 不必把卡继续加高。
 */
@Composable
private fun FeaturedCourseCard(
    course: CourseEntity,
    slot: CourseSlotEntity,
    pendingCount: Int,
    hasLinked: Boolean,
    room: String,
    timeStart: String,
    timeEnd: String,
    courseColor: Color,
    isCurrent: Boolean,
    startMinute: Int,
    endMinute: Int,
    nowMinute: Int,
    countdownText: String,
    contentBase: Float,
    courseTint: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val duration = (endMinute - startMinute).coerceAtLeast(1)
    val progress = if (isCurrent) {
        ((nowMinute - startMinute).toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (progress * 100).roundToInt()
    // 教室写全名（带校区前缀）。
    //
    // 早先这里会砍掉「紫金港」前缀，为的是给同一行里的教师名腾宽度——那时教室是
    // meta 行里一枚 9.5sp 的小胶囊，挤得下不下全看教师名长不长。
    // 那个理由现在**正好反过来了**：教室已经提成速查项、自己独占一行，宽度根本不紧张；
    // 而被砍掉的「紫金港」恰恰是速查时最要紧的那部分——「西2-105」不写校区，
    // 学生分不清是紫金港还是玉泉。列表卡、课程详情、课表页也一直都是写全名的。
    //
    // 判据：**为省宽度砍掉关键字段，是把成本转嫁给最不该承担它的人**——
    // 砍掉的是要出门找教室的人要读的那几个字，省下的是一行的空白。

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(124.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .glassSurface(
                    shape = shape,
                    tint = cardTint(courseColor, courseTint),
                    tintAlpha = courseTint,
                    baseCoatAlpha = contentBase
                )
                .courseCardWash(
                    courseColor = courseColor,
                    shape = shape,
                    washAlpha = 0.10f,
                    sheenAlpha = 0.12f
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左轨贯通整个内容区。原来只有 56dp，在 112dp 的卡里像半截没画完。
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accentBarColor(courseColor),
                                accentBarColor(courseColor).copy(alpha = 0.55f)
                            )
                        )
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeaturedStateChip(isCurrent = isCurrent)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = course.name,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 教室：速查项，单独一行。图标跟课程详情弹层用同一个（LocationOn）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (room.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = accentOnCourse(courseColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = room,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd) · ${course.teacher}",
                    style = MiuType.CourseMeta.copy(fontSize = 11.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 底部：时间进度。**轨道只属于「现在」**——
                // 进度条是「这节课上了百分之多少」的量表，「下一节」还没有百分比可言，
                // 画一条空轨道只是拿一根没有含义的线占位置。
                // 「下一节」这一行只留倒计时本身。
                if (isCurrent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.55f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                accentBarColor(courseColor),
                                                cardTint(courseColor, 0.55f)
                                            )
                                        )
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = "已上 $percent%",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentOnCourse(courseColor),
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = countdownText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))
            // 发丝线贯通内容区，把右边读数单独划成一列。
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.34f))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.width(64.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 标签在上、读数在下：旧版把「开始」压在数字底下，
                // 那个词又被组头说过一次，现在右栏只说明右边这个数字是什么。
                Text(
                    text = if (isCurrent) "下课" else "开课",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = formatMinute(if (isCurrent) endMinute else startMinute),
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(7.dp))
                CoursePendingBadge(
                    pendingCount = pendingCount,
                    hasLinked = hasLinked,
                    courseColor = courseColor
                )
            }
        }
    }
}

@Composable
private fun DashboardSectionHeader(
    title: String,
    titleSize: TextUnit,
    meta: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val customWallpaper = LocalWallpaperIsCustom.current
        Row(
            modifier = if (customWallpaper) {
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.62f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            } else {
                Modifier
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!meta.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** 没课 / 课上完时，空态把注意力引到待办上。 */
private fun pendingTasksSummary(count: Int): String =
    if (count > 0) "还有 $count 条待办" else "今天没有待办"

