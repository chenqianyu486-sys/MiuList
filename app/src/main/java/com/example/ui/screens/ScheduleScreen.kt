package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.cardRevealAt
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.components.CourseTimetableCell
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.epochDayOf
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.todayCalendar
import com.example.ui.components.todayStatusLabel
import com.example.data.calendar.DayOffReason
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.data.calendar.WeekDayPlan
import com.example.data.timetable.PeriodTable
import com.example.ui.components.parseColor
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.CardListGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.CourseTintAlpha
import com.example.ui.theme.CourseTintVivid
import com.example.ui.theme.FlatGridColumnLine
import com.example.ui.theme.FlatGridLine
import com.example.ui.theme.FlatGridLineStrong
import com.example.ui.theme.FlatGridNumber
import com.example.ui.theme.FlatTodayColumn
import com.example.ui.theme.LimeGreenOnPrimary
import com.example.ui.theme.LimeGreenPrimary
import com.example.ui.theme.TeaColor
import com.example.ui.theme.TeaColorBorder
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.isGreenishColor
import com.example.ui.theme.glassSurface
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.theme.deepEnoughForWhite
import com.example.ui.theme.pageChangeSpec
import com.example.ui.theme.rememberCardRevealProgress
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.ScheduleViewMode
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

@Composable
fun ScheduleScreen(
    state: MainUiState,
    onSelectViewMode: (ScheduleViewMode) -> Unit,
    onSelectWeek: (Int) -> Unit,
    onOpenWeekPicker: () -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    modifier: Modifier = Modifier
) {
    // 排课判定的唯一入口：开课周次、停课、补课都在它里面，
    // 周课表与列表页共用，不会各算各的。
    val resolver = remember(state.calendar) { ScheduleResolver(state.calendar) }
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // 视图切换（周课表 / 列表）。「生成本周待办」原来和它并排放在这一行，
        // 现在已经上顶栏成了常驻图标（AutoAwesome）：它是跨页面的全局操作，
        // 不属于课表页这一屏，挂在页内等于只有翻到课表页才找得到。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Flat Segmented Control (扁平化：外层平涂浅底，选中块滑进去，不描边)
            FlatSegmentedRow(
                selectedIndex = if (state.scheduleViewMode == ScheduleViewMode.WEEK_GRID) 0 else 1
            ) {
                FlatSegment(
                    label = "周课表",
                    icon = Icons.Outlined.ViewWeek,
                    selected = state.scheduleViewMode == ScheduleViewMode.WEEK_GRID,
                    onClick = { onSelectViewMode(ScheduleViewMode.WEEK_GRID) },
                    testTag = "schedule_tab_week_grid"
                )
                FlatSegment(
                    label = "列表",
                    icon = Icons.Outlined.ViewDay,
                    selected = state.scheduleViewMode == ScheduleViewMode.DAY_TIMELINE,
                    onClick = { onSelectViewMode(ScheduleViewMode.DAY_TIMELINE) },
                    testTag = "schedule_tab_day_timeline"
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 两种视图之间的切换走的是**和底部浮岛切页同一套**转场：
        // 旧视图缩小淡出 85ms → 新视图骨架淡入放大 110ms（晚 10ms 起跑，防拖影）
        // → 课程块再由虚及实、从上往下依次亮起 600ms。规格与时间轴都在 Mica.kt。
        //
        // 「换一个数据视图」和「换一页」在体感上是同一件事，不该一个滑动一个硬切。
        // 规格收在共享函数里而不是这里再抄一遍，是因为那几个数字每一个都牵着理由，
        // 抄一份就等于让其中一份变成没有依据的手感参数。
        //
        // weight(1f) 挂在 AnimatedContent **自己**身上而不是里面的内容：
        // ColumnScope 只对直接子节点有效，挪进内容 lambda 里编译不过。
        AnimatedContent(
            targetState = state.scheduleViewMode,
            transitionSpec = { pageChangeSpec() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = "schedule_view_transition"
        ) { viewMode ->
            // 第三段：课程块由虚及实、从上往下依次亮起。规格与时间轴和底部浮岛
            // 切页是同一份（见 Mica.kt），所以这里不传首帧豁免——每次重建都真的
            // 存在一次「旧视图」，该放就放。
            val cardRevealState = rememberCardRevealProgress()

            CompositionLocalProvider(LocalCardRevealProgress provides cardRevealState) {
                if (viewMode == ScheduleViewMode.WEEK_GRID) {
                    // 原来这里还有一条「有待办 / 无待办」图例条，已按要求撤掉（省一行竖向空间）。
                    // 状态本身不需要图例：深浅两档本来就靠底色本身表达，
                    // 角标上的「N 待办」/「✓」也只报数目不报颜色语义。
                    // 七列按「这一周真实的日期」排，不是按星期几——补课只有在这里才表达得出来。
                    // 判定全部交给 resolver 的 weekPlan（与列表页 slotsOn 同一套），
                    // 这一层不再自己筛一次，免得两边各算各的。
                    val plan = remember(
                        state.courseSlots,
                        state.slotWeeks,
                        state.selectedWeek,
                        state.calendar
                    ) {
                        resolver.weekPlan(state.selectedWeek, state.courseSlots, state.slotWeeks)
                    }
                    WeekTimetableGrid13(
                        week = state.selectedWeek,
                        currentWeek = state.currentWeek,
                        totalWeeks = state.totalWeeks,
                        plan = plan,
                        coursesWithSlots = state.coursesWithSlots,
                        tasks = state.tasks,
                        periodTable = state.periodTable,
                        onSelectWeek = onSelectWeek,
                        onOpenWeekPicker = onOpenWeekPicker,
                        onInspectCourse = onInspectCourse,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    DayTimelineView13(
                        coursesWithSlots = state.coursesWithSlots,
                        slots = state.courseSlots,
                        tasks = state.tasks,
                        periodTable = state.periodTable,
                        resolver = resolver,
                        weeksBySlotId = state.slotWeeks,
                        onInspectCourse = onInspectCourse,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PageHInset)
                    )
                }
            }
        }
    }
}

/**
 * 分段控件里每一档的宽度。**两档必须等宽**，这不是排版偏好，是动画的前提：
 * 选中块靠一块会滑动的背景指示器表示位置，位移 = 段宽 × 序号。
 * 早先两档按内容自适应（「周课表」比「列表」宽）、各自画自己的背景，
 * 那样「一格消失、另一格出现」读起来像刷新；改成滑动指示器后位移必须有个固定步长，
 * 等宽正好把这个步长给出来，顺带让控件左右对称。
 *
 * 76.dp 的来历：11.5.sp 的「周课表」约 34.dp + 图标 15.dp + 4.dp 间距 = 53.dp，
 * 剩下的 23.dp 作为居中文字两侧的呼吸；换成有文字标签的版本时也不会挤。
 */
private val ScheduleTabSegmentWidth = 76.dp

/** 外框高度。含 3.dp 内边距，选中块（指示器）实际高 24.dp。 */
private val ScheduleTabHeight = 30.dp

/** 外框内边距，同时也是两档之间的视觉缝宽：选中块要正好贴满整块高度，不留白边。 */
private val ScheduleTabInset = 3.dp

/**
 * 选中块滑动 + 字色过渡的时长。
 *
 * 220ms 是「看得清它在滑、又没让人等」的那一档：短于 ~150ms 时它读成瞬间跳变，
 * 长于 ~300ms 就变成一个需要等待的动画了——它只是确认「我切到了」，不是转场。
 * 字色用同一条曲线，于是绿块走到哪、字就亮到哪，两者是同一件事而不是两件事。
 */
private const val ScheduleTabAnimMs = 220

/**
 * 分段控件外框：一层平涂浅底（surfaceVariant），不描边、不加阴影，
 * 里面压一块会滑动的选中块。
 *
 * 选中态由**一整块会动的绿**表示，而不是让两档各自换背景色：两个独立背景在切换瞬间
 * 是「一格消失、另一格出现」，读起来像刷新；一块滑过去则是同一个控件换了位置，
 * 用户能直接看出「这两个是同一个东西的两档」。
 *
 * 容器宽度**写死**（两段 + 内边距 + 缝），因为外层那一行要用 Arrangement.Center 把它
 * 摆到正中，而按内容自适应的话居中的是内容、控件宽度会随文字抖动。
 */
@Composable
private fun FlatSegmentedRow(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    // 指示器的位移就是「段宽 + 缝」，索引直接乘进去：两档等宽，所以不需要测宽，
    // 也不需要在切换时读布局去反推每档的实际位置。
    val indicatorOffset by animateDpAsState(
        targetValue = if (selectedIndex == 0) 0.dp
        else ScheduleTabSegmentWidth + ScheduleTabInset,
        animationSpec = tween(ScheduleTabAnimMs, easing = FastOutSlowInEasing),
        label = "schedule_tab_indicator"
    )
    Box(
        modifier = modifier
            // 3 = 左右内边距（2）+ 两档之间的缝（1）
            .width(ScheduleTabSegmentWidth * 2 + ScheduleTabInset * 3)
            .height(ScheduleTabHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(ScheduleTabInset)
    ) {
        // 指示器必须**叠在外框上**，不能作为 Row 的子项混进排布：它自己就占一个段宽
        // （76.dp），放进 Row 会把两段文字整体右推，控件宽度又只按两段算，
        // 于是「列表」被挤出框外看不见——实机截图才发现，编译期完全看不出来。
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(ScheduleTabSegmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        // 两档排在指示器之后画：同父级里先画的在下，正好转绿块垫在文字底下。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ScheduleTabInset),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * 分段按钮：**不画自己的底色**，底色由 [FlatSegmentedRow] 里那块滑动的指示器统一表示。
 *
 * 选中态在这里只剩两件事：字色换成深墨（配合绿底，见 Color.kt 的全局约定）、
 * 字重加粗。字号两档一致且固定，所以加粗时文字虽然变宽，但它是**居中排**的——
 * 两侧同时外扩，不会把整段控件的宽度或相邻档的位置顶动。
 */
@Composable
private fun FlatSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    testTag: String? = null
) {
    // 字色跟着走一段比瞬间跳变顺：指示器要滑 220ms，字色若瞬间换，
    // 会出现「绿块还在路上、字已经黑了」的一瞬不协调。
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(ScheduleTabAnimMs, easing = FastOutSlowInEasing),
        label = "schedule_tab_label_color"
    )
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .width(ScheduleTabSegmentWidth)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor
            )
        }
    }
}

/**
 * 单个时段在日列里的分栏结果。
 *
 * [column] 是 0 基的列号，[columns] 是这一簇（相互重叠的一组课）一共分成几列。
 * 只被自己一个课占的簇，columns = 1，格子仍然占满整列。
 */
private data class DaySlotPlacement(
    val slot: CourseSlotEntity,
    val column: Int,
    val columns: Int
) {
    val conflicted: Boolean get() = columns > 1
}

/**
 * 把一天里的时段按时间重叠分簇，簇内再贪心分列。
 *
 * 原来的做法是所有格子一律 `fillMaxWidth()` 往上叠，两门撞课的课表互相压住：
 * 后画的那门把前一门整个盖掉，时间上其实是两节，用户只看得见一节，
 * 等于课表在关键信息上撒谎。
 *
 * 现在的算法：
 * 1. 按开始节次排序，扫一遍把「传递重叠」的课归到同一簇（A 和 B 重叠、B 和 C 重叠，
 *    但 A 和 C 不重叠，这三节仍然算一簇——分开列的话中间那列会空出难看的缺口）；
 * 2. 簇内贪心分列：给每节课找第一个「上一节已经上完」的列，都占满了就新开一列；
 * 3. 簇里用到几列，格子就分几列宽。这样 3 门课全撞时会分成三列，
 *    而不是把三块颜色摞在一起。
 */
private fun layoutDaySlots(daySlots: List<CourseSlotEntity>): List<DaySlotPlacement> {
    if (daySlots.isEmpty()) return emptyList()

    val sorted = daySlots.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
    val placements = mutableListOf<DaySlotPlacement>()

    var cluster = mutableListOf<CourseSlotEntity>()
    var clusterEnd = Int.MIN_VALUE

    fun flushCluster() {
        if (cluster.isEmpty()) return
        // 每列记「这一列最后一节课上到第几节」，用来判断新一节能不能接进去
        val columnEnds = mutableListOf<Int>()
        val columnOf = mutableMapOf<Long, Int>()
        for (s in cluster) {
            var placedAt = -1
            for (i in columnEnds.indices) {
                if (columnEnds[i] < s.startPeriod) {
                    columnEnds[i] = s.endPeriod
                    placedAt = i
                    break
                }
            }
            if (placedAt < 0) {
                placedAt = columnEnds.size
                columnEnds.add(s.endPeriod)
            }
            columnOf[s.id] = placedAt
        }
        val total = columnEnds.size
        for (s in cluster) {
            placements.add(DaySlotPlacement(s, columnOf[s.id] ?: 0, total))
        }
        cluster = mutableListOf()
        clusterEnd = Int.MIN_VALUE
    }

    for (s in sorted) {
        if (cluster.isNotEmpty() && s.startPeriod > clusterEnd) flushCluster()
        cluster.add(s)
        clusterEnd = maxOf(clusterEnd, s.endPeriod)
    }
    flushCluster()

    return placements
}

/**
 * 撞课列之间的缝。
 *
 * 格子本身两侧已经有 2.dp 的 padding，所以这条缝不需要太宽：2.dp 刚好让两块颜色
 * 之间出现一道白线，窄到不会把本就不宽的格子再切掉一截。
 */
private val ConflictColumnGap = 2.dp

/**
 * 周课表每一节课（一个节次行）的高度。
 *
 * 沿革：62dp → 43dp（按「变矮 30%」）→ **48dp**。
 *
 * 加回 5dp 是因为大节分割线取消后整张表只剩统一的 0.5dp 细线，
 * 课程块之间的呼吸全靠行距撑着，43dp 在 7 列并排时相邻色块贴得太紧、
 * 表读起来发闷。48dp 让一行里上下各多出约 2.5dp 空隙，
 * 在总高仍然一屏能放下的前提下把节奏松开。
 *
 * **改这一处要把所有推导都跟着改**：列总高 `totalHeight`、课程块的 `topOffset` 与
 * `cardHeight` 全部由它算出，代码里不允许再出现第二个行高字面量。
 *
 * 行高是格子内容的硬约束（见 `CourseTimetableCell` 的 maxLines 推导）：
 * 48dp 时单节卡的内容区约 38dp（48 − 4 外 padding − 6 内 padding），
 * 一行 12.5sp 标题 + 徽标仍放得下，两行标题只留给连堂课。
 */
private val PeriodRowHeight = 48.dp

/**
 * 周课表表头每一列（周一~周日）的高度。
 *
 * 单行文字 + 一个小图标，32.dp 就够。早先试过两行（「周五 10/16」+ 文字徽标），
 * 那让列头要占 44.dp，而且七列各挤一个 `10/12` 又多占一份与周次条重复的信息——
 * 日期已经由周次条的「10/12 – 10/18」说清了。
 */
private val WeekHeaderHeight = 32.dp

/**
 * 课表列背景放假水印图标的大小。
 *
 * 放假日期在课表对应列的背景中半透明居中展示，作为放假标识水印。
 * 在约 51.dp 宽的列内，28.dp 既足够清晰醒目，又与列宽留有充足呼吸余量（左右约各 11.dp）。
 */
private val HolidayWatermarkSize = 28.dp

/**
 * 左侧节次刻度列的宽度。
 *
 * 节次数字只在这里出现一次（13 个），七个日列里不再重复。
 * 20.dp 能放下两位数（13）+ 右侧留白 7.dp，数字右对齐后正好贴住标尺轴。
 *
 * 表头那一侧的占位宽度不是这个值，而是 `PeriodRulerWidth - 10.dp`：
 * 表头 Row 自带 6.dp 水平 padding 和 4.dp 的块间距，两者都要扣掉，
 * 否则七个星期块会整体右移，压不到对应的日列上。
 */
private val PeriodRulerWidth = 20.dp

/**
 * 周次条：第几周 + 日期区间 + 这一周的放假 / 补课概况。
 * 左右箭头翻周，点中间开周选择器。
 *
 * 「这一周有什么不一样」写在**周次条**上而不是让用户逐列去认，是因为用户翻周时的
 * 第一个问题从来不是「周三有什么课」，而是「这一周要不要我早起去上一天课」。
 * 概况行只在真有调休或当前是本周时才出现：16 周里绝大多数是普通周，
 * 为它们恒定预留一行，等于每翻一页都欠一行高度。
 */
@Composable
private fun WeekStrip(
    week: Int,
    currentWeek: Int,
    plan: List<WeekDayPlan>,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onSelectWeek: (Int) -> Unit,
    onOpenPicker: () -> Unit
) {
    val monday = plan.firstOrNull()?.epochDay ?: 0L
    val sunday = plan.lastOrNull()?.epochDay ?: 0L
    val offDays = plan.count {
        it.offReason == DayOffReason.NO_CLASS || it.offReason == DayOffReason.VACATED
    }
    val makeupDays = plan.count { it.isMakeup && it.offReason == null }

    val notes = buildList {
        // 「本周」跟着浏览位置走而不是跟着日期走：用户翻到第 5 周时它就消失，
        // 这正是「我现在不在本周」最省事的确认方式，不需要另做高亮。
        if (week == currentWeek) add("本周")
        if (plan.isNotEmpty() && plan.all { it.offReason == DayOffReason.OUT_OF_TERM }) {
            add("不在学期范围内")
        }
        if (offDays > 0) add("放假 $offDays 天")
        if (makeupDays > 0) add("补课 $makeupDays 天")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeekStepButton(
                icon = Icons.Outlined.ChevronLeft,
                enabled = canGoBack,
                description = "上一周",
                onClick = { onSelectWeek(week - 1) }
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenPicker),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "第 $week 周 · ${EpochDays.formatShortRange(monday, sunday)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            WeekStepButton(
                icon = Icons.Outlined.ChevronRight,
                enabled = canGoForward,
                description = "下一周",
                onClick = { onSelectWeek(week + 1) }
            )
        }
        if (notes.isNotEmpty()) {
            Text(
                text = notes.joinToString(" · "),
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/**
 * 翻周箭头。禁用时用 [FlatGridNumber] 淡到接近隐形而不是 `onSurfaceVariant.copy(alpha=…)`，
 * 因为它压在栅格底上——透明度叠出来的灰会和栅格线混成一种颜色，看不出「不能点」。
 */
@Composable
private fun WeekStepButton(
    icon: ImageVector,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(30.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else FlatGridNumber,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 周课表的列头：星期几。
 *
 * 星期表头为每列一个独立圆角块：今天填主题绿配深墨字，其余六天是中性冷灰片。
 * 原来在文字旁挂的放假小图标已移至课表对应那一天的列背景中半透明显示，
 * 使周几卡片更加纯粹简洁。
 */
@Composable
private fun WeekDayHeaderCell(
    dayLabel: String,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isToday) LimeGreenOnPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(WeekHeaderHeight)
            .clip(RoundedCornerShape(8.dp))
            // 主题绿只做填充，上面必须是深色前景（Color.kt 的全局约定）。
            .background(if (isToday) LimeGreenPrimary else ChipSurfaceNeutral),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "周$dayLabel",
            fontSize = 11.5.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1
        )
    }
}

/**
 * 扁平化周课表栅格（连堂课自动铺满）。
 *
 * 背景怎么从「一堆小方格」改成「一张平面表格」：
 * 1. 取消逐格描边与斑马纹。原来 7×13 个格子各自带 0.5dp 冷灰描边 + 冷灰间隔底色，
 *    在米白页面上就是一片灰扑扑的棋盘；现在格子只剩平涂底，靠 12 条横细线 + 6 条竖细线分层；
 * 2. 栅格线全部改走冷调灰绿（FlatGridLine 系列）。原来的 #F1F5F9 / #E2E8F0 是冷灰蓝，
 *    叠在纯白 + 抹茶主题上会偏蓝；
 * 3. 星期表头改成**每列一个独立圆角块**：今天填主题绿配深墨字（绿只做填充、上面必须配深色前景），
 *    其余六天是中性冷灰片。块与块之间不再画竖分隔线——有了独立圆角块再画竖线就是脏边；
 * 4. **早上/中午/晚上的大节分割线取消**：作息表 section 不同会在第 5/10 节后画出
 *    三条贯穿全宽的粗横线，把一张轻的表压成四段。现在每一行都是同一条 0.5dp 细线，
 *    节奏统一，靠课程块的连续色块自己分段；表头与主体之间留一条略沉的分隔线收口；
 * 5. 节次数字淡到 8.5sp 的水印色，只在没课的空格露出来给方位感，绝不压课程块。
 *
 * 行数一律走 [PeriodTable.periodsPerDay]，不写死节数：换作息时写死会让
 * 列总高与课程块偏移各说各话，越界的课程块直接被裁到列外。
 *
 * **七列是「这一周真实的七个日期」，不是「星期几的结构」。**
 * 之前按星期几分列，补课就表达不出来：10-16（周五）让出课、10-17（周六）补周五的课，
 * 而周六那一列按结构永远是空的，用户看到的是「周六没课」而不是「周六补周五的课」。
 * 现在的列内容全部来自 [com.example.data.calendar.ScheduleResolver.weekPlan]，
 * 和列表页同一套判定，列头则把星期几、日期、当天状态三件事一起报出来。
 */
@Composable
fun WeekTimetableGrid13(
    week: Int,
    currentWeek: Int,
    /** 学期总周数。翻周箭头的上界：学期之外没有课表，翻过去只会得到一张全空的表。 */
    totalWeeks: Int,
    plan: List<WeekDayPlan>,
    coursesWithSlots: List<CourseWithSlots>,
    tasks: List<TaskEntity>,
    periodTable: PeriodTable,
    onSelectWeek: (Int) -> Unit,
    onOpenWeekPicker: () -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val courseMap = coursesWithSlots.associateBy { it.course.id }
    val cardRevealProgress = LocalCardRevealProgress.current
    val periodHeight = PeriodRowHeight
    // 行数只认作息表。空表（periodsPerDay = 0）时给 1，免得整列高度算成 0 什么都不出。
    val periodsPerDay = periodTable.periodsPerDay.coerceAtLeast(1)
    val totalHeight = periodHeight * periodsPerDay

    // 「今天」必须按**日期**判，不能按星期几：翻到别的周时，周一那一列并不是今天。
    // 之前这里传的是 `selectedScheduleDay`（今天的星期几），于是看第 3 周也会把周一标绿。
    val today = remember { EpochDays.today() }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 栅格动态铺满屏幕宽度，平涂微透底色，避免全屏 recursive hazeEffect 引起的渲染残影与开销
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
    ) {
        // 周次条放在栅格**自己**的头部而不是页面上方那一行：它只在周课表视图里有意义
        // （列表页是从今天往后推的，没有「浏览第几周」这回事），跟着栅格走就不用为
        // 列表页也预留它那一行高度。
        WeekStrip(
            week = week,
            currentWeek = currentWeek,
            plan = plan,
            // 翻周只在学期范围内开：翻到学期外的那几周只会得到一张七列全空的表，
            // 而那不是用户要找的东西——学期外的情况留给周次条上的「不在学期范围内」。
            canGoBack = week > 1,
            canGoForward = week < totalWeeks,
            onSelectWeek = onSelectWeek,
            onOpenPicker = onOpenWeekPicker
        )

        // 吸顶星期表头：每列一个独立圆角块，今天填绿，其余是中性片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 刻度列在表头的占位：与网格主体刻度列完全同宽（PeriodRulerWidth），保证表头 7 列与网格 7 列精确对齐
            Box(modifier = Modifier.width(PeriodRulerWidth))
            plan.forEach { dayPlan ->
                WeekDayHeaderCell(
                    dayLabel = dayLabels.getOrElse(dayPlan.dayOfWeek - 1) { "" },
                    isToday = dayPlan.epochDay == today,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 表头与课表主体之间的分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FlatGridLineStrong)
        )

        // 可纵向滚动的 1-13 节主体
        //
        // 这里**不垫 IslandBottomInset**：课表是固定高度的网格，不是可以一直往下滚的
        // 列表，底部留一条空白只会让表格提前收住、下面空一块，反而把浮岛衬成
        // 「贴在空白上」而不是「浮在课表上」。网格直接铺到面板底，浮岛（毛玻璃）
        // 正常地压在下方的行线和节次数字上面透出内容——这才是悬浮层该有的样子。
        // 列表页那种要滚到最后一项的滚动容器才需要 contentPadding 避让。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
            // 左侧节次刻度列
            //
            // 节次数字原来在 7 个日列里各画一遍（7 × 13 = 91 个水印），靠「有课时被卡片
            // 盖住」才不至于糊成一片——那本身就是靠遮挡救噪音，越宽的课程块越把刻度吃掉，
            // 于是表头第一列常常一个数字都看不到。搬到这里之后：
            // 数字只出现一次、永远可见、不与课程块争位置，整列宽度让给七个日列。
            //
            // 刻度栏本身**不画横线**：行线从右侧的标尺轴才开始往右贯通，刻度区保持干净，
            // 眼睛读的是「标尺 + 右侧网格」两块，而不是一块被线切碎的面。
            Box(
                modifier = Modifier
                    .width(PeriodRulerWidth)
                    .height(totalHeight)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    for (period in 1..periodsPerDay) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(periodHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = "$period",
                                modifier = Modifier.padding(end = 7.dp),
                                color = FlatGridNumber,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
                // 标尺轴：刻度与网格之间的分界，和列分隔线同色同粗，整表只有这一条竖线
                // 是「结构性」的，其余列分隔线保持更淡
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(FlatGridLineStrong)
                )
            }

            // 7 Day Columns —— 每一列是这一周里的一个**真实日期**，不是「星期几」
            plan.forEachIndexed { columnIndex, dayPlan ->
                val isToday = dayPlan.epochDay == today
                val isHoliday = dayPlan.offReason == DayOffReason.NO_CLASS ||
                    dayPlan.offReason == DayOffReason.VACATED
                val daySlots = dayPlan.slots
                // 撞课的课在这里被分好列，后面按列宽摆格子
                val placements = layoutDaySlots(daySlots)
                // 入场序号按**节次**排，不按并排列：撞课并排的两块在同一行同一高度，
                // 按列排会让它们一个在左上、一个在右下依次亮起，读起来像错位而不是
                // 同时出现。所以这里只按 startPeriod 排序，同节次的块拿到同一个序号。
                val revealOrderBySlotId = remember(placements) {
                    placements
                        .sortedBy { it.slot.startPeriod }
                        .distinctBy { it.slot.startPeriod }
                        .mapIndexed { index, place -> place.slot.startPeriod to index }
                        .toMap()
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .height(totalHeight)
                        .background(if (isToday) FlatTodayColumn else Color.Transparent)
                ) {
                    // 1. 平涂行底 + 横细线（替代原来逐格描边的方格背景）
                    Column(modifier = Modifier.fillMaxSize()) {
                        for (period in 1..periodTable.periodsPerDay) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(periodHeight)
                            ) {
                                // 早上/中午/晚上的大节分割线整条删掉了：作息表推出来的
                                // section 边界会在表上画出三条贯穿全宽的粗横线，把一张
                                // 轻的表压成四段，配色再淡也救不回来。现在每一行都是
                                // 同一条 0.5dp 细线，靠课程块的连续色块自己分段。
                                //
                                // 节次数字也搬走了（见左侧刻度列）：它在 7 列里各画一遍，
                                // 91 个数字堆在表上，是这张网格仅剩的噪音源。列内现在只剩线，
                                // 整列宽度全给课程块。
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(FlatGridLine)
                                )
                            }
                        }
                    }

                    // 2. 竖向列分隔线：比横线更淡，竖线一多就容易糊成灰墙
                    if (columnIndex < plan.lastIndex) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(FlatGridColumnLine)
                        )
                    }

                    // 3. 放假背景水印：该天放假时在对应列背景上半透明呈现放假图标，表明这天放假不上课
                    if (isHoliday) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EventAvailable,
                                contentDescription = "放假",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                                modifier = Modifier.size(HolidayWatermarkSize)
                            )
                        }
                    }

                    // 4. 连堂课色块平铺在对应节次上；撞课的按列宽并排，互不遮挡
                    placements.forEach { place ->
                        val slot = place.slot
                        val cWithSlots = courseMap[slot.courseId]
                        val course = cWithSlots?.course
                        if (course != null) {
                            val start = slot.startPeriod.coerceIn(1, periodsPerDay)
                            val end = slot.endPeriod.coerceIn(start, periodsPerDay)
                            val span = (end - start + 1).coerceAtLeast(1)

                            val topOffset = periodHeight * (start - 1)
                            val cardHeight = periodHeight * span - 4.dp

                            // 撞课列之间留一条缝，两块颜色才分得开
                            val gap = if (place.conflicted) ConflictColumnGap else 0.dp
                            val cellWidth =
                                (maxWidth - gap * (place.columns - 1)) / place.columns
                            val left = cellWidth * place.column + gap * place.column

                            val linkedTasks = tasks.filter { it.courseId == course.id }

                            Box(
                                modifier = Modifier
                                    .offset(x = left, y = topOffset)
                                    .width(cellWidth)
                                    .height(cardHeight)
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                                    .cardRevealAt(
                                        cardRevealProgress,
                                        revealOrderBySlotId[slot.startPeriod] ?: 0
                                    )
                            ) {
                                CourseTimetableCell(
                                    course = course,
                                    slot = slot,
                                    linkedTasks = linkedTasks,
                                    onClick = { onInspectCourse(cWithSlots) },
                                    conflicted = place.conflicted,
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

/** 「列表」页在当天之外额外展开几个「有课的日子」。 */
private const val EXTRA_DAY_GROUPS = 2

/**
 * 课程卡右上角时段 chip 的文字对比度目标。
 *
 * **这个数不是随手定的 4.5**：chip 底色不是纯白，而是 15% 课程色叠在染色卡面上，
 * 实测相对亮度约 0.68~0.76（蓝课 #CEE5FF=0.764、青课 #B3DFE6=0.681）。
 * [deepEnoughForWhite] 拿近白当参考，会**低估**落在淡底上的真实对比度：
 * 按近白算 4.5 落到真实淡底上只剩 3.2~3.6，而 chip 的字是 11sp 加粗，
 * 够不上 WCAG「大字」豁免（要 14pt 加粗 / 18pt），所以必须按 4.5 的真实值反推。
 *
 * 反推：淡底要 4.5:1 → 字亮度 ≈ 0.11~0.13 → 换回近白口径的目标 ≈ 6.3。
 * 取 6.5 留一点余量。实测（改后）蓝 4.6 / 青 4.5 / 橙 5.6，全部过 AA，
 * 彩度仍在 90~150 之间，色相照样认得出。
 *
 * 不够就往上调这个常数，**不要改成手调色值**——一改就不再和八门课保持同一套推导。
 */
private const val CHIP_TEXT_TARGET = 6.5f

/** 最多往后找几天（两周），够覆盖「这一整周都没课」的极端排课。 */
private const val DAY_LOOKAHEAD = 14

/** 当天那段的标题。用户用「今日」而不是「今天」。 */
private const val TODAY_LABEL = "今日"
/** 列表页里的一天：一个日期 + 当天的课（已按开始节次排好）。 */
private class ScheduleDayGroup(
    val dayOffset: Int, // 0 = 今天，1 = 明天，以此类推
    val date: Calendar, // 当天 00:00
    val slots: List<CourseSlotEntity>
)

/**
 * 从系统时间往后推，收集「当天 + 接下来 [EXTRA_DAY_GROUPS] 个有课的日子」。
 *
 * 中间没课的日子**直接跳过**而不是留一截空白：周一有课、周二空、周三有课，
 * 就只出周一、周三两段，留个空段只会让人以为那天漏渲染了。
 *
 * 今天自己没课时就不出今天这一段，于是总组数会少于 EXTRA_DAY_GROUPS + 1——
 * 这是按「当天及接下来 2 天」的字面意思来的，不是漏算。
 */
private fun buildDayGroups(
    slots: List<CourseSlotEntity>,
    resolver: ScheduleResolver,
    weeksBySlotId: Map<Long, Set<Int>>
): List<ScheduleDayGroup> {
    val groups = ArrayList<ScheduleDayGroup>(EXTRA_DAY_GROUPS + 1)
    // 「今天」的锚点必须是 Asia/Shanghai 的今天：设备在别的时区时，
    // 用系统时区会在北京 00:30 之前把前一天当成今天（见 ScheduleZone）。
    val today = todayCalendar()
    var extra = 0
    var offset = 0
    while (offset < DAY_LOOKAHEAD && extra < EXTRA_DAY_GROUPS) {
        val date = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 判定权交给 resolver：停课日整天不产生分组，补课日走 effectiveDayOfWeek
        val daySlots = resolver.slotsOn(
            epochDayOf(date),
            slots,
            weeksBySlotId
        )
        if (daySlots.isNotEmpty()) {
            if (offset == 0) {
                groups += ScheduleDayGroup(0, date, daySlots)
            } else if (extra < EXTRA_DAY_GROUPS) {
                groups += ScheduleDayGroup(offset, date, daySlots)
                extra++
            }
        }
        offset++
    }
    return groups
}

/**
 * 纯文本的日期分隔标题：今日 / 明天 / 后天 / X 月 X 日。
 *
 * 判定用的是**距今天几天**而不是星期几——同一门课在列表里可能出现好几次，
 * 只有日期能唯一认出「哪一天的哪一节」。
 *
 * 只有「今日」带状态后缀，见 [todayStatusLabel]：明天/后天没有「还有多久下课」可言，
 * 硬套一句只会变成假信息。
 */
private fun daySeparatorLabel(
    group: ScheduleDayGroup,
    now: Long,
    periodTable: PeriodTable,
    resolver: ScheduleResolver
): String {
    val base = when (group.dayOffset) {
        0 -> TODAY_LABEL
        1 -> "明天"
        2 -> "后天"
        else -> "${group.date.get(Calendar.MONTH) + 1} 月 ${group.date.get(Calendar.DAY_OF_MONTH)} 日"
    }
    val withStatus = if (group.dayOffset != 0) base
    else "$base · ${todayStatusLabel(group.date, group.slots, now, periodTable)}"
    // 补课必须挂一句话出来。不说的话用户看到周六有课只会以为本来就有 ——
    // 而"周六本来不上课"恰恰是需要提醒他的那件事。
    val makeup = makeupSuffixLabel(group, resolver)
    return if (makeup == null) withStatus else "$withStatus · $makeup"
}

/**
 * 「补周五的课」这样的后缀；不是补课日就返回 null。
 *
 * 周几由 `makeupDayOfWeek` 从**来源日**推出来，不是另存的字段 ——
 * 2026-10-17 是周六，但它的来源日 10-02 是周五，所以写「补周五的课」；
 * 写成「补周六的课」就是把「这天是周几」当成了「补的是周几的课」。
 */
private fun makeupSuffixLabel(group: ScheduleDayGroup, resolver: ScheduleResolver): String? {
    val dow = resolver.calendar.makeupDayOfWeek(epochDayOf(group.date)) ?: return null
    val label = WEEKDAY_LABELS.getOrNull(dow - 1) ?: return null
    return "补${label}的课"
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")



/**
 * 「列表」页：按时间顺序铺开最近几天有课的课程，关联待办默认折叠。
 *
 * 三个结构决定：
 * 1. **星期几不由用户选**，日期一律从系统时间往后推。排课是循环的，
 *    「接下来有什么」比「任意某一天」有用；这里再摆一个周几分段器，
 *    反而会让人以为这页只是周表的另一种排法。所以周一分段器整条删掉了，
 *    `selectedDay` / `onSelectDay` 两个入参也跟着没有。
 * 2. **当天 + 接下来最近 [EXTRA_DAY_GROUPS] 个有课的日子**，见 [buildDayGroups]。
 * 3. 每张卡的关联待办默认折叠，一屏扫完只看见「几点什么课」。
 *    展开态按「日期 + 时段」记：排课是循环的，同一个 slotId 会在不同日期重复出现，
 *    只按 slotId 记会让两天的展开状态串起来，LazyColumn 的 key 也会撞。
 */
@Composable
fun DayTimelineView13(
    coursesWithSlots: List<CourseWithSlots>,
    slots: List<CourseSlotEntity>,
    tasks: List<TaskEntity>,
    periodTable: PeriodTable,
    resolver: ScheduleResolver,
    weeksBySlotId: Map<Long, Set<Int>>,
    onInspectCourse: (CourseWithSlots) -> Unit,
    modifier: Modifier = Modifier
) {
    val courseMap = coursesWithSlots.associateBy { it.course.id }
    // 只跟着课表本身重算；「还有 X 小时 X 分」在每次重组时现算，不跟着这里缓存，
    // 否则从别的页面切回来会一直显示旧倒计时。
    val groups = remember(slots, resolver, weeksBySlotId) {
        buildDayGroups(slots, resolver, weeksBySlotId)
    }
    val now = System.currentTimeMillis()

    if (groups.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "接下来两周没有排课",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val cardRevealProgress = LocalCardRevealProgress.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 2.dp, bottom = IslandBottomInset)
    ) {
        groups.forEachIndexed { groupIndex, group ->
            item(key = "day_${group.dayOffset}") {
                Column {
                    Text(
                        text = daySeparatorLabel(group, now, periodTable, resolver),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(CardBlockGap))
                }
            }
            group.slots.forEachIndexed { slotIndex, slot ->
                item(key = "slot_${group.dayOffset}_${slot.id}") {
                    val cWithSlots = courseMap[slot.courseId]
                    val course = cWithSlots?.course
                    if (course != null) {
                        // 入场序号要跨分组累计，才是从整页最上面一路数下来。
                        // 用 `groups.take(groupIndex).sumOf { … }` 而不是外面挂一个
                        // 累加计数器：LazyColumn 的 item 内容是**懒执行**的，捕获的
                        // 计数器在滚动时被后续 item 复用，会算到越滚越大的序号。
                        val revealOrder = groups
                            .take(groupIndex)
                            .sumOf { it.slots.size } + slotIndex

                        ScheduleCourseCard(
                            courseWithSlots = cWithSlots,
                            slot = slot,
                            periodTable = periodTable,
                            onInspectCourse = { onInspectCourse(cWithSlots) },
                            modifier = Modifier.cardRevealAt(cardRevealProgress, revealOrder)
                        )
                        Spacer(modifier = Modifier.height(CardListGap))
                    }
                }
            }
        }
        item(key = "tail") {
            Spacer(modifier = Modifier.height(CardBlockGap))
        }
    }
}

/**
 * 列表页的课程卡：整张卡就是一个入口，点了弹出「有待办」面板（[CourseLinkageSheet]）。
 */
@Composable
private fun ScheduleCourseCard(
    courseWithSlots: CourseWithSlots,
    slot: CourseSlotEntity,
    periodTable: PeriodTable,
    onInspectCourse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val course = courseWithSlots.course
    val courseColor = parseColor(course.colorHex)
    // 越界返回 null，界面显示占位符而不是一个错的时间
    val (timeStart, timeEnd) = periodTable.timeRange(slot.startPeriod, slot.endPeriod)
        ?: ("--" to "--")
    val room = slot.classroom.ifBlank { course.classroom }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onInspectCourse() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp), tint = courseColor, tintAlpha = CourseTintAlpha, vividness = CourseTintVivid)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Header: 课程名 + 时段
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 课程名 + 时段 chip。
                //
                // weight 必须挂在**这个 Row** 上，不能挂里面的 Text：外层 Row 先量非弹性
                // 子项，而「包着 Text 的那个 Row」自己是非弹性的，所以里面的 Text 根本
                // 等不到弹性那一轮——结果是课程名照旧先占满、chip 被挤到截断。
                //
                // 让课程名去吸收宽度不足，而不是反过来：chip 拿到完整宽度，剩下的才分给名字。
                // 被截的应该是名字而不是时间——「18:50-21:1…」会被读成 21:10，
                // 一个残缺的时间比一个残缺的名字有害得多。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // 课程名左侧的课程色竖条。这根条**一度删掉又按用户要求恢复**，
                    // 恢复的理由成立：卡面染色只有 20% 的粉彩（CourseTintAlpha），
                    // 一列长列表往下扫时那个浅色块不足以让人一眼认出是哪门课，
                    // 而这根实色竖条是更强的识别线索。
                    // 两者不是同一信息的重复表达——染色是「暗示」，竖条是「标识」，
                    // 色弱、色弱分辨或小屏幕上浅色块更容易被忽略。
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                                // 时段 chip 的底色分两种，文字色统一用「课程色压深」而不是 readableOn：
                // readableOn 是往墨炭 #1E2320 lerp 0.55，出来是灰蓝/灰绿——色相名义上还在，
                // 彩度只剩三四成，摆在「和主题色呼应」的位置上就是寡淡的。
                // deepEnoughForWhite 走 HSL 压明度 + 1.45 倍补饱和，出来的深色仍认得出
                // 是蓝、是绿还是橙，而且对比度是按 4.5 实算出来的，不是估的。
                val isGreen = isGreenishColor(courseColor)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isGreen) TeaColor else courseColor.copy(alpha = 0.15f))
                        .border(0.5.dp, if (isGreen) TeaColorBorder else courseColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${formatPeriodRange(slot.startPeriod, slot.endPeriod)} ($timeStart-$timeEnd)",
                        color = deepEnoughForWhite(courseColor, target = CHIP_TEXT_TARGET),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        // 单行是硬约束，不给 chip 折行的余地：宽度不够就截断加省略号，
                        // 而不是把这一张卡拉高到邻居的两倍。卡片高度必须由内容决定，
                        // 不能由某一个长字符串决定。
                        // 正常情况下是课程名先让位（见上面的 weight），这里只兜底。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "@$room · ${course.teacher} · ${course.category}",
                style = MaterialTheme.typography.labelSmall,
                // 和总览页课程卡同一个理由用 TextSecondary：这一行压在**这门课的粉彩**上，
                // 实测全局的 onSurfaceVariant（= TextMuted #78827B）只有 2.2~3.7:1，
                // 紫课面 #EBD8FF 上更是只有 2.99:1，11sp 小字过不了 AA。
                // TextSecondary #4A524D 在同一批卡面上是 6.1~6.6:1。
                color = TextSecondary
            )
        }
    }
}
