package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.cardRevealAt
import com.example.ui.theme.rememberScrollRevealEnabled
import com.example.ui.theme.scrollRevealAt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TASK_TYPE_NONE
import com.example.data.model.TaskStar
import com.example.data.model.TaskTypes
import com.example.ui.components.islandBottomInset
import com.example.ui.components.NO_DUE_TEXT
import com.example.ui.components.TaskCompleteMotion
import com.example.ui.components.TaskExitAnimation
import com.example.ui.components.TaskRowFadeOut
import com.example.ui.components.TaskItemCard
import com.example.ui.components.parseColor
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.CardBlockGap
import com.example.ui.theme.CardListGap
import com.example.ui.theme.PageHInset
import com.example.ui.theme.LocalCardRevealProgress
import com.example.ui.theme.StarInk
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.controlSurface
import com.example.ui.viewmodel.MainUiState
import com.example.data.calendar.AppZone
import com.example.ui.viewmodel.TaskGroupingMode
import com.example.ui.viewmodel.TaskStatusFilter
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * 组头的圆头胶囊色标。
 *
 * 原来这里是一根从组头贯穿到组内最后一张卡的竖条，靠 LazyColumn 的绘制阶段
 * 按 layoutInfo 把分段拼起来。三个问题叠在一起：长度随组内待办数无限增长；
 * 一根直上直下的细条压在左侧毫无曲线（3.5dp 宽最多只能有 1.75dp 圆角，
 * 肉眼等于直角）；每帧遍历可见 item 查表拼段，滚动和展开都发涩。
 *
 * 分组信息其实不靠连线表达——组头有名字，卡片那一行也带着同样的信息。
 * 所以改成：色标缩回组头、做成一颗两端全圆的胶囊，组内卡片统一左缩进。
 * 竖条、绘制阶段、layoutInfo 依赖整条链路一起删掉，滚动只剩 item 自己的绘制。
 */
private val GROUP_PILL_WIDTH = 5.dp
private val GROUP_PILL_HEIGHT = 20.dp

/** 色标到组名的距离。 */
private val GROUP_PILL_GAP = 9.dp

/**
 * 组内卡片相对页面左边线的缩进。
 *
 * 曾经是 12.dp（卡片比组头缩进一档，「读起来像属于上面那个组」），已删。
 * 用户要求卡片与页面左边距对齐：缩进让同一页里出现两条不同的左边线
 * （组头一条、卡片一条），而卡片本身的染色面 + 组头色标已经足够分出层级。
 */
private val GROUP_CONTENT_INSET = 0.dp

/**
 * 一个分组。**四种分组模式共用这一个结构**。
 *
 * 之前每种模式各写一套组头（「按截止」写个 Text、「按类型」写个色块 + Text、
 * 「按课程」写个胶囊 + Text + 箭头），于是「能不能折叠」「计数怎么显示」
 * 这两件事得改四遍，漏一遍就是两个模式的交互不一样。
 * 现在只有一份渲染逻辑，新增分组模式只往 [TaskGroupingMode] 加一项即可。
 */
private data class TaskGroup(
    /**
     * 折叠态的键，**自带分组模式前缀**（`BY_COURSE:高等数学`）。
     *
     * 前缀不是可有可无的：折叠状态活过筛选切换，所以「按课程」和「按类型」
     * 里同名的一组（课程名恰好叫「预习」之类）会共用同一个键，
     * 在按课程里折起的门会把按类型里同名的那一组也带走。
     */
    val id: String,
    val title: String,
    val count: Int,
    /** 组头那颗色标。null = 这一组没有可归属的颜色（如「较远」「无类型」），不画。 */
    val pillColor: Color?,
    /** 组名字色。 */
    val inkColor: Color
)

/** 摊平后的一个 item：要么组头，要么组内的一张待办卡。 */
private sealed interface ChecklistRow {
    val key: String

    data class Header(val group: TaskGroup, val isCollapsed: Boolean) : ChecklistRow {
        override val key = "h_${group.id}"
    }

    data class Item(val task: TaskEntity) : ChecklistRow {
        override val key = "t_${task.id}"
    }
}

/** 「按截止」的分组桶，顺序即展示顺序。 */
private val URGENCY_ORDER = listOf(
    "已逾期",
    "今天截止",
    "明天截止",
    "本周截止",
    "较远",
    NO_DUE_TEXT,
    "已完成"
)

/**
 * 哪些截止桶要染成警示色。
 *
 * 只有「已逾期」和「今天截止」染：再往后（明天/本周/较远）都有充裕时间，
 * 染红只是把一片列表变成一片红，反而让真正要处理的那两桶淹没掉。
 */
private val URGENCY_URGENT = setOf("已逾期", "今天截止")

private const val NO_TYPE_GROUP_TITLE = "未分类"

/** 「按星标」分组的两个组头名。标星的那桶在前。 */
private const val STAR_GROUP_TITLE = "已标星"
private const val UNSTARRED_GROUP_TITLE = "未标星"

/**
 * 筛选 chip：选中 = 主题绿 + 深墨字，未选中 = 中性冷灰玻璃片 + 石墨字。
 *
 * 抽出来是因为两行筛选（分组+状态、类型）现在有 10 颗 chip 走同一套语言，
 * 逐个摊开写必然出现两处长得不一样的那份。
 *
 * 底色走 [controlSurface]（18.dp 模糊 + 半透明面，见 Mica.kt）而不是实色平涂：
 * 这十颗 chip 压在页面底上，满不透明度时是一排实色片，和周课表的分段控件、
 * 顶栏图标按键都不在一套语言里。选中那一档传 `accent = true`（0.72），
 * 未选中走中性面（0.60）——绿块要靠颜色本身被认出，透明度的余量不能给一样多。
 *
 * **不描边**（2026-10-26 改）：未选中的 chip 原来挂了一道 0.5.dp 的
 * `GlassEdgeBottom` 发丝边。那道边压在浅色玻璃面上只会比面更亮，
 * 于是读出来就是一圈**白边**；换到深色或花纹壁纸，它又变成硬亮的一圈。
 * 全 App 的玻璃语言在这一轮已经统一过一次——`glassSurface` 自己那条
 * 0.5.dp 白描边早就删了（见 Mica.kt 的注释），这里跟着去掉才是同一套语言。
 *
 * 框没了，边界改由**字**扛：未选中的字从 `onSurfaceVariant`（#78827B）
 * 提到 [TextSecondary]（#4A524D）——前者压在浅玻璃上只有 3.4:1，
 * 没有框的时候它撑不住一颗 chip；后者 7.5:1，读起来才像一颗**按钮**
 * 而不是一行浮在页面上的浅色文字。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    /** 自定义类型的 chip 用长按删除，详见 [ChecklistScreen] 的筛选行说明。 */
    onLongClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable { onClick() }
    }
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .controlSurface(
                shape = shape,
                color = if (selected) MaterialTheme.colorScheme.primary else ChipSurfaceNeutral,
                accent = selected
            )
            .then(clickModifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                TextSecondary
            }
        )
    }
}

/**
 * 截止桶的判定。
 *
 * `hasDue` 单独一个桶（「不设截止」），**不能**让它掉进「已逾期」：
 * 哨兵是 `Long.MIN_VALUE`，直接减 now 会得到一个巨大的负数。
 */
private fun urgencyBucketOf(task: TaskEntity, now: Long): String {
    if (task.isCompleted) return "已完成"
    if (!TaskEntity.hasDue(task)) return NO_DUE_TEXT

    val diff = task.dueDateTimestamp - now
    if (diff < 0) return "已逾期"

    // 分组用的「今天」必须是北京的那一天，否则 GMT 设备在北京凌晨会把昨天的截止
    // 归进「今天截止」，已逾期的反而留在「明天」组里。
    val calNow = AppZone.calendar()
    val calDue = AppZone.calendar(task.dueDateTimestamp)
    val isSameDay = calNow.get(Calendar.YEAR) == calDue.get(Calendar.YEAR) &&
        calNow.get(Calendar.DAY_OF_YEAR) == calDue.get(Calendar.DAY_OF_YEAR)

    return when {
        isSameDay -> "今天截止"
        diff < 2 * 86400000L -> "明天截止"
        diff < 7 * 86400000L -> "本周截止"
        else -> "较远"
    }
}

/**
 * 把分组结果摊平成一串 [ChecklistRow]。
 *
 * 摊平而不是按组 `items` 的原因：`items` 拿不到「这一组折起来没有」，
 * 而折叠的整套语义（哪些组折着、组内要摊出几条）都得先算出来。
 * 摊平之后折叠态天然跟着筛选走——重建这张表就是在过滤掉已折叠的组。
 */
private fun buildChecklistRows(
    tasks: List<TaskEntity>,
    mode: TaskGroupingMode,
    collapsed: Set<String>,
    customTypes: List<CustomTaskType>,
    fallbackCourseColor: Color,
    defaultInk: Color,
    errorInk: Color
): List<ChecklistRow> {
    val rows = ArrayList<ChecklistRow>()

    // 一组待办 → 组头 + （未折叠时）组内所有卡片
    fun emit(group: TaskGroup, groupTasks: List<TaskEntity>) {
        val isCollapsed = group.id in collapsed
        rows += ChecklistRow.Header(group, isCollapsed)
        if (isCollapsed) return
        // 组内：**标星的在前**，其余保持原来的截止顺序（见 [starredFirst]）。
        groupTasks.starredFirst().forEach { rows += ChecklistRow.Item(it) }
    }

    when (mode) {
        TaskGroupingMode.BY_COURSE -> {
            tasks.groupBy { it.courseName }.forEach { (courseName, groupTasks) ->
                // 色标是色块不是文字，所以用课程原色，不走「粉彩当前景要压暗」那条约束
                val color = groupTasks.firstOrNull()?.let { parseColor(it.courseColorHex) }
                    ?: fallbackCourseColor
                emit(
                    TaskGroup(
                        id = "${mode.name}:$courseName",
                        title = courseName,
                        count = groupTasks.size,
                        pillColor = color,
                        inkColor = defaultInk
                    ),
                    groupTasks
                )
            }
        }

        TaskGroupingMode.BY_URGENCY -> {
            val now = System.currentTimeMillis()
            val buckets = tasks.groupBy { urgencyBucketOf(it, now) }
            URGENCY_ORDER.forEach { bucket ->
                val groupTasks = buckets[bucket] ?: return@forEach
                val urgent = bucket in URGENCY_URGENT
                emit(
                    TaskGroup(
                        id = "${mode.name}:$bucket",
                        title = bucket,
                        count = groupTasks.size,
                        // 截止桶没有可归属的单一颜色（一条待办的课程色在按截止分组时
                        // 只代表它自己，不代表这一桶），所以不画色标，只靠字色分级。
                        pillColor = null,
                        inkColor = if (urgent) errorInk else defaultInk
                    ),
                    groupTasks
                )
            }
        }

        TaskGroupingMode.BY_TYPE -> {
            val typeOptions = TaskTypes.options(customTypes)
            // 「未分类」钉在最后：它是兜底桶而不是一种类型，
            // 排在前面会让真正有类型的组被它顶下去。
            val buckets = tasks.groupBy { TaskTypes.groupKeyOf(it.taskType, customTypes) }
            val orderedKeys = typeOptions.map { it.key } + TASK_TYPE_NONE
            orderedKeys.forEach { key ->
                val groupTasks = buckets[key] ?: return@forEach
                val option = typeOptions.firstOrNull { it.key == key }
                emit(
                    TaskGroup(
                        id = "${mode.name}:$key",
                        title = option?.label ?: NO_TYPE_GROUP_TITLE,
                        count = groupTasks.size,
                        // 组头用墨色而不是淡彩：淡彩挂在近白页面底上几乎看不见，
                        // 而这行的字要靠它区分分组
                        pillColor = option?.let { parseColor(it.inkHex) },
                        inkColor = option?.let { parseColor(it.inkHex) } ?: defaultInk
                    ),
                    groupTasks
                )
            }
        }

        TaskGroupingMode.BY_STAR -> {
            // 顺序是「标星的在前」，和旧的「高 → 中 → 低」是同一个意图：
            // 这个维度存在的意义就是把「我挑出来的」先摆出来。
            // 现在只有两桶，所以直接按布尔分，别再引入第三档。
            val buckets = tasks.groupBy { TaskStar.isMarked(it.priority) }
            listOf(true, false).forEach { marked ->
                val groupTasks = buckets[marked] ?: return@forEach
                emit(
                    TaskGroup(
                        id = "${mode.name}:$marked",
                        title = if (marked) STAR_GROUP_TITLE else UNSTARRED_GROUP_TITLE,
                        count = groupTasks.size,
                        // 只有**色块**走 [StarInk]，标题仍是普通墨色。
                        //
                        // 色块原本是更深的一档 #D08F00（白底 2.76:1），理由是
                        // 「亮黄做这道 5×20dp 的竖条等于没画」。用户要它亮一些，
                        // 于是换成和星形同一支黄：2.76:1 → 1.58:1，色块比原来难找，
                        // 但仍是这一组唯一的黄色信号。
                        //
                        // 标题**试过**也走 [StarInk]（1.58:1），14sp 粗体在近白页面上
                        // 直接淡没了，所以退回普通墨色——组头是列表里的主要地标，
                        // 「能读出来」优先于「和色块同色」。
                        //
                        // 别按 3:1 把色块压深「修对比度」：这是知情选的，要改先问。
                        pillColor = if (marked) StarInk else null,
                        inkColor = defaultInk
                    ),
                    groupTasks
                )
            }
        }
    }

    return rows
}

/**
 * 清单页的分组头。**四种模式共用这一个。**
 *
 * 整行是折叠/展开的唯一落点：不再另设一个箭头按钮，一行里只留一个可点的东西，
 * 和卡片上的约定一致（勾选圈和整卡也各只有一个落点）。
 */
@Composable
private fun TaskGroupHeader(
    group: TaskGroup,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(end = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        group.pillColor?.let { pill ->
            Box(
                modifier = Modifier
                    .size(GROUP_PILL_WIDTH, GROUP_PILL_HEIGHT)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(pill)
            )
            Spacer(modifier = Modifier.width(GROUP_PILL_GAP))
        }
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = group.inkColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // 只写总数，不写「完成数 / 总数」。
        // 「0 / 4」里的 0 在折起来之后无从核对——看不见卡片就数不出完成了几条，
        // 于是那个分子只是一个会误导人的数字；而总数永远是对的。
        Text(
            text = "${group.count}",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        val arrowRotation by animateFloatAsState(
            targetValue = if (isCollapsed) 0f else 180f,
            label = "taskGroupArrow"
        )
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (isCollapsed) "展开 ${group.title}" else "折叠 ${group.title}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .rotate(arrowRotation)
        )
    }
}

@Composable
fun ChecklistScreen(
    state: MainUiState,
    onSelectTaskTypeKey: (String?) -> Unit,
    onOpenNewTypeDialog: () -> Unit,
    onConfirmDeleteType: (CustomTaskType) -> Unit,
    onSelectGroupingMode: (TaskGroupingMode) -> Unit,
    onToggleStatusFilter: (TaskStatusFilter) -> Unit,
    onToggleTaskComplete: (TaskEntity) -> Unit,
    onToggleTaskStar: (TaskEntity) -> Unit,
    onInspectTask: (TaskEntity) -> Unit,
    /**
     * 把「刚勾完」的信号从状态里消费掉。
     *
     * 离场只演一次：这一行离场之后信号必须消失，
     * 否则切走再切回来（或换一次筛选）时这一行会再演一遍。
     */
    onConsumeCompletions: (List<Long>) -> Unit,
    /**
     * 从待办截止通知点进来时要定位的那一条，以及这一次定位的令牌。
     *
     * 令牌每点一次通知就换一个新值：同一条待办改了截止时间后会被提醒第二次，
     * 只传 id 的话第二次点通知没有任何反应（值没变，Compose 不会重新执行）。
     */
    highlightedTaskId: Long? = null,
    highlightRequest: Long = 0L,
    modifier: Modifier = Modifier
) {
    val cardRevealProgress = LocalCardRevealProgress.current
    // 滚动浮现要读列表的滚动位置，所以得有 listState。
    // 「从下方滚入」的淡入 + 轻微放大见 [scrollRevealAt]。
    val listState = rememberLazyListState()
    val scrollRevealEnabled = rememberScrollRevealEnabled(listState)

    // 通知定位后的高亮。只活一小会儿：它回答的是「你点的那条在这里」，
    // 不是给这条待办加一个永久装饰。
    var activeHighlightTaskId by remember { mutableStateOf<Long?>(null) }

    // Filter tasks based on search, type, and status
    val filteredTasks = remember(
        state.tasks,
        state.searchQuery,
        state.selectedTaskTypeKey,
        state.statusFilters,
        activeHighlightTaskId
    ) {
        state.tasks.filter { task ->
            // 通知点进来的那一条**临时穿透所有筛选**：用户是按通知来找它的，
            // 它可能已经被勾成「已完成」、被搜索词或类型筛选挡在外面。
            // 只对 id 放行、不改用户自己的筛选条件 —— 几秒后高亮收回，
            // 列表回到用户原来那份筛选结果，不留下被悄悄改过的开关。
            if (task.id == activeHighlightTaskId) return@filter true

            val matchSearch = state.searchQuery.isBlank() ||
                    task.title.contains(state.searchQuery, ignoreCase = true) ||
                    task.courseName.contains(state.searchQuery, ignoreCase = true) ||
                    task.description.contains(state.searchQuery, ignoreCase = true)

            // 类型筛选直接比 key：内置存枚举名、自定义存 CUSTOM:<id>、未分类存空串。
            // 先 resolve 再比是多余的——未分类的哨兵本来就等于 taskType 的原值。
            val matchType = state.selectedTaskTypeKey == null ||
                task.taskType == state.selectedTaskTypeKey

            val matchStatus = if (task.isCompleted) {
                TaskStatusFilter.COMPLETED in state.statusFilters
            } else {
                TaskStatusFilter.PENDING in state.statusFilters
            }

            matchSearch && matchType && matchStatus
        }
    }

    // 各分组的折叠态。只存被折叠的组的键，默认全展开。
    // 刻意留在 Composable 里不进 ViewModel：它是浏览时的一次性操作，
    // 换筛选条件时同一组不该自己弹开，所以状态要活过 filter 的变化。
    // 键里带了分组模式前缀，所以切模式不会把另一模式的折叠状态带过来。
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }

    // 先把所有组展开再定位：目标很可能落在某个用户上次折起来的组里，
    // 不展开的话滚动找不到它，表现成「点了通知，列表根本没动」。
    LaunchedEffect(highlightRequest) {
        if (highlightRequest <= 0L) return@LaunchedEffect
        collapsedGroups = emptySet()
        activeHighlightTaskId = highlightedTaskId
    }

    // 课程缺 colorHex 时的兜底色，以及组头默认字色。
    // 必须在 LazyListScope 外面取：MaterialTheme.colorScheme 是 @Composable 读，
    // 而 buildChecklistRows 不是 composable 作用域。
    val fallbackCourseColor = MaterialTheme.colorScheme.primary
    val defaultInk = MaterialTheme.colorScheme.onSurface
    val errorInk = MaterialTheme.colorScheme.error

    val rows = remember(
        filteredTasks,
        state.groupingMode,
        collapsedGroups,
        state.customTaskTypes,
        fallbackCourseColor,
        defaultInk,
        errorInk
    ) {
        buildChecklistRows(
            tasks = filteredTasks,
            mode = state.groupingMode,
            collapsed = collapsedGroups,
            customTypes = state.customTaskTypes,
            fallbackCourseColor = fallbackCourseColor,
            defaultInk = defaultInk,
            errorInk = errorInk
        )
    }

    // 正在演离场的行，以及**离场期间冻结的整张表**。
    //
    // 为什么要冻结：勾完那一刻数据那边已经把它判成「已完成」，而 DAO 的排序会把已完成
    // 排到末尾——照数据重建这张表，卡片会在离场之前先跳到列表底部（实测日志：信号到达后
    // 7ms，这一条就从第 5 位跑到了最后一位）。所以冻结点哪儿就冻哪儿：点下去（或确认弹窗
    // 一开）时把当时的行表留住，离场这一行按**冻结表的位置**画、按**活的那份数据**上色
    // （绿圈、勾、划线都来自数据），演完再从冻结表里摘掉，然后放行。
    var exitingIds by remember { mutableStateOf(emptySet<Long>()) }
    var frozenRows by remember { mutableStateOf<List<ChecklistRow>?>(null) }
    // 正在淡出的**组头**。一个组的待办全部离场之后，组头跟着退场（见离场回调里的判断）。
    // 它和 [exitingIds] 分开记：组头不是待办，演的是另一支动画（纯淡出）。
    var exitingHeaderKeys by remember { mutableStateOf(emptySet<String>()) }

    // 点一颗圈：先冻表，**但不在这里演离场**。
    //
    // 冻表要抢在写库之前：数据一落地，这条待办就会被排到末尾（DAO 按 isCompleted 排），
    // 晚一帧冻表，抓到手的就已经是那张排过序的表了。
    //
    // 演离场却不能在点下去的这一拍。确认弹窗默认是开的，这一刻用户还没点「完成」，
    // 这一条到底算不算做完还不知道。早演一遍有两个后果：弹窗还挡在中间，卡片已经在后面
    // 淡走了；而且演完这一行就从冻结表里摘掉了，等用户真的按了「完成」，数据里它已经
    // 变成「已完成」被筛出去，表里也找不到它——那个 id 就永远留在「正在离场」的名单里，
    // 冻结表再也放不开，分类的展开/收起会整个失效。
    //
    // 所以离场只由「真的勾上了」的信号（`pendingCompletions`）起步：
    // 直接点圈那条在写库之前就发出信号，确认弹窗那条要等用户按了「完成」。
    val freezeTable: () -> Unit = {
        // 「已完成」也在筛选里时，勾完这一条并不会离开列表——它只是换个样子留在原地。
        // 那种情况不冻表：不会有人来演，冻了反而多一道放行要管。
        if (TaskStatusFilter.COMPLETED !in state.statusFilters && frozenRows == null) {
            frozenRows = rows
        }
    }

    // 确认弹窗这条路：弹窗一开也冻一次（点圈时通常已经冻过，这里是兜底——比如弹窗是
    // 从别的入口打开的）。弹窗关掉之后不能立刻放行：确认那条路的「勾完信号」紧跟着
    // 这次状态更新发出，中间隔着一次重组。现在就解冻，等于把刚冻好的表扔掉，卡片又会按
    // 「已完成排在末尾」重建。等一拍再判断，取消确认的情况也不过多冻半秒。
    val latestState by rememberUpdatedState(state)
    // 这两个要在 delay 之后读**当时**的值：500ms 里卡片可能刚演完、组头刚开始淡出，
    // 拿启动这个 effect 时捕获的旧值判断，就会在组头淡到一半时把冻结表撤掉。
    val latestExitingIds by rememberUpdatedState(exitingIds)
    val latestExitingHeaders by rememberUpdatedState(exitingHeaderKeys)
    LaunchedEffect(state.confirmingTaskComplete) {
        if (state.confirmingTaskComplete != null) {
            if (frozenRows == null) frozenRows = rows
        } else {
            delay(500)
            if (latestExitingIds.isEmpty() &&
                latestExitingHeaders.isEmpty() &&
                latestState.pendingCompletions.isEmpty()
            ) {
                frozenRows = null
            }
        }
    }

    // 勾完的信号从 ViewModel 来，**离场只在这里起步**（理由见 [freezeTable]）。
    LaunchedEffect(state.pendingCompletions) {
        val pending = state.pendingCompletions
        if (pending.isEmpty()) return@LaunchedEffect
        onConsumeCompletions(pending.map { it.taskId })
        val now = System.currentTimeMillis()
        val fresh = pending.filter {
            now - it.atMillis <= TaskCompleteMotion.CompletionSignalTtlMs
        }
        if (fresh.isEmpty()) return@LaunchedEffect
        // 同上：已完成也在这份筛选里时，卡片不会离开列表，不演离场
        if (TaskStatusFilter.COMPLETED in state.statusFilters) return@LaunchedEffect
        // 只演「这张表里真的有那一行」的离场。没有这一道，信号可能指向一条已经不在表里的
        // 待办（在总览页勾掉的、或者数据比信号先一步回流），那一行永远等不到演完的回调，
        // 冻结表就一直放不开。
        val table = frozenRows ?: rows
        val present = table.filterIsInstance<ChecklistRow.Item>().map { it.task.id }.toSet()
        val ids = fresh.map { it.taskId }.filter { it in present }.toSet()
        if (ids.isEmpty()) return@LaunchedEffect
        if (frozenRows == null) frozenRows = table
        exitingIds = exitingIds + ids
    }

    // 离场中的行：位置取冻结表，内容取数据里活的那一份。
    val liveById = state.tasks.associateBy { it.id }
    val shownRows: List<ChecklistRow> = run {
        val frozen = frozenRows ?: return@run rows
        if (exitingIds.isEmpty()) return@run frozen
        frozen.map { row ->
            if (row is ChecklistRow.Item && row.task.id in exitingIds) {
                liveById[row.task.id]?.let { ChecklistRow.Item(it) } ?: row
            } else {
                row
            }
        }
    }


    // 行数组是定位的唯一依据：组头 / 折叠状态 / 排序都可能改变目标的下标，
    // 在 effects 里重新算一遍 indexOfFirst，而不是从通知里带一个下标过来。
    LaunchedEffect(activeHighlightTaskId, rows) {
        val id = activeHighlightTaskId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is ChecklistRow.Item && it.task.id == id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            delay(2600)
        } else {
            // 待办可能刚被删掉。不必久留一个找不到目标的高亮。
            delay(400)
        }
        if (activeHighlightTaskId == id) activeHighlightTaskId = null
    }

    // 入场序号按卡片从上往下数，**组头不占号**。
    //
    // 必须预先算好并按 row.key 索引，不能在 item 里拿累加计数器：`items` 的内容
    // 是懒执行的，捕获的计数器在滚动时被后续 item 复用，序号会越滚越大，
    // 而且这种错只在滚动后才看得见，静止时一切正常。
    val revealOrderByRowKey = remember(rows) {
        buildMap {
            var i = 0
            rows.forEach { row ->
                if (row is ChecklistRow.Item) put(row.key, i++)
            }
        }
    }

    val typeOptions = remember(state.customTaskTypes) {
        TaskTypes.options(state.customTaskTypes)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PageHInset)
    ) {
        // 顶栏从 64.dp 收到 40.dp 之后，这一行留白也跟着收：原来那 8.dp 是在
        // 补偿一条高顶栏的视觉重量，现在顶栏自己已经够轻，再留一截会显得内容被推走。
        //
        // 这里原来还压着一个 44.dp 的满宽搜索框，已经挪进顶栏（见 MainActivity 的
        // SearchFieldStrip）：搜索框是正文里最占地方的一行，收起态下它一出现，
        // 第一屏就永远少一张卡片。现在这一行下面直接就是筛选 chip。
        Spacer(modifier = Modifier.height(4.dp))

        // 第一行 chip：分组方式 + 状态筛选。
        //
        // 状态筛选从第二行挪上来、排在「按类型」后面——筛选和分组是同一层级的操作，
        // 拆成两行意味着每次想同时改一个分组和一个状态都要在两行之间来回看。
        // chip 变多到 7 个，交给 LazyRow 横向滚动承接。
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(TaskGroupingMode.entries) { mode ->
                SelectableChip(
                    label = mode.label,
                    selected = state.groupingMode == mode,
                    onClick = { onSelectGroupingMode(mode) },
                    testTag = "grouping_mode_${mode.name}"
                )
            }
            items(TaskStatusFilter.entries) { status ->
                SelectableChip(
                    label = status.label,
                    selected = status in state.statusFilters,
                    onClick = { onToggleStatusFilter(status) },
                    testTag = "status_filter_${status.name}"
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 第二行 chip：待办类型。
        // 「全部 / 各类型 / 未分类 / + 新建」排在一起——它们回答的是同一个问题
        // （这些待办属于哪一类），拆到两处反而要来回找。
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                SelectableChip(
                    label = "全部分类",
                    selected = state.selectedTaskTypeKey == null,
                    onClick = { onSelectTaskTypeKey(null) }
                )
            }

            items(typeOptions) { option ->
                SelectableChip(
                    label = option.label,
                    selected = state.selectedTaskTypeKey == option.key,
                    onClick = { onSelectTaskTypeKey(option.key) },
                    testTag = "task_type_${option.key}",
                    // 删除挂在长按上：删除的入口必须显眼到能被发现，
                    // 又不能比「选中这个筛选」更显眼——一个常驻的删除按钮会让
                    // 这行 chip 变成一排随时会误触的按钮。
                    onLongClick = if (option.isCustom) {
                        {
                            state.customTaskTypes
                                .firstOrNull { it.key == option.key }
                                ?.let(onConfirmDeleteType)
                        }
                    } else {
                        null
                    }
                )
            }

            // 类型可以留空，所以「未分类」本身就是一个可筛的类目。
            // 不给它 chip 的话，用户在编辑里清掉类型之后就再也找不回这批待办了。
            item {
                SelectableChip(
                    label = NO_TYPE_GROUP_TITLE,
                    selected = state.selectedTaskTypeKey == TASK_TYPE_NONE,
                    onClick = { onSelectTaskTypeKey(TASK_TYPE_NONE) },
                    testTag = "task_type_none"
                )
            }

            item {
                SelectableChip(
                    label = "+ 新建",
                    selected = false,
                    onClick = onOpenNewTypeDialog,
                    testTag = "create_task_type_chip"
                )
            }
        }

        Spacer(modifier = Modifier.height(CardBlockGap))

        // Task List with Grouping
        if (shownRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .cardRevealAt(cardRevealProgress, 0),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📋 暂无待办",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "换个筛选条件，或点右下角添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(CardListGap),
                // 底部给浮岛导航留出收尾空间，内容本身仍会滚到岛底下被毛玻璃糊住
                contentPadding = PaddingValues(bottom = islandBottomInset())
            ) {
                // 逐行一个 item：视口外的卡片不会被 compose，这才是真正的懒加载。
                // animateItem 只取位移这一半：折叠时下方已有卡平滑让位；
                // 勾掉一条时它是**第二拍**——卡片原地淡出之后，下面的卡片滚上来补位。
                // 少了它，插入/删除都是瞬时的，视觉上是「跳」而不是「动」。
                //
                // 淡入淡出必须显式关掉：默认两个 spec 都是 spring，fadeOut 会在 item
                // 离开 composition 时继续画半透明的旧卡片。而切换分组模式/筛选会一次性
                // 把整批 key 全换掉、旧 item 同时 dispose，于是整屏旧卡片半透明地叠在
                // 新内容上（连组头文字都留着残影）。位移动画没有这个问题——key 对不上的
                // item 直接 dispose，位移无从谈起。
                items(shownRows, key = { it.key }) { row ->
                    when (row) {
                        is ChecklistRow.Header -> TaskRowFadeOut(
                            playing = row.key in exitingHeaderKeys,
                            onFinished = {
                                exitingHeaderKeys = exitingHeaderKeys - row.key
                                frozenRows = frozenRows?.filterNot { it.key == row.key }
                                if (exitingIds.isEmpty() && exitingHeaderKeys.isEmpty()) {
                                    frozenRows = null
                                }
                            },
                            modifier = Modifier
                                .animateItem(
                                    // 组头也跟着用清单页统一的那条位移：折叠时下方卡片
                                    // 让位、勾掉一条之后补位，都是同一次「重力」，不该两种手感。
                                    placementSpec = TaskCompleteMotion.RiseSpec,
                                    fadeInSpec = null,
                                    fadeOutSpec = null
                                )
                                // 组头跟组内的卡片一起浮现：组头「啪」地出现、
                                // 底下的卡却还在淡入，读起来是两种动画在打架。
                                .scrollRevealAt(listState, row.key, scrollRevealEnabled)
                        ) {
                            TaskGroupHeader(
                                group = row.group,
                                isCollapsed = row.isCollapsed,
                                onToggle = {
                                    collapsedGroups = if (row.isCollapsed) {
                                        collapsedGroups - row.group.id
                                    } else {
                                        collapsedGroups + row.group.id
                                    }
                                }
                            )
                        }

                        is ChecklistRow.Item -> TaskExitAnimation(
                            playing = row.task.id in exitingIds,
                            onFinished = {
                                // 这一条走了之后，它这一组还剩几行？
                                // 组头挂在「它上面最近的那一个 Header」之下，所以先定位这个 Header，
                                // 再数到下一个 Header 之前还有没有别的待办——正在演的那几条
                                // 也还在表里，所以「还剩几行」= 真实还看得见的行数。
                                val snapshot = frozenRows ?: emptyList()
                                val index = snapshot.indexOfFirst {
                                    it is ChecklistRow.Item && it.task.id == row.task.id
                                }
                                val headerKey = if (index >= 0) {
                                    snapshot.subList(0, index)
                                        .lastOrNull { it is ChecklistRow.Header }
                                        ?.key
                                } else {
                                    null
                                }
                                val rowsLeftInGroup = if (index >= 0) {
                                    snapshot.drop(index + 1)
                                        .takeWhile { it !is ChecklistRow.Header }
                                        .count { it is ChecklistRow.Item }
                                } else {
                                    1
                                }

                                exitingIds = exitingIds - row.task.id
                                frozenRows = snapshot.filterNot {
                                    it is ChecklistRow.Item && it.task.id == row.task.id
                                }
                                if (headerKey != null && rowsLeftInGroup == 0) {
                                    // 整组都走完了：组头跟着淡出（卡先淡完，它再退），
                                    // 淡完才把它从表里摘掉，然后下面的组滚上来。
                                    exitingHeaderKeys = exitingHeaderKeys + headerKey
                                } else if (exitingIds.isEmpty() && exitingHeaderKeys.isEmpty()) {
                                    // 最后一个演完才放行：列表回到按数据重建的那一份
                                    frozenRows = null
                                }
                            },
                            // 位移挂在**这一行的根**上：卡片淡出之后才用得上，
                            // 由它把下面的卡片**滚上来**补位——节奏见
                            // [TaskCompleteMotion.RiseSpec]（比淡出慢一截，
                            // 让「让位」成为一眼看得见的第二拍）。
                            // 淡入淡出依旧关着，见上面的理由。
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = TaskCompleteMotion.RiseSpec,
                                    fadeInSpec = null,
                                    fadeOutSpec = null
                                )
                                .cardRevealAt(
                                    cardRevealProgress,
                                    revealOrderByRowKey[row.key] ?: 0
                                )
                                // 从下方滚进来时淡入 + 轻微放大（微微「浮出」）。
                                .scrollRevealAt(listState, row.key, scrollRevealEnabled)
                                // 卡片与页面左边距对齐，不再比组头缩进
                                .padding(start = GROUP_CONTENT_INSET)
                        ) {
                            TaskItemCard(
                                task = row.task,
                                customTypes = state.customTaskTypes,
                                onToggleComplete = {
                                    // 点下去就先冻表：晚一帧，这张表里它已经被排到末尾了。
                                    // 离场等信号来了才演，见上面的 [freezeTable]。
                                    if (!row.task.isCompleted) freezeTable()
                                    onToggleTaskComplete(row.task)
                                },
                                onToggleStar = { onToggleTaskStar(row.task) },
                                onOpenDetail = { onInspectTask(row.task) },
                                highlighted = activeHighlightTaskId == row.task.id
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
