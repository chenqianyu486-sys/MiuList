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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.di.AppContainer
import com.example.ui.theme.LocalGlassHazeState
import com.example.ui.components.AcademicCalendarDialog
import com.example.ui.components.CategoryRulesDialog
import com.example.ui.components.CourseEditDialog
import com.example.ui.components.CourseLinkageSheet
import com.example.ui.components.FloatingIslandNav
import com.example.ui.components.IslandBottomInset
import com.example.ui.components.DeleteTaskTypeConfirmDialog
import com.example.ui.components.NewTaskTypeDialog
import com.example.ui.components.QuickGenerateDialog
import com.example.ui.components.ScheduleTransferDialog
import com.example.ui.components.TaskDetailSheet
import com.example.ui.components.TaskEditDialog
import com.example.ui.components.TaskRulesDialog
import com.example.ui.components.WeekPickerDialog
import com.example.ui.screens.ChecklistScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ScheduleScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AppBackgroundLayer
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ImportSource
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.MainViewModelFactory
import com.example.ui.viewmodel.NavigationTab
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        // 依赖统一从 Application 持有的容器取，界面不再自己拼装数据库与 Repository
        val container = (application as MiuListApplication).container
        MainViewModelFactory(container)
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

    // haze：整页内容当模糊源。浮岛导航是压在内容之上的，
    // 得把从它下面滚过去的卡片真糊掉，而不只是糊页面底那张渐变。
    // haze 1.3.1 没有 rememberHazeState() 了，直接 remember 一个 HazeState。
    val hazeState = remember { HazeState() }

    AppBackgroundLayer {
        CompositionLocalProvider(LocalGlassHazeState provides hazeState) {
        Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 不铺不透明底，让 Mesh Gradient 露出来
        containerColor = Color.Transparent,
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
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.openWeekPicker() }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("topbar_week_picker_button")
                        ) {
                            Text(
                                text = "第 ${state.currentWeek} 周 ▾",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                },
                actions = {
                    // Quick Generate Action with lake blue accent badge button
                    IconButton(
                        onClick = { viewModel.openQuickGenerateWeekDialog() },
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
                                contentDescription = "生成今日待办",
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
                                contentDescription = "添加待办",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // 顶栏不再铺一层不透明面，页面渐变直接透上来
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // hazeSource 和 hazeEffect 必须是兄弟节点，不能互相嵌套：
            // 源层要先被合成进父节点，模糊节点才拿得到那份已完成的纹理。
            // 把 hazeEffect 塞进 hazeSource 内部会变成自绘反馈，
            // 结果是「一点都糊不掉」，只剩一层白纱盖在身后的卡片上。
            //
            // 这里刻意不给底部留 92dp：内容要能滚到浮岛导航底下去，
            // 浮岛才真的是毛玻璃，而不是浮在一片空背景上。
            // 收尾靠各页滚动容器自己的 contentPadding（IslandBottomInset）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // 页面底由外层 MeshBackgroundLayer 提供：它同时把那张网格渐变
                    // 下发给所有玻璃面板做屏幕对齐重绘
                    .hazeSource(hazeState)
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
                            onGenerateTodayTasks = { viewModel.generateTasksDirectlyForWeek() },
                            onPreviewGenerateTasks = { viewModel.openQuickGenerateWeekDialog() },
                            onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                            onInspectTask = { viewModel.inspectTask(it) },
                            onInspectCourse = { viewModel.inspectCourse(it) },
                            onOpenWeekPicker = { viewModel.openWeekPicker() }
                        )
                    }

                    NavigationTab.SCHEDULE -> {
                        ScheduleScreen(
                            state = state,
                            onSelectViewMode = { viewModel.setScheduleViewMode(it) },
                            onInspectCourse = { viewModel.inspectCourse(it) },
                            onGenerateTodayTasks = { viewModel.generateTasksDirectlyForWeek() }
                        )
                    }

                    NavigationTab.CHECKLIST -> {
                        ChecklistScreen(
                            state = state,
                            onSearchChange = { viewModel.setSearchQuery(it) },
                            onSelectTaskTypeKey = { viewModel.setSelectedTaskType(it) },
                            onOpenNewTypeDialog = { viewModel.openNewTaskTypeDialog() },
                            onConfirmDeleteType = { viewModel.confirmDeleteTaskType(it) },
                            onSelectGroupingMode = { viewModel.setGroupingMode(it) },
                            onSelectStatusFilter = { viewModel.setStatusFilter(it) },
                            onToggleTaskComplete = { viewModel.toggleTaskComplete(it) },
                            onInspectTask = { viewModel.inspectTask(it) }
                        )
                    }

                    NavigationTab.SETTINGS -> {
                        SettingsScreen(
                            state = state,
                            onOpenCategoryRulesDialog = { viewModel.openCategoryRulesDialog() },
                            onOpenTaskRulesDialog = { viewModel.openTaskRulesDialog() },
                            onOpenAddCourseDialog = { viewModel.openAddCourseDialog() },
                            onEditCourse = { viewModel.openEditCourseDialog(it) },
                            onDeleteCourse = { id, name -> viewModel.deleteCourse(id, name) },
                            onOpenTransferDialog = { viewModel.openTransferDialog() },
                            onOpenAcademicCalendarDialog = { viewModel.openAcademicCalendarDialog() },
                            onResetDemoData = { viewModel.resetDemoData() }
                        )
                    }
                }
            }
            }

            // 底部浮岛导航：悬浮在内容之上，不占布局高度
            FloatingIslandNav(
                current = state.currentTab,
                onSelect = viewModel::setNavigationTab,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // FAB 抬到浮岛之上，别和岛叠在一起
            if (state.currentTab == NavigationTab.CHECKLIST || state.currentTab == NavigationTab.SCHEDULE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        // 底部跟着 IslandBottomInset 走，别写死数字：
                        // 岛一改高度，这里写死的值就会悄悄和岛错开。
                        .padding(end = 24.dp, bottom = IslandBottomInset)
                ) {
                    FloatingActionButton(
                        onClick = { viewModel.openAddTaskDialog() },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                            defaultElevation = 6.dp,
                            pressedElevation = 10.dp
                        ),
                        modifier = Modifier.testTag("fab_add_task")
                    ) {
                        Icon(imageVector = Icons.Outlined.Add, contentDescription = "添加待办")
                    }
                }
            }
        }
    }
        }
    }

    // 单条待办详情弹层：卡片上点一下进来，编辑/删除都在这里
    state.inspectingTask?.let { task ->
        TaskDetailSheet(
            task = task,
            customTypes = state.customTaskTypes,
            onDismiss = { viewModel.closeTaskSheet() },
            onEdit = { viewModel.editTaskFromSheet(task) },
            // 删完就把弹层收掉，否则它会停在一个已经不存在的待办上
            onDelete = {
                viewModel.closeTaskSheet()
                viewModel.deleteTask(task)
            }
        )
    }

    // Task Edit/Add Dialog
    if (state.isAddTaskDialogOpen) {
        TaskEditDialog(
            initialTask = state.editingTask,
            courses = state.courses,
            customTypes = state.customTaskTypes,
            preselectedCourseId = state.preselectedCourseIdForNewTask,
            onDismiss = { viewModel.closeTaskDialog() },
            onCreateTaskType = { viewModel.openNewTaskTypeDialog() },
            onSave = { viewModel.saveTask(it) }
        )
    }

    // 新建待办类型。放在编辑弹层**之上**：类型是在「给一条待办选类型」的
    // 过程中被意识到缺的，所以建完必须能直接回到那一步，不该把编辑弹层一起关掉。
    if (state.isNewTaskTypeDialogOpen) {
        NewTaskTypeDialog(
            onDismiss = { viewModel.closeNewTaskTypeDialog() },
            onCreate = { label, paletteIndex -> viewModel.createTaskType(label, paletteIndex) }
        )
    }

    state.deletingTaskType?.let { type ->
        DeleteTaskTypeConfirmDialog(
            typeLabel = type.label,
            onDismiss = { viewModel.cancelDeleteTaskType() },
            onConfirm = { viewModel.deleteTaskType(type) }
        )
    }

    // Course Edit/Add Dialog (supports multi-slot, data-driven periods and week sets)
    if (state.isAddCourseDialogOpen) {
        CourseEditDialog(
            initialCourseWithSlots = state.editingCourseWithSlots,
            periodTable = state.periodTable,
            initialSlotWeeks = state.editingCourseWithSlots?.slots
                ?.associate { it.id to (state.slotWeeks[it.id] ?: emptySet()) }
                ?: emptyMap(),
            totalWeeks = state.totalWeeks,
            onDismiss = { viewModel.closeCourseDialog() },
            onSave = { course, slots -> viewModel.saveCourseWithSlots(course, slots) }
        )
    }

    // 课表导入 / 导出
    if (state.isTransferDialogOpen) {
        val contentResolver = LocalContext.current.contentResolver
        ScheduleTransferDialog(
            strategy = state.importStrategy,
            importSource = state.importSource,
            zjuTermOptions = state.zjuTermOptions,
            zjuError = state.zjuError,
            isZjuRunning = state.isZjuImportRunning,
            onSourceChange = { source ->
                viewModel.setImportSource(source)
                if (source == ImportSource.ZJU) viewModel.loadZjuTerms()
            },
            onZjuImport = { id, pwd, option -> viewModel.importFromZju(id, pwd, option) },
            preview = state.importPreview,
            issues = state.importIssues,
            errorMessage = state.importError,
            isBusy = state.isImporting,
            report = state.importReport,
            onStrategyChange = { viewModel.setImportStrategy(it) },
            onExport = { uri -> viewModel.exportSchedule(uri, contentResolver) },
            onImportUri = { uri -> viewModel.importFromUri(uri, contentResolver) },
            onCommit = { viewModel.commitImport() },
            onDismissReport = { viewModel.dismissImportReport() },
            onDismiss = { viewModel.closeTransferDialog() }
        )
    }

    // 本学期校历
    if (state.isAcademicCalendarDialogOpen) {
        AcademicCalendarDialog(
            semester = state.semester,
            periodTable = state.periodTable,
            existingNoClass = state.noClassDays,
            existingMakeup = state.makeupDays,
            onSave = { sem, periods, noClass, makeup ->
                viewModel.saveAcademicCalendar(sem, periods, noClass, makeup)
            },
            onDismiss = { viewModel.closeAcademicCalendarDialog() }
        )
    }

    // Quick Generate Dialog
    if (state.isQuickGenerateDialogOpen) {
        QuickGenerateDialog(
            candidateTasks = state.candidateTasksForPreview,
            week = state.selectedWeek,
            customTypes = state.customTaskTypes,
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
            customTypes = state.customTaskTypes,
            periodTable = state.periodTable,
            slotWeeks = state.slotWeeks,
            totalWeeks = state.totalWeeks,
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

    // 课程 × 类型的生成规则
    if (state.isTaskRulesDialogOpen) {
        TaskRulesDialog(
            courses = state.courses,
            taskRules = state.taskRules,
            onDismiss = { viewModel.closeTaskRulesDialog() },
            onSaveRule = { courseId, type, spec ->
                viewModel.saveTaskRule(courseId, type, spec)
            },
            onToggleEnabled = { courseId, type, enabled ->
                viewModel.setCourseTaskEnabled(courseId, type, enabled)
            }
        )
    }

    // Week Picker Dialog
    if (state.isWeekPickerDialogOpen) {
        WeekPickerDialog(
            currentWeek = state.currentWeek,
            onDismiss = { viewModel.closeWeekPicker() },
            onSelectWeek = { viewModel.selectWeek(it) }
        )
    }
}
