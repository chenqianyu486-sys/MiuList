package com.example.notification

import com.example.data.calendar.AcademicCalendar
import com.example.data.calendar.AppZone
import com.example.data.calendar.ScheduleResolver
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.ExamEntity
import com.example.data.local.TaskEntity
import com.example.data.model.ExamKind
import com.example.data.timetable.PeriodTable
import java.time.Instant
import java.time.LocalDate

/**
 * 排期要用到的一份**只读快照**。
 *
 * 提醒在三个地方被计算：App 前台（数据一变就重排）、闹钟 Receiver（响的那一刻重算内容）、
 * 开机 Receiver（重新注册）。三处读的是同一组表，但读的时刻不同 ——
 * 传一个快照进去，Planner 本身就能保持纯函数、可在普通 JUnit 里测。
 */
data class ReminderSnapshot(
    val coursesById: Map<Long, CourseEntity> = emptyMap(),
    val slots: List<CourseSlotEntity> = emptyList(),
    val weeksBySlotId: Map<Long, Set<Int>> = emptyMap(),
    val calendar: AcademicCalendar = AcademicCalendar.EMPTY,
    val periodTable: PeriodTable = PeriodTable.DEFAULT,
    val exams: List<ExamEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList()
)

/**
 * 一堂课或一场考试的提醒。
 *
 * [triggerAtMillis] 是闹钟该响的时刻；[startAtMillis] 是事情本身开始的时刻。
 * 两者分开存，是因为 Doze 会把闹钟推迟几分钟：响的时候要判断
 * 「现在是不是已经开课/开考了」，已经开始的就不该再提醒。
 */
data class ClassReminderEvent(
    val triggerAtMillis: Long,
    val startAtMillis: Long,
    val eventTitle: String,
    val location: String,
    val startMinuteOfDay: Int,
    val isExam: Boolean,
    val seat: String = "",
    val kindLabel: String = ""
)

/** 一条进入提醒窗口的待办。 */
data class TodoReminderEvent(
    val task: TaskEntity,
    val triggerAtMillis: Long
)

/**
 * 某一天的汇总。
 *
 * 课程数按**连续课堂**数，不按节数：第 1–2 节连上的同一门课是一门课的一次课，
 * 说成「今天有 2 节课」和学生的直觉对不上（他们也会觉得那是同一节大课）。
 */
data class DailyBriefing(
    val sessionCount: Int = 0,
    val firstCourseName: String = "",
    val firstStartMinute: Int? = null,
    val firstLocation: String = "",
    val dueTaskCount: Int = 0,
    val examCount: Int = 0,
    val firstExamName: String = "",
    val firstExamMinute: Int? = null,
    val weatherLine: String? = null
) {
    /** 全天无课、无考试、无截止待办时保持静默。 */
    val hasAnything: Boolean
        get() = sessionCount > 0 || dueTaskCount > 0 || examCount > 0
}

/**
 * 提醒排期的**唯一计算入口**。
 *
 * 不依赖 Android / Room / 通知 API，因此所有时间边界都能在普通 JUnit 里钉住：
 * 调休日、停课日、连堂课、考试、已完成待办、没设截止的待办、Doze 迟到。
 *
 * ## 为什么只算「下一次」
 *
 * 每个渠道只注册一个 Alarm，响完再排下一次。相比「一次排一周」：
 * 改课、勾待办、改校历之后不用追着一堆 PendingIntent 去注销，
 * 而且每次响时都会按**当下的数据**重新判定，不会拿着旧课表提醒。
 */
object ReminderPlanner {

    /** 考试固定提前 30 分钟。考试不是每周都有的高频事件，不单独给设置项。 */
    const val EXAM_LEAD_MINUTES = 30

    /**
     * 「创建时已经在窗口内」的待办，排到这么久之后提醒。
     *
     * 不能排 `now`：当前台正在重排时立刻弹一条通知，用户会以为是点出来的。
     * 也不能不提醒：临近截止的待办正是最该被看见的那一类。
     */
    const val IMMEDIATE_DELAY_MILLIS = 10_000L

    /** 闹钟响时允许提前这么多毫秒命中事件，吸收系统时钟的细微抖动。 */
    const val FIRE_AHEAD_MILLIS = 60_000L

    /** Doze 把闹钟推迟后，最多容忍迟到这么久仍然提醒（且必须还没开始）。 */
    const val LATE_TOLERANCE_MILLIS = 30 * 60_000L

    /** 课程提醒向后最多看这么多天。一个 16 周的学期约 112 天。 */
    const val CLASS_HORIZON_DAYS = 180

    private const val MINUTE_MILLIS = 60_000L
    private const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS

    /** 某一天 00:00（北京时间）的绝对毫秒。 */
    fun dayStartMillis(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(AppZone.ZONE).toInstant().toEpochMilli()

    /** 绝对毫秒 → 当天第几分钟（北京时间）。 */
    fun minuteOfDay(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(AppZone.ZONE).let { it.hour * 60 + it.minute }

    /**
     * 下一次每日汇总。
     *
     * 今天该时刻还没到就是今天，已经过了就是明天 —— 不做「补发」：
     * 早上 10 点才打开 App 的人不需要在 10 点收到一条「早安」。
     */
    fun nextDailyTrigger(nowMillis: Long, minuteOfDay: Int): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(AppZone.ZONE).toLocalDate()
        val todayTarget = today.atStartOfDay(AppZone.ZONE)
            .plusMinutes(minuteOfDay.coerceIn(0, 24 * 60 - 1).toLong())
            .toInstant()
            .toEpochMilli()
        return if (todayTarget > nowMillis) todayTarget else todayTarget + DAY_MILLIS
    }

    /**
     * 从今天起扫出一批课堂与考试提醒（含已经过去的那些）。
     *
     * 返回的是「这一段时间里所有可能响的事件」，调用方按需筛：
     * 排下一次用 `trigger > now`，响铃时用 `trigger` 落在窗口内。
     *
     * 调的仍是 [ScheduleResolver.slotsOn]：停课日返回空、补课日换成来源日的课，
     * 和总览 / 课表用的是同一套判定。连堂课在这里先合并，后续所有计算都不会重复。
     */
    fun classEvents(
        snapshot: ReminderSnapshot,
        nowMillis: Long,
        leadMinutes: Int,
        horizonDays: Int = CLASS_HORIZON_DAYS
    ): List<ClassReminderEvent> {
        val resolver = ScheduleResolver(snapshot.calendar)
        val today = AppZone.epochDayOf(nowMillis)
        val safeLead = leadMinutes.coerceIn(0, 24 * 60)
        val safeHorizon = horizonDays.coerceIn(0, 365)
        val out = ArrayList<ClassReminderEvent>()

        for (offset in 0..safeHorizon) {
            val day = today + offset
            val sessions = collapseContiguousSessions(
                resolver.slotsOn(day, snapshot.slots, snapshot.weeksBySlotId)
            )
            if (sessions.isEmpty()) continue
            val dayStart = dayStartMillis(day)
            for (slot in sessions) {
                val course = snapshot.coursesById[slot.courseId] ?: continue
                val startMinute = snapshot.periodTable.startMinuteOf(slot.startPeriod) ?: continue
                val startAt = dayStart + startMinute * MINUTE_MILLIS
                out += ClassReminderEvent(
                    triggerAtMillis = startAt - safeLead * MINUTE_MILLIS,
                    startAtMillis = startAt,
                    eventTitle = course.name,
                    location = slot.classroom.ifBlank { course.classroom }.trim(),
                    startMinuteOfDay = startMinute,
                    isExam = false
                )
            }
        }

        for (exam in snapshot.exams) {
            // 0 = 学校还没公布具体日期，这种考试不该排提醒。
            if (exam.startAt <= 0L) continue
            val examDay = AppZone.epochDayOf(exam.startAt)
            if (examDay < today || examDay > today + safeHorizon) continue
            val startMinute = minuteOfDay(exam.startAt)
            out += ClassReminderEvent(
                triggerAtMillis = exam.startAt - EXAM_LEAD_MINUTES * MINUTE_MILLIS,
                startAtMillis = exam.startAt,
                eventTitle = exam.courseName.ifBlank { "考试" },
                location = exam.classroom.trim(),
                startMinuteOfDay = startMinute,
                isExam = true,
                seat = exam.seat.trim(),
                kindLabel = ExamKind.of(exam.kind).label
            )
        }

        return out.sortedBy { it.triggerAtMillis }
    }

    /** 下一次上课 / 考试提醒的时刻。没有未来事件时返回 null（= 不注册闹钟）。 */
    fun nextClassTrigger(
        snapshot: ReminderSnapshot,
        nowMillis: Long,
        leadMinutes: Int
    ): Long? = classEvents(snapshot, nowMillis, leadMinutes)
        .firstOrNull { it.triggerAtMillis > nowMillis }
        ?.triggerAtMillis

    /**
     * 闹钟响时该展示的事件。
     *
     * 条件里的 `startAtMillis > now` 不是可选的：Doze 把 07:45 的闹钟拖到 08:10，
     * 那时课已经开始了，再弹「15 分钟后上课」只会让人以为时间算错了。
     * 宁可这一次不提醒，也不要给出一条自相矛盾的信息。
     */
    fun classEventsInFireWindow(
        snapshot: ReminderSnapshot,
        nowMillis: Long,
        leadMinutes: Int
    ): List<ClassReminderEvent> = classEvents(snapshot, nowMillis, leadMinutes)
        .filter { event ->
            event.startAtMillis > nowMillis &&
                event.triggerAtMillis <= nowMillis + FIRE_AHEAD_MILLIS &&
                event.triggerAtMillis >= nowMillis - LATE_TOLERANCE_MILLIS
        }

    /** 待办提醒的去重键。截止时刻变了就是一条新事实，键也跟着变。 */
    fun todoDeliveryKey(task: TaskEntity): String = "${task.id}:${task.dueDateTimestamp}"

    /** 一条待办理论上该提醒的时刻。 */
    fun todoTriggerOf(task: TaskEntity, leadMinutes: Int): Long =
        task.dueDateTimestamp - leadMinutes.coerceIn(0, 7 * 24 * 60) * MINUTE_MILLIS

    /**
     * 还没到截止、没完成、也还没提醒过的那批待办。
     *
     * 没设截止的待办（哨兵 [TaskEntity.NO_DUE_TIMESTAMP]）根本不进来；
     * 已经过期的也不进来 —— 对一条已经逾期的事说「即将截止」是错的。
     */
    fun eligibleTodoTasks(
        tasks: List<TaskEntity>,
        nowMillis: Long,
        deliveredKeys: Set<String> = emptySet()
    ): List<TaskEntity> = tasks
        .asSequence()
        .filter { !it.isCompleted }
        .filter { TaskEntity.hasDue(it) }
        .filter { it.dueDateTimestamp > nowMillis }
        .filter { todoDeliveryKey(it) !in deliveredKeys }
        .sortedBy { it.dueDateTimestamp }
        .toList()

    /**
     * 下一次待办提醒。
     *
     * 触发时刻已经在过去的（创建得晚 / 提前量刚改大），排到 [IMMEDIATE_DELAY_MILLIS]
     * 之后，而不是直接丢弃 —— 这时离截止不到一个提前量，正是最该提醒的时候。
     */
    fun nextTodoTrigger(
        tasks: List<TaskEntity>,
        nowMillis: Long,
        leadMinutes: Int,
        deliveredKeys: Set<String> = emptySet()
    ): Long? {
        val eligible = eligibleTodoTasks(tasks, nowMillis, deliveredKeys)
        if (eligible.isEmpty()) return null
        val earliest = eligible.minOf { todoTriggerOf(it, leadMinutes) }
        return if (earliest > nowMillis) earliest else nowMillis + IMMEDIATE_DELAY_MILLIS
    }

    /**
     * 闹钟响时进入提醒窗口的待办。
     *
     * 这里**没有下界**：触发时刻可能远在过去（创建得晚、提前量刚调大）。
     * 那些正是 [nextTodoTrigger] 排到「稍后」的那一批，响的时候就该发出去。
     * 重复由 [ReminderDeliveryStore] 挡住，不靠时间窗口 —— 用时间窗口反而会把
     * 「三小时前就该提醒、但那时还没创建」的待办永远漏掉。
     */
    fun todoEventsInFireWindow(
        tasks: List<TaskEntity>,
        nowMillis: Long,
        leadMinutes: Int,
        deliveredKeys: Set<String> = emptySet()
    ): List<TodoReminderEvent> = eligibleTodoTasks(tasks, nowMillis, deliveredKeys)
        .map { TodoReminderEvent(task = it, triggerAtMillis = todoTriggerOf(it, leadMinutes)) }
        .filter { it.triggerAtMillis <= nowMillis + FIRE_AHEAD_MILLIS }

    /**
     * 某一天的汇总内容。
     *
     * [weatherLine] 由调用方从**本机天气缓存**读出后传进来；这里不联网，
     * 也不把「读不到天气」当成异常 —— 一行缺了就少一行。
     */
    fun dailyBriefing(
        snapshot: ReminderSnapshot,
        epochDay: Long,
        weatherLine: String? = null
    ): DailyBriefing {
        val resolver = ScheduleResolver(snapshot.calendar)
        val sessions = collapseContiguousSessions(
            resolver.slotsOn(epochDay, snapshot.slots, snapshot.weeksBySlotId)
        )
        val first = sessions.firstOrNull()
        val firstCourse = first?.let { snapshot.coursesById[it.courseId] }
        val firstStart = first?.let { snapshot.periodTable.startMinuteOf(it.startPeriod) }
        val firstLocation = first?.let { slot ->
            slot.classroom.ifBlank { firstCourse?.classroom.orEmpty() }.trim()
        }.orEmpty()

        val dueTasks = snapshot.tasks.filter {
            !it.isCompleted &&
                TaskEntity.hasDue(it) &&
                AppZone.epochDayOf(it.dueDateTimestamp) == epochDay
        }
        val exams = snapshot.exams
            .filter { it.startAt > 0L && AppZone.epochDayOf(it.startAt) == epochDay }
            .sortedBy { it.startAt }
        val firstExam = exams.firstOrNull()

        return DailyBriefing(
            sessionCount = sessions.size,
            firstCourseName = firstCourse?.name.orEmpty(),
            firstStartMinute = firstStart,
            firstLocation = firstLocation,
            dueTaskCount = dueTasks.size,
            examCount = exams.size,
            firstExamName = firstExam?.courseName.orEmpty(),
            firstExamMinute = firstExam?.let { minuteOfDay(it.startAt) },
            weatherLine = weatherLine
        )
    }

    /**
     * 「同一门课连续上」合并成一次提醒。
     *
     * 判据是**节次相邻**（`下一节开始 <= 上一节结束 + 1`）且课程相同。
     * 只看课程相同是不够的：同一天上午 1–2 节和晚上 11–12 节都是这门课，
     * 中间隔着大半天，不能只提醒早上那一次。
     */
    private fun collapseContiguousSessions(
        slots: List<CourseSlotEntity>
    ): List<CourseSlotEntity> {
        if (slots.size <= 1) return slots
        val sorted = slots.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
        val out = ArrayList<CourseSlotEntity>(sorted.size)
        for (slot in sorted) {
            val previous = out.lastOrNull()
            val contiguous = previous != null &&
                previous.courseId == slot.courseId &&
                slot.startPeriod <= previous.endPeriod + 1
            if (!contiguous) out += slot
        }
        return out
    }
}
