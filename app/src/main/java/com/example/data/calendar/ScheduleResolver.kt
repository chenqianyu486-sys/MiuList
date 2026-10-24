package com.example.data.calendar

import com.example.data.local.CourseSlotEntity

/**
 * 周课表的一列 = 学期里**真实的某一天**，而不是「星期几」。
 *
 * 这是周课表和「一周的结构」之间唯一也最重要的区别：2026-10-17 是周六，
 * 但它要出周五的课，同一周里的 10-16（周五）反而一节都没有。
 * 按星期几分列就永远表达不出这件事，只会把补课挪走的那一周显示错。
 */
data class WeekDayPlan(
    val epochDay: Long,
    /** 这天**真实**的星期几（1=周一 … 7=周日）。补课日也照实报它本来的星期几。 */
    val dayOfWeek: Int,
    /** 这天要上的课，已按开课周次筛过、也套好了调休。 */
    val slots: List<CourseSlotEntity>,
    /** 补课日：补的是星期几的课（1..7）。null = 不是补课日。 */
    val makeupDayOfWeek: Int? = null,
    val offReason: DayOffReason? = null
) {
    val isMakeup: Boolean get() = makeupDayOfWeek != null
}

/**
 * 排课判定的**唯一入口**。
 *
 * 改造前筛 `dayOfWeek` 的逻辑散在 4 处且语义已经开始漂移：
 * `DashboardScreen` 的「今日课程」、`ScheduleScreen` 的周课表每列、
 * `ScheduleScreen.buildDayGroups` 的列表页分组、以及 Repository 的
 * `getSlotsByDaySync`。加入开课周次、停课、补课后，这 4 处会被同时改坏，
 * 因此统一收口到这里。
 *
 * 无状态、不依赖 Android / Room / DAO，可在普通 JUnit 里直接测。
 *
 * @param weeksBySlotId 每个时段的「开课周次」集合。某时段缺 key = 未指定 = 每周。
 */
class ScheduleResolver(val calendar: AcademicCalendar) {

    /**
     * **校历口径**：这天是不是学校规定的上课日。停课日 / 普通周末返回 false。
     *
     * 注意它**不**决定「那天显示哪些课」—— 那是 [slotsOn] 的事，而 [slotsOn]
     * 的闸门不含周末（周末排了课就该出课）。两者口径不同是**有意的**：
     * 「周六不是上学日」是常识，「周六有课」是用户自己的排课，两件事都成立。
     */
    fun isSchoolDay(epochDay: Long): Boolean = calendar.isSchoolDay(epochDay)

    /**
     * 这天的课。空时段列表由调用方自行跳过成组（列表页要靠这个判断是否出分组）。
     *
     * ## 周末为什么不再是闸门
     *
     * 以前这里第一行是 `if (!calendar.isSchoolDay(epochDay)) return emptyList()`，
     * 而 [AcademicCalendar.isSchoolDay] 把周末判成「不上课」。于是用户自己在周六
     * 排的课，总览的「今日课程」与课表页的「列表」视图**都看不见**，
     * 只有周课表格栅格（[weekPlan]）认 —— 同一份数据在两个视图里给出相反的答案。
     *
     * 现在闸门只看**硬**理由（停课 / 让出 / 学期外，见 [AcademicCalendar.isOffDay]），
     * 周末交给排课数据自己说话：**周六排了课就出课，没排就空着**。
     *
     * 筛开课周次时用 [AcademicCalendar.teachingWeekOf] 而不是 [AcademicCalendar.weekOf]：
     * 补课日归属**来源日**那一周，10-17 补的 10-02 的课要按第 3 周去匹配。
     */
    fun slotsOn(
        epochDay: Long,
        allSlots: List<CourseSlotEntity>,
        weeksBySlotId: Map<Long, Set<Int>> = emptyMap()
    ): List<CourseSlotEntity> {
        if (calendar.isOffDay(epochDay)) return emptyList()
        val targetDow = calendar.effectiveDayOfWeek(epochDay)
        val candidates = allSlots.filter { it.dayOfWeek == targetDow }
        if (!calendar.hasSemester) {
            // 未配置学期 → 忽略开课周次，行为与改造前完全一致
            return candidates.sortedBy { it.startPeriod }
        }
        val week = calendar.teachingWeekOf(epochDay)
        return candidates.filter { occursInWeek(it.id, week, weeksBySlotId) }
            .sortedBy { it.startPeriod }
    }

    /**
     * 周课表要的一周：**按真实日期**排的 7 列。
     *
     * 固定返回 7 条（周一…周日），空的那天也占一列——周课表有固定的 7 列栅格，
     * 少一列会让整张表左右错位，而「周六没课」这件事本来就该由列头的状态徽标说。
     *
     * 每一列的课 = **那天自己的星期几**（补课日换成它补的那个星期几），
     * 再按那一天实际所属的教学周筛开课周次。补课日取的是**来源日**那一周，
     * 10-02 的课挪到 10-17，那节课的身份仍然是「第 3 周的周五课」——
     * 换成 10-17 所在的那一周去筛，单双周的课会凭空多出一次或者一次都不出。
     *
     * **周末不是闸门，只是一个标签。** 这里刻意没有走 [slotsOn]：
     * 它按日期流水判断，会用「周末默认不上课」把整列挡掉，于是排进了课表的
     * 周六课在周课表里会整列消失。周末是「默认没课」，不是「一定没课」——
     * 用户自己的排课数据说那天有课，就该认。
     *
     * 现在 [slotsOn] 也改成同样的口径了（闸门只看硬理由），两个视图不再打架。
     */
    fun weekPlan(
        week: Int,
        allSlots: List<CourseSlotEntity>,
        weeksBySlotId: Map<Long, Set<Int>> = emptyMap()
    ): List<WeekDayPlan> {
        val monday = firstMondayOfWeek(week)
        return (0 until 7).map { offset ->
            val day = monday + offset
            val makeupDow = calendar.makeupDayOfWeek(day)
            // 「为什么没课」只判一次，周课表与列表页共用同一套口径（见 AcademicCalendar.dayOffReason）。
            val off = calendar.dayOffReason(day)
            val dow = makeupDow ?: EpochDays.dayOfWeek1To7(day)
            // 没配置学期时 teachingWeekOf 恒为 0，而 occursInWeek 会把 0 判成「不开课」，
            // 所以这里要显式退回「不看开课周次」——与 slotsOn 的降级路径保持一致。
            val teachingWeek = calendar.teachingWeekOf(day).takeIf { calendar.hasSemester }
            val candidates = allSlots.filter { it.dayOfWeek == dow }
            val slots = when {
                off == DayOffReason.NO_CLASS ||
                    off == DayOffReason.VACATED ||
                    off == DayOffReason.OUT_OF_TERM -> emptyList()
                teachingWeek == null -> candidates.sortedBy { it.startPeriod }
                else -> candidates
                    .filter { occursInWeek(it.id, teachingWeek, weeksBySlotId) }
                    .sortedBy { it.startPeriod }
            }
            WeekDayPlan(
                epochDay = day,
                dayOfWeek = EpochDays.dayOfWeek1To7(day),
                slots = slots,
                makeupDayOfWeek = makeupDow,
                // 周末**排了课** → 那天就不是「没课日」了。列头因此不会再把它说成休息日，
                // 而用户看到的正是自己排的课 —— 两边不打架。
                // 周末没排课 → 保留 [DayOffReason.WEEKEND]，那一列是空的，标签仍然成立。
                offReason = if (off == DayOffReason.WEEKEND && slots.isNotEmpty()) null else off
            )
        }
    }

    /**
     * 第 [week] 周的周一。
     *
     * 有学期时用学期里显式存的 `firstMondayEpochDay`（见 [Semester] 里为什么不能猜）。
     * 没有学期时以**今天所在周**当第 1 周往后推：那时的数据里没有任何周次基准，
     * 不这么锚定，列头就报不出真实日期，「10 月 12 日停课」这类提示也就无从谈起。
     */
    fun firstMondayOfWeek(week: Int, today: Long = EpochDays.today()): Long {
        val firstMonday = calendar.semester?.firstMondayEpochDay
            ?: EpochDays.mondayOf(today)
        return firstMonday + (week - 1).coerceAtLeast(0) * 7L
    }

    /**
     * 周课表：**按教学周**投影出来的时段（星期几 → 时段），不看具体日期。
     *
     * 它和 [weekPlan] 是两件不同的事，别混用：
     * - [weekPlan] 回答「第 5 周（10/12–10/18）这七天分别上什么课」，是日历视图；
     * - 这里回答「第 5 周开课的时段有哪些」，是教学周视图。
     *
     * 后者供「总览的本周课时」和「按周生成待办」使用：这两件事算的是一周的课量，
     * 与课被调到了周几无关；而按周生成的去重键是 (课程, 类型, 周次, 星期几)，
     * 若改用 [weekPlan]，调休补上来的那节课会按补课当天的星期几再生成一次，
     * 和它来源日那次撞成两条待办。
     */
    fun slotsInWeek(
        week: Int,
        allSlots: List<CourseSlotEntity>,
        weeksBySlotId: Map<Long, Set<Int>> = emptyMap()
    ): Map<Int, List<CourseSlotEntity>> {
        val result = LinkedHashMap<Int, List<CourseSlotEntity>>()
        for (day in 1..7) {
            val daySlots = allSlots.filter { it.dayOfWeek == day }.sortedBy { it.startPeriod }
            val visible = if (calendar.hasSemester) {
                daySlots.filter { occursInWeek(it.id, week, weeksBySlotId) }
            } else {
                daySlots
            }
            if (visible.isNotEmpty()) result[day] = visible
        }
        return result
    }

    /** 时段在第 [week] 周是否开课。空集合 = 每周（与改造前一致）。 */
    fun occursInWeek(slotId: Long, week: Int, weeksBySlotId: Map<Long, Set<Int>>): Boolean {
        if (week < 1) return false
        val weeks = weeksBySlotId[slotId] ?: return true
        if (weeks.isEmpty()) return true
        return week in weeks
    }

    /**
     * 当前周次。顶栏「第 N 周」的数据源。
     *
     * 未配置学期时返回 [FALLBACK_CURRENT_WEEK]（6），与改造前
     * `MutableStateFlow(6)` 的默认值一致。
     */
    fun currentWeek(todayEpochDay: Long = EpochDays.today()): Int {
        val w = calendar.weekOf(todayEpochDay)
        return if (w > 0) w else FALLBACK_CURRENT_WEEK
    }

    companion object {
        const val FALLBACK_CURRENT_WEEK = 6
    }
}