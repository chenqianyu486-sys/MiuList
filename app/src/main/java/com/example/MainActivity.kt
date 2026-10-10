package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AppDatabase
import com.example.data.repository.CourseTodoRepository
import com.example.ui.components.CategoryRulesDialog
import com.example.ui.components.CourseEditDialog
import com.example.ui.components.CourseLinkageSheet
import com.example.ui.components.QuickGenerateDialog
import com.example.ui.components.TaskEditDialog
import com.example.ui.components.WeekPickerDialog
import com.example.ui.screens.ChecklistScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ScheduleScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.MainViewModelFactory
import com.example.ui.viewmodel.NavigationTab

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = CourseTodoRepository(
            database.courseDao(),
            database.courseSlotDao(),
            database.taskDao()
        )
        MainViewModelFactory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MiuList",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.3).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.openWeekPicker() }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("topbar_week_picker_button")
                        ) {
                            Text(
                                text = "第 ${state.currentWeek} 周 ▾",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                },
                actions = {
                    // Quick Generate Action with lake blue accent badge button
                    IconButton(
                        onClick = { viewModel.openQuickGenerateDialog() },
                        modifier = Modifier
                            .testTag("topbar_auto_generate_button")
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = "依据今日课表智能生成待办",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // Quick Add Task
                    IconButton(
                        onClick = { viewModel.openAddTaskDialog() },
                        modifier = Modifier
                            .testTag("topbar_add_task_button")
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "添加作业待办",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            ) {
                val navItemColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )

                NavigationBarItem(
                    selected = state.currentTab == NavigationTab.DASHBOARD,
                    onClick = { viewModel.setNavigationTab(NavigationTab.DASHBOARD) },
                    icon = {
                        Icon(
                            imageVector = if (state.currentTab == NavigationTab.DASHBOARD) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                            contentDescription = "总览看板"
                        )
                    },
                    label = { Text("总览", fontWeight = if (state.currentTab == NavigationTab.DASHBOARD) FontWeight.Bold else FontWeight.Medium) },
                    colors = navItemColors,
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )

                NavigationBarItem(
                    selected = state.currentTab == NavigationTab.SCHEDULE,
                    onClick = { viewModel.setNavigationTab(NavigationTab.SCHEDULE) },
                    icon = {
                        Icon(
                            imageVector = if (state.currentTab == NavigationTab.SCHEDULE) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth,
                            contentDescription = "日程课表"
                        )
                    },
                    label = { Text("日程课表", fontWeight = if (state.currentTab == NavigationTab.SCHEDULE) FontWeight.Bold else FontWeight.Medium) },
                    colors = navItemColors,
                    modifier = Modifier.testTag("nav_tab_schedule")
                )

                NavigationBarItem(
                    selected = state.currentTab == NavigationTab.CHECKLIST,
                    onClick = { viewModel.setNavigationTab(NavigationTab.CHECKLIST) },
                    icon = {
                        Icon(
                            imageVector = if (state.currentTab == NavigationTab.CHECKLIST) Icons.Filled.Checklist else Icons.Outlined.Checklist,
                            contentDescription = "分类清单"
                        )
                    },
                    label = { Text("分类清单", fontWeight = if (state.currentTab == NavigationTab.CHECKLIST) FontWeight.Bold else FontWeight.Medium) },
                    colors = navItemColors,
                    modifier = Modifier.testTag("nav_tab_checklist")
                )

                NavigationBarItem(
                    selected = state.currentTab == NavigationTab.SETTINGS,
                    onClick = { viewModel.setNavigationTab(NavigationTab.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = if (state.currentTab == NavigationTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "课程与规则"
                        )
                    },
                    label = { Text("课程与规则", fontWeight = if (state.currentTab == NavigationTab.SETTINGS) FontWeight.Bold else FontWeight.Medium) },
                    colors = navItemColors,
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        },
        floatingActionButton = {
            if (state.currentTab == NavigationTab.CHECKLIST || state.currentTab == NavigationTab.SCHEDULE) {
                FloatingActionButton(
                    onClick = { viewModel.openAddTaskDialog() },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 4.dp
                    ),
                    modifier = Modifier.testTag("fab_add_task")
                ) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = "新增作业待办")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (state.currentTab) {
                    NavigationTab.DASHBOARD -> {
                        DashboardScreen(
                            state = state,
                            onNavigateTab = { viewModel.setNavigationTab(it) },
                            onGenerateTodayTasks = { viewModel.generateTasksDirectlyForToday() },
                            onPreviewGenerateTasks = { viewModel.openQuickGenerateDialog() },
                            onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                            onEditTask = { viewModel.openEditTaskDialog(it) },
                            onDeleteTask = { viewModel.deleteTask(it) },
                            onInspectCourse = { viewModel.inspectCourse(it) },
                            onOpenWeekPicker = { viewModel.openWeekPicker() }
                        )
                    }

                    NavigationTab.SCHEDULE -> {
                        ScheduleScreen(
                            state = state,
                            onSelectDay = { viewModel.setSelectedScheduleDay(it) },
                            onSelectViewMode = { viewModel.setScheduleViewMode(it) },
                            onInspectCourse = { viewModel.inspectCourse(it) },
                            onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                            onEditTask = { viewModel.openEditTaskDialog(it) },
                            onDeleteTask = { viewModel.deleteTask(it) },
                            onAddNewTask = { courseId -> viewModel.openAddTaskDialog(courseId) },
                            onGenerateTodayTasks = { viewModel.generateTasksDirectlyForToday() }
                        )
                    }

                    NavigationTab.CHECKLIST -> {
                        ChecklistScreen(
                            state = state,
                            onSearchChange = { viewModel.setSearchQuery(it) },
                            onSelectTaskType = { viewModel.setSelectedTaskType(it) },
                            onSelectGroupingMode = { viewModel.setGroupingMode(it) },
                            onSelectStatusFilter = { viewModel.setStatusFilter(it) },
                            onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                            onEditTask = { viewModel.openEditTaskDialog(it) },
                            onDeleteTask = { viewModel.deleteTask(it) }
                        )
                    }

                    NavigationTab.SETTINGS -> {
                        SettingsScreen(
                            state = state,
                            onOpenCategoryRulesDialog = { viewModel.openCategoryRulesDialog() },
                            onOpenAddCourseDialog = { viewModel.openAddCourseDialog() },
                            onEditCourse = { viewModel.openEditCourseDialog(it) },
                            onDeleteCourse = { id, name -> viewModel.deleteCourse(id, name) },
                            onResetDemoData = { viewModel.resetDemoData() }
                        )
                    }
                }
            }
        }
    }

    // Task Edit/Add Dialog
    if (state.isAddTaskDialogOpen) {
        TaskEditDialog(
            initialTask = state.editingTask,
            courses = state.courses,
            preselectedCourseId = state.preselectedCourseIdForNewTask,
            onDismiss = { viewModel.closeTaskDialog() },
            onSave = { viewModel.saveTask(it) }
        )
    }

    // Course Edit/Add Dialog (supports multi-slot and 1-13 periods)
    if (state.isAddCourseDialogOpen) {
        CourseEditDialog(
            initialCourseWithSlots = state.editingCourseWithSlots,
            onDismiss = { viewModel.closeCourseDialog() },
            onSave = { course, slots -> viewModel.saveCourseWithSlots(course, slots) }
        )
    }

    // Quick Generate Dialog
    if (state.isQuickGenerateDialogOpen) {
        QuickGenerateDialog(
            candidateTasks = state.candidateTasksForPreview,
            onDismiss = { viewModel.closeQuickGenerateDialog() },
            onConfirm = { viewModel.confirmGenerateTasks(it) }
        )
    }

    // Inspect Course Linkage Sheet
    state.inspectingCourse?.let { courseWithSlots ->
        val linkedTasks = state.tasks.filter { it.courseId == courseWithSlots.course.id }
        CourseLinkageSheet(
            courseWithSlots = courseWithSlots,
            linkedTasks = linkedTasks,
            onDismiss = { viewModel.inspectCourse(null) },
            onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
            onAddNewTaskForCourse = {
                val cId = courseWithSlots.course.id
                viewModel.inspectCourse(null)
                viewModel.openAddTaskDialog(cId)
            },
            onEditCourse = {
                viewModel.inspectCourse(null)
                viewModel.openEditCourseDialog(courseWithSlots)
            },
            onDeleteCourse = {
                viewModel.deleteCourse(courseWithSlots.course.id, courseWithSlots.course.name)
            }
        )
    }

    // Category Rules Configuration Dialog
    if (state.isCategoryRulesDialogOpen) {
        CategoryRulesDialog(
            rules = state.categoryRules,
            onDismiss = { viewModel.closeCategoryRulesDialog() },
            onSaveRule = { viewModel.updateCategoryRule(it) }
        )
    }

    // Week Picker Dialog
    if (state.isWeekPickerDialogOpen) {
        WeekPickerDialog(
            currentWeek = state.currentWeek,
            onDismiss = { viewModel.closeWeekPicker() },
            onSelectWeek = { viewModel.setCurrentWeek(it) }
        )
    }
}
