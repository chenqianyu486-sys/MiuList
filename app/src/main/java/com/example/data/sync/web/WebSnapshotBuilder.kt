package com.example.data.sync.web

import com.example.data.calendar.AppZone
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.data.model.TaskTypes
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ExamRepository
import com.example.data.repository.TaskRepository
import com.example.data.repository.TaskTypeRepository
import kotlinx.coroutines.flow.first

/**
 * 把 Room 里的数据拼成网页端要的**一份快照**。
 *
 * ## 为什么是「整份」而不是「增量」
 *
 * 增量协议（带游标、带 delta）要处理漏收、乱序、游标失效，
 * 而这里的规模是**一个人自己的待办**：几百条记录、几十门课。
 * 整份快照的体积也就几十 KB，而局域网传输几十 KB 只要几毫秒。
 *
 * 用整份换来的是**不会错**——网页端不需要维护任何状态机，
 * 收到什么就显示什么，重连时也不用「接着上次那条 delta 往下拼」。
 *
 * 真要省流量的时候，正确的方向是**压缩**（gzip）而不是**增量**。
 *
 * ## 为什么「今天的课」在服务端算
 *
 * 「今天哪些课」这件事牵扯开课周次、停课日、补课日、学期范围，
 * 而且每一条都有坑（见 `ScheduleResolver` 的注释 —— 周末不是闸门、
 * 补课日的周次归属来源日、停课与让出是两回事）。
 *
 * 这些逻辑**已经在 App 里有一份实现**了。在网页端重写一份，
 * 两边迟早会给出不同的答案，而用户会以为是网页端算错了。
 * 所以这里直接调 [ScheduleResolver.slotsOn]，
 * 网页端拿到的就是 App 总览页会显示的那一套。
 *
 * ## 周课表同理，而且理由更硬
 *
 * 周课表的一列是「这一周里的一个**真实日期**」，不是「星期几」——
 * 补课只有在这一层才表达得出来（10-17 周六出周五的课，10-16 周五反而空着）。
 * 网页端没有校历（学期、停课、补课都不在同步范围内），
 * 让它自己算等于让它**猜**。所以整张周表在这里算好发过去，
 * 网页端只负责画 —— 见 [WebTimetableBuilder]。
 */
class WebSnapshotBuilder(
    private val taskRepo: TaskRepository,
    private val courseRepo: CourseRepository,
    private val examRepo: ExamRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val taskTypeRepo: TaskTypeRepository,
    private val deviceName: () -> String,
    private val appVersion: () -> String,
) {

    suspend fun build(revision: Long): WebSnapshotDto {
        val calState = calendarRepo.calendarState.first()
        val resolver = ScheduleResolver(calState.calendar)
        val today = EpochDays.today()
        val todayText = EpochDays.format(today)

        val allSlots = courseRepo.getAllSlotsOnce()
        val weeksBySlot = courseRepo.getWeeksBySlotIdOnce()
        val courses = courseRepo.getAllCoursesOnce()
        val coursesById = courses.associateBy { it.id }
        val slotsByCourse = allSlots.groupBy { it.courseId }
        val customTypes = taskTypeRepo.getAllOnce()

        // 待办
        val tasks = taskRepo.getAllTasksOnce().map { task ->
            val course = coursesById[task.courseId]
            val courseKey = if (course != null) course.dedupeKey else ""
            val typeLabel = TaskTypes.resolve(task.taskType, customTypes)?.label.orEmpty()
            task.toWebDto(courseKey = courseKey, taskTypeLabel = typeLabel)
        }

        // 自定义分类
        val taskTypes = customTypes.map {
            WebTaskTypeDto(
                id = it.id,
                label = it.label,
                colorHex = it.colorHex,
                inkHex = it.inkHex,
                sortOrder = it.sortOrder,
            )
        }

        // 课程
        val courseDtos = courses.map { course ->
            val courseSlots = slotsByCourse[course.id].orEmpty()
                .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
            WebCourseDto(
                key = course.dedupeKey,
                name = course.name,
                code = course.code,
                teacher = course.teacher,
                category = course.category,
                classroom = course.classroom,
                colorHex = course.colorHex,
                note = course.note,
                weeklyText = courseSlots.joinToString(" · ") {
                    "${dayName(it.dayOfWeek)} ${it.startPeriod}-${it.endPeriod} 节"
                },
                slots = courseSlots.map { slot ->
                    WebSlotDto(
                        dayOfWeek = slot.dayOfWeek,
                        startPeriod = slot.startPeriod,
                        endPeriod = slot.endPeriod,
                        classroom = slot.classroom.ifBlank { course.classroom },
                        weeks = weeksBySlot[slot.id]?.toList().orEmpty(),
                    )
                },
            )
        }

        // 考试
        val examEntries = examRepo.getExamEntries().first()
        val examDtos = examEntries.map { exam ->
            WebExamDto(
                id = exam.id,
                courseCode = "",
                courseName = exam.courseName,
                kind = exam.kind.name,
                whenText = whenTextOf(exam.startAt, exam.endAt, exam.dateLabel),
                dateLabel = exam.dateLabel,
                classroom = exam.classroom,
                seat = exam.seat,
                credit = exam.credit,
                termLabel = exam.termLabel,
                colorHex = exam.courseColorHex,
                isToday = exam.startAt > 0 &&
                    AppZone.format("yyyy-MM-dd", exam.startAt) == todayText,
                startAt = exam.startAt,
                endAt = exam.endAt,
                startMinute = exam.startMinute,
                endMinute = exam.endMinute,
            )
        }

        // 今天的课：与 App 总览页同一套判定
        val periodTable = calState.periodTable
        val nowMinute = minuteOfDayNow()
        val todayCourses = resolver.slotsOn(today, allSlots, weeksBySlot).mapNotNull { slot ->
            val course = coursesById[slot.courseId] ?: return@mapNotNull null
            val start = periodTable.startMinuteOf(slot.startPeriod)
            val end = periodTable.endMinuteOf(slot.endPeriod)
            val state = when {
                start == null || end == null -> "future"
                nowMinute in start until end -> "now"
                nowMinute >= end -> "past"
                else -> "future"
            }
            WebTodayCourseDto(
                courseKey = course.dedupeKey,
                name = course.name,
                colorHex = course.colorHex,
                classroom = slot.classroom.ifBlank { course.classroom },
                teacher = course.teacher,
                timeText = periodTable.timeRangeText(slot.startPeriod, slot.endPeriod),
                startPeriod = slot.startPeriod,
                endPeriod = slot.endPeriod,
                state = state,
            )
        }

        return WebSnapshotDto(
            protocol = WEB_SYNC_PROTOCOL,
            revision = revision,
            generatedAt = System.currentTimeMillis(),
            today = todayText,
            weekday = EpochDays.dayOfWeek1To7(today),
            deviceName = deviceName(),
            appVersion = appVersion(),
            latestEditAt = tasks.maxOfOrNull { it.updatedAt } ?: 0L,
            utcOffsetMinutes = AppZone.ZONE.rules.getOffset(java.time.Instant.now()).totalSeconds / 60,
            // 手机这一端**总是完整的一份**：课程、考试、分类三块都有权威内容。
            // 这三个标记是给「网页端那边还没拉过一次」用的护栏。
            hasCourses = true,
            hasExams = true,
            hasTaskTypes = true,
            tasks = tasks,
            taskTypes = taskTypes,
            courses = courseDtos,
            exams = examDtos,
            todayCourses = todayCourses,
            // 周课表：和「今天的课」同一个理由 —— 排课判定只有一份实现，
            // 在这边算好发过去，网页端拿到的就是 App 课表页会显示的那一套。
            timetable = WebTimetableBuilder.build(
                calendar = calState.calendar,
                slots = allSlots,
                weeksBySlotId = weeksBySlot,
                coursesById = coursesById,
                exams = examEntries,
                periodTable = periodTable,
            ),
        )
    }

    private fun whenTextOf(startAt: Long, endAt: Long, dateLabel: String): String = when {
        startAt > 0 -> {
            val s = AppZone.format("MM-dd HH:mm", startAt)
            val e = AppZone.format("HH:mm", endAt)
            "$s-$e"
        }
        dateLabel.isNotBlank() -> dateLabel
        else -> "时间待定"
    }

    private fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> ""
    }
}
