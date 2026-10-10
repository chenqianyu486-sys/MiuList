package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskType
import com.example.data.repository.CourseTodoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(
    private val repository: CourseTodoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())

    private data class RepoState(
        val coursesWithSlots: List<CourseWithSlots>,
        val slots: List<CourseSlotEntity>,
        val tasks: List<TaskEntity>,
        val week: Int,
        val rules: List<CategoryRule>
    )

    private val repoFlow = combine(
        repository.getAllCoursesWithSlots(),
        repository.getAllSlots(),
        repository.getAllTasks(),
        repository.currentWeek,
        repository.categoryRules
    ) { coursesWithSlots, slots, tasks, week, rules ->
        RepoState(coursesWithSlots, slots, tasks, week, rules)
    }

    val uiState: StateFlow<MainUiState> = combine(_uiState, repoFlow) { currentUi, repo ->
        currentUi.copy(
            courses = repo.coursesWithSlots.map { it.course },
            coursesWithSlots = repo.coursesWithSlots,
            courseSlots = repo.slots,
            tasks = repo.tasks,
            currentWeek = repo.week,
            categoryRules = repo.rules
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(isLoading = true)
    )

    init {
        viewModelScope.launch {
            repository.initializeIfEmpty()
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val appDay = if (day == Calendar.SUNDAY) 7 else day - 1
            _uiState.update { it.copy(selectedScheduleDay = appDay, isLoading = false) }
        }
    }

    fun setNavigationTab(tab: NavigationTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedTaskType(type: TaskType?) {
        _uiState.update { it.copy(selectedTaskType = type) }
    }

    fun setGroupingMode(mode: TaskGroupingMode) {
        _uiState.update { it.copy(groupingMode = mode) }
    }

    fun setStatusFilter(filter: TaskStatusFilter) {
        _uiState.update { it.copy(statusFilter = filter) }
    }

    fun setSelectedScheduleDay(day: Int) {
        _uiState.update { it.copy(selectedScheduleDay = day) }
    }

    fun setScheduleViewMode(mode: ScheduleViewMode) {
        _uiState.update { it.copy(scheduleViewMode = mode) }
    }

    fun setCurrentWeek(week: Int) {
        repository.setCurrentWeek(week)
    }

    fun openWeekPicker() {
        _uiState.update { it.copy(isWeekPickerDialogOpen = true) }
    }

    fun closeWeekPicker() {
        _uiState.update { it.copy(isWeekPickerDialogOpen = false) }
    }

    fun openAddTaskDialog(courseId: Long? = null) {
        _uiState.update {
            it.copy(
                isAddTaskDialogOpen = true,
                preselectedCourseIdForNewTask = courseId,
                editingTask = null
            )
        }
    }

    fun openEditTaskDialog(task: TaskEntity) {
        _uiState.update {
            it.copy(
                isAddTaskDialogOpen = true,
                preselectedCourseIdForNewTask = task.courseId,
                editingTask = task
            )
        }
    }

    fun closeTaskDialog() {
        _uiState.update {
            it.copy(
                isAddTaskDialogOpen = false,
                editingTask = null,
                preselectedCourseIdForNewTask = null
            )
        }
    }

    fun openAddCourseDialog() {
        _uiState.update {
            it.copy(
                isAddCourseDialogOpen = true,
                editingCourseWithSlots = null
            )
        }
    }

    fun openEditCourseDialog(courseWithSlots: CourseWithSlots) {
        _uiState.update {
            it.copy(
                isAddCourseDialogOpen = true,
                editingCourseWithSlots = courseWithSlots
            )
        }
    }

    fun closeCourseDialog() {
        _uiState.update {
            it.copy(
                isAddCourseDialogOpen = false,
                editingCourseWithSlots = null
            )
        }
    }

    fun inspectCourse(courseWithSlots: CourseWithSlots?) {
        _uiState.update { it.copy(inspectingCourse = courseWithSlots) }
    }

    fun openCategoryRulesDialog() {
        _uiState.update { it.copy(isCategoryRulesDialogOpen = true) }
    }

    fun closeCategoryRulesDialog() {
        _uiState.update { it.copy(isCategoryRulesDialogOpen = false) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    // Toggle Task Complete
    fun toggleTaskComplete(task: TaskEntity) {
        viewModelScope.launch {
            repository.toggleTaskComplete(task.id, task.isCompleted)
        }
    }

    // Save or Update Task
    fun saveTask(task: TaskEntity) {
        viewModelScope.launch {
            if (task.id == 0L) {
                repository.insertTask(task)
                _uiState.update { it.copy(userMessage = "待办已添加") }
            } else {
                repository.updateTask(task)
                _uiState.update { it.copy(userMessage = "待办已更新") }
            }
            closeTaskDialog()
        }
    }

    // Delete Task
    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
            _uiState.update { it.copy(userMessage = "待办已删除") }
        }
    }

    // Save or Update Course with multiple custom slots
    fun saveCourseWithSlots(course: CourseEntity, slots: List<CourseSlotEntity>) {
        viewModelScope.launch {
            repository.saveCourseWithSlots(course, slots)
            _uiState.update { it.copy(userMessage = "课程「${course.name}」已保存（包含 ${slots.size} 个上课时段）") }
            closeCourseDialog()
        }
    }

    // Delete Course
    fun deleteCourse(courseId: Long, courseName: String) {
        viewModelScope.launch {
            repository.deleteCourse(courseId)
            _uiState.update { it.copy(userMessage = "课程「$courseName」及对应时段与作业已删除", inspectingCourse = null) }
        }
    }

    // Prepare candidate tasks for preview
    fun openQuickGenerateDialog() {
        viewModelScope.launch {
            val candidates = repository.previewCandidateTasks(Calendar.getInstance())
            _uiState.update {
                it.copy(
                    candidateTasksForPreview = candidates,
                    isQuickGenerateDialogOpen = true
                )
            }
        }
    }

    fun closeQuickGenerateDialog() {
        _uiState.update { it.copy(isQuickGenerateDialogOpen = false) }
    }

    fun confirmGenerateTasks(selectedCandidates: List<TaskEntity>) {
        viewModelScope.launch {
            var count = 0
            for (candidate in selectedCandidates) {
                repository.insertTask(candidate)
                count++
            }
            closeQuickGenerateDialog()
            _uiState.update {
                it.copy(
                    userMessage = if (count > 0) "已成功根据今日课表生成 $count 项作业待办！" else "今日课表待办均已存在"
                )
            }
        }
    }

    fun generateTasksDirectlyForToday() {
        viewModelScope.launch {
            val generated = repository.generateTasksForDay(Calendar.getInstance())
            _uiState.update {
                it.copy(
                    userMessage = if (generated.isNotEmpty()) "根据今日课表智能生成了 ${generated.size} 项预习/课后作业！" else "今日课程对应待办均已生成，无需重复添加"
                )
            }
        }
    }

    fun updateCategoryRule(rule: CategoryRule) {
        viewModelScope.launch {
            repository.updateCategoryRule(rule)
            _uiState.update { it.copy(userMessage = "「${rule.category}」规则已保存") }
        }
    }

    fun resetDemoData() {
        viewModelScope.launch {
            repository.resetToDemoData()
            _uiState.update { it.copy(userMessage = "已重置为大学官方示例课表（支持一周多次与1-13节）与作业待办") }
        }
    }
}

class MainViewModelFactory(
    private val repository: CourseTodoRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
