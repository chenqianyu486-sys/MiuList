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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.calendar.AppZone
import com.example.data.local.CourseEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import com.example.data.model.TaskTypes
import com.example.ui.theme.AdaptiveDialogContent
import com.example.ui.theme.ChipSurfaceNeutral
import com.example.ui.theme.readableOn
import com.example.ui.theme.scaledWithText
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

/**
 * 类型行那颗「不属于任何类型」的 chip 文案。与 `ChecklistScreen.NO_TYPE_GROUP_TITLE` 同名，
 * 别再改回「不设」——那是否定式选择，读起来像「这项可以不设」，而不是一个归类名。
 */
private const val TASK_TYPE_NONE_LABEL = "未分类"

/**
 * Material3 `DatePickerState.selectedDateMillis` 的约定：**UTC 零点**。
 *
 * 所以「造初值」和「读回选中的值」这两处**必须**用 UTC，
 * 换成北京时区会整体偏一天（东八区下偏 8 小时）。
 *
 * 但它只是个中间语言，不是墙上的截止时间 —— 真正写进 [dueMillis] 的那一侧
 * 要用 [AppZone]，否则用户挑了 10/20，存进去的是 10/20 的某个钟面时刻。
 */
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/**
 * 待办的**编辑弹层**：卡片上点一下待办，进来的就是这一层，改完直接存。
 *
 * ## 为什么是「弹层里就地编辑」，而不是「详情弹层 + 编辑弹窗」两层
 *
 * 原来是两层：点待办 → 详情弹层（只读）→ 点「编辑」→ 编辑弹窗（表单）→ 保存。
 * 用户为了改一个字，要经过两层弹层、三次点击；而**弹窗叠在弹层上**这件事在手机上
 * 尤其别扭：底下那层被盖住一半，返回键还要先关掉上面那层才对得下面那层。
 *
 * 现在合成一层：
 * - 标题、备注**常驻就是输入框**（不是只读文字 + 一个「编辑」按钮）；
 * - 分类、截止、课程**就地可改**，不需要先切模式；
 * - 保存按钮固定在底部，永远看得见、点得到。
 *
 * 底部那枚主按钮**跟着「有没有改动过」变**：没改动时它是「关闭」（描边），
 * 改动了才变成实心的「保存」。没东西可存的时候摆一个「保存」，
 * 只会让人以为要点它一下 —— 尤其新建待办：什么都没填就按「保存」，
 * 会造出一条空记录。
 *
 * 「查看」和「编辑」不再分成两个状态：弹层里看到的就是能改的那份，
 * 改完不点保存就当作没改（和所有表单一致）。
 *
 * ## 版式为什么是「笔记」而不是「表单」
 *
 * 表单那套（每个字段一个带 label 的带框输入框）在这里全撤了：
 * 一个 411dp 的屏上，两个带框输入框 + 三个字段标题就吃掉 200dp，
 * 弹层被顶得比屏幕还高。而这条 App 里待办是「一件事」，不是「一条表单记录」——
 * 所以标题和备注按笔记的写法：无边框、平时就是一段文字，聚焦时才浮出一条底线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    initialTask: TaskEntity?,
    courses: List<CourseEntity>,
    customTypes: List<CustomTaskType>,
    preselectedCourseId: Long?,
    onDismiss: () -> Unit,
    onCreateTaskType: () -> Unit,
    onDelete: (TaskEntity) -> Unit,
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

    // ---- 初值：留着算「有没有改动过」 ----
    // 打开弹层时的那一份。它不参与渲染，只用来跟草稿比差别 ——
    // 有差别才谈得上「保存」。
    val initialTitle = remember(initialTask) { initialTask?.title ?: "" }
    val initialDescription = remember(initialTask) { initialTask?.description ?: "" }
    val initialTypeKey = remember(initialTask) { initialTask?.taskType.orEmpty() }
    val initialIsStarred = remember(initialTask) {
        // 旧的三级值（高/中/低）在这里就已经归一成两态了。
        initialTask?.let { TaskStar.isMarked(it.priority) } ?: false
    }

    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    // 类型存 key 字符串（枚举名 / CUSTOM:<id> / 空串），不再存枚举：
    // 用户新建的类型不在枚举里，存枚举就等于把新建的类型在编辑时也一起丢掉。
    // 新建待办时默认不选类型——「先想清楚这条属于哪类」不该是记一件事的前置条件。
    var selectedTypeKey by remember { mutableStateOf(initialTypeKey) }
    var isStarred by remember { mutableStateOf(initialIsStarred) }

    // 截止存真实时间戳，不存「几天后」的偏移——偏移只在保存那一刻才有意义。
    // 关掉「不设截止」时 dueMillis 里的值原样留着不清零，这样用户改主意打开它，
    // 日期回到刚才那个而不是跳成 3 天后。
    val initialHasDue = remember(initialTask) {
        initialTask?.let { TaskEntity.hasDue(it) } ?: true
    }
    // 新建时的默认截止**只算一次**：算两次会差几毫秒，于是新建待办刚打开
    // 就被判成「已经改过了」。
    val initialDueMillis = remember(initialTask, initialHasDue) {
        initialTask?.dueDateTimestamp?.takeIf { initialHasDue } ?: defaultDueTimestamp()
    }
    var hasDue by remember { mutableStateOf(initialHasDue) }
    var dueMillis by remember { mutableStateOf(initialDueMillis) }
    val dueCal = remember(dueMillis) { AppZone.calendar(dueMillis) }

    // ---- 有没有改动过 ----
    // 没改动就没有东西要存，主按钮就不该叫「保存」，该叫「关闭」。
    // 新建待办尤其要紧：什么都没填就按「保存」，会造出一条标题是「待办」的
    // 空记录 —— 而用户其实只是误点开了这个弹层。
    val hasChanges = title != initialTitle ||
        description != initialDescription ||
        selectedTypeKey != initialTypeKey ||
        isStarred != initialIsStarred ||
        hasDue != initialHasDue ||
        // 截止按**秒**比：时间选择器会把毫秒清零，拿毫秒比会算出「改过了」，
        // 而用户其实只是选了个一样的时刻。
        (hasDue && dueMillis / 1000 != initialDueMillis / 1000) ||
        selectedIndex != initialIndex

    // 保存：把草稿拼成一条待办交出去。抽成 lambda 是为了让操作栏里只剩
    // 「按了会发生什么」，不夹着一大段拼字段的代码。
    val saveDraft: () -> Unit = {
        val course = selectedCourse
        val cal = AppZone.calendar(dueMillis)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            timeZone = AppZone.TIME_ZONE
        }
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
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // 课程滚筒默认**收着**：它有 3 行高，一直摊开会把弹层顶高一倍，
    // 而「换关联课程」是低频动作，不该让低频控件长期占着版面。
    var courseExpanded by remember { mutableStateOf(false) }
    // 删除是破坏性的，先切成二次确认，别让一次误点就没了
    var confirmDelete by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    GlassBottomSheetHost(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---------- 可滚动区 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // weight(fill = false)：这块区按内容取高，但**不许超过**
                    // 「面板剩下的高度」——底下的操作栏是固定的不参与滚动，
                    // 内容再长也不能把它挤出屏幕。
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 10.dp)
            ) {
                // ---- 标题 + 星标 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlainTextInput(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "待办名称",
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (initialTask?.isCompleted == true) {
                                TextDecoration.LineThrough
                            } else {
                                TextDecoration.None
                            }
                        ),
                        // 标题**允许折行**：单行输入框在极端字号下会把
                        // 「【作业】数据结构 第6周作业」截成「【作业】数据结构 第6周作」，
                        // 而那一行是用户唯一能一眼读到这条待办的地方 —— 读不全就不算标题。
                        // 行数**不设上限**：名字再长也照原样折行，不截断（原来卡在两行）。
                        // 键盘动作仍是「下一项」，所以回车不会打出换行。
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier.weight(1f),
                        testTag = "task_title_input"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (initialTask?.isCompleted == true) {
                        Text(
                            text = "已完成",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    TaskStarToggle(
                        isStarred = isStarred,
                        onToggle = { isStarred = !isStarred },
                        size = 26.dp.scaledWithText(),
                        // 未标星那一态压淡一档：亮底上一个实打实的深灰星
                        // 会被读成「已经标了」，而它本来是「还没标」。
                        unstarredColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.testTag("task_edit_star_chip")
                    )
                }

                // ---- 备注 ----
                Spacer(modifier = Modifier.height(6.dp))
                PlainTextInput(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "添加备注…",
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = false,
                    maxLines = 6,
                    restingUnderline = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    testTag = "task_description_input"
                )

                Spacer(modifier = Modifier.height(10.dp))
                HairLine()
                Spacer(modifier = Modifier.height(10.dp))

                // ---- 待办分类 ----
                // 分类是一行横排：内置 3 个 + 用户新建的，数量不可控，
                // 所以既用横向滚动，又在最前面留一个「未分类」的落点。
                // 「未分类」不是一种分类，是「这条不属于任何分类」——它必须一直摆在
                // 第一位，否则一旦有人建了自定义分类，想清掉分类就得先滚回最左端。
                // 措辞跟列表页那个兜底分组（ChecklistScreen 的 NO_TYPE_GROUP_TITLE）一致：
                // 「不设」是个否定式选择，读起来像「这项可以不设」，而它其实是个归类名。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = "待办分类" },
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TypeChoiceChip(
                        label = TASK_TYPE_NONE_LABEL,
                        selected = selectedTypeKey.isEmpty(),
                        onClick = { selectedTypeKey = "" }
                    )
                    TaskTypes.options(customTypes).forEach { option ->
                        TypeChoiceChip(
                            label = option.label,
                            selected = selectedTypeKey == option.key,
                            onClick = {
                                val previousTypeKey = selectedTypeKey
                                selectedTypeKey = option.key
                                selectedCourse?.let { c ->
                                    // 标题只在「它正好是按（课程 + 类型）算出来的那份模板」
                                    // 时才跟着分类变。判据不能是「以【开头」——
                                    // 用户自己写的「【作业】数据结构 第6周作业」也是以【开头，
                                    // 而用户只是点了个**同名**的 chip，
                                    // 标题却被改写成了「【作业】数据结构」。
                                    if (title.isBlank() || isAutoTitle(title, previousTypeKey, c)) {
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

                Spacer(modifier = Modifier.height(8.dp))

                // ---- 截止 ----
                // 「不设截止」是开关而不是又一个日期选项：
                // 「不设」和某个具体日期不是同一条轴上的两个值，混在一排里
                // 只会让人以为不设=某个日期。关掉时日期/时间两段一起收起来，
                // 留一个写死的日期在那儿等于在暗示「这条其实有截止」。
                EditorRow(label = "截止") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(checked = hasDue, onCheckedChange = { hasDue = it })
                        if (hasDue) {
                            Spacer(modifier = Modifier.width(6.dp))
                            PickerSegment(
                                text = "${dueCal.get(Calendar.MONTH) + 1}月${dueCal.get(Calendar.DAY_OF_MONTH)}日",
                                // 日期段比时间段**多分一点权重**：「10月27日」比「23:59」宽
                                // 一倍多，两段平分时极端字号（系统 2.0）下日期会正好差一个字
                                // ——「日」被挤掉，读出来是「10月27」。
                                modifier = Modifier.weight(1.2f),
                                onClick = { showDatePicker = true }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = NO_DUE_TEXT,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ---- 关联课程 ----
                // 整行可点（含标签）：滚筒默认收着，用户得知道这一行能展开。
                // 只让课程名那截可点的话，「课程」两个字反而像纯说明文字。
                EditorRow(label = "课程", onClick = { courseExpanded = !courseExpanded }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedCourse != null) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(parseColor(selectedCourse.colorHex))
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                        }
                        Text(
                            text = selectedCourse?.name ?: TaskEntity.UNLINKED_COURSE_NAME,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (selectedCourse == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedCourse != null && selectedCourse.category.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedCourse.category,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = if (courseExpanded) "收起课程选择" else "更换关联课程",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (courseExpanded) {
                    CourseWheelPicker(
                        options = courseOptions,
                        selectedIndex = selectedIndex,
                        onSelected = { index ->
                            val previousCourse = courseOptions.getOrNull(selectedIndex)
                            selectedIndex = index
                            // 换课程时补上自动标题，但只在标题还是模板时才动
                            // （理由同上面的分类 chip）
                            val c = courseOptions.getOrNull(index)
                            if (c != null && (title.isBlank() || isAutoTitle(title, selectedTypeKey, previousCourse))) {
                                title = autoTitle(selectedTypeKey, c)
                            }
                        }
                    )
                }
            }

            // ---------- 固定操作栏 ----------
            // 不跟着内容滚：保存按钮必须**一直看得见**。表单一长就把它推进滚动区
            // 看不见，表现就和「点了没反应」一样。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (confirmDelete) {
                    TextButton(
                        onClick = { confirmDelete = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(
                        onClick = { onDelete(initialTask!!) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    if (initialTask == null) {
                        // 新建待办：没改动时**没有「取消」的意义** —— 右边那个
                        // 「关闭」做的就是同一件事，两个都摆着只会让人犹豫按哪个。
                        if (hasChanges) {
                            TextButton(onClick = onDismiss) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (hasChanges) {
                        Button(
                            onClick = saveDraft,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("save_task_button")
                        ) {
                            Text(
                                text = if (initialTask == null) "保存待办" else "保存",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        // 没改动：主按钮让位给「关闭」。
                        // 它是**描边**而不是实心绿 —— 此刻没有任何东西要提交，
                        // 摆一个主按钮在那儿只会让人以为要点它一下。
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("close_task_button")
                        ) {
                            Text("关闭", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }

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
                        dueMillis = AppZone.calendar(dueMillis).apply {
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
            // 独立窗口拿不到 haze，做不了玻璃面，但遮罩得跟全 App 的弹层对齐：
            // 平台默认给的 0.32 比 GlassOverlayHost 重一倍以上。
            AlignDialogWindowScrim()
            // 独立窗口有自己的 Composition，主题里的缩放与安全区**传不进来**，
            // 所以这里自己套一遍（见 AdaptiveDialogContent 的注释）。
            AdaptiveDialogContent {
                DatePicker(state = datePickerState)
            }
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = dueCal.get(Calendar.HOUR_OF_DAY),
            initialMinute = dueCal.get(Calendar.MINUTE),
            is24Hour = true
        )
        // 必须自己开一个 `Dialog` 窗口，**不能**用 GlassOverlayHost。
        //
        // 外层这个编辑弹层现在是同窗口的底部弹层了，所以「上下窗口把事件吃掉」
        // 那条已经不成立——独立窗口画在上层反而是正确方向。这里仍然是独立窗口，
        // 理由是另外两条：
        // 1. 嵌套层需要自己的栈。同窗口 overlay 只有一层，日期/时间选择器再叠一个
        //    弹层，返回键、点外面关闭、点击不穿透这三件都会和外层抢，
        //    而它们互不相识（各自的 BackHandler 谁在上面谁先执行，遮罩也会互相盖住）。
        //    独立窗口自带独立的返回栈和 scrim，这是 Dialog 白送的东西。
        // 2. 窗口里也用不了玻璃面：haze 读不到别的窗口的源层（同一坑，同一个前提），
        //    所以这里给实心 surface，面板观感与上面的日期选择器一致。
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            // 同上：玻璃面做不到，压暗程度至少跟 GlassOverlayHost 对齐。
            AlignDialogWindowScrim()
            // 独立窗口有自己的 Composition，主题里的缩放与安全区**传不进来**，
            // 所以这里自己套一遍（见 AdaptiveDialogContent 的注释）。
            AdaptiveDialogContent {
                Surface(
                    shape = GlassDialogShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        // 上下留白用 12.dp：原来 24.dp 在 332dp 屏上把表盘挤到放不下。
                        // 左右不能写死 24.dp —— 那样在 332dp 屏上留给表盘的宽度不够，
                        // M3 的 TimePicker 是固定尺寸，宽度不够就会溢出屏幕两边。
                        .padding(horizontal = 12.dp)
                        .widthIn(max = 400.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "选择时间",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            TimePicker(state = timePickerState)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showTimePicker = false }) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    dueMillis = AppZone.calendar(dueMillis).apply {
                                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                                        set(Calendar.MINUTE, timePickerState.minute)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                    showTimePicker = false
                                },
                                modifier = Modifier.testTag("confirm_time_button")
                            ) { Text("确定") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一条无边框输入区。
 *
 * 平时它就是一段文字（跟正文读起来没两样），聚焦了才浮出一条底线 ——
 * 告诉用户「这儿能打字」，但不去占一条带框输入格的位置。
 *
 * 占位文字用**叠放**而不是排在前面：排在前面的话，一打字整个布局就往下挪一格，
 * 输入框会「跳」一下。
 *
 * @param restingUnderline 静止时（没聚焦）要不要留那条底线。
 *   标题留（它需要一条「标题线」把自己框住），备注不留 ——
 *   备注平时就该像一段正文，而不是一条等着被填的表格行。
 */
@Composable
private fun PlainTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    restingUnderline: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    testTag: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            decorationBox = { inner ->
                Box(modifier = Modifier.defaultMinSize(minHeight = 26.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    }
                    inner()
                }
            }
        )
        if (focused || restingUnderline) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(1.dp)
                    .background(
                        if (focused) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
                    )
            )
        }
    }
}

/**
 * 一条「标签 : 内容」的编辑行。标签固定宽度，内容自己占剩下的。
 *
 * [onClick] 给的是**整行**（含标签）：像「课程」这种行，标签本身也是这一行的
 * 一部分，只让内容可点会让标签看着像不可动的说明文字。
 */
@Composable
private fun EditorRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // **不是写死的宽度**：写死 38.dp 的话，系统字号 2.0 时「截止」两个字
            // 会被挤成竖着的一列（「截 / 止」），看着像坏掉了。
            // 给一个下限、让标签按内容长，两行标签就永远不会发生。
            modifier = Modifier.widthIn(min = 38.dp)
        )
        content()
    }
}

@Composable
private fun HairLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * 类型行里的一颗 chip。选中 = 主题绿平涂 + 深墨字，与筛选 chip 同一套语言。
 *
 * 单独抽出来而不是复用 `SelectableChip`（在 `ChecklistScreen` 里）：
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

/**
 * 「课程」滚筒：中间一项高亮，惯性滚动停稳后落到最近的一项。
 *
 * 初值就要落位，不能等 LaunchedEffect 里 scrollToItem：
 * 那时候 LazyColumn 还没测量，滚动指令会被直接吞掉，滚筒停在第 0 项。
 * contentPadding 上下各留一个 itemHeight、视口 3 高，所以 firstVisibleItemIndex = i
 * 恰好让第 i 项的行中心对准高亮带，不需要再补偏移。
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
            .padding(top = 4.dp)
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
            // 左右留白压到 6.dp：极端字号下这一行要塞「标签 + 开关 + 日期 + 时间」，
            // 12.dp 的留白正好把「10月27日」挤掉一个「日」字。
            .padding(horizontal = 6.dp, vertical = 10.dp),
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
 * 标题「是不是按（课程 + 类型）算出来的那一份模板」。
 *
 * 只有是模板，标题才跟着分类/课程变 —— 用户自己写的标题不该被顺手改掉。
 */
private fun isAutoTitle(title: String, typeKey: String, course: CourseEntity?): Boolean =
    course != null && title == autoTitle(typeKey, course)

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

/** 新建待办时的默认截止：3 天后的 23:59（北京时间）。 */
private fun defaultDueTimestamp(): Long = AppZone.calendar().apply {
    add(Calendar.DAY_OF_YEAR, DEFAULT_DUE_DAYS)
    set(Calendar.HOUR_OF_DAY, DEFAULT_DUE_HOUR)
    set(Calendar.MINUTE, DEFAULT_DUE_MINUTE)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
