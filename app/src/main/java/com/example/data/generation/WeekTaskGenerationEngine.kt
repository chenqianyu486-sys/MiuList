package com.example.data.generation

import com.example.data.calendar.AppZone
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
 * 一个「周 × 类型」范围：这一周里，哪些课、为哪些类型产出待办。
 *
 * 范围之所以做成一个列表而不是单个 `week`，是因为**三类待办的视野不一样**：
 * 作业与实验报告是「上完课才交」，看得过去就还来得及，所以只看本周；
 * 预习是「上课之前」要做的事，只看本周的话周二打开 App 就已经把周三的课预习完了，
 * 于是它要一路看到**下周结束**。同一周里的课因此会被两个不同类型的范围各用一次。
 */
data class WeekScope(
    val week: Int,
    /** 该周的周一（epochDay）。整周的日期都从它推。 */
    val weekMondayEpochDay: Long,
    /** 已按该周过滤（含单双周 / 停课 / 补课）的时段 */
    val slots: List<CourseSlotEntity>,
    /** 本范围只覆盖这些类型；null = 全部自动类型。 */
    val types: Set<TaskType>? = null,
    /**
     * 只保留「**开课时刻**不早于这一刻」的课；null = 整周都要。
     *
     * 「今天起至下周结束」由它表达，但比「今天 00:00」更准一档：
     * 预习的截止就是开课前几分钟（[GenerationRuleResolver] 的 `dueBeforeMinutes`），
     * 所以下午两点生成时，当天上午那节课的截止时刻**已经过去了** ——
     * 按日期下界放它进来，产出的是一条一出生就逾期的待办。
     * 因此这个下界取「现在」，让范围只覆盖还没上的课。
     *
     * 它同时是「已经过去就别再补」的判据：本周上过的课、以及今天已经开课的那节，
     * 都不会产出预习；而作业与实验报告不需要这个界（它们是上完课才交，截止还在未来）。
     *
     * 过滤发生在选锚点**之前**：本周只剩周三一节课的课在周四就该一条都不产出，
     * 而不是锚到已经上过的周一再补一个日期。
     */
    val fromStartTimestamp: Long? = null
)

/**
 * 「一键生成本周待办」的引擎。
 *
 * 改造前这里只处理**某一天**（`generateTasksForDay`），标题是「【作业】高数 习题」，
 * 去重靠 `generatedDate`。现在按**一整周**产出，标题是「【高数】第3周作业」，
 * 去重靠「同课程 + 同类型 + 同周次 + 同周几」四元组。
 *
 * 六条关键决定：
 * 1. **时段由调用方先按周过滤好再传进来**（`ScheduleResolver.slotsInWeek`），
 *    所以这里不关心星期几、单双周、停课补课；它只做「这些课要产出哪些待办」。
 * 2. **范围按类型分**（[WeekScope]）：作业与实验报告取本周，预习取今天起至下周结束。
 * 3. **「每周一次」的锚点取该周最早的那节课**。锚点一旦确定，这条待办的截止日在一周之内
 *    就是稳定的，不会因为多开一次 App、多生成一次就往后漂。锚点在 [WeekScope.fromStartTimestamp]
 *    过滤**之后**选，「今天起」才不会锚到已经上过的课。
 * 4. 「每周一次」也照常记下锚点那天的周几进 `dayOfWeek`，于是四元组判据天然唯一，
 *    不需要为它另开一个特例。
 * 5. **不预置星标**：`priority` 恒为 `STAR_UNMARKED`。星标留给用户手动标。
 * 6. **不预置备注**：`description` 恒为空。备注是用户自己写的那部分，
 *    引擎替用户写的那句谁都能写出来，写出来却让人以为那是有用的信息。
 *
 * 无 Android / Room 依赖，可在普通 JUnit 里直接测。
 */
class WeekTaskGenerationEngine(private val periodTable: PeriodTable) {

    data class Input(
        val scopes: List<WeekScope>,
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
        // 时区钉死成北京时间：待办的「开课时刻 / 截止时刻」都是浙大的墙上时间。
        // 用系统时区会让 GMT 设备上的预习截止整体偏 8 小时 —— 界面上写着 09:55，
        // 算出来却已经是前一天的 17:55，一条刚生成就逾期的待办。
        val zone = AppZone.ZONE
        val out = mutableListOf<TaskEntity>()

        for (scope in input.scopes) {
            out += buildScope(scope, input, zone, hasExisting)
        }

        // 跨范围之后按上课时间排：范围一多（作业看本周、预习看到下周），
        // 不排的话同一个「本次要生成什么」在列表里会分成两截，看着像漏了一部分。
        return out.sortedWith(compareBy({ it.startTimeTimestamp }, { it.courseId }, { it.id }))
    }

    private suspend fun buildScope(
        scope: WeekScope,
        input: Input,
        zone: ZoneId,
        hasExisting: ExistingChecker
    ): List<TaskEntity> {
        val monday = LocalDate.ofEpochDay(scope.weekMondayEpochDay)

        // 一天里的多个时段按「周几 + 开始节次」排好序；
        // 「每周一次」的锚点就是每门课排完之后的第一个。
        val ordered = scope.slots
            // 「今天起」先砍掉已经过去 / 已经开课的课，再谈锚点。
            .filter { scope.fromStartTimestamp == null || startTimestampOf(it, classDateOf(it, monday), zone) >= scope.fromStartTimestamp }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.id }))

        val out = mutableListOf<TaskEntity>()

        for ((courseId, courseSlots) in ordered.groupBy { it.courseId }) {
            val course = input.coursesById[courseId] ?: continue
            // 本周没有这门课的时段 —— groupBy 天然不会产生这一组，不用额外判
            for (type in GenerationRuleResolver.AUTO_TYPES) {
                if (scope.types != null && type !in scope.types) continue
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
                    val date = classDateOf(anchor, monday)
                    if (hasExisting.exists(courseId, type, scope.week, dayOfWeek)) continue
                    out += buildTask(course, anchor, date, scope.week, type, rule, zone)
                }
            }
        }

        return out
    }

    /**
     * 某节课在它所属那一周里的开课时刻（epochMilli）。
     *
     * [classDate] 是「那一周的周一 + 周几 - 1」，也就是这节课真正上的那天。
     * 范围过滤与 [buildTask] 必须用**同一个**算法：过滤按 A 算、写入按 B 算的话，
     * 会出现「判定还在未来、产出的截止却已经过去」这种自相矛盾的待办。
     */
    private fun startTimestampOf(slot: CourseSlotEntity, classDate: LocalDate, zone: ZoneId): Long {
        val startMinute = periodTable.startMinuteOf(slot.startPeriod)
        return classDate
            .atTime((startMinute ?: 8 * 60) / 60, (startMinute ?: 8 * 60) % 60)
            .atZone(zone).toInstant().toEpochMilli()
    }

    /** 该周周一 + 周几 - 1：这节课真正落在哪一天。 */
    private fun classDateOf(slot: CourseSlotEntity, monday: LocalDate): LocalDate =
        monday.plusDays((slot.dayOfWeek.coerceIn(1, 7) - 1).toLong())

    private fun buildTask(
        course: CourseEntity,
        anchor: CourseSlotEntity,
        date: LocalDate,
        week: Int,
        type: TaskType,
        rule: TaskGenerationRule,
        zone: ZoneId
    ): TaskEntity {
        val startTimeText = periodTable.slotOf(anchor.startPeriod)?.startTime ?: "08:00"
        val startTimestamp = startTimestampOf(anchor, date, zone)

        // 预习挂在某一节课上（提前 N 分钟），作业与实验是收尾型任务（上课日 + N 天 23:59）。
        val dueTimestamp = when (type) {
            TaskType.PRE_STUDY ->
                startTimestamp - rule.dueBeforeMinutes * 60_000L

            else -> date.plusDays(rule.dueOffsetDays.toLong())
                .atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        }
        val dueDate = java.time.Instant.ofEpochMilli(dueTimestamp)
            .atZone(zone).toLocalDate()

        // 自动生成的待办**一律不标星**。星标语义见 `TaskStar`：它是「我挑出来的」，
        // 只有手动勾的才算数。按类型预置星标（曾是预习 + 实验报告）会让整屏凭空多出
        // 一片星，而用户从没挑过它们，于是「标星」恰好不再表示「我挑的」。
        // 想标哪条，一键生成之后自己点一下即可。
        return TaskEntity(
            courseId = course.id,
            courseName = course.name,
            courseCategory = course.category,
            courseColorHex = course.colorHex,
            title = buildTitle(course, week, type, anchor, rule),
            // **不自带备注**。备注是用户自己写的那部分（老师改了什么要求、
            // 作业交到哪、书留在哪），引擎替用户写一句「整理第N周课堂重点并完成
            // 课后作业」——它谁都能写出来，写出来却让人以为那是有用的信息。
            //
            // 空着更好：空备注在编辑弹层里有一句「点击编辑备注」的小字提示，
            // 是一条**邀请**；塞一句模板话则是一段没人读的正文。
            description = "",
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

    /**
     * 自动生成待办的**名称**。
     *
     * 形状：`【课程名】第N周周几作业`。
     *
     * - **课程名放进【】**：名字里自带「哪门课」，于是这条待办被单独拎出来的时候
     *   （搜索结果、待办详情、导出、通知）仍然说得清自己是谁的活儿。
     * - **周几只在按节生成时才有**：「每次课」这条待办对应的就是某一节课，
     *   落在周几是它的一部分；「每周一次」覆盖的是整周，写上某一天反而会误导
     *   ——读起来像只有那天要交。
     * - **周几不加括号**：一个名字里混两种括号（【】和（））看着像两段拼起来的，
     *   而它本来就是一件事。去掉之后整条名字是一串连续的字。
     *
     * 卡片上显示的名字会把 `【课程名】` 裁掉（见 `taskDisplayTitle`）：
     * 卡片的 meta 行里本来就写着课程名，标题再带一遍是同一句话说两遍。
     */
    private fun buildTitle(
        course: CourseEntity,
        week: Int,
        type: TaskType,
        anchor: CourseSlotEntity,
        rule: TaskGenerationRule
    ): String {
        val typeWord = when (type) {
            TaskType.PRE_STUDY -> "预习"
            TaskType.LAB_REPORT -> "实验报告"
            else -> "作业"
        }
        val weekday = if (rule.perClass) getDayName(anchor.dayOfWeek) else ""
        return "【${course.name}】第${week}周${weekday}${typeWord}"
    }

    private companion object {
        val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}