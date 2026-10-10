package com.example.data.repository

import com.example.data.local.CourseDao
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotDao
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskDao
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskPriority
import com.example.data.model.TaskType
import com.example.data.sample.SampleDataProvider
import com.example.ui.components.ALL_PERIODS
import com.example.ui.components.formatPeriodRange
import com.example.ui.components.getDayName
import com.example.ui.components.getPeriodTimeRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CourseTodoRepository(
    private val courseDao: CourseDao,
    private val courseSlotDao: CourseSlotDao,
    private val taskDao: TaskDao
) {
    private val _currentWeek = MutableStateFlow(6)
    val currentWeek = _currentWeek.asStateFlow()

    private val _categoryRules = MutableStateFlow(
        listOf(
            CategoryRule("公共基础", autoPreStudy = true, autoPostHomework = true, autoLabReport = false, defaultPostDays = 3, defaultMinutes = 45),
            CategoryRule("专业必修", autoPreStudy = true, autoPostHomework = true, autoLabReport = false, defaultPostDays = 4, defaultMinutes = 60),
            CategoryRule("实验实践", autoPreStudy = true, autoPostHomework = false, autoLabReport = true, defaultPostDays = 5, defaultMinutes = 90),
            CategoryRule("通识公共", autoPreStudy = true, autoPostHomework = true, autoLabReport = false, defaultPostDays = 3, defaultMinutes = 30),
            CategoryRule("专业选修", autoPreStudy = true, autoPostHomework = true, autoLabReport = false, defaultPostDays = 4, defaultMinutes = 45)
        )
    )
    val categoryRules = _categoryRules.asStateFlow()

    fun getAllCourses(): Flow<List<CourseEntity>> = courseDao.getAllCourses()

    fun getAllCoursesWithSlots(): Flow<List<CourseWithSlots>> = courseDao.getAllCoursesWithSlots()

    fun getAllSlots(): Flow<List<CourseSlotEntity>> = courseSlotDao.getAllSlots()

    fun getSlotsByDay(dayOfWeek: Int): Flow<List<CourseSlotEntity>> = courseSlotDao.getSlotsByDay(dayOfWeek)

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    fun getTasksByCourse(courseId: Long): Flow<List<TaskEntity>> = taskDao.getTasksByCourse(courseId)

    fun setCurrentWeek(week: Int) {
        _currentWeek.value = week.coerceIn(1, 25)
    }

    suspend fun initializeIfEmpty() {
        val existing = courseDao.getAllCourses().firstOrNull()
        if (existing.isNullOrEmpty()) {
            resetToDemoData()
        }
    }

    suspend fun resetToDemoData() {
        taskDao.clearTasks()
        courseSlotDao.clearSlots()
        courseDao.clearCourses()
        courseDao.insertCourses(SampleDataProvider.sampleCourses)
        courseSlotDao.insertSlots(SampleDataProvider.sampleCourseSlots)
        taskDao.insertTasks(SampleDataProvider.createSampleTasks())
    }

    fun updateCategoryRule(updated: CategoryRule) {
        val current = _categoryRules.value.toMutableList()
        val index = current.indexOfFirst { it.category == updated.category }
        if (index >= 0) {
            current[index] = updated
            _categoryRules.value = current
        }
    }

    // Full CRUD for Courses with multi-slots
    suspend fun saveCourseWithSlots(course: CourseEntity, slots: List<CourseSlotEntity>): Long {
        return if (course.id == 0L) {
            val courseId = courseDao.insertCourse(course)
            val slotsWithCourseId = slots.map { it.copy(courseId = courseId) }
            courseSlotDao.insertSlots(slotsWithCourseId)
            courseId
        } else {
            courseDao.updateCourse(course)
            courseSlotDao.deleteSlotsByCourseId(course.id)
            val slotsWithCourseId = slots.map { it.copy(id = 0, courseId = course.id) }
            courseSlotDao.insertSlots(slotsWithCourseId)
            course.id
        }
    }

    suspend fun deleteCourse(courseId: Long) {
        taskDao.deleteTasksByCourseId(courseId)
        courseSlotDao.deleteSlotsByCourseId(courseId)
        courseDao.deleteCourseById(courseId)
    }

    // Full CRUD for Tasks
    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)

    suspend fun toggleTaskComplete(id: Long, currentStatus: Boolean) {
        val newStatus = !currentStatus
        val completedAt = if (newStatus) System.currentTimeMillis() else null
        taskDao.updateTaskCompletion(id, newStatus, completedAt)
    }

    /**
     * Core Automation Engine:
     * Generates to-dos automatically based on the courses scheduled on [targetCalendar].
     * Sets startTimeTimestamp and startTimeString based on the course start time (作业开始时间).
     */
    suspend fun generateTasksForDay(
        targetCalendar: Calendar,
        courseIdFilter: Long? = null
    ): List<TaskEntity> {
        val dayOfWeekIndex = targetCalendar.get(Calendar.DAY_OF_WEEK)
        val appDayOfWeek = if (dayOfWeekIndex == Calendar.SUNDAY) 7 else dayOfWeekIndex - 1

        val slotsToday = courseSlotDao.getSlotsByDaySync(appDayOfWeek)
        val filteredSlots = if (courseIdFilter != null) {
            slotsToday.filter { it.courseId == courseIdFilter }
        } else {
            slotsToday
        }

        val courseIdsToday = filteredSlots.map { it.courseId }.distinct()

        val dateOnlySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateString = dateOnlySdf.format(targetCalendar.time)
        val timeSdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val generatedList = mutableListOf<TaskEntity>()

        for (cId in courseIdsToday) {
            val course = courseDao.getCourseById(cId) ?: continue
            val slotsForCourseToday = filteredSlots.filter { it.courseId == cId }.sortedBy { it.startPeriod }
            val earliestSlot = slotsForCourseToday.firstOrNull() ?: continue
            val classroom = earliestSlot.classroom.ifBlank { course.classroom }

            val (startTimeStr, _) = getPeriodTimeRange(earliestSlot.startPeriod, earliestSlot.endPeriod)
            val startCal = targetCalendar.clone() as Calendar
            val parts = startTimeStr.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            startCal.set(Calendar.HOUR_OF_DAY, hour)
            startCal.set(Calendar.MINUTE, minute)
            startCal.set(Calendar.SECOND, 0)
            val startTimestamp = startCal.timeInMillis
            val startPeriodLabel = "${getDayName(earliestSlot.dayOfWeek)} $startTimeStr (${formatPeriodRange(earliestSlot.startPeriod, earliestSlot.endPeriod)})"

            // 1. Pre-study todo
            if (course.autoPreStudy) {
                val existing = taskDao.findExistingAutoTask(course.id, TaskType.PRE_STUDY.name, dateString)
                if (existing == null) {
                    val task = TaskEntity(
                        courseId = course.id,
                        courseName = course.name,
                        courseCategory = course.category,
                        courseColorHex = course.colorHex,
                        title = "【课前预习】${course.name} 第${earliestSlot.startPeriod}-${earliestSlot.endPeriod}节 核心概念预备",
                        description = "在${classroom}上课（${course.teacher}）前完成教材对应小节与预习思考题",
                        taskType = TaskType.PRE_STUDY.name,
                        startTimeTimestamp = startTimestamp,
                        startTimeString = startPeriodLabel,
                        dueDateTimestamp = startTimestamp,
                        dueDateString = timeSdf.format(startCal.time),
                        estimatedMinutes = course.preStudyEstimatedMinutes,
                        priority = TaskPriority.HIGH.name,
                        isCompleted = false,
                        isAutoGenerated = true,
                        generatedDate = dateString
                    )
                    taskDao.insertTask(task)
                    generatedList.add(task)
                }
            }

            // 2. Post-class homework todo
            if (course.autoPostHomework) {
                val existing = taskDao.findExistingAutoTask(course.id, TaskType.POST_CLASS.name, dateString)
                if (existing == null) {
                    val postCal = targetCalendar.clone() as Calendar
                    postCal.add(Calendar.DAY_OF_YEAR, course.defaultPostDueDays)
                    postCal.set(Calendar.HOUR_OF_DAY, 23)
                    postCal.set(Calendar.MINUTE, 59)
                    postCal.set(Calendar.SECOND, 0)

                    val task = TaskEntity(
                        courseId = course.id,
                        courseName = course.name,
                        courseCategory = course.category,
                        courseColorHex = course.colorHex,
                        title = "【课后作业】${course.name} 习题巩固与知识整理",
                        description = "梳理本次课堂重点板书内容，独立完成课后作业并在截止前按时提交",
                        taskType = TaskType.POST_CLASS.name,
                        startTimeTimestamp = startTimestamp,
                        startTimeString = startPeriodLabel,
                        dueDateTimestamp = postCal.timeInMillis,
                        dueDateString = timeSdf.format(postCal.time),
                        estimatedMinutes = course.postHomeworkEstimatedMinutes,
                        priority = TaskPriority.MEDIUM.name,
                        isCompleted = false,
                        isAutoGenerated = true,
                        generatedDate = dateString
                    )
                    taskDao.insertTask(task)
                    generatedList.add(task)
                }
            }

            // 3. Lab report todo
            if (course.autoLabReport) {
                val existing = taskDao.findExistingAutoTask(course.id, TaskType.LAB_REPORT.name, dateString)
                if (existing == null) {
                    val labCal = targetCalendar.clone() as Calendar
                    labCal.add(Calendar.DAY_OF_YEAR, course.defaultPostDueDays + 2)
                    labCal.set(Calendar.HOUR_OF_DAY, 23)
                    labCal.set(Calendar.MINUTE, 59)
                    labCal.set(Calendar.SECOND, 0)

                    val task = TaskEntity(
                        courseId = course.id,
                        courseName = course.name,
                        courseCategory = course.category,
                        courseColorHex = course.colorHex,
                        title = "【实验报告】${course.name} 实验数据记录与分析报告",
                        description = "根据${classroom}实验操作记录，整理测试用例与分析报告并提交系统",
                        taskType = TaskType.LAB_REPORT.name,
                        startTimeTimestamp = startTimestamp,
                        startTimeString = startPeriodLabel,
                        dueDateTimestamp = labCal.timeInMillis,
                        dueDateString = timeSdf.format(labCal.time),
                        estimatedMinutes = course.labReportEstimatedMinutes,
                        priority = TaskPriority.HIGH.name,
                        isCompleted = false,
                        isAutoGenerated = true,
                        generatedDate = dateString
                    )
                    taskDao.insertTask(task)
                    generatedList.add(task)
                }
            }
        }

        return generatedList
    }

    /**
     * Preview candidate tasks that can be generated for a day without committing.
     */
    suspend fun previewCandidateTasks(targetCalendar: Calendar): List<TaskEntity> {
        val dayOfWeekIndex = targetCalendar.get(Calendar.DAY_OF_WEEK)
        val appDayOfWeek = if (dayOfWeekIndex == Calendar.SUNDAY) 7 else dayOfWeekIndex - 1
        val slotsToday = courseSlotDao.getSlotsByDaySync(appDayOfWeek)
        val courseIdsToday = slotsToday.map { it.courseId }.distinct()

        val dateOnlySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateString = dateOnlySdf.format(targetCalendar.time)
        val timeSdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val candidates = mutableListOf<TaskEntity>()

        for (cId in courseIdsToday) {
            val course = courseDao.getCourseById(cId) ?: continue
            val slotsForCourseToday = slotsToday.filter { it.courseId == cId }.sortedBy { it.startPeriod }
            val earliestSlot = slotsForCourseToday.firstOrNull() ?: continue
            val classroom = earliestSlot.classroom.ifBlank { course.classroom }
            val (startTimeStr, _) = getPeriodTimeRange(earliestSlot.startPeriod, earliestSlot.endPeriod)

            val startCal = targetCalendar.clone() as Calendar
            val parts = startTimeStr.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            startCal.set(Calendar.HOUR_OF_DAY, hour)
            startCal.set(Calendar.MINUTE, minute)
            startCal.set(Calendar.SECOND, 0)
            val startTimestamp = startCal.timeInMillis
            val startPeriodLabel = "${getDayName(earliestSlot.dayOfWeek)} $startTimeStr (${formatPeriodRange(earliestSlot.startPeriod, earliestSlot.endPeriod)})"

            if (course.autoPreStudy) {
                val existing = taskDao.findExistingAutoTask(course.id, TaskType.PRE_STUDY.name, dateString)
                if (existing == null) {
                    candidates.add(
                        TaskEntity(
                            courseId = course.id,
                            courseName = course.name,
                            courseCategory = course.category,
                            courseColorHex = course.colorHex,
                            title = "【课前预习】${course.name} 第${earliestSlot.startPeriod}-${earliestSlot.endPeriod}节 核心概念预备",
                            description = "在${classroom}上课（${course.teacher}）前完成教材对应小节与预习思考题",
                            taskType = TaskType.PRE_STUDY.name,
                            startTimeTimestamp = startTimestamp,
                            startTimeString = startPeriodLabel,
                            dueDateTimestamp = startTimestamp,
                            dueDateString = timeSdf.format(startCal.time),
                            estimatedMinutes = course.preStudyEstimatedMinutes,
                            priority = TaskPriority.HIGH.name,
                            isCompleted = false,
                            isAutoGenerated = true,
                            generatedDate = dateString
                        )
                    )
                }
            }
            if (course.autoPostHomework) {
                val existing = taskDao.findExistingAutoTask(course.id, TaskType.POST_CLASS.name, dateString)
                if (existing == null) {
                    val postCal = targetCalendar.clone() as Calendar
                    postCal.add(Calendar.DAY_OF_YEAR, course.defaultPostDueDays)
                    postCal.set(Calendar.HOUR_OF_DAY, 23)
                    postCal.set(Calendar.MINUTE, 59)
                    postCal.set(Calendar.SECOND, 0)

                    candidates.add(
                        TaskEntity(
                            courseId = course.id,
                            courseName = course.name,
                            courseCategory = course.category,
                            courseColorHex = course.colorHex,
                            title = "【课后作业】${course.name} 习题巩固与知识整理",
                            description = "梳理本次课堂重点板书内容，独立完成课后作业并在截止前按时提交",
                            taskType = TaskType.POST_CLASS.name,
                            startTimeTimestamp = startTimestamp,
                            startTimeString = startPeriodLabel,
                            dueDateTimestamp = postCal.timeInMillis,
                            dueDateString = timeSdf.format(postCal.time),
                            estimatedMinutes = course.postHomeworkEstimatedMinutes,
                            priority = TaskPriority.MEDIUM.name,
                            isCompleted = false,
                            isAutoGenerated = true,
                            generatedDate = dateString
                        )
                    )
                }
            }
            if (course.autoLabReport) {
                val existing = taskDao.findExistingAutoTask(course.id, TaskType.LAB_REPORT.name, dateString)
                if (existing == null) {
                    val labCal = targetCalendar.clone() as Calendar
                    labCal.add(Calendar.DAY_OF_YEAR, course.defaultPostDueDays + 2)
                    labCal.set(Calendar.HOUR_OF_DAY, 23)
                    labCal.set(Calendar.MINUTE, 59)
                    labCal.set(Calendar.SECOND, 0)

                    candidates.add(
                        TaskEntity(
                            courseId = course.id,
                            courseName = course.name,
                            courseCategory = course.category,
                            courseColorHex = course.colorHex,
                            title = "【实验报告】${course.name} 实验数据记录与分析报告",
                            description = "根据${classroom}实验操作记录，整理测试用例与分析报告并提交系统",
                            taskType = TaskType.LAB_REPORT.name,
                            startTimeTimestamp = startTimestamp,
                            startTimeString = startPeriodLabel,
                            dueDateTimestamp = labCal.timeInMillis,
                            dueDateString = timeSdf.format(labCal.time),
                            estimatedMinutes = course.labReportEstimatedMinutes,
                            priority = TaskPriority.HIGH.name,
                            isCompleted = false,
                            isAutoGenerated = true,
                            generatedDate = dateString
                        )
                    )
                }
            }
        }

        return candidates
    }
}
