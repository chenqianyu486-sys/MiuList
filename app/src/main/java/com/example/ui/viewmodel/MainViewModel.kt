package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.calendar.CalendarDiffCalculator
import com.example.data.calendar.CalendarOrigin
import com.example.data.calendar.CalendarSnapshot
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.data.codec.AcademicTerm
import com.example.data.di.AppContainer
import com.example.data.generation.CourseRuleSpec
import com.example.data.generation.WeekScope
import com.example.data.generation.WeekTaskGenerationEngine
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.CustomTaskType
import com.example.data.model.SlotWithWeeks
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.source.zju.CalendarUpdateService
import com.example.data.source.zju.ZjuImportService
import com.example.data.source.weather.WeatherService
import com.example.data.sync.SyncFailure
import com.example.data.sync.SyncResult
import com.example.data.transfer.ImportReport
import com.example.data.transfer.ImportStrategy
import com.example.data.transfer.PreviewResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())

    private val courseRepo = container.courseRepository
    private val taskRepo = container.taskRepository
    private val settingsRepo = container.settingsRepository
    private val calendarRepo = container.calendarRepository
    private val taskTypeRepo = container.taskTypeRepository
    private val zjuImportService = container.zjuImportService
    private val calendarUpdates = container.calendarUpdateService
    private val secureStorage = container.secureAccountStorage
    private val syncManager = container.syncManager
    private val weatherService = container.weatherService
    private val wallpaperRepo = container.wallpaperRepository
    private val onboardingRepo = container.onboardingRepository
    private val zjuCoursesClient = container.zjuCoursesClient
    private val coursesTodoCache = container.coursesTodoCache

    private data class RepoState(
        val coursesWithSlots: List<CourseWithSlots>,
        val slots: List<CourseSlotEntity>,
        val slotWeeks: Map<Long, Set<Int>>,
        val tasks: List<TaskEntity>,
        val rules: List<CategoryRule>,
        val taskRules: Map<Long, Map<TaskType, CourseRuleSpec>>,
        val calendar: AcademicCalendarRepository.CalendarState,
        val semester: SemesterEntity?,
        val noClassDays: List<NoClassDayEntity>,
        val makeupDays: List<MakeupDayEntity>,
        val selectedWeek: Int
    )

    private val calendarFlow = calendarRepo.calendarState

    // 分两级 combine：Kotlin 的 combine 超过 5 路就走 vararg + Any? 强转，
    // 那样丢掉了类型安全，编译期也帮不上忙。
    private val scheduleData = combine(
        courseRepo.getAllCoursesWithSlots(),
        courseRepo.getAllSlots(),
        courseRepo.getWeeksBySlotId()
    ) { cws, slots, weeks ->
        Triple(cws, slots, weeks)
    }

    private val calendarData = combine(
        calendarFlow,
        calendarRepo.semester,
        calendarRepo.noClassDays,
        calendarRepo.makeupDays
    ) { calState, semester, noClass, makeup ->
        CalendarData(calState, semester, noClass, makeup)
    }

    private data class CalendarData(
        val calendar: AcademicCalendarRepository.CalendarState,
        val semester: SemesterEntity?,
        val noClassDays: List<NoClassDayEntity>,
        val makeupDays: List<MakeupDayEntity>
    )

    /**
     * 两路规则流先合成一路。
     *
     * `kotlinx.coroutines.flow.combine` 只有 2..5 路的具体重载，第 6 路就退化成
     * vararg + `Any?` 强转，类型安全当场失效、编译期也不再报错。所以宁可在这里
     * 先折一层，也不把规则流和别的流硬凑进同一个 6 路 combine。
     */
private val ruleFlow = combine(
        settingsRepo.categoryRules,
        settingsRepo.taskRules
    ) { rules, taskRules -> rules to taskRules }

    private val repoFlow = combine(
        scheduleData,
        taskRepo.getAllTasks(),
        ruleFlow,
        calendarData,
        settingsRepo.selectedWeek
    ) { schedule, tasks, rulePair, calendar, selectedWeek ->
        RepoState(
            coursesWithSlots = schedule.first,
            slots = schedule.second,
            slotWeeks = schedule.third,
            tasks = tasks,
            rules = rulePair.first,
            taskRules = rulePair.second,
            calendar = calendar.calendar,
            semester = calendar.semester,
            noClassDays = calendar.noClassDays,
            makeupDays = calendar.makeupDays,
            selectedWeek = selectedWeek
        )
    }

    /**
     * 自定义类型单独一路，不塞进 [repoFlow]。
     *
     * repoFlow 已经是 5 路 combine 的上限，再加第 6 路会退化成 vararg + `Any?`
     * 强转（和上面 ruleFlow 那段注释是同一条理由）。自定义类型又是新建/删除都要
     * 立刻反映到界面上的东西，用独立一路喂给 uiState 更直接。
     */
    val uiState: StateFlow<MainUiState> = combine(
        _uiState,
        repoFlow,
        taskTypeRepo.observeAll()
    ) { currentUi, repo, customTaskTypes ->
        val resolver = ScheduleResolver(repo.calendar.calendar)
        val today = EpochDays.today()
        val derivedWeek = resolver.currentWeek(today)
        currentUi.copy(
            courses = repo.coursesWithSlots.map { it.course },
            coursesWithSlots = repo.coursesWithSlots,
            courseSlots = repo.slots,
            slotWeeks = repo.slotWeeks,
            tasks = repo.tasks,
            customTaskTypes = customTaskTypes,
            categoryRules = repo.rules,
            taskRules = repo.taskRules,
            currentWeek = derivedWeek,
            // selectedWeek 是「在看第几周」，从未设置过时跟随今天
            selectedWeek = if (repo.selectedWeek > 0) repo.selectedWeek else derivedWeek,
            semester = repo.semester,
            periodTable = repo.calendar.periodTable,
            calendar = repo.calendar.calendar,
            totalWeeks = repo.calendar.calendar.totalWeeks,
            noClassDays = repo.noClassDays,
            makeupDays = repo.makeupDays,
            noClassDayCount = repo.noClassDays.size,
            makeupDayCount = repo.makeupDays.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(isLoading = true)
    )

    init {
        viewModelScope.launch { loadWeather() }

        // 瞬间加载学在浙大待办的本地只读缓存（离线优先，零延迟呈现）
        val (cachedTodos, cachedTime) = coursesTodoCache.load()
        _uiState.update {
            it.copy(
                coursesTodoList = cachedTodos,
                coursesTodoLastUpdated = cachedTime
            )
        }

        // 每次打开软件自动刷新学在浙大待办（若已保存凭据）
        if (secureStorage.hasSavedCredentials()) {
            viewModelScope.launch {
                refreshCoursesTodos(silent = true)
            }
        }

        viewModelScope.launch {
            wallpaperRepo.wallpaperConfig.collect { config ->
                _uiState.update { it.copy(wallpaperConfig = config) }
            }
        }

        viewModelScope.launch {
            settingsRepo.ensureDefaultRules(AppContainer.DEFAULT_CATEGORY_RULES)
            // 全新安装时作息表是空的（迁移只给升级用户写），不补的话课表栅格会是空白
            calendarRepo.ensureDefaultPeriods()
            // 清理历史残留的非法补课脏数据（如旧版本生成的「工作日补周末课」）
            calendarRepo.sanitizeMakeupDays()
            // 给迁移后还没有同步身份的旧待办补上。
            taskRepo.ensureSyncUids()

            // 旧版本会自动播种一份示例课表，2026-10-20 起这个功能已整项取消。
            // 存量安装里那份示例数据还在，而界面上已经没有任何东西说明它是假的 ——
            // 用户会把「高等数学(上)」当成自己的课。先清掉，再谈本地有什么。
            courseRepo.clearLegacySampleData()

            val currentCourses = courseRepo.getAllCoursesOnce()
            val hasSavedCreds = secureStorage.hasSavedCredentials()

            // 【离线优先 / 本地缓存优先】
            // 只要本地数据库已有课表，立刻优先展示本地缓存，断网立即可用。
            // 本地为空时才去云端拉；没存凭据就什么都不做 —— 全新安装就是一张空课表，
            // 用户在总览页的账号入口登录或导入课表即可（不再有示例数据兜底）。
            if (currentCourses.isEmpty() && hasSavedCreds) {
                _uiState.update { it.copy(isSyncing = true) }
                val res = syncManager.syncWithSavedCredentials()
                _uiState.update { it.copy(isSyncing = false) }
                when (res) {
                    is SyncResult.Success ->
                        _uiState.update {
                            it.withSyncSuccess(
                                userMessage = "已从云端拉取 ${res.courseCount} 门课程并存入本地缓存" +
                                    res.report.unlinkedTasksSuffix()
                            )
                        }
                    // 冷启动自动同步失败：以前这里什么都不做，于是用户打开 App 看到一张
                    // 空课表，既不知道是同步没成，也不知道本地本来就没有数据。
                    // 只走 Snackbar、不弹弹层 —— 冷启动就推一个对话框过去太突兀。
                    is SyncResult.Failed ->
                        _uiState.update { it.withSyncFailure(res.failure, keepDialogOpen = false) }
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    boundStudentId = secureStorage.getMaskedStudentId(),
                    isRememberCredentials = secureStorage.isRememberCredentials(),
                    lastSyncTimestamp = secureStorage.getLastSyncTimestamp(),
                    lastSyncAttemptTime = secureStorage.getLastSyncAttemptTime(),
                    lastSyncFailureKind = secureStorage.getLastSyncFailureKind()
                )
            }

            // 【空态引导】
            //
            // 本地一门课都没有、而且用户还没看过引导时，给一份上手说明。
            //
            // 判据是「没有课程」而不是「没绑定浙大账号」：有课的用户照样看得见课表，
            // 这时弹引导是在打断一个能正常用的人。
            //
            // 放在同步**之后**判断：存了凭据的用户冷启动会自动同步，
            // 同步成功就有课了，不该再弹引导；同步失败（或者压根没存凭据）
            // 才轮到引导接手。
            if (courseRepo.getAllCoursesOnce().isEmpty() && !onboardingRepo.hasSeenOnboarding) {
                // 从上次停住的那一步接着讲：引导是可以暂停的
                // （去看看某个功能，做完接回来），冷启动不该从头再推一遍。
                val step = onboardingRepo.currentStep.coerceIn(0, GuideTarget.entries.size - 1)
                _uiState.update { it.copy(isOnboardingGuideOpen = true, onboardingStep = step) }
            }
        }
    }

    // ------------------------------------------------------------ 天气

    /**
     * 总览页当天那块天气的唯一入口。
     *
     * 「先用缓存立刻显示，再后台刷新」的两段式在这里是**自动完成**的：
     * 3 小时窗口内直接返回缓存、一次请求都不发；窗口外先联网，
     * 拉不到再退回今天的缓存 —— 用户全程面对一个非空的那一块。
     *
     * **没有 force 参数了**（2026-10-16 用户定）：手动刷新已取消，
     * 「无视窗口强制拉一次」这条路径因此没有调用方。留着它等于留一个
     * 没有入口的开关，下一个人会以为天气还能手动刷。
     */
    private suspend fun loadWeather() {
        when (val state = weatherService.loadForEntry()) {
            is WeatherService.WeatherState.Available ->
                _uiState.update { it.copy(weather = state) }

            is WeatherService.WeatherState.Unavailable -> {
                // 正在显示着一份天气时，不把它撤成一句报错：
                // 用户看到天气凭空消失，却不知道是自己断网了还是服务挂了。
                // 冷启动时没得可撤，才让原因直接显示在那一块上。
                val showingSomething = _uiState.value.weather is WeatherService.WeatherState.Available
                _uiState.update {
                    if (showingSomething) it else it.copy(weather = state)
                }
            }

            WeatherService.WeatherState.Loading -> Unit
        }
    }

    /**
     * **每次进入软件**自动同步一次（冷启动 + 从后台切回来都会走到）。
     *
     * 距上次拉取不足 3 小时就直接读缓存，不发请求。
     *
     * 手动刷新取消后，**这是唯一会触发拉取的入口** —— 用户在前台停留再久
     * 也不会有第二次拉取（没有定时器，也没有别的地方挂它）。真要在一屏之内
     * 更新，得先加一个周期检查，别指望现在有。
     */
    fun syncWeather() = viewModelScope.launch { loadWeather() }

    // ------------------------------------------------------------ 导航与筛选

    fun setNavigationTab(tab: NavigationTab) = _uiState.update { it.copy(currentTab = tab) }
    fun setSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }
    fun setSelectedTaskType(key: String?) = _uiState.update { it.copy(selectedTaskTypeKey = key) }
    fun setGroupingMode(mode: TaskGroupingMode) = _uiState.update { it.copy(groupingMode = mode) }
    fun setStatusFilters(filters: Set<TaskStatusFilter>) = _uiState.update { it.copy(statusFilters = filters) }
    fun toggleStatusFilter(filter: TaskStatusFilter) = _uiState.update { current ->
        val currentFilters = current.statusFilters
        val nextFilters = if (filter in currentFilters) {
            if (currentFilters.size > 1) currentFilters - filter else currentFilters
        } else {
            currentFilters + filter
        }
        current.copy(statusFilters = nextFilters)
    }
    fun setStatusFilter(filter: TaskStatusFilter) = toggleStatusFilter(filter)
    fun setScheduleViewMode(mode: ScheduleViewMode) = _uiState.update { it.copy(scheduleViewMode = mode) }

    /** 现在是「浏览第 N 周」，不再是「声明现在是第几周」——周次由校历派生。 */
    fun selectWeek(week: Int) {
        settingsRepo.setSelectedWeek(week)
    }

    fun openWeekPicker() = _uiState.update { it.copy(isWeekPickerDialogOpen = true) }
    fun closeWeekPicker() = _uiState.update { it.copy(isWeekPickerDialogOpen = false) }

    fun clearUserMessage() = _uiState.update { it.copy(userMessage = null) }

    // ------------------------------------------------------------ 待办

    fun openAddTaskDialog(courseId: Long? = null) = _uiState.update {
        it.copy(
            isAddTaskDialogOpen = true,
            preselectedCourseIdForNewTask = courseId,
            editingTask = null
        )
    }

    fun openEditTaskDialog(task: TaskEntity) = _uiState.update {
        it.copy(
            isAddTaskDialogOpen = true,
            preselectedCourseIdForNewTask = task.courseId,
            editingTask = task
        )
    }

    fun closeTaskDialog() = _uiState.update {
        it.copy(isAddTaskDialogOpen = false, editingTask = null, preselectedCourseIdForNewTask = null)
    }

    fun inspectTask(task: TaskEntity) = _uiState.update { it.copy(inspectingTask = task) }
    fun closeTaskSheet() = _uiState.update { it.copy(inspectingTask = null) }

    fun editTaskFromSheet(task: TaskEntity) = _uiState.update {
        it.copy(
            inspectingTask = null,
            isAddTaskDialogOpen = true,
            preselectedCourseIdForNewTask = task.courseId,
            editingTask = task
        )
    }

    fun toggleTaskComplete(task: TaskEntity) = viewModelScope.launch {
        taskRepo.toggleTaskComplete(task.id, task.isCompleted)
    }

    /**
     * 星标切换（待办卡片右侧那颗星）。
     *
     * 当前状态从传入的 `task` 上读，和 [toggleTaskComplete] 同一个路子——
     * 界面上那颗星渲染时用的就是这份值，用户点下去时它一定还没过期。
     * 旧值里可能混着 `HIGH`/`MEDIUM`/`LOW`，所以读也要走 `TaskStar.isMarked`，
     * 直接比字符串会把「旧 HIGH」当成没标星，点一下反而把它标上。
     */
    fun toggleTaskStar(task: TaskEntity) = viewModelScope.launch {
        taskRepo.setTaskStar(task.id, !TaskStar.isMarked(task.priority))
    }

    // ------------------------------------------------------------ 待办类型

    fun openNewTaskTypeDialog() = _uiState.update { it.copy(isNewTaskTypeDialogOpen = true) }

    fun closeNewTaskTypeDialog() = _uiState.update { it.copy(isNewTaskTypeDialogOpen = false) }

    /**
     * 新建一个自定义待办分类。
     *
     * 失败（重名 / 超上限）不静默：分类是筛选和分组的一级维度，
     * 一个「没建上但也不吭声」的反馈会让用户以为是自己哪里点错了。
     */
    fun createTaskType(label: String, paletteIndex: Int) = viewModelScope.launch {
        val result = taskTypeRepo.create(label, paletteIndex)
        _uiState.update {
            if (result.isSuccess) {
                it.copy(isNewTaskTypeDialogOpen = false, userMessage = "已新建分类")
            } else {
                it.copy(userMessage = result.exceptionOrNull()?.message ?: "新建分类失败")
            }
        }
    }

    fun confirmDeleteTaskType(type: CustomTaskType) = _uiState.update {
        it.copy(deletingTaskType = type)
    }

    fun cancelDeleteTaskType() = _uiState.update { it.copy(deletingTaskType = null) }

    fun openTaskCategoryManageDialog() = _uiState.update {
        it.copy(isTaskCategoryManageDialogOpen = true)
    }

    fun closeTaskCategoryManageDialog() = _uiState.update {
        it.copy(isTaskCategoryManageDialogOpen = false)
    }

    /**
     * 删掉一个自定义类型。级联删除该分类下的所有待办。
     */
    fun deleteTaskType(type: CustomTaskType) = viewModelScope.launch {
        taskTypeRepo.delete(type.id)
        // 正按这个类型筛着的话，把它一起撤掉，正查看的待办如果属于此分类也关闭
        _uiState.update {
            it.copy(
                deletingTaskType = null,
                selectedTaskTypeKey = it.selectedTaskTypeKey?.takeIf { key -> key != type.key },
                inspectingTask = it.inspectingTask?.takeIf { task -> task.taskType != type.key },
                userMessage = "已删除「${type.label}」及该分类下的待办"
            )
        }
    }

    fun saveTask(task: TaskEntity) = viewModelScope.launch {
        if (task.id == 0L) {
            taskRepo.insertTask(task)
            _uiState.update { it.copy(userMessage = "待办已添加") }
        } else {
            taskRepo.updateTask(task)
            _uiState.update { it.copy(userMessage = "待办已更新") }
        }
        closeTaskDialog()
    }

    fun deleteTask(task: TaskEntity) = viewModelScope.launch {
        taskRepo.deleteTask(task)
        _uiState.update { it.copy(userMessage = "待办已删除", inspectingTask = null) }
    }

    // ------------------------------------------------------------ 课程

    /**
     * 打开新增课程弹层时的状态变化。
     *
     * 抽成可复用的变换，是因为引导的「去看看」要**在同一次更新里**
     * 既打开这个弹层又暂停引导（见 [openOnboardingTarget]）。
     */
    private fun MainUiState.withAddCourseOpen() =
        copy(isAddCourseDialogOpen = true, editingCourseWithSlots = null)

    /**
     * 「给每门课分门别类」这一步要打开的弹层。
     *
     * 落点是**课程分类弹层**，不是课程编辑表单：这一步只跟类别有关，
     * 而编辑表单里还有教师、教室、主题色、时段、周次和三个待办开关 ——
     * 为改一个类别让用户穿过那张表单，既吓人又容易顺手改坏别的字段。
     *
     * 弹层里所有课程列在一起，一门一门展开选；没有课时空态里直接给「去添加课程」。
     */
    private fun MainUiState.withCourseCategoryOpen() = copy(isCourseCategoryDialogOpen = true)

    fun openCourseCategoryDialog() = _uiState.update { it.withCourseCategoryOpen() }

    fun closeCourseCategoryDialog() = _uiState.update {
        it.copy(isCourseCategoryDialogOpen = false).resumeOnboardingIfTargetDone()
    }

    /**
     * 改一门课的类别。
     *
     * **只改类别**，不动那三个待办开关 —— 它们是用户自己在课程编辑里给的值，
     * 改类别不等于替他重置（见 `CourseRepository.saveCourseWithSlots` 的编辑分支）。
     * 要按类别批量改开关，去「课程联动规则」。
     */
    fun updateCourseCategory(course: CourseWithSlots, category: String) = viewModelScope.launch {
        courseRepo.updateCourseCategory(course.course.id, category)
        _uiState.update {
            it.copy(userMessage = "课程「${course.course.name}」已归到「$category」")
        }
    }

    fun openAddCourseDialog() = _uiState.update { it.withAddCourseOpen() }

    fun openEditCourseDialog(c: CourseWithSlots) = _uiState.update {
        it.copy(isAddCourseDialogOpen = true, editingCourseWithSlots = c)
    }

    fun closeCourseDialog() = _uiState.update {
        it.copy(isAddCourseDialogOpen = false, editingCourseWithSlots = null)
            .resumeOnboardingIfTargetDone()
    }

    fun inspectCourse(c: CourseWithSlots?) = _uiState.update { it.copy(inspectingCourse = c) }

    fun saveCourseWithSlots(course: CourseEntity, slots: List<SlotWithWeeks>) = viewModelScope.launch {
        courseRepo.saveCourseWithSlots(course, slots)
        _uiState.update {
            it.copy(userMessage = "课程「${course.name}」已保存（包含 ${slots.size} 个上课时段）")
        }
        closeCourseDialog()
    }

    /**
     * 请求删除课程：**只打开确认弹层，不动数据**。
     *
     * 两个入口（课程详情弹层、更多抽屉的课程行）都走这里，于是「按错一下就没了」
     * 和「抽屉里手滑删掉一门课」这两种不可逆误操作都有同一道闸。
     */
    fun confirmDeleteCourse(course: CourseWithSlots) = _uiState.update {
        it.copy(deletingCourse = course)
    }

    /** 取消删除：门课程都不碰，确认对象丢掉即可。 */
    fun cancelDeleteCourse() = _uiState.update { it.copy(deletingCourse = null) }

    /**
     * 删课程。课程与时段真的删掉，它上面的待办**移入「未关联课程」而不是跟着删** ——
     * 待办是用户自己的数据，一门课不上了不代表那些作业记录该消失。
     *
     * 移走多少条要如实报出来：这是替用户改了一批待办的归属，
     * 闷头改完只说「课程已删除」，用户回头发现待办换了个分组却不知道为什么。
     */
    fun deleteCourse(courseId: Long, courseName: String) = viewModelScope.launch {
        val unlinked = courseRepo.deleteCourse(courseId)
        _uiState.update {
            it.copy(
                userMessage = buildString {
                    append("课程「$courseName」及对应时段已删除")
                    if (unlinked > 0) append("；$unlinked 条待办已移入「未关联课程」")
                },
                // 删完连同确认弹层一起收：确认的标的已经不存在了，
                // 留着它等于让用户对着一个消失的课程再点一次「删除」。
                deletingCourse = null,
                inspectingCourse = null
            )
        }
    }

    // ------------------------------------------------------------ 类别规则

    /** 打开类别联动规则弹层时的状态变化。见 [withAddCourseOpen] 的说明。 */
    private fun MainUiState.withCategoryRulesOpen() = copy(isCategoryRulesDialogOpen = true)

    fun openCategoryRulesDialog() = _uiState.update { it.withCategoryRulesOpen() }
    fun closeCategoryRulesDialog() = _uiState.update {
        it.copy(isCategoryRulesDialogOpen = false).resumeOnboardingIfTargetDone()
    }

    /**
     * 批量应用类别联动规则（弹层「应用」一次可能改多个类别）。
     *
     * [com.example.data.repository.ScheduleSettingsRepository.saveRules] 会把三个开关
     * **批量设置**到该类别下的所有课程，所以这里把实际同步了几门课如实报给用户 ——
     * 联动改的是别人的课，闷头改完不吭声会让人以为没生效。
     */
    fun applyCategoryRules(rules: List<CategoryRule>) = viewModelScope.launch {
        if (rules.isEmpty()) {
            closeCategoryRulesDialog()
            return@launch
        }
        val synced = settingsRepo.saveRules(rules)
        val categories = rules.size
        _uiState.update {
            it.copy(
                userMessage = if (synced > 0) {
                    "已应用到 $categories 个类别 · 同步 $synced 门课程"
                } else {
                    "已应用到 $categories 个类别"
                },
                isCategoryRulesDialogOpen = false
            ).resumeOnboardingIfTargetDone()
        }
    }

    // ------------------------------------------------------------ 课程级生成规则

    fun openTaskRulesDialog() = _uiState.update { it.copy(isTaskRulesDialogOpen = true) }
    fun closeTaskRulesDialog() = _uiState.update { it.copy(isTaskRulesDialogOpen = false) }

    /** 保存一格「课程 × 类型」的节奏与截止。 */
    fun saveTaskRule(courseId: Long, taskType: TaskType, spec: CourseRuleSpec) = viewModelScope.launch {
        settingsRepo.saveTaskRule(courseId, taskType, spec)
        _uiState.update { it.copy(userMessage = "规则已保存") }
    }

    /** 清掉一格，让它回落到类别默认 / 内置默认。 */
    fun clearTaskRule(courseId: Long, taskType: TaskType) = viewModelScope.launch {
        settingsRepo.clearTaskRule(courseId, taskType)
        _uiState.update { it.copy(userMessage = "已恢复默认规则") }
    }

    /**
     * 某一类待办的开关。**写穿回 `CourseEntity.auto*`**，规则表不存开关
     * （见 `TaskGenerationRuleEntity` 的注释：两个真相源必然有一处忘改）。
     */
    fun setCourseTaskEnabled(courseId: Long, taskType: TaskType, enabled: Boolean) = viewModelScope.launch {
        val course = courseRepo.getCourseById(courseId) ?: return@launch
        val updated = when (taskType) {
            TaskType.PRE_STUDY -> course.copy(autoPreStudy = enabled)
            TaskType.POST_CLASS -> course.copy(autoPostHomework = enabled)
            TaskType.LAB_REPORT -> course.copy(autoLabReport = enabled)
            else -> return@launch
        }
        // 时段与开课周次原样回写：这里只改一个开关，不该顺手把排课重排一遍。
        val weeksBySlot = courseRepo.getWeeksBySlotIdOnce()
        val slots = courseRepo.getSlotsForCourseSync(courseId).map {
            SlotWithWeeks(slot = it, weeks = weeksBySlot[it.id].orEmpty())
        }
        courseRepo.saveCourseWithSlots(updated, slots)
    }

    // ------------------------------------------------------------ 自动生成待办

    /** 一批候选待办 + 它的覆盖范围，供弹层文案与提示语如实说明。 */
    private data class CandidateBatch(
        val tasks: List<TaskEntity>,
        /** 作业 / 实验报告所属周次。 */
        val week: Int,
        /** 预习覆盖到的最后一周周次；等于 [week] 表示预习没有跨周。 */
        val preStudyLastWeek: Int
    )

    /**
     * 造出「一键生成」的全部候选待办。
     *
     * 周次由调用方给，调用方一律传 [resolveRealGenerationWeek] 的结果，**不再取**
     * [MainUiState.selectedWeek]（正在浏览的那一周）。
     *
     * **三类待办的视野不一样**，这是本函数存在的理由：
     * - 课后作业、实验报告 = **本周**。它们是「上完课才交」，本周的课上完才几天，
     *   再往后看就超出了「今天该做」的视野。
     * - 预习 = **今天起至下周结束**。预习是上课之前要做的事，只看本周的话，
     *   周二打开 App 就得一次性把周三到周日的课全预习完，而下周才上的课此刻就该有个预告；
     *   反过来，本周已经上过的课再补一条预习毫无意义（课都上了）。
     *
     * 因此预习拆成**两个范围**（本周 `fromStartTimestamp = 现在`、下周整周），
     * 而作业与实验报告合用**本周整周**这一个范围。
     *
     * 边界都由学期裁：下周超出 [SemesterEntity.totalWeeks] 时那个范围整个不产出，
     * 而不是造出一批不属于任何一周的待办。
     *
     * 没配置学期时**拒绝生成**：没有学期就没有周次概念，
     * 硬生成出来的去重键全是 0，第二周第三次点就会把第一周那份也当成重复。
     */
    private suspend fun buildCandidatesForWeek(week: Int): CandidateBatch {
        val calState = calendarRepo.calendarState.first()
        val semester = calendarRepo.semesterOnce()
            ?: return CandidateBatch(emptyList(), week, week)
        val resolver = ScheduleResolver(calState.calendar)
        val engine = WeekTaskGenerationEngine(calState.periodTable)

        val allSlots = courseRepo.getAllSlotsOnce()
        val weeksBySlot = courseRepo.getWeeksBySlotIdOnce()
        val now = System.currentTimeMillis()

        fun mondayOf(w: Int) = semester.firstMondayEpochDay + (w - 1).coerceAtLeast(0) * 7L
        fun slotsOf(w: Int) = resolver.slotsInWeek(w, allSlots, weeksBySlot).values.flatten()

        // 下周还在学期内才做预习跨周那一段；否则预习只到本周（仍是今天起）。
        val preStudyLastWeek = if (week + 1 <= semester.totalWeeks) week + 1 else week

        val scopes = buildList {
            // 作业与实验报告：本周整周，不设下界 —— 上周遗留的作业照样该出现在清单里。
            add(
                WeekScope(
                    week = week,
                    weekMondayEpochDay = mondayOf(week),
                    slots = slotsOf(week),
                    types = setOf(TaskType.POST_CLASS, TaskType.LAB_REPORT)
                )
            )
            // 预习：本周从「现在」起（今天之前那几天的课已经上过了，当天已经开课的
            // 那节截止时刻也已过去，补出来只会是一条一出生就逾期的待办）。
            add(
                WeekScope(
                    week = week,
                    weekMondayEpochDay = mondayOf(week),
                    slots = slotsOf(week),
                    types = setOf(TaskType.PRE_STUDY),
                    fromStartTimestamp = now
                )
            )
            // 预习：下周整周（必然还没上，所以同样给同一个下界也不影响）。
            if (preStudyLastWeek > week) {
                add(
                    WeekScope(
                        week = preStudyLastWeek,
                        weekMondayEpochDay = mondayOf(preStudyLastWeek),
                        slots = slotsOf(preStudyLastWeek),
                        types = setOf(TaskType.PRE_STUDY),
                        fromStartTimestamp = now
                    )
                )
            }
        }

        val tasks = engine.build(
            WeekTaskGenerationEngine.Input(
                scopes = scopes,
                coursesById = courseRepo.getAllCoursesOnce().associateBy { it.id },
                courseRules = settingsRepo.getTaskRulesOnce(),
                categoryRules = settingsRepo.getCategoryRulesOnce().associateBy { it.category }
            )
        ) { courseId, taskType, w, day -> taskRepo.hasWeekTask(courseId, taskType.name, w, day) }

        return CandidateBatch(tasks, week, preStudyLastWeek)
    }

    /**
     * 「生成本周待办」要落在哪一周：**永远是真实时间所在的周**，与界面上在看第几周无关。
     *
     * `selectedWeek` 是「浏览周次」，是纯读操作留下的位置；生成是一次性动作，
     * 用户翻到第 12 周看点别的再点生成，产出的却要是今天这一周 —— 否则顶栏那个
     * 「生成本周待办」的常驻按钮会因为当前页面在看哪一周而产出不同的一周，
     * 同一个按钮两次点出不同结果。
     *
     * 读**最新**的校历而不是 [uiState] 里那份：combine 有一帧延迟，
     * 刚存完校历就点生成时那份可能还是空的，那样会误判成「今天不在学期内」。
     *
     * 今天不在学期范围内（学期开始前 / 结束后 / 假期）时返回 null 并如实说明：
     * 那时压根没有「本周」这个周次，编一个（[ScheduleResolver.FALLBACK_CURRENT_WEEK]
     * 那种兜底）只会静默生成出一批不在任何一周上的待办。
     */
    private suspend fun resolveRealGenerationWeek(): Int? {
        val week = calendarRepo.calendarState.first().calendar.weekOf(EpochDays.today())
        if (week > 0) return week
        _uiState.update { it.copy(userMessage = "今天不在本学期范围内，没有可生成的周") }
        return null
    }

    private suspend fun commitCandidates(candidates: List<TaskEntity>): Int {
        if (candidates.isEmpty()) return 0
        taskRepo.insertTasks(candidates)
        return candidates.size
    }

    /**
     * 生成本周待办时没有学期就不给生成。
     *
     * 必须读 [uiState] 而不是 [_uiState]：`_uiState` 只是「一次性动作」的载体
     * （开关弹层、userMessage），**数据库派生的字段一个都不写它** ——
     * `semester` / `tasks` / `courses` 全都只存在于 `uiState` 这条 combine 结果里。
     * 这里原本读 `_uiState.value.semester`，而那个值恒为 `MainUiState` 的默认 `null`，
     * 于是「先设置本学期校历」这条提示在**校历明明已经保存**的情况下照样弹出来，
     * 而且按周生成永远走不下去。
     */
    private fun requireSemester(): Boolean {
        if (uiState.value.semester != null) return true
        _uiState.update { it.copy(userMessage = "先设置本学期校历，才能按周生成待办") }
        return false
    }

    fun openQuickGenerateWeekDialog() = viewModelScope.launch {
        if (!requireSemester()) return@launch
        val week = resolveRealGenerationWeek() ?: return@launch
        val batch = buildCandidatesForWeek(week)
        _uiState.update {
            it.copy(
                candidateTasksForPreview = batch.tasks,
                quickGenerateWeek = batch.week,
                quickGeneratePreStudyLastWeek = batch.preStudyLastWeek,
                isQuickGenerateDialogOpen = true
            )
        }
    }

    fun closeQuickGenerateDialog() = _uiState.update {
        it.copy(
            isQuickGenerateDialogOpen = false,
            quickGenerateWeek = null,
            quickGeneratePreStudyLastWeek = null
        )
    }

    fun confirmGenerateTasks(selected: List<TaskEntity>) = viewModelScope.launch {
        // 周次取弹层打开那一刻存下的那个，不重算：候选是那会儿按这个周造出来的，
        // 中间跨过了一周一字不改地换个周次报，会出现「说的是 A、生成了 B」。
        val week = _uiState.value.quickGenerateWeek ?: uiState.value.currentWeek
        val preStudyLastWeek = _uiState.value.quickGeneratePreStudyLastWeek ?: week
        val count = commitCandidates(selected)
        closeQuickGenerateDialog()
        _uiState.update {
            it.copy(userMessage = generatedMessage(count, week, preStudyLastWeek))
        }
    }

    fun generateTasksDirectlyForWeek() = viewModelScope.launch {
        if (!requireSemester()) return@launch
        val week = resolveRealGenerationWeek() ?: return@launch
        val batch = buildCandidatesForWeek(week)
        val count = commitCandidates(batch.tasks)
        _uiState.update {
            it.copy(userMessage = generatedMessage(count, batch.week, batch.preStudyLastWeek))
        }
    }

    /**
     * 生成结果的提示语。
     *
     * 跨周时必须把「作业是本周、预习到下周」说出来：提示只报「第 N 周」，
     * 用户回头看到清单里多出下一周的预习会以为生成错了。
     */
    private fun generatedMessage(count: Int, week: Int, preStudyLastWeek: Int): String {
        val range = if (preStudyLastWeek > week) {
            "第 $week 周作业与实验报告，今天起至第 $preStudyLastWeek 周末的预习"
        } else {
            "第 $week 周待办"
        }
        return if (count > 0) "已生成 $count 项：$range" else "$range 已全部生成"
    }

    // ------------------------------------------------------------ 导入 / 导出

    fun openTransferDialog() = _uiState.update {
        it.copy(
            isTransferDialogOpen = true,
            importPreview = null,
            importReport = null,
            importError = null,
            importIssues = emptyList()
        )
    }

    fun closeTransferDialog() = _uiState.update {
        it.copy(isTransferDialogOpen = false, importPreview = null, importReport = null, importError = null)
    }

    fun setImportStrategy(strategy: ImportStrategy) = _uiState.update {
        it.copy(importStrategy = strategy, importPreview = null, importError = null)
    }
    fun dismissImportReport() = _uiState.update { it.copy(importReport = null, importPreview = null) }

    /** 只解析不落库：产出预览并存下，等用户确认。 */
    fun previewImportAndHold(text: String) = viewModelScope.launch {
        val strategy = _uiState.value.importStrategy
        _uiState.update { it.copy(isImporting = true, importError = null, importPreview = null) }
        when (val r = withContext(Dispatchers.IO) { container.importService.preview(text, strategy) }) {
            is PreviewResult.Failed -> {
                pendingPreview = null
                _uiState.update { it.copy(isImporting = false, importError = r.message) }
            }

            is PreviewResult.Ready -> {
                pendingPreview = r
                _uiState.update {
                    it.copy(isImporting = false, importPreview = r.preview, importIssues = r.validated.issues)
                }
            }
        }
    }

    private var pendingPreview: PreviewResult.Ready? = null

    fun importFromUri(uri: Uri, contentResolver: ContentResolver) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
        }
        if (text == null) {
            _uiState.update { it.copy(isImporting = false, importError = "无法读取所选文件") }
            return@launch
        }
        previewImportAndHold(text)
    }

    /** 预览确认后才落库。全程单事务，任一步失败整体回滚。 */
    fun commitImport() = viewModelScope.launch {
        val pending = pendingPreview ?: return@launch
        _uiState.update { it.copy(isImporting = true) }
        val report = withContext(Dispatchers.IO) { container.importService.commit(pending) }
        pendingPreview = null
        _uiState.update {
            it.copy(
                isImporting = false,
                importPreview = null,
                importReport = report,
                // 待办那部分也要报出来，否则用户点完「确认导入」只看到课程数字，
                // 会以为同步没生效 —— 而他真正关心的往往就是待办。
                userMessage = "导入完成：课程新增 ${report.inserted} 门、更新 ${report.updated} 门" +
                    if (report.taskMerge.added > 0 || report.taskMerge.overwritten > 0) {
                        "；待办新增 ${report.taskMerge.added} 条、覆盖 ${report.taskMerge.overwritten} 条"
                    } else {
                        ""
                    }
            )
        }
    }

    fun exportSchedule(uri: Uri, contentResolver: ContentResolver) = viewModelScope.launch {
        _uiState.update { it.copy(isImporting = true) }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val json = container.exportService.exportJson()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }
        _uiState.update {
            it.copy(
                isImporting = false,
                userMessage = if (ok) "数据已导出到所选位置" else "导出失败：无法写入所选位置"
            )
        }
    }

    // ------------------------------------------------------------ 浙大同步：学期下拉与取课表

    /**
     * 拉一次公共校历配置，填学期的下拉。
     *
     * 由浙大同步的入口触发（「更多」抽屉的 ZJU 账号、总览页翻面卡的快捷登录）：
     * 下拉里没有学期用户就无从选起。
     */
    fun loadZjuTerms() = viewModelScope.launch {
        if (_uiState.value.zjuTermOptions.isNotEmpty()) return@launch
        _uiState.update { it.copy(isZjuImportRunning = true, zjuError = null) }
        val options = runCatching { zjuImportService.availableTerms() }
            .getOrElse { emptyList() }
        _uiState.update {
            it.copy(
                isZjuImportRunning = false,
                zjuTermOptions = options.map { o -> ZjuTermOption(o.year, o.term, o.season) },
                zjuError = if (options.isEmpty()) "校历配置拉取失败，可改用文件导入" else null
            )
        }
    }

    /**
     * 登录浙大、取课表与校历，产出的 JSON 交给**既有的** [previewImportAndHold]。
     *
     * 密码只作为参数在这条调用栈里活一次，不进 state、不落库、不进日志。
     */
    fun importFromZju(
        studentId: String,
        password: String,
        option: ZjuTermOption,
        remember: Boolean = true
    ) = viewModelScope.launch {
        if (_uiState.value.isZjuImportRunning) return@launch
        if (studentId.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(zjuError = "请填写学号与密码") }
            return@launch
        }
        _uiState.update { it.copy(isZjuImportRunning = true, zjuError = null) }

        val json = runCatching {
            zjuImportService.fetchScheduleJson(
                studentId = studentId,
                password = password,
                option = com.example.data.source.zju.ZjuImportService.TermOption(
                    option.year, option.term
                )
            )
        }

        json.onSuccess { text ->
            if (remember) {
                secureStorage.saveCredentials(studentId, password, remember = true)
            } else {
                secureStorage.saveMaskedStudentIdOnly(studentId)
            }
            secureStorage.saveLastTerm(option.year, option.term)
            val currentCourses = courseRepo.getAllCoursesOnce()
            // 本地一张课表都没有 → 整表替换；已经有课表 → 合并，
            // 别把用户自己加过、改过的课冲掉。
            //
            // 这里的判据原来是「当前是不是示例数据」：示例数据本来就该被整表顶掉，
            // 而真实数据不该。示例数据功能取消后，「本地有没有东西」就成了同一件事
            // ——本地空 = 没什么可丢的，语义完全一致。
            val strategy = if (currentCourses.isEmpty()) ImportStrategy.REPLACE else ImportStrategy.MERGE
            _uiState.update {
                it.copy(
                    isZjuImportRunning = false,
                    importStrategy = strategy,
                    boundStudentId = secureStorage.getMaskedStudentId(),
                    isRememberCredentials = secureStorage.isRememberCredentials()
                )
            }
            previewImportAndHold(text)
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    isZjuImportRunning = false,
                    zjuError = e.message ?: "取课表失败，可改用文件导入"
                )
            }
        }
    }

    // ------------------------------------------------------------ 云端同步与账号管理

    fun openCloudAccountDialog() = _uiState.update { it.withCloudAccountOpen() }

    /** 打开浙大账号弹层时的状态变化。见 [withAddCourseOpen] 的说明。 */
    private fun MainUiState.withCloudAccountOpen() =
        copy(isCloudAccountDialogOpen = true, zjuError = null, syncFailure = null)

    fun closeCloudAccountDialog() = _uiState.update {
        it.copy(isCloudAccountDialogOpen = false, zjuError = null, syncFailure = null)
            .resumeOnboardingIfTargetDone()
    }

    fun syncFromCloud(
        studentId: String,
        password: String,
        option: ZjuTermOption,
        remember: Boolean = true
    ) = viewModelScope.launch {
        if (_uiState.value.isSyncing) return@launch
        _uiState.update { it.copy(isSyncing = true, zjuError = null, syncFailure = null) }

        val result = syncManager.sync(
            studentId = studentId,
            password = password,
            option = com.example.data.source.zju.ZjuImportService.TermOption(option.year, option.term),
            rememberCredentials = remember
        )

        _uiState.update { state ->
            when (result) {
                is SyncResult.Success -> state.withSyncSuccess(
                    userMessage = "云端同步完成：${result.courseCount} 门课程已更新到本地缓存" +
                        result.report.unlinkedTasksSuffix(),
                    closeDialog = true
                )
                is SyncResult.Failed -> state.withSyncFailure(result.failure)
            }
        }
    }

    fun syncWithSavedCredentials() = viewModelScope.launch {
        if (_uiState.value.isSyncing) return@launch
        if (!secureStorage.hasSavedCredentials()) {
            _uiState.update {
                it.copy(
                    isCloudAccountDialogOpen = true,
                    userMessage = "请先配置教务网学号与密码"
                )
            }
            return@launch
        }
        _uiState.update { it.copy(isSyncing = true, syncFailure = null) }
        val result = syncManager.syncWithSavedCredentials()
        _uiState.update { state ->
            when (result) {
                is SyncResult.Success -> state.withSyncSuccess(
                    userMessage = "云端同步完成：已更新本地缓存（${result.courseCount} 门课程）" +
                        result.report.unlinkedTasksSuffix()
                )
                // 失败一律回到弹层里那张卡。早先这里只写了 userMessage，
                // 于是「在弹层里点立即同步 → 失败」这条最常见的路径上，
                // 弹层什么都不显示（Snackbar 画在 Scaffold 上，被弹层盖住），
                // 用户看到的唯一现象就是按钮转了一圈然后什么也没发生。
                is SyncResult.Failed -> state.withSyncFailure(result.failure, keepDialogOpen = true)
            }
        }
    }

    /**
     * 一次成功之后要落到 state 上的东西，**只有这一处**在写。
     *
     * ## 为什么要专门抽出来
     *
     * 因为这里出过一次 bug：成功时只回填了 `lastSyncTimestamp`，没回填
     * `lastSyncFailureKind`，而 `ScheduleSyncManager.markSyncSuccess` 明明已经把
     * 持久化的失败类别清掉了。内存里于是留着一份旧值 ——
     * 用户同步成功后再打开弹层，**「最近一次尝试失败：浙大教务系统异常」仍然红着**。
     *
     * 那个 bug 和上一次那个（漏设 `zjuError`）是同一类：**「失败时会更新哪些字段」
     * 没有唯一答案，就迟早有一处忘了**。所以成功也收敛成一个函数，
     * 三个字段（成功时间 / 尝试时间 / 失败类别）永远一起从存储刷新，
     * 谁都别想只更新其中一部分。
     *
     * [closeDialog] 为真时弹层是**代码**收的，不是用户点的「关闭」——
     * 所以接回引导也得在这里做（见 [closeCloudAccountDialogAfterSync]）。
     */
    private fun MainUiState.withSyncSuccess(
        userMessage: String,
        closeDialog: Boolean = false
    ): MainUiState {
        val base = copy(
            isSyncing = false,
            boundStudentId = secureStorage.getMaskedStudentId(),
            isRememberCredentials = secureStorage.isRememberCredentials(),
            lastSyncTimestamp = secureStorage.getLastSyncTimestamp(),
            lastSyncAttemptTime = secureStorage.getLastSyncAttemptTime(),
            // 成功过一次之后这里是 null：那条失败信息已经过期，不该再挂着吓人。
            lastSyncFailureKind = secureStorage.getLastSyncFailureKind(),
            syncFailure = null,
            userMessage = userMessage
        )
        // 收弹层 + 接回引导合成一步：分两次做的话，中间会有一瞬间「弹层没了、
        // 引导还没回来」，那一瞬间屏幕上什么都不剩。
        return if (closeDialog) base.closeCloudAccountDialogAfterSync() else base
    }

    /**
     * 把一次失败同时落到三处：**弹层里的卡**（能照着做）、**Snackbar 摘要**（弹层关掉后还在）、
     * **同步历史**（下次打开弹层仍能看到「上次那次没成」）。
     *
     * 凭据类的失败顺带把弹层切到输入态：用户已经停在这个页面上了，
     * 让ta再去点一次「修改账号/学期」才能修，是把「给出解法」和「提供入口」拆成了两步。
     */
    private fun MainUiState.withSyncFailure(
        failure: SyncFailure,
        keepDialogOpen: Boolean = true
    ): MainUiState {
        val needsEdit = failure.kind.needsCredentials
        val localLine = when {
            !failure.localDataIntact -> "本地课表已被这次同步更新过"
            failure.localCourseCount != null && failure.localCourseCount > 0 ->
                "本地 ${failure.localCourseCount} 门课程仍然照常可用"
            failure.localCourseCount == 0 -> "本地还没有课表数据"
            else -> null
        }
        return copy(
            isSyncing = false,
            isCloudAccountDialogOpen = keepDialogOpen || needsEdit,
            syncFailure = failure,
            lastSyncTimestamp = secureStorage.getLastSyncTimestamp(),
            lastSyncAttemptTime = secureStorage.getLastSyncAttemptTime(),
            lastSyncFailureKind = secureStorage.getLastSyncFailureKind(),
            userMessage = listOfNotNull(failure.headline(), localLine).joinToString("，")
        )
    }

    fun clearSavedAccount() {
        secureStorage.clearCredentials()
        _uiState.update {
            it.copy(
                boundStudentId = null,
                isRememberCredentials = false,
                syncFailure = null,
                userMessage = "已清除保存的凭据"
            )
        }
    }

    // ------------------------------------------------------------ 校历设置

    /**
     * 打开弹层。
     *
     * 「开弹层」与「开始读官方校历」必须落在**同一次 update** 里：分成两次的话，
     * 中间那一帧弹层看到的是 `preset = null && loading = false`，
     * 于是先闪一句「读不到官方校历」再变成「正在读取…」。
     * 读不到和没在读是两件事，不该用同一帧去表示。
     */
    fun openAcademicCalendarDialog() {
        _uiState.update {
            it.copy(
                isAcademicCalendarDialogOpen = true,
                isZjuPresetLoading = it.zjuPreset == null
            )
        }
        loadZjuPreset()
    }

    fun closeAcademicCalendarDialog() = _uiState.update {
        // 差异预览是一次性动作：关掉弹层时一并收起，否则下次打开会直接顶在
        // 上一次的确认界面上。
        it.copy(isAcademicCalendarDialogOpen = false, isCalendarDiffOpen = false, calendarDiff = null)
    }

    /**
     * 读当前可用的官方校历，给弹层单独显示。**不联网**（缓存 → 内置），秒开。
     *
     * 远程更新是**显式**点「检查更新」才发生的，不在开弹层这条路上 ——
     * 否则每开一次弹层都要等最多 15s 超时，而校历是天级变更的低频动作。
     *
     * 结果与 loading 在**同一次 update** 里落：只设 `isZjuPresetLoading = false` 而漏了
     * `zjuPreset`，界面上就会一直转圈；反过来只设结果不设 loading，转圈永远不停。
     * 成功与失败都不抛 —— 读不到是「这台机器上没这份数据」，
     * 该由弹层显示「官方校历不可用」，不该让开弹层这个动作失败。
     */
    private fun loadZjuPreset() = viewModelScope.launch {
        // 只按「有没有结果」判重，不按 loading：打开时已经把 loading 置 true 了，
        // 拿 loading 当「已经在读」的凭据会把这次读取自己挡在门外。
        if (_uiState.value.zjuPreset != null) return@launch
        val loaded = runCatching { calendarUpdates.loadOffline() }.getOrNull()
        val prefer = currentTermRef()
        val preset = loaded?.let {
            runCatching { zjuImportService.presetFrom(it.bundle, prefer, it.origin) }.getOrNull()
        }
        // 把这份配置里**所有**的学年学期一起摆出来：加新学期之后用户要能切过去，
        // 只自动选一的话新加的那期永远不会被摆出来，看着像"检查更新没生效"。
        val allTerms = loaded?.let {
            runCatching { zjuImportService.academicTermsIn(it.bundle) }.getOrDefault(emptyList())
        }.orEmpty()
        _uiState.update {
            it.copy(isZjuPresetLoading = false, zjuPreset = preset, zjuAvailableTerms = allTerms)
        }
    }

    /** 当前正在用的学期，作为"官方校历该摆哪一期"的偏好。 */
    private fun currentTermRef(): ZjuImportService.PreferredTerm? {
        val s = uiState.value.semester ?: return null
        return ZjuImportService.PreferredTerm(s.yearSpan, s.term.takeIf { it.isNotBlank() })
    }

    /**
     * 换一个学年学期看 —— 只影响弹层上"官方校历"那块显示哪一期，**不写库**。
     *
     * 加了新学期（同一学年多出春夏，或多了一个新学年）之后，用户要有个办法
     * 把它切过去看内容。真正落库仍然走「应用到本机校历」→ 差异 → 确认。
     */
    fun previewZjuTerm(term: AcademicTerm) = viewModelScope.launch {
        val loaded = runCatching { calendarUpdates.loadOffline() }.getOrNull() ?: return@launch
        val preset = runCatching {
            zjuImportService.presetFrom(
                loaded.bundle,
                ZjuImportService.PreferredTerm(term.yearSpan, term.season),
                loaded.origin
            )
        }.getOrNull()
        // 切看的动作不该把"上次检查到的待应用新版"清掉：那是另一次检查的结果。
        _uiState.update { it.copy(zjuPreset = preset) }
    }

    /**
     * 「检查更新」：联网拉一次远程配置，比 `lastUpdated`，可能写缓存。**不碰用户正在用的校历。**
     *
     * 结果与 [MainUiState.isCheckingCalendarUpdate] 必须落**同一次 update**，
     * 否则按钮会永远停在转圈状态（沿用 [loadZjuPreset] 那条教训）。
     *
     * 三个分支**语义互不重叠**，别把它们揉成一句话：
     * - [CalendarUpdateService.UpdateOutcome.Updated]：有新版 → 存进 `pendingPreset`，
     *   界面上出现「发现新版」，等用户看差异。**这时已经把远程那份写进缓存了**，
     *   所以下次开弹层看到的就是新版；但仍要用户确认才落到库里。
     * - `UpToDate`：确实拉到过远程，且不比缓存新。
     * - `Failed`：拉不到或内容坏了。**绝不显示成「已是最新」** ——
     *   那等于骗用户，而恰恰是网络受限时最需要说实话的时候。
     */
    fun checkCalendarUpdate() = viewModelScope.launch {
        _uiState.update { it.copy(isCheckingCalendarUpdate = true) }

        when (val outcome = runCatching { calendarUpdates.checkForUpdate() }.getOrElse {
            CalendarUpdateService.UpdateOutcome.Failed(it.message ?: "检查失败")
        }) {
            is CalendarUpdateService.UpdateOutcome.Updated -> {
                val prefer = currentTermRef()
                val preset = runCatching {
                    zjuImportService.presetFrom(outcome.bundle, prefer, CalendarOrigin.REMOTE)
                }.getOrNull()
                val allTerms = runCatching {
                    zjuImportService.academicTermsIn(outcome.bundle)
                }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        isCheckingCalendarUpdate = false,
                        pendingPreset = preset,
                        zjuAvailableTerms = allTerms,
                        userMessage = if (preset == null) {
                            "校历已更新到缓存，但这一期解析不出来，没有可应用的内容"
                        } else {
                            "发现新版校历（${preset.versionLabel ?: "未知版本"}），可查看差异后应用"
                        }
                    )
                }
            }

            is CalendarUpdateService.UpdateOutcome.UpToDate -> _uiState.update {
                it.copy(
                    isCheckingCalendarUpdate = false,
                    userMessage = "官方校历已是最新（版本 ${outcome.version?.let(::formatVersion) ?: "未知"}）"
                )
            }

            is CalendarUpdateService.UpdateOutcome.Failed -> _uiState.update {
                it.copy(isCheckingCalendarUpdate = false, userMessage = "检查更新失败：${outcome.reason}")
            }
        }
    }

    /**
     * 算「应用 [MainUiState.pendingPreset] 或 [MainUiState.zjuPreset] 会变什么」，打开差异预览。
     *
     * **差异是用当前库里的值算的，不是用上一次拉到的值** —— 用户可能在
     * 上一次预览之后又手改过校历，界面上摆的必须是此刻真会发生的变化。
     */
    fun previewPendingPreset() = viewModelScope.launch {
        val target = _uiState.value.pendingPreset ?: _uiState.value.zjuPreset
        if (target == null) {
            _uiState.update { it.copy(userMessage = "没有可应用的官方校历") }
            return@launch
        }
        val current = CalendarSnapshot.of(
            semester = uiState.value.semester,
            noClassDays = uiState.value.noClassDays,
            makeupDays = uiState.value.makeupDays
        )
        val diff = CalendarDiffCalculator.compute(current, target.toSnapshot())
        _uiState.update {
            it.copy(
                pendingPreset = target,
                calendarDiff = diff,
                isCalendarDiffOpen = true,
                userMessage = if (diff.isEmpty) "与当前校历没有差异" else null
            )
        }
    }

    fun dismissCalendarDiff() =
        _uiState.update { it.copy(isCalendarDiffOpen = false, calendarDiff = null) }

    /**
     * 确认应用官方校历。
     *
     * 落库走的是与手动保存**同一条**路径（[saveAcademicCalendar]），
     * 语义是全量替换停课与补课 —— 用户在差异预览里已经看过会被删掉什么了。
     *
     * **不动作息表**：官方配置不带作息，刻意只换学期起止与停课 / 补课。
     * 作息由用户单独维护，官方那份的作息是硬编码在 app 里的另一份常量，
     * 混进来会让「我明明设过 13 节」这类配置被悄悄改掉。
     *
     * `periods` 传空 → [AcademicCalendarRepository.saveCalendar] 的
     * `replacePeriods` 为 false，作息表原样保留。
     */
    fun confirmApplyPreset() = viewModelScope.launch {
        val pending = _uiState.value.pendingPreset ?: _uiState.value.zjuPreset ?: return@launch
        val periods = container.calendarRepository.periodsOnce()

        calendarRepo.saveCalendar(
            semester = SemesterEntity(
                name = pending.label,
                yearSpan = pending.yearSpan,
                term = AcademicCalendarRepository.seasonOf(pending.label),
                startEpochDay = pending.startEpochDay,
                firstMondayEpochDay = pending.firstMondayEpochDay,
                endEpochDay = pending.endEpochDay,
                totalWeeks = pending.totalWeeks,
                // 节数跟着**实际保留的作息表**走，不是官方那份里带的数字。
                periodsPerDay = periods.size
            ),
            periods = emptyList(),
            noClass = pending.noClassDays,
            makeup = pending.makeupDays,
            replacePeriods = false,
            replaceNoClass = true,
            replaceMakeup = true
        )
        courseRepo.clampSlotWeeksTo(pending.totalWeeks)

        _uiState.update {
            it.copy(
                // 关弹层：与手动保存那条路径保持一致。
                // 留着的话用户会停在一个已经落库的表单前，不知道该干什么。
                isAcademicCalendarDialogOpen = false,
                isCalendarDiffOpen = false,
                calendarDiff = null,
                pendingPreset = null,
                // 应用之后官方那块就与本机一致了，没有"待应用"这回事。
                zjuPreset = pending.copy(origin = CalendarOrigin.CACHED),
                userMessage = "本机校历已更新至 ${pending.versionLabel ?: "官方版本"}"
            )
        }
    }

    /** `20260917` → `2026-09-17`。 */
    private fun formatVersion(v: Int): String =
        "%04d-%02d-%02d".format(v / 10000, v / 100 % 100, v % 100)

    fun saveAcademicCalendar(
        semester: SemesterEntity?,
        periods: List<com.example.data.local.TimetablePeriodEntity>,
        noClassDays: List<NoClassDayEntity>,
        makeupDays: List<MakeupDayEntity>
    ) = viewModelScope.launch {
        calendarRepo.saveCalendar(
            semester = semester,
            periods = periods,
            noClass = noClassDays,
            makeup = makeupDays,
            replacePeriods = periods.isNotEmpty(),
            // 弹层里展示的就是已存的全部条目，保存即整体替换 —— 用户删掉的那些要真的消失
            replaceNoClass = true,
            replaceMakeup = true
        )
        semester?.let { courseRepo.clampSlotWeeksTo(it.totalWeeks) }
        closeAcademicCalendarDialog()
        _uiState.update { it.copy(userMessage = "学期校历已保存") }
    }

    // ------------------------------------------------------------ 自定义壁纸

    fun openWallpaperDialog() {
        _uiState.update { it.withWallpaperOpen() }
    }

    /** 打开壁纸弹层时的状态变化。见 [withAddCourseOpen] 的说明。 */
    private fun MainUiState.withWallpaperOpen() = copy(isWallpaperDialogOpen = true)

    fun closeWallpaperDialog() {
        _uiState.update { it.copy(isWallpaperDialogOpen = false).resumeOnboardingIfTargetDone() }
    }

    fun applyCustomWallpaper(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            val path = wallpaperRepo.setCustomWallpaperFromUri(uri, contentResolver)
            if (path != null) {
                _uiState.update { it.copy(userMessage = "已应用自定义壁纸") }
            } else {
                _uiState.update { it.copy(userMessage = "导入壁纸失败，请重试") }
            }
        }
    }

    fun applyPresetWallpaper(presetId: String) {
        wallpaperRepo.setPresetWallpaper(presetId)
        _uiState.update { it.copy(userMessage = "已应用预设壁纸") }
    }

    fun resetToDefaultWallpaper() {
        wallpaperRepo.resetToDefault()
        _uiState.update { it.copy(userMessage = "已恢复默认壁纸") }
    }

    fun setWallpaperDimOpacity(opacity: Float) {
        wallpaperRepo.setDimOpacity(opacity)
    }

    fun setWallpaperSoftFocus(value: Float) {
        wallpaperRepo.setSoftFocus(value)
    }

    // ------------------------------------------------------------ 上手引导

    /**
     * 打开引导。
     *
     * 从**上次停住的那一步**接着看，而不是每次都从头开始：
     * 引导现在是可以暂停的（去看看某个功能，做完再回来接着讲），
     * 停在哪就是停在哪 —— 从头再推一遍，等于把用户刚看过的东西又推一次。
     *
     * 走完或跳过的那一趟会把步数归零，所以「重看一遍」和「接着看」
     * 用的是同一个数：没走完就接着看，走完了就从头看。
     */
    fun openOnboardingGuide() {
        val step = onboardingRepo.currentStep.coerceIn(0, GuideTarget.entries.size - 1)
        _uiState.update {
            it.copy(
                isOnboardingGuideOpen = true,
                onboardingStep = step,
                onboardingAwaitingTarget = null,
                onboardingResumed = false
            )
        }
    }

    /**
     * 关掉引导。
     *
     * **跳过**（以及返回键、点外面）算「这一趟到此为止」：记下「看过」，
     * 下次冷启动不该再弹一次打断他。步数归零 —— 这一趟没走完，
     * 下次从设置页进来是「重看一遍」，从头开始。
     */
    fun closeOnboardingGuide() {
        onboardingRepo.hasSeenOnboarding = true
        onboardingRepo.currentStep = 0
        _uiState.update {
            it.copy(
                isOnboardingGuideOpen = false,
                onboardingAwaitingTarget = null,
                onboardingResumed = false
            )
        }
    }

    /**
     * 下一步；已经是最后一步就走完这一趟（记「看过」并把步数归零）。
     *
     * 步数取 [GuideTarget] 的枚举个数：引导一步对应一个目标，
     * 两边是同一份清单（`GuideSteps` 一步一个目标，见那里的说明）。
     */
    fun advanceOnboardingStep() {
        val next = _uiState.value.onboardingStep + 1
        if (next >= GuideTarget.entries.size) {
            onboardingRepo.hasSeenOnboarding = true
            onboardingRepo.currentStep = 0
            _uiState.update {
                it.copy(
                    isOnboardingGuideOpen = false,
                    onboardingStep = 0,
                    onboardingAwaitingTarget = null,
                    onboardingResumed = false
                )
            }
        } else {
            onboardingRepo.currentStep = next
            _uiState.update { it.copy(onboardingStep = next, onboardingResumed = false) }
        }
    }

    /**
     * 「去看看」：把引导**暂停**在这里，打开那个功能。
     *
     * ## 暂停，不是关闭
     *
     * 收掉引导就把用户一个人扔在目标功能里了 —— 他还欠着几页没看，
     * 收掉等于让他自己记着「我刚才看到第几步了」。所以这里只**暂停**：
     * 目标功能做完、弹层关掉之后，引导自己接回来（见
     * [resumeOnboardingIfTargetDone]）。
     *
     * 顺带：「去看看」不再算「看过」。空态的条件还在（他可能没同步成功），
     * 下次冷启动引导该回来还得回来。
     *
     * ## 为什么收掉而不是叠在上面
     *
     * 叠着的话弹层的遮罩会叠着引导的遮罩，背后被压两遍，玻璃面也就糊了；
     * 两张面板还会重叠成一堆。收掉（暂停）等目标做完再接回来，
     * 全程同一时刻只有一层。
     *
     * ## 为什么一次更新里做完两件事
     *
     * 「关掉引导 + 记下在等谁」和「打开目标」必须发生在**同一次状态更新**里：
     * 分两次的话，中途会有一瞬间既「引导开着」又「目标开着」，
     * 两层遮罩与两张面板撞在一起 —— 正是上面要避免的那一幕。
     *
     * ## 落点只管「打开哪个弹层」
     *
     * 别在这里挑课程：课程列表是**仓库来的**字段，只存在于合成后的 `uiState` 里，
     * raw 的 `_uiState` 那份永远是空的 —— 读它不报错，只是「没有课」，
     * 于是「挑一门课」这种分支会被悄悄走成另一条。
     * 要课程就取 `uiState.value`。
     */
    fun openOnboardingTarget(target: GuideTarget) {
        _uiState.update { state ->
            val opened = when (target) {
                GuideTarget.ZJU_SYNC -> state.withCloudAccountOpen()
                GuideTarget.COURSE_CATEGORY -> state.withCourseCategoryOpen()
                GuideTarget.LINKAGE_RULES -> state.withCategoryRulesOpen()
                GuideTarget.WALLPAPER -> state.withWallpaperOpen()
            }
            opened.copy(
                isOnboardingGuideOpen = false,
                onboardingAwaitingTarget = target,
                onboardingResumed = false
            )
        }
    }

    /**
     * 刷新学在浙大待办事项。
     * [silent] 为 true 时代表启动静默刷新，不主动弹出成功/失败 Snackbar。
     */
    fun refreshCoursesTodos(silent: Boolean = false) {
        val studentId = secureStorage.getStudentId()
        val password = secureStorage.getPassword()
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) {
            if (!silent) {
                _uiState.update {
                    it.copy(
                        userMessage = "未保存浙大统一身份认证凭据，请先绑定",
                        isQuickZjuLoginDialogOpen = true
                    )
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCoursesTodoLoading = true, coursesTodoError = null) }
            try {
                val todos = zjuCoursesClient.fetchTodos(studentId, password)
                val now = System.currentTimeMillis()
                coursesTodoCache.save(todos, now)
                _uiState.update {
                    it.copy(
                        coursesTodoList = todos,
                        coursesTodoLastUpdated = now,
                        isCoursesTodoLoading = false,
                        coursesTodoError = null,
                        userMessage = if (!silent) "学在浙大待办已更新（共 ${todos.size} 项）" else it.userMessage
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "刷新学在浙大待办失败"
                _uiState.update {
                    it.copy(
                        isCoursesTodoLoading = false,
                        coursesTodoError = msg,
                        userMessage = if (!silent) msg else it.userMessage
                    )
                }
            }
        }
    }

    fun setQuickZjuLoginDialogOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isQuickZjuLoginDialogOpen = isOpen) }
    }

    fun saveZjuCredentialsAndSyncTodos(studentId: String, password: String) {
        if (studentId.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(userMessage = "学号与密码不能为空") }
            return
        }
        secureStorage.saveCredentials(studentId, password, remember = true)
        _uiState.update {
            it.copy(
                boundStudentId = secureStorage.getMaskedStudentId(),
                isRememberCredentials = true,
                isQuickZjuLoginDialogOpen = false,
                userMessage = "已保存浙大统一身份认证凭据"
            )
        }
        refreshCoursesTodos(silent = false)
    }
}

class MainViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(container) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * 同步成功后追加的一句：有多少条待办因为所属课程被换掉而落进了「未关联课程」。
 *
 * 这件事不该只有用户自己在清单里翻到才发现 —— 静默改挂一批待办的归属，
 * 不说出来就等于让用户自己怀疑数据是不是坏了。没有待办被动过时返回空串。
 */
private fun ImportReport.unlinkedTasksSuffix(): String =
    if (tasksUnlinked > 0) "；$tasksUnlinked 条待办已移入「未关联课程」" else ""