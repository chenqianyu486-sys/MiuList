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
import com.example.data.model.WallpaperConfig
import com.example.data.source.zju.CoursesTodoItem
import com.example.data.source.zju.ZjuFailureKind
import com.example.data.source.weather.WeatherService
import com.example.data.sync.SyncFailure
import com.example.data.timetable.PeriodTable
import com.example.data.transfer.ImportPreview
import com.example.data.transfer.ImportReport
import com.example.data.transfer.ImportStrategy
import com.example.data.validate.ImportIssue

// 底部浮岛只有三个入口，标签一律两字：含义交给图标，文案不再抢位置。
// 设置**不在这个枚举的浮岛里**：它由顶栏展开出来的抽屉进入，
// 枚举值本身保留——抽屉点「设置」仍然要把 currentTab 切到这一项。
enum class NavigationTab(val title: String) {
    DASHBOARD("总览"),
    SCHEDULE("课表"),
    CHECKLIST("清单"),
    SETTINGS("设置")
}

// 「按类别」整条去掉了：课程类别本身已经是卡片上的一个标签，再做一遍分组等于把同一条
// 信息说两遍，而且分组标题里只有类别名，扫不出任何新东西。
//
// 待办这一轴统一叫「分类」（设置页的「待办分类管理」、清单页的「全部分类」
// 都是它）；「类型」是同一件东西的另一个叫法，App 里两套叫法混着出现，
// 用户会以为是两种不同的东西。
enum class TaskGroupingMode(val label: String) {
    BY_URGENCY("按截止"),
    BY_COURSE("按课程"),
    BY_TYPE("按分类"),
    // 从「按优先级」改名而来：优先级从三档收成了星标两态之后，
    // 「高/中/低优先级」这个组头名已经指代不了任何东西。
    // 这只是内存态的 UI 选择，没有落库，所以改名不需要迁移。
    BY_STAR("按星标")
}

enum class ScheduleViewMode(val label: String) {
    WEEK_GRID("周课表"),
    DAY_TIMELINE("列表")
}

enum class TaskStatusFilter(val label: String) {
    PENDING("待完成"),
    COMPLETED("已完成")
}

/**
 * 浙大校历里的一个**学年学期**（秋冬 / 春夏，16 周）。
 *
 * [term] 是教务网查询用的那一半（秋冬 → 0 秋，春夏 → 4 春），
 * [season] 只影响显示：有它就写「2026-2027学年 秋冬学期」，
 * 短学期那类独立段没有季节，退回写半学期自己的名字。
 */
data class ZjuTermOption(val year: String, val term: Int, val season: String? = null) {
    val label: String
        get() = if (season != null) "${year}学年 $season 学期" else "$year ${ZjuTermLabels.of(term)}"

    val key: String get() = "$year:$term"
}

private object ZjuTermLabels {
    private val LABELS = listOf("秋学期", "冬学期", "短学期A", "小学期", "春学期", "夏学期", "短学期B")
    fun of(term: Int): String = LABELS.getOrElse(term) { "未知学期" }
}

/**
 * 待办编辑弹层要编辑什么。
 *
 * 「详情弹层」与「编辑弹窗」合成一层之后，打开弹层就等于进入编辑，
 * 所以这个类型要同时表达两种入口：**新建**一条（[task] 为 null），
 * 或者**编辑**已有的一条。
 *
 * 存 [task] 的**快照**而不是 id：弹层里那份草稿（标题、备注、分类…）
 * 就是从这条待办读出来初始化的，用 id 反而要再查一次库、多一份可能对不上的副本。
 */
data class TaskEditorTarget(
    /** 要编辑的待办；null = 新建。 */
    val task: TaskEntity?,
    /** 新建时预选的课程（从课程详情弹层「给这门课加待办」进来时带上）。 */
    val preselectedCourseId: Long? = null
)

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

    /**
     * 官方校历，**只读**，仅供校历弹层单独显示。
     *
     * 与上面那份 [semester] / [noClassDays] / [makeupDays] 是两件事：
     * 那是用户自己的（可能改过、可能不是浙大），这是官方的（谁都没改过）。
     * 弹层里分两块摆，不混。
     *
     * 它自己带着 [com.example.data.source.zju.ZjuPresetSemester.origin]，
     * 界面上把来源显示出来 —— 用户凭什么相信这一块，唯一的依据就是这个。
     */
    val zjuPreset: com.example.data.source.zju.ZjuPresetSemester? = null,
    val isZjuPresetLoading: Boolean = false,

    /**
     * 这份官方配置里**所有**的学年学期，供弹层顶部切换。
     *
     * 加新学期之后用户要有个办法把它切过去 —— 只按当前学期自动选一的话，
     * 新加的那一期永远不会被摆出来，看着像「检查更新没生效」。
     */
    val zjuAvailableTerms: List<com.example.data.codec.AcademicTerm> = emptyList(),

    /** 「检查更新」在跑。与结果必须落在**同一次 update** 里（见 MainViewModel）。 */
    val isCheckingCalendarUpdate: Boolean = false,
    /**
     * 刚从远程拉到、**还没应用**的新版官方校历。
     *
     * 非 null 时弹层顶部出现「发现新版」+「查看差异」。
     * 它与 [zjuPreset] 分开存：拉到新版只更新缓存，**不碰用户正在用的校历**。
     */
    val pendingPreset: com.example.data.source.zju.ZjuPresetSemester? = null,
    /** 应用 [pendingPreset] 会造成的差异。null = 没在预览。 */
    val calendarDiff: com.example.data.calendar.CalendarDiff? = null,
    val isCalendarDiffOpen: Boolean = false,

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
     * 去掉「全部」入口；「已完成」「待完成」同时选中语义等同「全部」。
     */
    val statusFilters: Set<TaskStatusFilter> = setOf(TaskStatusFilter.PENDING),
    val scheduleViewMode: ScheduleViewMode = ScheduleViewMode.WEEK_GRID,

    // Dialog & Sheet states
    /**
     * 待办编辑弹层：卡片上点一下待办进来的就是它，**弹层本身就是编辑面**。
     *
     * 原来是「详情弹层（只读）+ 编辑弹窗（表单）」两层，现在合成一层，
     * 所以这里只有一个目标：编辑哪一条（或者新建一条）。
     */
    val taskEditor: TaskEditorTarget? = null,
    /** 「新建待办类型」弹层。清单页的类型 chip 行和编辑待办弹层都能打开它。 */
    val isNewTaskTypeDialogOpen: Boolean = false,
    /** 删除自定义类型前的二次确认对象；null = 没在确认。 */
    val deletingTaskType: CustomTaskType? = null,

    val isAddCourseDialogOpen: Boolean = false,
    val editingCourseWithSlots: CourseWithSlots? = null,

    /**
     * 课程分类弹层（给所有课程逐门定类别）。
     *
     * 与 [isAddCourseDialogOpen] 分开：**「改类别」不必打开整张课程编辑表单**，
     * 那一屏里还有教师、教室、主题色、时段、周次 —— 为一件轻活开一张重表单。
     */
    val isCourseCategoryDialogOpen: Boolean = false,

    val inspectingCourse: CourseWithSlots? = null, // for detailed course + slots + linked tasks sheet

    /**
     * 删除课程前的二次确认对象；null = 没在确认。
     *
     * 删课是不可逆的，而入口有两处（课程详情弹层、更多抽屉的课程行），两处都得先落到这里，
     * 所以把「正在确认哪门课」放进状态，而不是让两处各自记一个局部 flag。
     */
    val deletingCourse: CourseWithSlots? = null,
    val candidateTasksForPreview: List<TaskEntity> = emptyList(),
    val isQuickGenerateDialogOpen: Boolean = false,
    /**
     * 预览弹层里那批候选待办**实际属于第几周**（课后作业与实验报告的范围）。
     *
     * 不能拿 [selectedWeek] 顶替：生成永远落在真今天所在的周，而浏览周次可以随便翻，
     * 两者在弹层打开的那一瞬间就可能不同步。按点击那一刻解析出来的周存下来，
     * 弹层文案和生成结果才必然说同一件事。
     */
    val quickGenerateWeek: Int? = null,

    /**
     * 这批候选里**预习**覆盖到第几周（作业与实验报告只看 [quickGenerateWeek] 那一周）。
     *
     * 单独存是因为两类待办的视野不同（预习看到下周结束，作业只看本周），
     * 弹层要如实说明这一点，用户才知道为什么列表里会出现下一周的课。
     * 等于 [quickGenerateWeek] 表示预习没有跨周（今天是学期最后一周）。
     */
    val quickGeneratePreStudyLastWeek: Int? = null,

    val isCategoryRulesDialogOpen: Boolean = false,
    /** 课程 × 类型的生成规则弹层。 */
    val isTaskRulesDialogOpen: Boolean = false,
    val isWeekPickerDialogOpen: Boolean = false,

    // 数据导入 / 导出（整机快照）
    val isTransferDialogOpen: Boolean = false,
    /**
     * 默认整机同步。这条接口现在的定位就是「把本机状态搬到另一台设备」，
     * 课表之外的待办、自定义分类、生成规则只有这一条路能带走；
     * 默认选别的等于让用户每次导入都要先想一遍「我这次到底想不想同步待办」。
     */
    val importStrategy: ImportStrategy = ImportStrategy.SNAPSHOT,

    // 待办分类管理
    val isTaskCategoryManageDialogOpen: Boolean = false,

    // 自定义壁纸
    val wallpaperConfig: WallpaperConfig = WallpaperConfig(),
    val isWallpaperDialogOpen: Boolean = false,

    // 上手引导
    /**
     * 引导是否打开。
     *
     * 三条来路：**空态自动触发**（本地一门课都没有、且用户还没看过）、
     * **设置页的入口**（不受「看过」标记限制，想重看随时能看）、
     * 以及**从目标那边接回来**（用户点「去看看」去配置某个功能，
     * 那个功能做完关掉之后，引导自己回到刚才那一步）。
     */
    val isOnboardingGuideOpen: Boolean = false,

    /**
     * 引导当前第几步（0 起）。
     *
     * 放在 state 里而不是引导组件的局部变量：组件可能被整体重建（配置变化、
     * 进程恢复），而「用户读到第几步」是这次引导的进度，不该在重建后从头再来。
     */
    val onboardingStep: Int = 0,

    /**
     * 引导被送去某个功能了，正在等那个功能做完。
     *
     * 「去看看」不是关闭，是**暂停**：用户去配置壁纸，关掉壁纸弹层之后
     * 引导要自己接回来 —— 他还欠着几页没看。null = 没在等。
     *
     * 判据与接回规则见 [resumeOnboardingIfTargetDone]。
     */
    val onboardingAwaitingTarget: GuideTarget? = null,

    /**
     * 引导刚从目标那边接回来。
     *
     * 只在「接回来」这一瞬间为真，用来给面板加一句「刚带你去过这一项」——
     * 否则用户看到的是和离开前一模一样的第 N 步，会以为引导没动过。
     */
    val onboardingResumed: Boolean = false,

    // 浙大同步（账号 / 学期下拉 / 取课表）与文件导入导出各自的状态
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

    // 云端同步与账号状态
    val isSyncing: Boolean = false,
    val lastSyncTimestamp: Long? = null,

    /**
     * 当前要展示的同步失败详情。
     *
     * 与 [userMessage] 的分工：Snackbar 一闪就没、还会被弹层盖住，
     * 而用户面对「同步没成功」需要的是**能停下来照着做**的提示，
     * 所以失败详情走弹层里那张卡，Snackbar 只带一句摘要。
     */
    val syncFailure: SyncFailure? = null,

    /** 上一次尝试（不论成败）的时间，用来如实表述同步历史。 */
    val lastSyncAttemptTime: Long? = null,

    /** 上一次失败的类别。成功过一次之后为 null。 */
    val lastSyncFailureKind: ZjuFailureKind? = null,

    val boundStudentId: String? = null,
    val isRememberCredentials: Boolean = false,
    val isCloudAccountDialogOpen: Boolean = false,

    /**
     * 总览页当天那块天气。
     *
     * 默认 [WeatherState.Loading] 而不是 null：「还没读到」和「压根没这个功能」
     * 是两件事，共用一个可空字段的话，界面上分不出该显示骨架还是该显示「未配置」。
     */
    val weather: WeatherService.WeatherState = WeatherService.WeatherState.Loading,

    // 学在浙大 (courses.zju.edu.cn) 待办事项
    val coursesTodoList: List<CoursesTodoItem> = emptyList(),
    val isCoursesTodoLoading: Boolean = false,
    val coursesTodoLastUpdated: Long = 0L,
    val coursesTodoError: String? = null,
    val isQuickZjuLoginDialogOpen: Boolean = false,

    val userMessage: String? = null
) {
    /** 是否已保存凭据（用于判断卡片背面是否显示未绑定提示） */
    val hasZjuCredentials: Boolean
        get() = !boundStudentId.isNullOrBlank() && isRememberCredentials
}