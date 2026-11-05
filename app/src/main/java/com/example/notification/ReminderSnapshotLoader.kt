package com.example.notification

import com.example.data.calendar.AcademicCalendar
import com.example.data.calendar.MakeupShift
import com.example.data.calendar.Semester
import com.example.data.di.AppContainer
import com.example.data.timetable.PeriodSlot
import com.example.data.timetable.PeriodTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把排期需要的六张表读成一份 [ReminderSnapshot]。
 *
 * 读法一律走 Repository 的 `...Once()`：提醒是**某一刻**的判定，
 * 不是一条要持续观察的流 —— 拿着 Flow 在 Receiver 里等首帧只会多一层复杂度。
 */
class ReminderSnapshotLoader(private val container: AppContainer) {

    suspend fun load(): ReminderSnapshot = withContext(Dispatchers.IO) {
        val courses = container.courseRepository.getAllCoursesOnce()
        val slots = container.courseRepository.getAllSlotsOnce()
        val weeks = container.courseRepository.getWeeksBySlotIdOnce()
        val semesterEntity = container.calendarRepository.semesterOnce()
        val periods = container.calendarRepository.periodsOnce()
        val noClass = container.calendarRepository.noClassDaysOnce()
        val makeup = container.calendarRepository.makeupDaysOnce()
        val exams = container.examRepository.getAllExamsOnce()
        val tasks = container.taskRepository.getAllTasksOnce()

        val semester = semesterEntity?.let {
            Semester(
                name = it.name,
                yearSpan = it.yearSpan,
                term = it.term,
                startEpochDay = it.startEpochDay,
                firstMondayEpochDay = it.firstMondayEpochDay,
                endEpochDay = it.endEpochDay,
                totalWeeks = it.totalWeeks,
                periodsPerDay = it.periodsPerDay
            )
        }
        val periodTable = if (periods.isEmpty()) {
            // 理论上不该发生（启动时会 ensureDefaultPeriods），但 Receiver 可能在
            // 首次启动的极早窗口里被叫醒。空表排不出任何时间，退回内置作息比
            // 「一节课都算不出来」更接近用户预期。
            PeriodTable.DEFAULT
        } else {
            PeriodTable.from(periods.map { PeriodSlot(it.period, it.startMinute, it.endMinute, it.section) })
        }

        ReminderSnapshot(
            coursesById = courses.associateBy { it.id },
            slots = slots,
            weeksBySlotId = weeks,
            calendar = AcademicCalendar(
                semester = semester,
                noClassDays = noClass.map { it.epochDay }.toSet(),
                makeupDays = makeup.associate { it.epochDay to MakeupShift(it.sourceEpochDay) }
            ),
            periodTable = periodTable,
            exams = exams,
            tasks = tasks
        )
    }
}
