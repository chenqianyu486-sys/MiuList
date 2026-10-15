package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.CourseEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import com.example.data.model.TaskTypes
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.readableOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val DEFAULT_DUE_DAYS = 3
private const val DEFAULT_DUE_HOUR = 23
private const val DEFAULT_DUE_MINUTE = 59

/** 一行紧凑的字段标签。原先每个字段上方都顶一个加粗标题，弹层高度被标签吃掉大半。 */
@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * 类型行里的一颗 chip。选中 = 主题绿平涂 + 深墨字，与筛选 chip 同一套语言。
 *
 * 单独抽出来而不是复用 [SelectableChip]（在 `ChecklistScreen` 里）：
 * 那个的字号是给筛选行的，弹层里一行塞着更多东西，字得小一档。
 */
@Composable
private fun TypeChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        shape = RoundedCornerShape(10.dp),
        colors = micaChipColors(),
        border = micaChipBorder(selected),
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditDialog(
    initialTask: TaskEntity?,
    courses: List<CourseEntity>,
    customTypes: List<CustomTaskType>,
    preselectedCourseId: Long?,
    onDismiss: () -> Unit,
    onCreateTaskType: () -> Unit,
    onSave: (TaskEntity) -> Unit
) {
    // 第 0 项永远是「不关联课程」，其余是真实课程
    val courseOptions: List<CourseEntity?> = remember(courses) { buildList { add(null); addAll(courses) } }

    // 用 index 表示选择，避免同时持有「选中的课程对象」——课程可能刚好在编辑期间被删掉
    val initialIndex = remember(initialTask, courseOptions, preselectedCourseId) {
        val wantedId = initialTask?.courseId ?: preselectedCourseId
        courseOptions.indexOfFirst { it?.id == wantedId }.let { if (it < 0) 0 else it }
    }
    var selectedIndex by remember { mutableIntStateOf(initialIndex) }
    val selectedCourse = courseOptions.getOrNull(selectedIndex)

    var title by remember { mutableStateOf(initialTask?.title ?: "") }
    var description by remember { mutableStateOf(initialTask?.description ?: "") }
    // 类型存 key 字符串（枚举名 / CUSTOM:<id> / 空串），不再存枚举：
    // 用户新建的类型不在枚举里，存枚举就等于把新建的类型在编辑时也一起丢掉。
    // 新建待办时默认不选类型——「先想清楚这条属于哪类」不该是记一件事的前置条件。
    var selectedTypeKey by remember {
        mutableStateOf(initialTask?.taskType.orEmpty())
    }
    var isStarred by remember {
        // 默认不标星：新建待办不该顺手替用户做一次重要性判断。
        // 旧的三级值（高/中/低）在这里就已经归一成两态了。
        mutableStateOf(initialTask?.let { TaskStar.isMarked(it.priority) } ?: false)
    }

    // 截止存真实时间戳，不存「几天后」的偏移——偏移只在保存那一刻才有意义。
    // 关掉「不设截止」时 dueMillis 里的值原样留着不清零，这样用户改主意打开它，
    // 日期回到刚才那个而不是跳成 3 天后。
    val initialHasDue = remember(initialTask) {
        initialTask?.let { TaskEntity.hasDue(it) } ?: true
    }
    var hasDue by remember { mutableStateOf(initialHasDue) }
    var dueMillis by remember {
        mutableStateOf(
            initialTask?.dueDateTimestamp?.takeIf { initialHasDue } ?: defaultDueTimestamp()
        )
    }
    val dueCal = remember(dueMillis) { Calendar.getInstance().apply { timeInMillis = dueMillis } }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        // 关掉平台默认宽度：默认左右各留一大截内边距，这里全是单行控件，
        // 那点宽度换不来任何信息量
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp),
        title = {
            Text(
                text = if (initialTask == null) "新建待办" else "编辑待办",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("待办名称") },
                    placeholder = { Text("如：【作业】数据结构 树遍历") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_title_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注（选填）") },
                    placeholder = { Text("如：教材第58页第3题，周五前交") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3
                )

                FieldLabel("关联课程")
                CourseWheelPicker(
                    options = courseOptions,
                    selectedIndex = selectedIndex,
                    onSelected = { index ->
                        selectedIndex = index
                        // 换课程时补上自动标题，但只在标题还是空或模板时才动
                        val c = courseOptions.getOrNull(index)
                        if (c != null && (title.isBlank() || title.startsWith("【"))) {
                            title = autoTitle(selectedTypeKey, c)
                        }
                    }
                )

                FieldLabel("待办类型")
                // 类型是一行横排：内置 3 个 + 用户新建的，数量不可控，
                // 所以既用横向滚动，又在最前面留一个「不设」的落点。
                // 「不设」不是一种类型，是「这条不属于任何类型」——它必须一直摆在
                // 第一位，否则一旦有人建了自定义类型，想清掉类型就得先滚回最左端。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TypeChoiceChip(
                        label = "不设",
                        selected = selectedTypeKey.isEmpty(),
                        onClick = { selectedTypeKey = "" }
                    )
                    TaskTypes.options(customTypes).forEach { option ->
                        TypeChoiceChip(
                            label = option.label,
                            selected = selectedTypeKey == option.key,
                            onClick = {
                                selectedTypeKey = option.key
                                selectedCourse?.let { c ->
                                    if (title.isBlank() || title.startsWith("【")) {
                                        title = autoTitle(option.key, c)
                                    }
                                }
                            },
                            testTag = "task_edit_type_${option.key}"
                        )
                    }
                    // 建类型必须能从「给一条待办选类型」这件事直接发生。
                    // 放到设置页去新建，就等于要求用户先想清楚「我现在到底是在配置
                    // 还是在记事」——而绝大多数人是在记到一半才发现缺一类才去找这个功能。
                    TypeChoiceChip(
                        label = "+ 新建",
                        selected = false,
                        onClick = onCreateTaskType,
                        testTag = "create_task_type_chip"
                    )
                }

                FieldLabel("星标")
                // 星标只有两态，所以是**一个可点的开关**而不是一排选项：
                // 一排「高/中/低」会让人以为这是个必答的选择题，
                // 而实际上绝大多数待办都不该被标星——默认空着，什么都不做就是对的。
                // 单个 chip 还少占一整行的宽度，下面的截止时间不用被挤下去。
                FilterChip(
                    selected = isStarred,
                    onClick = { isStarred = !isStarred },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                // 选中态跟 chip 自己说话：主题绿平涂 + 墨色图标，
                                // 不在这里硬塞琥珀（琥珀压在 #BCE472 上只有 3.96:1，
                                // 而墨色是和选中文字同一支，能到 15:1 以上）。
                                // 琥珀只属于卡片和详情弹层——那才是它要被发现的地方。
                                tint = if (isStarred) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("标星")
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = micaChipColors(),
                    border = micaChipBorder(isStarred),
                    modifier = Modifier.testTag("task_edit_star_chip")
                )

                FieldLabel("截止时间")
                // 「不设截止」是开关而不是又一个日期选项：
                // 「不设」和某个具体日期不是同一条轴上的两个值，混在一排里
                // 只会让人以为不设=某个日期。关掉时日期/时间两段一起收起来，
                // 留一个写死的日期在那儿等于在暗示「这条其实有截止」。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Switch(
                            checked = hasDue,
                            onCheckedChange = { hasDue = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "设置截止时间",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (hasDue) {
                        PickerSegment(
                            text = "${dueCal.get(Calendar.MONTH) + 1}月${dueCal.get(Calendar.DAY_OF_MONTH)}日",
                            modifier = Modifier.weight(1f),
                            onClick = { showDatePicker = true }
                        )
                        PickerSegment(
                            text = String.format(
                                Locale.getDefault(), "%02d:%02d",
                                dueCal.get(Calendar.HOUR_OF_DAY),
                                dueCal.get(Calendar.MINUTE)
                            ),
                            modifier = Modifier.weight(1f),
                            onClick = { showTimePicker = true }
                        )
                    } else {
                        Text(
                            text = NO_DUE_TEXT,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val course = selectedCourse
                    val cal = Calendar.getInstance().apply { timeInMillis = dueMillis }
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val typeOption = TaskTypes.resolve(selectedTypeKey, customTypes)
                    val resolvedTitle = title.ifBlank {
                        course?.let { autoTitle(selectedTypeKey, it) }
                            ?: typeOption?.label
                            ?: "待办"
                    }

                    val taskToSave = initialTask?.copy(
                        courseId = course?.id ?: TaskEntity.UNLINKED_COURSE_ID,
                        courseName = course?.name ?: TaskEntity.UNLINKED_COURSE_NAME,
                        courseCategory = course?.category ?: "",
                        courseColorHex = course?.colorHex ?: TaskEntity.UNLINKED_COLOR_HEX,
                        title = resolvedTitle,
                        description = description,
                        taskType = selectedTypeKey,
                        startTimeString = initialTask?.startTimeString
                            ?: course?.let { "本周排期 · ${it.name}" }
                            ?: "",
                        priority = TaskStar.encode(isStarred),
                        // 不设截止时两列一起清：只把时间戳换成哨兵、dueDateString
                        // 还留着旧日期的话，弹层里就会显示一个和「不设截止」矛盾的时间。
                        dueDateTimestamp = if (hasDue) dueMillis else TaskEntity.NO_DUE_TIMESTAMP,
                        dueDateString = if (hasDue) sdf.format(cal.time) else ""
                    ) ?: TaskEntity(
                        courseId = course?.id ?: TaskEntity.UNLINKED_COURSE_ID,
                        courseName = course?.name ?: TaskEntity.UNLINKED_COURSE_NAME,
                        courseCategory = course?.category ?: "",
                        courseColorHex = course?.colorHex ?: TaskEntity.UNLINKED_COLOR_HEX,
                        title = resolvedTitle,
                        description = description,
                        taskType = selectedTypeKey,
                        startTimeString = course?.let { "本周排期 · ${it.name}" } ?: "",
                        priority = TaskStar.encode(isStarred),
                        dueDateTimestamp = if (hasDue) dueMillis else TaskEntity.NO_DUE_TIMESTAMP,
                        dueDateString = if (hasDue) sdf.format(cal.time) else "",
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
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )

    if (showDatePicker) {
        // DatePickerState 的选中值是 **UTC 零点**：初值也要造一个 UTC 毫秒数，
        // 拿本地日历直接换算会整体偏一天
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Calendar.getInstance(UTC).apply {
                clear()
                set(
                    dueCal.get(Calendar.YEAR),
                    dueCal.get(Calendar.MONTH),
                    dueCal.get(Calendar.DAY_OF_MONTH)
                )
            }.timeInMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMillis ->
                        val utc = Calendar.getInstance(UTC).apply { timeInMillis = utcMillis }
                        dueMillis = Calendar.getInstance().apply {
                            timeInMillis = dueMillis
                            set(Calendar.YEAR, utc.get(Calendar.YEAR))
                            set(Calendar.MONTH, utc.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                        }.timeInMillis
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = dueCal.get(Calendar.HOUR_OF_DAY),
            initialMinute = dueCal.get(Calendar.MINUTE),
            is24Hour = true
        )
        GlassOverlayHost(
            onDismissRequest = { showTimePicker = false },
            horizontalPadding = 24.dp,
            title = { Text("选择时间", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dueMillis = Calendar.getInstance().apply {
                        timeInMillis = dueMillis
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            }
        )
    }
}

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/**
 * 「课程」滚筒：中间一项高亮，惯性滚动停稳后落到最近的一项。
 *
 * [DatePickerState] 的选中值是 **UTC 零点**，所以这里也必须造一个 UTC 毫秒值来当初值，
 * 拿本地时区去换算会整体偏一天。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CourseWheelPicker(
    options: List<CourseEntity?>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    val itemHeight: Dp = 40.dp
    val visibleCount = 3
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }

    // 初值就要落位，不能等 LaunchedEffect 里 scrollToItem：
    // 那时候 LazyColumn 还没测量，滚动指令会被直接吞掉，滚筒停在第 0 项。
    // contentPadding 上下各留一个 itemHeight、视口 3 高，所以 firstVisibleItemIndex = i
    // 恰好让第 i 项的行中心对准高亮带，不需要再补偏移。
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()

    // 停稳后才认新的选中项：按「首个可见项 + 它已滑过去的比例」算出正中间落在第几项。
    // 滚动过程中置空，这样 snapshotFlow 只在停下来那一刻发一次值。
    var settledIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(listState, itemHeightPx, options.size) {
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                listState.firstVisibleItemIndex +
                    (listState.firstVisibleItemScrollOffset / itemHeightPx).toInt()
            }
        }
            .distinctUntilChanged()
            .filterNotNull()
            .collect { settledIndex = it.coerceIn(0, options.lastIndex.coerceAtLeast(0)) }
    }

    LaunchedEffect(settledIndex) {
        if (settledIndex != selectedIndex) onSelected(settledIndex)
    }

    // 外部改了选中项（比如从别的入口进来、或刚 tap 某一项）要把滚筒拨过去。
    // contentPadding 上下各留一个 itemHeight，视口 3 高，所以 scrollToItem(i) 之后
    // 第 i 项的行中心正好落在高亮带上，不需要再补偏移。
    LaunchedEffect(selectedIndex, options.size) {
        if (!listState.isScrollInProgress &&
            listState.firstVisibleItemIndex != selectedIndex
        ) {
            listState.scrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight * visibleCount)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        // 中间那条高亮带压在列表底下，靠 contentPadding 露出中间正好一项
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(ChipSurfaceNeutral)
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = itemHeight * ((visibleCount - 1) / 2f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(options) { index, course ->
                val isSelected = index == settledIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable {
                            onSelected(index)
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (course != null) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(parseColor(course.colorHex))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = course?.name ?: TaskEntity.UNLINKED_COURSE_NAME,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        // 粉彩原色压浅底读不出来，选中项压暗一档保住色相
                        color = when {
                            !isSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                            course == null -> MaterialTheme.colorScheme.onSurface
                            else -> readableOn(parseColor(course.colorHex))
                        }
                    )
                    if (course != null && course.category.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = course.category,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 截止日期 / 时间各自的可点段落。两段一行，省掉原先一整排「今天/明天/3天后」chip。 */
@Composable
private fun PickerSegment(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ChipSurfaceNeutral)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/**
 * 换课程时补的默认标题。
 *
 * 入参是**类型 key** 而不是枚举：用户新建的类型也要参与，但它们不在枚举里。
 * 只有三个内置类型有专属的方括号前缀——那是 App 认得的几种活儿；
 * 自定义类型不套模板，直接用「课程名 + 待办」，因为自动加一个「【读书笔记】」
 * 前缀是在替用户断言这条待办的性质，而他自己还没想好。
 */
private fun autoTitle(typeKey: String, course: CourseEntity): String = when (typeKey) {
    TaskType.PRE_STUDY.name -> "【预习】${course.name}"
    TaskType.POST_CLASS.name -> "【作业】${course.name}"
    TaskType.LAB_REPORT.name -> "【实验】${course.name}"
    else -> "${course.name} 待办"
}

/** 新建待办时的默认截止：3 天后的 23:59。 */
private fun defaultDueTimestamp(): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, DEFAULT_DUE_DAYS)
    set(Calendar.HOUR_OF_DAY, DEFAULT_DUE_HOUR)
    set(Calendar.MINUTE, DEFAULT_DUE_MINUTE)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
