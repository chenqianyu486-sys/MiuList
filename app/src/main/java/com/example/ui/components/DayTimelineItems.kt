package com.example.ui.components

import com.example.data.local.CourseSlotEntity
import com.example.data.model.ExamEntry
import com.example.data.timetable.PeriodTable

/**
 * 当天列表里的一项：**要么是一节课，要么是一场考试**。
 *
 * ## 为什么两种东西要住进同一个类型
 *
 * 考试与课用的是**两套时间**：课是「第几节」，考试是「几点几分」。
 * 而两个页面（总览的今日列表、课表页的列表视图）都要把它们
 * **放进同一条时间线上按时刻排**。不共用一个类型的话，
 * 合并逻辑会写两遍 —— 而两遍排序迟早会对不上。
 *
 * 所以排序规则只有这一处：[mergeDayItems]。
 */
sealed interface DayItem {
    /** 当天第几分钟。用于排序；取不到时用 [Int.MAX_VALUE] 垫底。 */
    val startMinute: Int

    data class SlotItem(
        val slot: CourseSlotEntity,
        override val startMinute: Int
    ) : DayItem

    data class ExamItem(
        val entry: ExamEntry,
        override val startMinute: Int
    ) : DayItem
}

/**
 * 把当天的课与考试混成一条按时刻排好的列表。
 *
 * 课按作息表折算成钟点（[PeriodTable.startMinuteOf]），考试本来就有钟点。
 *
 * **时间未知的考试排在最后**：它没有可比的时间，
 * 按 0 排会把「09:00 的考试」挤到「时间待定」后面，而后者其实更该垫底。
 */
fun mergeDayItems(
    slots: List<CourseSlotEntity>,
    exams: List<ExamEntry>,
    periodTable: PeriodTable
): List<DayItem> {
    val slotItems = slots.map { slot ->
        DayItem.SlotItem(
            slot = slot,
            startMinute = periodTable.startMinuteOf(slot.startPeriod) ?: Int.MAX_VALUE
        )
    }
    val examItems = exams.map { exam ->
        DayItem.ExamItem(
            entry = exam,
            startMinute = if (exam.startMinute > 0) exam.startMinute else Int.MAX_VALUE
        )
    }
    return (slotItems + examItems).sortedBy { it.startMinute }
}

/**
 * 近程时间线取哪几天：**今天（若有日程）+ 后面 [extra] 个有日程的日子**。
 *
 * ## 「有日程」= 有课**或**有考试
 *
 * 只有考试的那天当然也是有日程的 —— 而且那一段里只有这一件事。
 * 不算的话那天的考试就永远显示不出来。
 *
 * ## 今天自己没日程就不出今天这一段
 *
 * 于是总组数会少于 [extra] + 1 —— 这是按「当天及接下来几天」的字面意思来的，
 * 不是漏算。
 *
 * @param hasSchedule 这一天有没有日程（课或考试）
 * @param extra 今天之外还要取几天
 * @param horizon 最多往后找几天
 */
fun pickDayOffsets(
    hasSchedule: (Int) -> Boolean,
    extra: Int,
    horizon: Int
): List<Int> {
    val picked = mutableListOf<Int>()
    var extraPicked = 0
    var offset = 0
    while (offset < horizon && extraPicked < extra) {
        if (hasSchedule(offset)) {
            picked += offset
            // 今天（offset 0）不占「额外」的名额。
            if (offset > 0) extraPicked++
        }
        offset++
    }
    return picked
}

/**
 * 这一项在 LazyColumn 里的 key。
 *
 * @param dayOffset 第几天。课表页的列表里同一门课会在不同日期重复出现，
 *   所以 key 必须带日期；总览的列表只有今天，用 0 即可。
 */
fun DayItem.lazyKey(dayOffset: Int): String = when (this) {
    is DayItem.SlotItem -> "slot_${dayOffset}_${slot.id}"
    is DayItem.ExamItem -> "exam_${dayOffset}_${entry.id}"
}

/** 「下一件事」是课还是考试。 */
enum class FeaturedPick {
    CLASS,
    EXAM,
    NONE
}

/**
 * 「下一件事」只有一个：课与考试**抢同一个位置**。
 *
 * ## 为什么不能各挑一个
 *
 * 两边各挑一个加高的话，屏幕上会同时出现两张「下一节」与「下一场」，
 * 用户得自己判断哪个才是真的下一件 —— 而时间线上明明只有一个下一件。
 *
 * ## 判据
 *
 * 1. **正在进行的优先**（课在上还是考试在上不重要，重要的是「正在发生」）；
 * 2. 都没在进行 → **谁更早开始谁上**；
 * 3. 同刻开始 → 课优先（它是默认项，用户对「下一节课」的预期更强）。
 *
 * 抽成纯函数是为了能直接单测：这条规则决定屏幕上**哪张卡被加高**，
 * 判错一次用户就会盯着错误的倒计时。
 */
fun pickFeatured(
    currentClassStart: Long?,
    currentExamStart: Long?,
    nextClassStart: Long?,
    nextExamStart: Long?
): FeaturedPick = when {
    currentClassStart != null -> FeaturedPick.CLASS
    currentExamStart != null -> FeaturedPick.EXAM
    nextClassStart == null && nextExamStart == null -> FeaturedPick.NONE
    nextClassStart == null -> FeaturedPick.EXAM
    nextExamStart == null -> FeaturedPick.CLASS
    nextClassStart <= nextExamStart -> FeaturedPick.CLASS
    else -> FeaturedPick.EXAM
}
