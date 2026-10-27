package com.example.data.model

import com.squareup.moshi.JsonClass

/**
 * **渲染好的**周课表：一列 = 这一周里的一个真实日期。
 *
 * ## 为什么它是「渲染结果」而不是原始规则
 *
 * 一列是「这一周里的一个**真实日期**」，不是「星期几」——
 * 补课只有在这一层才表达得出来：10-17 是周六，它要出周五的课，
 * 而同一周的 10-16（周五）反而一节都没有。
 *
 * 哪些课落在哪一天牵扯四件事：开课周次、停课日、补课日、学期范围，
 * 而这些规则在 App 里已经收口到 `ScheduleResolver` 一个地方了。
 * 在网页端重写一份的代价不是「多写几十行」，而是**两边迟早给出不同的答案**，
 * 而用户看到不一致时只会觉得网页端算错了 —— 他不知道手机那边有一份权威实现。
 *
 * 所以：**手机算好发过去，网页端只负责画。**
 *
 * ## 它同时出现在两个地方
 *
 * - **局域网同步协议**：`WebSnapshotDto.timetable`（见 `sync/web`）。
 * - **整机快照文件**：`ScheduleDocumentDto.weekPlan`（见 `codec`）。
 *
 * 同一份形状，两个地方各用各的 —— 所以它住在 `data/model` 这个共享词汇层，
 * 而不是任何一边里。网页端导入手机导出的文件时，靠它把周视图直接画出来；
 * 网页端自己导出的文件里也带着它，于是文件在两端之间来回搬，周视图不会消失。
 *
 * ## 载荷为什么长这样
 *
 * - **时段只存一份**（[WeekPlanDto.slotList]），周计划里存下标（[WeekPlanDayDto.slotIndexes]）。
 *   一门课在一学期里要出几十次，全文重复就是几十份一模一样的 JSON；
 * - **考试不重复**：一场考试只落在**一天**上，所以整张表里它只出现一次，
 *   内容直接内联进那一天。
 */
@JsonClass(generateAdapter = true)
data class WeekPlanDto(
    /** 学期总周数。翻周箭头的上界。 */
    val totalWeeks: Int = 0,
    /** 当前周次（顶栏与「本周」标识用）。 */
    val currentWeek: Int = 0,
    /** 一天几节。栅格的行数。 */
    val periodsPerDay: Int = 0,
    /** 每节的起止分钟（自 00:00 起）。给「这一节几点到几点」用。 */
    val periods: List<WeekPeriodDto> = emptyList(),
    /** 去重后的全部时段。周计划按下标引用它。 */
    val slotList: List<WeekPlanSlotDto> = emptyList(),
    /** 每一周，按周次排好。 */
    val weeks: List<WeekPlanWeekDto> = emptyList(),
)

/** 一节课的时间定义（自 00:00 起的分钟数）。 */
@JsonClass(generateAdapter = true)
data class WeekPeriodDto(
    val period: Int = 0,
    val startMinute: Int = 0,
    val endMinute: Int = 0,
)

/** 一个时段。课程由 [courseKey] 指向 `courses[]` 里那门课。 */
@JsonClass(generateAdapter = true)
data class WeekPlanSlotDto(
    val courseKey: String = "",
    val startPeriod: Int = 0,
    val endPeriod: Int = 0,
    val classroom: String = "",
)

/** 一周。 */
@JsonClass(generateAdapter = true)
data class WeekPlanWeekDto(
    val week: Int = 0,
    /** 周一到周日，**七列恒在**（少一列整张表会左右错位）。 */
    val days: List<WeekPlanDayDto> = emptyList(),
)

/**
 * 周课表里的一天 = 一列。
 *
 * [date] / [dayOfWeek] 说的是**这一天本身**；[slotIndexes] 里装的却是
 * 「这一天要上的课」—— 补课日补的是别的星期几的课，那部分信息在
 * [makeupDayOfWeek] 里。
 */
@JsonClass(generateAdapter = true)
data class WeekPlanDayDto(
    /** `YYYY-MM-DD`，北京时间。 */
    val date: String = "",
    /** 这天**真实**的星期几（1=周一 … 7=周日）。补课日也照实报它本来的星期几。 */
    val dayOfWeek: Int = 0,
    /**
     * 这一天为什么没课：`NO_CLASS` / `VACATED` / `WEEKEND` / `OUT_OF_TERM`，
     * 空 = 正常上课日。
     *
     * 周末是**软**理由（用户自己排了课就有课），其余三种是硬理由。
     */
    val offReason: String? = null,
    /** 补课日：补的是星期几的课（1..7）。空 = 不是补课日。 */
    val makeupDayOfWeek: Int? = null,
    /** 这天要上的课。下标指向 [WeekPlanDto.slotList]。 */
    val slotIndexes: List<Int> = emptyList(),
    /** 这天的考试（考试只有钟点，节次已按作息表换算好）。 */
    val exams: List<WeekPlanExamDto> = emptyList(),
)

/** 周课表格子里的一场考试。 */
@JsonClass(generateAdapter = true)
data class WeekPlanExamDto(
    val courseName: String = "",
    val colorHex: String = "",
    val startPeriod: Int = 0,
    val endPeriod: Int = 0,
    /** 开考时刻（自 00:00 起的分钟数）。0 = 学校还没公布。 */
    val startMinute: Int = 0,
    val classroom: String = "",
    val seat: String = "",
)
