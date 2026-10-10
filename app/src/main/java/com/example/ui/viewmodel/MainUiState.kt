package com.example.ui.viewmodel

import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskType

enum class NavigationTab(val title: String) {
    DASHBOARD("总览看板"),
    SCHEDULE("日程课表"),
    CHECKLIST("分类清单"),
    SETTINGS("课程与规则")
}

enum class TaskGroupingMode(val label: String) {
    BY_COURSE_TIME("按课程时间(开始)"),
    BY_COURSE("按课程分类"),
    BY_URGENCY("按截止时间"),
    BY_TYPE("按任务类型"),
    BY_CATEGORY("按课程属性")
}

enum class ScheduleViewMode(val label: String) {
    WEEK_GRID("周课表视图(1-13节)"),
    DAY_TIMELINE("今日日程联动")
}

enum class TaskStatusFilter(val label: String) {
    ALL("全部任务"),
    PENDING("待完成"),
    COMPLETED("已完成")
}

data class MainUiState(
    val currentTab: NavigationTab = NavigationTab.DASHBOARD,
    val currentWeek: Int = 6,
    val courses: List<CourseEntity> = emptyList(),
    val courseSlots: List<CourseSlotEntity> = emptyList(),
    val coursesWithSlots: List<CourseWithSlots> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val categoryRules: List<CategoryRule> = emptyList(),
    val isLoading: Boolean = false,

    // Filter & Presentation options
    val searchQuery: String = "",
    val selectedTaskType: TaskType? = null,
    val groupingMode: TaskGroupingMode = TaskGroupingMode.BY_COURSE,
    val statusFilter: TaskStatusFilter = TaskStatusFilter.ALL,
    val selectedScheduleDay: Int = 1, // 1 = Monday ... 7 = Sunday
    val scheduleViewMode: ScheduleViewMode = ScheduleViewMode.WEEK_GRID,

    // Dialog & Sheet states
    val isAddTaskDialogOpen: Boolean = false,
    val preselectedCourseIdForNewTask: Long? = null,
    val editingTask: TaskEntity? = null,

    val isAddCourseDialogOpen: Boolean = false,
    val editingCourseWithSlots: CourseWithSlots? = null,

    val inspectingCourse: CourseWithSlots? = null, // for detailed course + slots + linked tasks sheet
    val candidateTasksForPreview: List<TaskEntity> = emptyList(),
    val isQuickGenerateDialogOpen: Boolean = false,

    val isCategoryRulesDialogOpen: Boolean = false,
    val isWeekPickerDialogOpen: Boolean = false,

    val userMessage: String? = null
)
