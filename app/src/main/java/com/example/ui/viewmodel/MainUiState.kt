package com.example.ui.viewmodel

import com.example.data.calendar.AcademicCalendar
import com.example.data.generation.CourseRuleSpec
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskType
import com.example.data.timetable.PeriodTable
import com.example.data.transfer.ImportPreview
import com.example.data.transfer.ImportReport
import com.example.data.transfer.ImportStrategy
import com.example.data.validate.ImportIssue

// 底部浮岛只有四个入口，标签一律两字：含义交给图标，文案不再抢位置
enum class NavigationTab(val title: String) {
    DASHBOARD("总览"),
    SCHEDULE("课表"),
    CHECKLIST("清单"),
    SETTINGS("设置")
}

// 「按类别」整条去掉了：课程类别本身已经是卡片上的一个标签，再做一遍分组等于把同一条
// 信息说两遍，而且分组标题里只有类别名，扫不出任何新东西。
enum class TaskGroupingMode(val label: String) {
    BY_URGENCY("按截止"),
    BY_COURSE("按课程"),
    BY_TYPE("按类型"),
    BY_PRIORITY("按优先级")
}

enum class ScheduleViewMode(val label: String) {
    WEEK_GRID("周课表"),
    DAY_TIMELINE("列表")
}

enum class TaskStatusFilter(val label: String) {
    ALL("全部"),
    PENDING("待完成"),
    COMPLETED("已完成")
}

/**
 * 课表的导入来源。
 *
 * `ZJU` 走 App 内的浙大登录与取数，产出的 JSON 与文件导入走**同一条管线**；
 * `FILE` 读本地文件。两者唯一的区别是数据从哪来。
 */
enum class ImportSource(val label: String) {
    FILE("文件"),
    ZJU("ZJU")
}

/** 浙大校历里的一个学期（学年的哪一段）。 */
data class ZjuTermOption(val year: String, val term: Int) {
    val label: String get() = "$year ${ZjuTermLabels.of(term)}"
    val key: String get() = "$year:$term"
}

private object ZjuTermLabels {
    private val LABELS = listOf("秋学期", "冬学期", "短学期A", "小学期", "春学期", "夏学期", "短学期B")
    fun of(term: Int): String = LABELS.getOrElse(term) { "未知学期" }
}

data class MainUiState(
    val currentTab: NavigationTab = NavigationTab.DASHBOARD,
    val currentWeek: Int = 6,
    /** 周课表当前浏览的周次。currentWeek 是「今天第几周」，这个是「在看第几周」。 */
    val selectedWeek: Int = 6,
    val courses: List<CourseEntity> = emptyList(),
    val courseSlots: List<CourseSlotEntity> = emptyList(),
    /** 时段 id → 开课周次。缺 key = 每周。 */
    val slotWeeks: Map<Long, Set<Int>> = emptyMap(),
    val coursesWithSlots: List<CourseWithSlots> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    /**
     * 用户自己新建的待办类型（内置的三个在 [com.example.data.model.TaskType] 枚举里）。
     *
     * 类型变成「枚举 + 数据」之后，界面上的类型 chip 就不能再枚举出来了，
     * 必须由这里和枚举合并而成（`TaskTypes.options`）。
     */
    val customTaskTypes: List<CustomTaskType> = emptyList(),
    val categoryRules: List<CategoryRule> = emptyList(),
    /**
     * 课程 × 类型的生成规则（课程级，优先级高于 [categoryRules]）。
     * 缺 key = 用户没配过这一格，生成时回落到类别默认 / 内置默认。
     */
    val taskRules: Map<Long, Map<TaskType, CourseRuleSpec>> = emptyMap(),
    val isLoading: Boolean = false,

    // 校历
    val semester: SemesterEntity? = null,
    val periodTable: PeriodTable = PeriodTable.DEFAULT,
    val calendar: AcademicCalendar = AcademicCalendar.EMPTY,
    val totalWeeks: Int = 25,
    /**
     * 停课 / 补课必须是完整列表而不是计数：校历弹层要能把已存的条目列出来编辑。
     * 只传 count 的话弹层里永远是空的，用户改一次校历就会看不见自己存的停课。
     */
    val noClassDays: List<NoClassDayEntity> = emptyList(),
    val makeupDays: List<MakeupDayEntity> = emptyList(),
    val noClassDayCount: Int = 0,
    val makeupDayCount: Int = 0,

    // Filter & Presentation options
    val searchQuery: String = "",
    /**
     * 类型筛选：null = 不按类型筛，`""` = 只看未分类（`TASK_TYPE_NONE`）。
     *
     * 用 key 而不是 [TaskType]：内置之外的类型存在数据库里，枚举表达不了，
     * 存枚举就等于把自定义类型永远关在筛选之外。
     */
    val selectedTaskTypeKey: String? = null,
    val groupingMode: TaskGroupingMode = TaskGroupingMode.BY_COURSE,
    /**
     * 默认落在「待完成」。
     *
     * 「全部」里一半以上是已经做完的旧待办，默认全量等于每次打开都先扫一遍噪音；
     * 真要看已完成，点一下 chip 就行。
     */
    val statusFilter: TaskStatusFilter = TaskStatusFilter.PENDING,
    val selectedScheduleDay: Int = 1, // 1 = Monday ... 7 = Sunday
    val scheduleViewMode: ScheduleViewMode = ScheduleViewMode.WEEK_GRID,

    // Dialog & Sheet states
    val isAddTaskDialogOpen: Boolean = false,
    val preselectedCourseIdForNewTask: Long? = null,
    val editingTask: TaskEntity? = null,
    /** 单条待办的详情弹层。编辑/删除入口都收在这里，卡片上不再挂图标。 */
    val inspectingTask: TaskEntity? = null,
    /** 「新建待办类型」弹层。清单页的类型 chip 行和编辑待办弹层都能打开它。 */
    val isNewTaskTypeDialogOpen: Boolean = false,
    /** 删除自定义类型前的二次确认对象；null = 没在确认。 */
    val deletingTaskType: CustomTaskType? = null,

    val isAddCourseDialogOpen: Boolean = false,
    val editingCourseWithSlots: CourseWithSlots? = null,

    val inspectingCourse: CourseWithSlots? = null, // for detailed course + slots + linked tasks sheet
    val candidateTasksForPreview: List<TaskEntity> = emptyList(),
    val isQuickGenerateDialogOpen: Boolean = false,

    val isCategoryRulesDialogOpen: Boolean = false,
    /** 课程 × 类型的生成规则弹层。 */
    val isTaskRulesDialogOpen: Boolean = false,
    val isWeekPickerDialogOpen: Boolean = false,

    // 课表导入 / 导出
    val isTransferDialogOpen: Boolean = false,
    val importStrategy: ImportStrategy = ImportStrategy.MERGE,

    // 「ZJU」导入源
    /** 导入来源分段：本地文件 or 直接登录浙大教务网。 */
    val importSource: ImportSource = ImportSource.FILE,
    val isZjuImportRunning: Boolean = false,
    /** 学期的下拉选项，来自 zju-ical 的公共校历配置。 */
    val zjuTermOptions: List<ZjuTermOption> = emptyList(),
    /** 非敏感的错误提示。密码**不进 state**，只留在弹层的 remember 里。 */
    val zjuError: String? = null,
    val importPreview: ImportPreview? = null,
    val importIssues: List<ImportIssue> = emptyList(),
    val importError: String? = null,
    val isImporting: Boolean = false,
    val importReport: ImportReport? = null,

    // 学期校历设置
    val isAcademicCalendarDialogOpen: Boolean = false,

    val userMessage: String? = null
)