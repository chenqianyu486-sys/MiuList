package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.data.di.AppContainer
import com.example.data.generation.CourseRuleSpec
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
import com.example.data.model.TaskType
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.sample.SampleDataProvider
import com.example.data.source.zju.ZjuImportService
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
        viewModelScope.launch {
            settingsRepo.ensureDefaultRules(AppContainer.DEFAULT_CATEGORY_RULES)
            // 全新安装时作息表是空的（迁移只给升级用户写），不补的话课表栅格会是空白
            calendarRepo.ensureDefaultPeriods()
            if (courseRepo.getAllCoursesOnce().isEmpty()) {
                courseRepo.resetToDemoData(
                    SampleDataProvider.sampleCourses,
                    SampleDataProvider.sampleCourseSlots,
                    SampleDataProvider.createSampleTasks()
                )
            }
            _uiState.update {
                it.copy(selectedScheduleDay = EpochDays.dayOfWeek1To7(EpochDays.today()), isLoading = false)
            }
        }
    }

    // ------------------------------------------------------------ 导航与筛选

    fun setNavigationTab(tab: NavigationTab) = _uiState.update { it.copy(currentTab = tab) }
    fun setSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }
    fun setSelectedTaskType(key: String?) = _uiState.update { it.copy(selectedTaskTypeKey = key) }
    fun setGroupingMode(mode: TaskGroupingMode) = _uiState.update { it.copy(groupingMode = mode) }
    fun setStatusFilter(filter: TaskStatusFilter) = _uiState.update { it.copy(statusFilter = filter) }
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

    // ------------------------------------------------------------ 待办类型

    fun openNewTaskTypeDialog() = _uiState.update { it.copy(isNewTaskTypeDialogOpen = true) }

    fun closeNewTaskTypeDialog() = _uiState.update { it.copy(isNewTaskTypeDialogOpen = false) }

    /**
     * 新建一个自定义待办类型。
     *
     * 失败（重名 / 超上限）不静默：类型是筛选和分组的一级维度，
     * 一个「没建上但也不吭声」的反馈会让用户以为是自己哪里点错了。
     */
    fun createTaskType(label: String, paletteIndex: Int) = viewModelScope.launch {
        val result = taskTypeRepo.create(label, paletteIndex)
        _uiState.update {
            if (result.isSuccess) {
                it.copy(isNewTaskTypeDialogOpen = false, userMessage = "已新建类型")
            } else {
                it.copy(userMessage = result.exceptionOrNull()?.message ?: "新建类型失败")
            }
        }
    }

    fun confirmDeleteTaskType(type: CustomTaskType) = _uiState.update {
        it.copy(deletingTaskType = type)
    }

    fun cancelDeleteTaskType() = _uiState.update { it.copy(deletingTaskType = null) }

    /**
     * 删掉一个自定义类型。已经用了它的待办会退化成「未分类」
     * （`CUSTOM:<id>` 变成解析不出的悬空 key，见 `TaskTypes.resolve`），
     * 待办本身一条都不动。
     */
    fun deleteTaskType(type: CustomTaskType) = viewModelScope.launch {
        taskTypeRepo.delete(type.id)
        // 正按这个类型筛着的话，把它一起撤掉，否则筛选条件会指向一个不存在的类型
        _uiState.update {
            it.copy(
                deletingTaskType = null,
                selectedTaskTypeKey = it.selectedTaskTypeKey?.takeIf { key -> key != type.key },
                userMessage = "已删除「${type.label}」"
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

    fun openAddCourseDialog() = _uiState.update {
        it.copy(isAddCourseDialogOpen = true, editingCourseWithSlots = null)
    }

    fun openEditCourseDialog(c: CourseWithSlots) = _uiState.update {
        it.copy(isAddCourseDialogOpen = true, editingCourseWithSlots = c)
    }

    fun closeCourseDialog() = _uiState.update {
        it.copy(isAddCourseDialogOpen = false, editingCourseWithSlots = null)
    }

    fun inspectCourse(c: CourseWithSlots?) = _uiState.update { it.copy(inspectingCourse = c) }

    fun saveCourseWithSlots(course: CourseEntity, slots: List<SlotWithWeeks>) = viewModelScope.launch {
        courseRepo.saveCourseWithSlots(course, slots)
        _uiState.update {
            it.copy(userMessage = "课程「${course.name}」已保存（包含 ${slots.size} 个上课时段）")
        }
        closeCourseDialog()
    }

    fun deleteCourse(courseId: Long, courseName: String) = viewModelScope.launch {
        courseRepo.deleteCourse(courseId)
        _uiState.update {
            it.copy(userMessage = "课程「$courseName」及对应时段与作业已删除", inspectingCourse = null)
        }
    }

    // ------------------------------------------------------------ 类别规则

    fun openCategoryRulesDialog() = _uiState.update { it.copy(isCategoryRulesDialogOpen = true) }
    fun closeCategoryRulesDialog() = _uiState.update { it.copy(isCategoryRulesDialogOpen = false) }

    fun updateCategoryRule(rule: CategoryRule) = viewModelScope.launch {
        settingsRepo.saveRule(rule)
        _uiState.update { it.copy(userMessage = "「${rule.category}」规则已保存") }
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

    fun resetDemoData() = viewModelScope.launch {
        courseRepo.resetToDemoData(
            SampleDataProvider.sampleCourses,
            SampleDataProvider.sampleCourseSlots,
            SampleDataProvider.createSampleTasks()
        )
        _uiState.update { it.copy(userMessage = "已重置为示例课表（支持一周多次、1-13 节）与作业待办") }
    }

    // ------------------------------------------------------------ 自动生成待办

    /**
     * 造出「第 [week] 周」的全部候选待办。
     *
     * 周次用的是 [MainUiState.selectedWeek]（没手动选过就是今天所在的周）：
     * 用户在周课表上浏览第 N 周时点生成，产出的就该是第 N 周，
     * 否则「先翻到下周、再点生成」这种最自然的用法会生成到本周去。
     *
     * 没配置学期时**拒绝生成**：没有学期就没有周次概念，
     * 硬生成出来的去重键全是 0，第二周第三次点就会把第一周那份也当成重复。
     */
    private suspend fun buildCandidatesForWeek(week: Int): List<TaskEntity> {
        val calState = calendarRepo.calendarState.first()
        val semester = calendarRepo.semesterOnce() ?: return emptyList()
        val resolver = ScheduleResolver(calState.calendar)
        val engine = WeekTaskGenerationEngine(calState.periodTable)

        val weekMonday = semester.firstMondayEpochDay + (week - 1).coerceAtLeast(0) * 7L
        val slots = resolver.slotsInWeek(
            week,
            courseRepo.getAllSlotsOnce(),
            courseRepo.getWeeksBySlotIdOnce()
        ).values.flatten()

        return engine.build(
            WeekTaskGenerationEngine.Input(
                week = week,
                weekMondayEpochDay = weekMonday,
                slots = slots,
                coursesById = courseRepo.getAllCoursesOnce().associateBy { it.id },
                courseRules = settingsRepo.getTaskRulesOnce(),
                categoryRules = settingsRepo.getCategoryRulesOnce().associateBy { it.category }
            )
        ) { courseId, taskType, w, day -> taskRepo.hasWeekTask(courseId, taskType.name, w, day) }
    }

    private suspend fun commitCandidates(candidates: List<TaskEntity>): Int {
        if (candidates.isEmpty()) return 0
        taskRepo.insertTasks(candidates)
        return candidates.size
    }

    /**
     * 本周（第几周由 selectedWeek 决定）没有学期就不给生成。
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
        val week = uiState.value.selectedWeek
        val candidates = buildCandidatesForWeek(week)
        _uiState.update {
            it.copy(
                candidateTasksForPreview = candidates,
                isQuickGenerateDialogOpen = true
            )
        }
    }

    fun closeQuickGenerateDialog() = _uiState.update { it.copy(isQuickGenerateDialogOpen = false) }

    fun confirmGenerateTasks(selected: List<TaskEntity>) = viewModelScope.launch {
        val week = uiState.value.selectedWeek
        val count = commitCandidates(selected)
        closeQuickGenerateDialog()
        _uiState.update {
            it.copy(
                userMessage = if (count > 0) "已生成 $count 项第 $week 周待办"
                else "第 $week 周待办已全部生成"
            )
        }
    }

    fun generateTasksDirectlyForWeek() = viewModelScope.launch {
        if (!requireSemester()) return@launch
        val week = uiState.value.selectedWeek
        val candidates = buildCandidatesForWeek(week)
        val count = commitCandidates(candidates)
        _uiState.update {
            it.copy(
                userMessage = if (count > 0) "已生成 $count 项第 $week 周待办"
                else "第 $week 周待办已全部生成"
            )
        }
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
                userMessage = "课表导入完成：新增 ${report.inserted} 门、更新 ${report.updated} 门"
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
                userMessage = if (ok) "课表已导出" else "导出失败：无法写入所选位置"
            )
        }
    }

    // ------------------------------------------------------------ 「ZJU」导入源

    fun setImportSource(source: ImportSource) = _uiState.update {
        it.copy(importSource = source, zjuError = null)
    }

    /**
     * 拉一次公共校历配置，填学期的下拉。
     *
     * 切到 ZJU 那一档时触发一次：下拉里没有学期用户就无从选起。
     */
    fun loadZjuTerms() = viewModelScope.launch {
        if (_uiState.value.zjuTermOptions.isNotEmpty()) return@launch
        _uiState.update { it.copy(isZjuImportRunning = true, zjuError = null) }
        val options = runCatching { zjuImportService.availableTerms() }
            .getOrElse { emptyList() }
        _uiState.update {
            it.copy(
                isZjuImportRunning = false,
                zjuTermOptions = options.map { o -> ZjuTermOption(o.year, o.term) },
                zjuError = if (options.isEmpty()) "校历配置拉取失败，可改用文件导入" else null
            )
        }
    }

    /**
     * 登录浙大、取课表与校历，产出的 JSON 交给**既有的** [previewImportAndHold]。
     *
     * 密码只作为参数在这条调用栈里活一次，不进 state、不落库、不进日志。
     */
    fun importFromZju(studentId: String, password: String, option: ZjuTermOption) =
        viewModelScope.launch {
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
                _uiState.update { it.copy(isZjuImportRunning = false, importStrategy = ImportStrategy.MERGE) }
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

    // ------------------------------------------------------------ 校历设置

    fun openAcademicCalendarDialog() = _uiState.update { it.copy(isAcademicCalendarDialogOpen = true) }

    fun closeAcademicCalendarDialog() = _uiState.update { it.copy(isAcademicCalendarDialogOpen = false) }

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