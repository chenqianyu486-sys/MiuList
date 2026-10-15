package com.example.data.generation

import com.example.data.calendar.EpochDays
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskStar
import com.example.data.model.TaskType
import com.example.data.model.formatPeriodRange
import com.example.data.model.getDayName
import com.example.data.timetable.PeriodTable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「一键生成本周待办」的引擎。
 *
 * 改造前这里只处理**某一天**（`generateTasksForDay`），标题是「【作业】高数 习题」，
 * 去重靠 `generatedDate`。现在按**一整周**产出，标题是「高数 第3周作业」，
 * 去重靠「同课程 + 同类型 + 同周次 + 同周几」四元组。
 *
 * 三条关键决定：
 * 1. **时段由调用方先按周过滤好再传进来**（`ScheduleResolver.slotsInWeek`），
 *    所以这里不关心星期几、单双周、停课补课；它只做「一周的这些课要产出哪些待办」。
 * 2. **「每周一次」的锚点取该周最早的那节课**。锚点一旦确定，这条待办的截止日在一周之内
 *    就是稳定的，不会因为多开一次 App、多生成一次就往后漂。
 * 3. 「每周一次」也照常记下锚点那天的周几进 `dayOfWeek`，于是四元组判据天然唯一，
 *    不需要为它另开一个特例。
 * 4. **不预置星标**：`priority` 恒为 `STAR_UNMARKED`。星标留给用户手动标。
 *
 * 无 Android / Room 依赖，可在普通 JUnit 里直接测。
 */
class WeekTaskGenerationEngine(private val periodTable: PeriodTable) {

    data class Input(
        val week: Int,
        /** 该周的周一（epochDay）。整周的日期都从它推。 */
        val weekMondayEpochDay: Long,
        /** 已按该周过滤（含单双周 / 停课 / 补课）的时段 */
        val slots: List<CourseSlotEntity>,
        val coursesById: Map<Long, CourseEntity>,
        /** 课程 × 类型的课程级规则；缺 key = 用户没配过这一格 */
        val courseRules: Map<Long, Map<TaskType, CourseRuleSpec>> = emptyMap(),
        /** 课程类别 → 默认规则 */
        val categoryRules: Map<String, CategoryRule> = emptyMap()
    )

    /**
     * 去重判据。写入路径与预览路径共用同一判据。
     */
    fun interface ExistingChecker {
        suspend fun exists(courseId: Long, taskType: TaskType, week: Int, dayOfWeek: Int): Boolean
    }

    suspend fun build(input: Input, hasExisting: ExistingChecker): List<TaskEntity> {
        val zone = ZoneId.systemDefault()
        val monday = LocalDate.ofEpochDay(input.weekMondayEpochDay)

        // 一天里的多个时段按「周几 + 开始节次」排好序；
        // 「每周一次」的锚点就是每门课排完之后的第一个。
        val ordered = input.slots.sortedWith(
            compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.id })
        )

        val out = mutableListOf<TaskEntity>()

        for ((courseId, courseSlots) in ordered.groupBy { it.courseId }) {
            val course = input.coursesById[courseId] ?: continue
            // 本周没有这门课的时段 —— groupBy 天然不会产生这一组，不用额外判
            for (type in GenerationRuleResolver.AUTO_TYPES) {
                if (!GenerationRuleResolver.isEnabled(course, type)) continue

                val rule = GenerationRuleResolver.resolve(
                    type = type,
                    courseRule = input.courseRules[courseId]?.get(type),
                    categoryRule = input.categoryRules[course.category]
                )

                // 「每次课一次」每个时段一条；「每周一次」整门课一条，锚点取最早那节。
                val anchors = if (rule.perClass) {
                    courseSlots
                } else {
                    listOf(courseSlots.first())
                }

                for (anchor in anchors) {
                    val dayOfWeek = anchor.dayOfWeek.coerceIn(1, 7)
                    val date = monday.plusDays((dayOfWeek - 1).toLong())
                    if (hasExisting.exists(courseId, type, input.week, dayOfWeek)) continue
                    out += buildTask(course, anchor, date, input.week, type, rule, zone)
                }
            }
        }

        return out
    }

    private fun buildTask(
        course: CourseEntity,
        anchor: CourseSlotEntity,
        date: LocalDate,
        week: Int,
        type: TaskType,
        rule: TaskGenerationRule,
        zone: ZoneId
    ): TaskEntity {
        val classroom = anchor.classroom.ifBlank { course.classroom }
        val startMinute = periodTable.startMinuteOf(anchor.startPeriod)
        val startTimeText = periodTable.slotOf(anchor.startPeriod)?.startTime ?: "08:00"
        val startTimestamp = date
            .atTime((startMinute ?: 8 * 60) / 60, (startMinute ?: 8 * 60) % 60)
            .atZone(zone).toInstant().toEpochMilli()

        // 预习挂在某一节课上（提前 N 分钟），作业与实验是收尾型任务（上课日 + N 天 23:59）。
        val dueTimestamp = when (type) {
            TaskType.PRE_STUDY ->
                startTimestamp - rule.dueBeforeMinutes * 60_000L

            else -> date.plusDays(rule.dueOffsetDays.toLong())
                .atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        }
        val dueDate = java.time.Instant.ofEpochMilli(dueTimestamp)
            .atZone(zone).toLocalDate()

        val typeWord = when (type) {
            TaskType.PRE_STUDY -> "预习"
            TaskType.LAB_REPORT -> "实验报告"
            else -> "作业"
        }

        // 自动生成的待办**一律不标星**。星标语义见 `TaskStar`：它是「我挑出来的」，
        // 只有手动勾的才算数。按类型预置星标（曾是预习 + 实验报告）会让整屏凭空多出
        // 一片星，而用户从没挑过它们，于是「标星」恰好不再表示「我挑的」。
        // 想标哪条，一键生成之后自己点一下即可。
        return TaskEntity(
            courseId = course.id,
            courseName = course.name,
            courseCategory = course.category,
            courseColorHex = course.colorHex,
            title = "${course.name} 第${week}周${typeWord}",
            description = describe(type, classroom, course.teacher, week),
            taskType = type.name,
            startTimeTimestamp = startTimestamp,
            startTimeString = "${getDayName(anchor.dayOfWeek)} $startTimeText " +
                "(${formatPeriodRange(anchor.startPeriod, anchor.endPeriod)})",
            dueDateTimestamp = dueTimestamp,
            dueDateString = DATE_TIME.format(
                java.time.Instant.ofEpochMilli(dueTimestamp).atZone(zone)
            ),
            priority = TaskStar.encode(false),
            isCompleted = false,
            isAutoGenerated = true,
            generatedDate = DATE_ONLY.format(date),
            week = week,
            dayOfWeek = anchor.dayOfWeek.coerceIn(1, 7)
        )
    }

    private fun describe(
        type: TaskType,
        classroom: String,
        teacher: String,
        week: Int
    ): String = when (type) {
        TaskType.PRE_STUDY ->
            "第${week}周上课前完成教材对应小节与预习思考题（$teacher / $classroom）"

        TaskType.LAB_REPORT ->
            "根据${classroom}实验操作记录，整理测试用例与分析报告并提交"

        else ->
            "梳理第${week}周课堂重点，独立完成课后作业并在截止前按时提交"
    }

    private companion object {
        val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}