package com.example.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.formatPeriodRange
import com.example.data.calendar.ScheduleResolver
import com.example.data.timetable.PeriodTable
import com.example.ui.components.parseColor
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
import com.example.ui.theme.isGreenishColor
import com.example.ui.theme.glassSurface
import com.example.ui.theme.deepEnoughForWhite
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.ScheduleViewMode
import java.time.ZoneId
import java.util.Calendar

@Composable
fun ScheduleScreen(
    state: MainUiState,
    onSelectViewMode: (ScheduleViewMode) -> Unit,
    onInspectCourse: (CourseWithSlots) -> Unit,
    onGenerateTodayTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 排课判定的唯一入口：开课周次、停课、补课都在它里面，
    // 周课表与列表页共用，不会各算各的。
    val resolver = remember(state.calendar) { ScheduleResolver(state.calendar) }
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
            // Flat Segmented Control (扁平化：外层平涂浅底，选中块直接平涂主题绿，不描边)
            FlatSegmentedRow {
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

            Button(
                onClick = onGenerateTodayTasks,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag("schedule_generate_tasks_button")
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(4.dp))
                Text("生成本周待办", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.scheduleViewMode == ScheduleViewMode.WEEK_GRID) {
            // 原来这里还有一条「有待办 / 无待办」图例条，已按要求撤掉（省一行竖向空间）。
            // 状态本身不需要图例：深浅两档本来就靠底色本身表达，
            // 角标上的「N 待办」/「✓」也只报数目不报颜色语义。
            val visibleByDay = remember(state.courseSlots, state.slotWeeks, state.selectedWeek, state.calendar) {
                resolver.slotsInWeek(state.selectedWeek, state.courseSlots, state.slotWeeks)
            }
            WeekTimetableGrid13(
                coursesWithSlots = state.coursesWithSlots,
                slots = state.courseSlots,
                tasks = state.tasks,
                selectedDay = state.selectedScheduleDay,
                periodTable = state.periodTable,
                visibleSlotsByDay = visibleByDay,
                onInspectCourse = onInspectCourse,
                modifier = Modifier.weight(1f)
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
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 扁平化分段控件外框：一层平涂浅米底 (#F3EFE8)，不描边、不加阴影。
 */
@Composable
private fun FlatSegmentedRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 扁平化分段按钮：选中 = 主题绿平涂 + 墨炭字，未选中 = 透明 + 次级灰字。
 * 选中态完全交给色块本身表达，不再叠一层描边，所以不会出现「框套框」的脏边。
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
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
 * 周课表表头每一列（周一~周日）的高度。独立圆角块之后不需要太高。
 */
private val WeekHeaderHeight = 30.dp

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
 * 行数一律走 [PeriodTable.periodsPerDay]，不写死 13：换作息（浙大是 15 节）时
 * 写死的 13 会让列总高与课程块偏移各说各话，第 14/15 节的卡片直接被裁到列外。
 */
@Composable
fun WeekTimetableGrid13(
    coursesWithSlots: List<CourseWithSlots>,
    slots: List<CourseSlotEntity>,
    tasks: List<TaskEntity>,
    selectedDay: Int,
    periodTable: PeriodTable,
    /** 已按所浏览周次做过开课周次过滤的分组结果，由 ScheduleResolver 产出 */
    visibleSlotsByDay: Map<Int, List<CourseSlotEntity>>,
    onInspectCourse: (CourseWithSlots) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val courseMap = coursesWithSlots.associateBy { it.course.id }
    val periodHeight = PeriodRowHeight
    // 行数只认作息表。空表（periodsPerDay = 0）时给 1，免得整列高度算成 0 什么都不出。
    val periodsPerDay = periodTable.periodsPerDay.coerceAtLeast(1)
    val totalHeight = periodHeight * periodsPerDay

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 栅格整体是一块云母面板：外面浮起来，内部保持近白的平整底，网格线照旧
            .glassSurface(shape = RoundedCornerShape(20.dp))
    ) {
        // 吸顶星期表头：每列一个独立圆角块，今天填绿，其余是中性片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 刻度列在表头的占位：宽度要扣掉本 Row 的 6.dp 水平 padding **和** 4.dp 的
            // 块间距，否则表头会整体右移 10.dp，七个星期块压在七个日列右边一截
            Box(modifier = Modifier.width(PeriodRulerWidth - 10.dp))
            dayLabels.forEachIndexed { index, day ->
                val dayNum = index + 1
                val isToday = dayNum == selectedDay
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(WeekHeaderHeight)
                        .clip(RoundedCornerShape(10.dp))
                        // 主题绿只做填充，上面必须是深色前景（Color.kt 的全局约定）。
                        // 原本挂在表头底部的绿色小横条删掉了：整块已经是绿的，横条纯属冗余。
                        .background(
                            if (isToday) LimeGreenPrimary else ChipSurfaceNeutral
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "周$day",
                        fontSize = 11.5.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isToday) LimeGreenOnPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
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

            // 7 Day Columns
            for (dayNum in 1..7) {
                val isToday = dayNum == selectedDay
                val daySlots = visibleSlotsByDay[dayNum].orEmpty()
                // 撞课的课在这里被分好列，后面按列宽摆格子
                val placements = layoutDaySlots(daySlots)

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
                    if (dayNum < 7) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(FlatGridColumnLine)
                        )
                    }

                    // 3. 连堂课色块平铺在对应节次上；撞课的按列宽并排，互不遮挡
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

/** 列表页里的一天：一个日期 + 当天的课（已按开始节次排好）。 */
private class ScheduleDayGroup(
    val dayOffset: Int, // 0 = 今天，1 = 明天，以此类推
    val date: Calendar, // 当天 00:00
    val slots: List<CourseSlotEntity>
)

/** Calendar 的 DAY_OF_WEEK（周日=1）转成 App 的 1=周一 .. 7=周日。 */
private fun appDayOfWeek(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

/** Calendar → epochDay。校历判定统一走 epochDay，不受时区/夏令时影响。 */
private fun epochDayOf(cal: Calendar): Long =
    cal.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/**
 * 某个时段在指定日期的开课时刻（epoch millis）。
 *
 * 取不到该节次时返回 0 —— 越界不该静默兜底成一个「看起来正常」的假时间，
 * 那正是「改了作息但某个调用点漏改」的藏身处。
 */
private fun periodStartMillis(
    date: Calendar,
    slot: CourseSlotEntity,
    periodTable: PeriodTable
): Long {
    val minute = periodTable.startMinuteOf(slot.startPeriod) ?: return 0L
    return (date.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** 某个时段在指定日期的结课时刻（epoch millis）。 */
private fun periodEndMillis(
    date: Calendar,
    slot: CourseSlotEntity,
    periodTable: PeriodTable
): Long {
    val minute = periodTable.endMinuteOf(slot.endPeriod) ?: return 0L
    return (date.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

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
    val today = Calendar.getInstance()
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
 * 纯文本的日期分隔标题：今天 / 明天 / 后天 / X 月 X 日。
 *
 * 判定用的是**距今天几天**而不是星期几——同一门课在列表里可能出现好几次，
 * 只有日期能唯一认出「哪一天的哪一节」。
 *
 * 只有今天挂倒计时，而且倒计时数的是「下一节课」而不是「当天第一节课」：
 * 第一节课已经过去的时候，对着它做减法只会得到一个负数。
 * 今天的课全上完了就只留「今天」，不做无意义的补零。
 */
private fun daySeparatorLabel(group: ScheduleDayGroup, now: Long, periodTable: PeriodTable): String {
    val base = when (group.dayOffset) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        else -> "${group.date.get(Calendar.MONTH) + 1} 月 ${group.date.get(Calendar.DAY_OF_MONTH)} 日"
    }
    if (group.dayOffset != 0) return base
    val next = group.slots.firstOrNull { periodEndMillis(group.date, it, periodTable) > now } ?: return base
    val remain = periodStartMillis(group.date, next, periodTable) - now
    if (remain <= 0L) return "$base · 进行中"
    return "$base · 还有 ${remain / 3_600_000L} 小时 ${(remain % 3_600_000L) / 60_000L} 分"
}

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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 2.dp, bottom = IslandBottomInset)
    ) {
        groups.forEach { group ->
            item(key = "day_${group.dayOffset}") {
                Column {
                    Text(
                        text = daySeparatorLabel(group, now, periodTable),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            group.slots.forEach { slot ->
                item(key = "slot_${group.dayOffset}_${slot.id}") {
                    val cWithSlots = courseMap[slot.courseId]
                    val course = cWithSlots?.course
                    if (course != null) {
                        ScheduleCourseCard(
                            courseWithSlots = cWithSlots,
                            slot = slot,
                            periodTable = periodTable,
                            onInspectCourse = { onInspectCourse(cWithSlots) }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
        item(key = "tail") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 列表页的课程卡：整张卡就是一个入口，点了弹出「有待办」面板（[CourseLinkageSheet]）。
 *
 * 交互只留这一种，不是为了省代码——卡片内联展开待办有实打实的三个问题：
 * 1. 一屏放不下。三天 × 每天三四堂课，展开一两条之后后面的课就得滚，
 *    「扫一眼今天有什么」这个主要用途反而没了；
 * 2. 两个层级打架。卡片头部原本点开课程详情、下面那行点开待办，
 *    一张卡两个落点，用户得先猜该点哪；
 * 3. 功能是重复的。[CourseLinkageSheet] 里已经有勾选、添加、编辑课程，
 *    卡内再摆一份等于是同一个东西的第二个实现，改一处漏一处。
 *
 * 所以卡片上只剩两行信息：课程名 + 时段 chip，以及一行元信息。
 * 有没有待办不进卡片——点开弹层第一眼就是「关联待办 · N」，在卡片上再报一遍是重复。
 *
 * 时段 chip 的字用 [deepEnoughForWhite] 而不是 [readableOn]：前者压明度的同时补饱和，
 * 出来的深色仍认得出是蓝是绿是橙（也就是「与主题色呼应」），后者往墨炭里 lerp，
 * 彩度掉到三四成，摆在呼应主题色的位置上只会显得寡淡。
 */
@Composable
private fun ScheduleCourseCard(
    courseWithSlots: CourseWithSlots,
    slot: CourseSlotEntity,
    periodTable: PeriodTable,
    onInspectCourse: () -> Unit
) {
    val course = courseWithSlots.course
    val courseColor = parseColor(course.colorHex)
    // 越界返回 null，界面显示占位符而不是一个错的时间
    val (timeStart, timeEnd) = periodTable.timeRange(slot.startPeriod, slot.endPeriod)
        ?: ("--" to "--")
    val room = slot.classroom.ifBlank { course.classroom }

    Card(
        // 云母面板 + 无描边：卡片比页面底亮一档，靠投影浮起来。
        // clickable 挂在 Card 上而不是里面的某一行——整卡任意位置都能点开面板，
        // 也就不会出现「点得到但要点对地方」的情况。
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onInspectCourse)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape = RoundedCornerShape(18.dp), tint = courseColor, tintAlpha = CourseTintAlpha, vividness = CourseTintVivid)
                .padding(14.dp)
        ) {
            // Header: 课程名 + 时段
            Row(
                modifier = Modifier.fillMaxWidth(),
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
        }
    }
}
