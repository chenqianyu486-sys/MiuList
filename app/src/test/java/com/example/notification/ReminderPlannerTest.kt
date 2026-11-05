package com.example.notification

import com.example.data.calendar.AcademicCalendar
import com.example.data.calendar.AppZone
import com.example.data.calendar.MakeupShift
import com.example.data.calendar.Semester
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.ExamEntity
import com.example.data.local.TaskEntity
import com.example.data.timetable.PeriodTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 提醒排期的纯逻辑测试。
 *
 * 这里钉的是三件最容易悄悄坏掉的事：
 * 1. **时间口径**：每日汇总用北京时间；明天不是「+24h」当借口。
 * 2. **校历联动**：停课日不排、补课日按来源日的课排。
 * 3. **去重与状态**：连堂课只提醒一次、已完成/没设截止的待办不提醒。
 */
class ReminderPlannerTest {

    private val firstMonday = epochDay(2026, 9, 14)

    private fun epochDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).toEpochDay()

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(AppZone.ZONE)
            .toInstant()
            .toEpochMilli()

    private fun semester(): Semester = Semester(
        name = "2026-2027学年 秋冬学期",
        yearSpan = "2026-2027",
        term = "秋冬",
        startEpochDay = firstMonday,
        firstMondayEpochDay = firstMonday,
        endEpochDay = firstMonday + 16 * 7 - 1,
        totalWeeks = 16,
        periodsPerDay = PeriodTable.ZJU.periodsPerDay
    )

    private fun calendar(
        noClass: Set<Long> = emptySet(),
        makeup: Map<Long, MakeupShift> = emptyMap()
    ): AcademicCalendar = AcademicCalendar(
        semester = semester(),
        noClassDays = noClass,
        makeupDays = makeup
    )

    private fun course(id: Long = 1L, name: String = "高等数学"): CourseEntity = CourseEntity(
        id = id,
        name = name,
        category = "专业必修",
        teacher = "张老师",
        classroom = "东1-101",
        colorHex = "#BBCFE4"
    )

    private fun slot(
        id: Long = 1L,
        courseId: Long = 1L,
        dayOfWeek: Int = 4,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        classroom: String = "东1-101"
    ): CourseSlotEntity = CourseSlotEntity(
        id = id,
        courseId = courseId,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        classroom = classroom
    )

    private fun task(
        id: Long = 1L,
        title: String = "第 8 周作业",
        due: Long,
        completed: Boolean = false
    ): TaskEntity = TaskEntity(
        id = id,
        courseId = 1L,
        courseName = "高等数学",
        courseCategory = "专业必修",
        courseColorHex = "#BBCFE4",
        title = title,
        taskType = "HOMEWORK",
        dueDateTimestamp = due,
        isCompleted = completed
    )

    private fun snapshot(
        courses: List<CourseEntity> = emptyList(),
        slots: List<CourseSlotEntity> = emptyList(),
        calendar: AcademicCalendar = calendar(),
        exams: List<ExamEntity> = emptyList(),
        tasks: List<TaskEntity> = emptyList(),
        weeks: Map<Long, Set<Int>> = emptyMap()
    ): ReminderSnapshot = ReminderSnapshot(
        coursesById = courses.associateBy { it.id },
        slots = slots,
        weeksBySlotId = weeks,
        calendar = calendar,
        periodTable = PeriodTable.ZJU,
        exams = exams,
        tasks = tasks
    )

    // ------------------------------------------------------------ 每日汇总时间

    @Test
    fun `每日汇总今天还没到就排今天`() {
        val now = millis(2026, 11, 5, 7, 0)
        assertEquals(
            millis(2026, 11, 5, 7, 30),
            ReminderPlanner.nextDailyTrigger(now, 7 * 60 + 30)
        )
    }

    @Test
    fun `每日汇总今天已经过了就排明天`() {
        val now = millis(2026, 11, 5, 8, 0)
        assertEquals(
            millis(2026, 11, 6, 7, 30),
            ReminderPlanner.nextDailyTrigger(now, 7 * 60 + 30)
        )
    }

    // ------------------------------------------------------------ 课堂排期

    @Test
    fun `今天有课就排到上课前十五分钟`() {
        val snapshot = snapshot(
            courses = listOf(course()),
            slots = listOf(slot())
        )
        val now = millis(2026, 11, 5, 6, 0) // 周四，第 1 节 08:00

        assertEquals(
            millis(2026, 11, 5, 7, 45),
            ReminderPlanner.nextClassTrigger(snapshot, now, 15)
        )
    }

    @Test
    fun `停课日不排上课提醒`() {
        val snapshot = snapshot(
            courses = listOf(course()),
            slots = listOf(slot()),
            calendar = calendar(noClass = setOf(epochDay(2026, 11, 5)))
        )
        val now = millis(2026, 11, 5, 6, 0)

        assertEquals(
            millis(2026, 11, 12, 7, 45),
            ReminderPlanner.nextClassTrigger(snapshot, now, 15)
        )
    }

    @Test
    fun `补课日按来源日的课排提醒且来源日不排`() {
        val friday = epochDay(2026, 10, 16)
        val saturday = epochDay(2026, 10, 17)
        val snapshot = snapshot(
            courses = listOf(course()),
            slots = listOf(slot(id = 1, dayOfWeek = 5, startPeriod = 3, endPeriod = 4)),
            calendar = calendar(makeup = mapOf(saturday to MakeupShift(friday)))
        )
        val now = millis(2026, 10, 14, 0, 0)

        val events = ReminderPlanner.classEvents(snapshot, now, 15)
        assertTrue(
            "补课当天应当有课",
            events.any { it.startAtMillis == millis(2026, 10, 17, 10, 0) }
        )
        assertFalse(
            "被让出的周五不应再排课",
            events.any { it.startAtMillis >= millis(2026, 10, 16, 0, 0) && it.startAtMillis < millis(2026, 10, 17, 0, 0) }
        )
    }

    @Test
    fun `连堂课只提醒一次`() {
        val snapshot = snapshot(
            courses = listOf(course()),
            slots = listOf(
                slot(id = 1, startPeriod = 1, endPeriod = 2),
                slot(id = 2, startPeriod = 3, endPeriod = 4),
                // 晚上还有一次同一门课，不能被上午那次吞掉
                slot(id = 3, startPeriod = 11, endPeriod = 12)
            )
        )
        val briefing = ReminderPlanner.dailyBriefing(snapshot, epochDay(2026, 11, 5))

        assertEquals("连续的第 1-4 节应合成一次，晚上这一次单独算", 2, briefing.sessionCount)
        assertEquals(8 * 60, briefing.firstStartMinute)
        assertEquals("东1-101", briefing.firstLocation)
    }

    @Test
    fun `同一天的课与考试都进入汇总`() {
        val exam = ExamEntity(
            id = 1,
            courseName = "数据结构",
            kind = ExamEntity.KIND_FINAL,
            startAt = millis(2026, 11, 5, 14, 0),
            startMinute = 14 * 60,
            endMinute = 16 * 60,
            classroom = "紫金港东1-101",
            seat = "12"
        )
        val snapshot = snapshot(
            courses = listOf(course()),
            slots = listOf(slot()),
            exams = listOf(exam),
            tasks = listOf(
                task(id = 1, due = millis(2026, 11, 5, 23, 59)),
                task(id = 2, due = millis(2026, 11, 5, 23, 59), completed = true)
            )
        )
        val briefing = ReminderPlanner.dailyBriefing(snapshot, epochDay(2026, 11, 5))

        assertEquals(1, briefing.sessionCount)
        assertEquals(1, briefing.examCount)
        assertEquals("数据结构", briefing.firstExamName)
        assertEquals("已完成的待办不算今天的截止数", 1, briefing.dueTaskCount)
        assertTrue(briefing.hasAnything)
    }

    @Test
    fun `全天无安排时保持静默`() {
        val briefing = ReminderPlanner.dailyBriefing(
            snapshot = snapshot(),
            epochDay = epochDay(2026, 11, 5)
        )
        assertFalse(briefing.hasAnything)
    }

    // ------------------------------------------------------------ 考试提醒

    @Test
    fun `考试提前三十分钟提醒且开考后不再补发`() {
        val exam = ExamEntity(
            id = 1,
            courseName = "数据结构",
            kind = ExamEntity.KIND_FINAL,
            startAt = millis(2026, 11, 5, 10, 0),
            startMinute = 10 * 60,
            endMinute = 12 * 60,
            classroom = "紫金港东1-101",
            seat = "12"
        )
        val snapshot = snapshot(exams = listOf(exam))

        assertEquals(
            millis(2026, 11, 5, 9, 30),
            ReminderPlanner.nextClassTrigger(snapshot, millis(2026, 11, 5, 7, 0), 15)
        )
        val atFire = ReminderPlanner.classEventsInFireWindow(
            snapshot,
            millis(2026, 11, 5, 9, 30),
            15
        )
        assertEquals(1, atFire.size)
        assertTrue(atFire.single().isExam)
        assertEquals("紫金港东1-101", atFire.single().location)
        assertEquals("12", atFire.single().seat)

        assertTrue(
            "开考之后闹钟才到，不应再说「即将考试」",
            ReminderPlanner.classEventsInFireWindow(
                snapshot,
                millis(2026, 11, 5, 10, 1),
                15
            ).isEmpty()
        )
    }

    // ------------------------------------------------------------ 待办提醒

    @Test
    fun `待办按提前量排期且已完成与无截止的不参与`() {
        val now = millis(2026, 11, 5, 8, 0)
        val due = millis(2026, 11, 5, 23, 59)
        val tasks = listOf(
            task(id = 1, due = due),
            task(id = 2, due = due, completed = true),
            task(id = 3, due = TaskEntity.NO_DUE_TIMESTAMP),
            task(id = 4, due = millis(2026, 11, 5, 7, 0)) // 已过期
        )

        assertEquals(
            due - 6 * 60 * 60_000L,
            ReminderPlanner.nextTodoTrigger(tasks, now, 6 * 60)
        )
        val eligible = ReminderPlanner.eligibleTodoTasks(tasks, now)
        assertEquals(1, eligible.size)
        assertEquals(1L, eligible.single().id)
    }

    @Test
    fun `创建时已进入提醒窗口的待办排到稍后一次且只提醒一次`() {
        val now = millis(2026, 11, 5, 20, 0)
        val due = millis(2026, 11, 5, 23, 0) // 3 小时后截止，提前量 6 小时
        val tasks = listOf(task(id = 1, due = due))

        val at = ReminderPlanner.nextTodoTrigger(tasks, now, 6 * 60)
        assertEquals(now + ReminderPlanner.IMMEDIATE_DELAY_MILLIS, at)
        requireNotNull(at)

        val fireAt = at + 1_000L
        val events = ReminderPlanner.todoEventsInFireWindow(tasks, fireAt, 6 * 60)
        assertEquals(1, events.size)

        val key = ReminderPlanner.todoDeliveryKey(tasks.single())
        assertNull(
            "已经提醒过的待办不应再排下一次",
            ReminderPlanner.nextTodoTrigger(tasks, fireAt, 6 * 60, deliveredKeys = setOf(key))
        )
    }

    @Test
    fun `改了截止时间会重新提醒`() {
        val now = millis(2026, 11, 5, 8, 0)
        val oldTask = task(id = 1, due = millis(2026, 11, 5, 23, 59))
        val movedTask = oldTask.copy(dueDateTimestamp = millis(2026, 11, 6, 9, 0))
        val delivered = setOf(ReminderPlanner.todoDeliveryKey(oldTask))

        assertNotNull(
            ReminderPlanner.nextTodoTrigger(listOf(movedTask), now, 6 * 60, delivered)
        )
    }
}
