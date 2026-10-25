package com.example.data.sync.web

import com.example.data.calendar.AcademicCalendar
import com.example.data.calendar.AppZone
import com.example.data.calendar.EpochDays
import com.example.data.calendar.ScheduleResolver
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.model.ExamEntry
import com.example.data.timetable.PeriodTable
import java.time.Instant

/**
 * 把「这一学期每周的课表」算成网页端能直接画的那份数据。
 *
 * ## 为什么是这一层，而不是让网页端自己算
 *
 * 周课表的一列是**这一周里的一个真实日期**，不是「星期几」。
 * 哪些课落在哪一天牵扯四件事：开课周次、停课日、补课日、学期范围，
 * 而 App 里这件事已经收口到 [ScheduleResolver] 一个地方了
 * （见那里的注释：早先散在 4 处，语义已经开始漂移）。
 *
 * 在网页端重写一份的代价不是「多写几十行」，而是**两边迟早给出不同的答案**，
 * 而用户看到不一致时只会觉得网页端算错了 —— 他不知道手机那边有一份权威实现。
 * 所以这里在手机侧算好，网页端只负责把算好的东西画出来。
 *
 * ## 为什么是一个**纯函数**
 *
 * 它不碰 Room、不碰 Android、不碰协程：所有输入都由参数喂进来。
 * 于是「补课日到底出的是不是周五的课」「停课日是不是真的空着」
 * 这类问题可以直接在普通 JUnit 里断言，不用起模拟器、不用灌库。
 */
object WebTimetableBuilder {

    /**
     * @param calendar 校历（学期、停课、补课）。判定口径全在它与 [ScheduleResolver] 里。
     * @param slots 全部课程时段。
     * @param weeksBySlotId 每个时段的「开课周次」。缺 key = 每周。
     * @param coursesById 课程表，时段要靠它拿到课程名与颜色。
     * @param exams 全部考试。考试只有钟点，节次在这里按作息表换算。
     * @param periodTable 作息表。
     */
    fun build(
        calendar: AcademicCalendar,
        slots: List<CourseSlotEntity>,
        weeksBySlotId: Map<Long, Set<Int>>,
        coursesById: Map<Long, CourseEntity>,
        exams: List<ExamEntry>,
        periodTable: PeriodTable,
    ): WebTimetableDto {
        val resolver = ScheduleResolver(calendar)
        val totalWeeks = calendar.totalWeeks.coerceAtLeast(1)

        // ── 时段去重表 ──────────────────────────────────────────
        //
        // 一门课在一学期里要出几十次，全文重复就是几十份一模一样的 JSON。
        // 这里把每个时段**按 id** 归一成一条，周计划里只存下标。
        //
        // 按 id 而不是按「课程 + 节次」归一：同一个课程同一节上成两条时段
        // （各开一半的周次）是两份独立的数据，App 的栅格会把它们并排画成两格，
        // 这里若合并成一条，网页端就会少画一格。
        //
        // 排序是为了下标稳定：DAO 不保证返回顺序，而下标会写进快照里。
        // 顺序乱一点不影响显示（下标只是一一对应的编号），但快照会因此每次都不同，
        // 让「两次快照应当一模一样」这种断言无从下手。
        val sortedSlots = slots.sortedWith(
            compareBy({ it.courseId }, { it.dayOfWeek }, { it.startPeriod }, { it.id })
        )
        val slotIndexById = LinkedHashMap<Long, Int>(sortedSlots.size)
        val slotList = ArrayList<WebTimetableSlotDto>(sortedSlots.size)
        for (slot in sortedSlots) {
            if (slotIndexById.containsKey(slot.id)) continue
            val course = coursesById[slot.courseId]
            slotIndexById[slot.id] = slotList.size
            slotList += WebTimetableSlotDto(
                courseKey = course?.dedupeKey.orEmpty(),
                startPeriod = slot.startPeriod,
                endPeriod = slot.endPeriod,
                classroom = slot.classroom.ifBlank { course?.classroom.orEmpty() },
            )
        }

        // ── 考试按天归堆 ────────────────────────────────────────
        //
        // 一场考试只落在**一天**上，所以整张周表里它只出现一次 ——
        // 内容可以直接内联进那一天，不必再去别处查。
        val examsByDay: Map<Long, List<ExamEntry>> = exams
            .mapNotNull { exam -> examDay(exam)?.let { it to exam } }
            .groupBy({ it.first }, { it.second })

        // ── 每一周 ──────────────────────────────────────────────
        val weeks = (1..totalWeeks).map { week ->
            val plan = resolver.weekPlan(week, slots, weeksBySlotId)
            WebTimetableWeekDto(
                week = week,
                days = plan.map { dayPlan ->
                    WebTimetableDayDto(
                        date = EpochDays.format(dayPlan.epochDay),
                        dayOfWeek = dayPlan.dayOfWeek,
                        offReason = dayPlan.offReason?.name,
                        makeupDayOfWeek = dayPlan.makeupDayOfWeek,
                        slotIndexes = dayPlan.slots.mapNotNull { slotIndexById[it.id] },
                        exams = examsByDay[dayPlan.epochDay].orEmpty().mapNotNull { exam ->
                            // 考试没有节号，只有钟点，得按作息表换算成节次才能落进格子。
                            // 连开考时刻都没有（学校还没公布）就换不出来 —— 这种考试
                            // 进不了栅格，但仍然出现在「考试」列表里。
                            if (exam.startMinute <= 0) return@mapNotNull null
                            val span = periodTable.periodSpanOf(exam.startMinute, exam.endMinute)
                                ?: return@mapNotNull null
                            WebTimetableExamDto(
                                courseName = exam.courseName,
                                colorHex = exam.courseColorHex,
                                startPeriod = span.first,
                                endPeriod = span.last,
                                startMinute = exam.startMinute,
                                classroom = exam.classroom,
                                seat = exam.seat,
                            )
                        },
                    )
                },
            )
        }

        return WebTimetableDto(
            totalWeeks = totalWeeks,
            currentWeek = resolver.currentWeek(),
            periodsPerDay = periodTable.periodsPerDay,
            periods = periodTable.periods.map {
                WebPeriodDto(period = it.period, startMinute = it.startMinute, endMinute = it.endMinute)
            },
            slotList = slotList,
            weeks = weeks,
        )
    }

    /**
     * 考试落在哪一天（北京的 epochDay）。日期未知时返回 null。
     *
     * 时区钉死在 [AppZone]：设备在别的时区时，跟着设备走会让考试落错列。
     */
    private fun examDay(exam: ExamEntry): Long? {
        if (!exam.hasDate) return null
        return Instant.ofEpochMilli(exam.startAt)
            .atZone(AppZone.ZONE)
            .toLocalDate()
            .toEpochDay()
    }
}
