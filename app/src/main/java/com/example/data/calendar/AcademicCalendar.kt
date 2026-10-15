package com.example.data.calendar

import com.example.data.timetable.WeekSet

/**
 * 一个学期的校历。字段与 JSON Schema 的 `semester` 段一一对应。
 *
 * [firstMondayEpochDay] 显式存而不是每次猜：学期起始日通常是「第一天上课」，
 * 未必落在周一，靠「取起始日所在周的周一」去猜，猜错就是**整体周次偏移**——
 * 单双周、第几周会集体判错且不易察觉。
 *
 * 一个 [name] 是**学年学期**（秋冬 / 春夏，16 周），不是半学期。
 * [startEpochDay] / [endEpochDay] 覆盖秋(1–8 周)与冬(9–16 周)两段，
 * 见 [com.example.data.codec.academicTermOf]。
 */
data class Semester(
    val name: String,
    val yearSpan: String,
    val term: String,
    val startEpochDay: Long,
    val firstMondayEpochDay: Long,
    val endEpochDay: Long,
    val totalWeeks: Int,
    val periodsPerDay: Int
) {
    fun dateRangeOfWeek(week: Int): Pair<Long, Long>? {
        if (week < 1 || week > totalWeeks) return null
        val start = firstMondayEpochDay + (week - 1) * 7L
        return start to (start + 6L)
    }
}

/**
 * 一次「哪天调到哪天」。
 *
 * 调休是**日期到日期**的搬运，不是「把星期几挪一下」：同一条规则的两端
 * 可以隔两周以上，所以两端都得记成具体日期。
 *
 * - [dayOfWeek] 补课日（To）上**星期几**的课 → 决定界面上出哪门课
 * - [sourceEpochDay] 那份课**原本属于哪一天**（From）→ 决定它算第几周
 *
 * [sourceEpochDay] 为 0 表示来源日未知，此时周次退回按补课当天算。
 */
data class MakeupShift(
    val dayOfWeek: Int,
    val sourceEpochDay: Long = 0L
)

/**
 * 校历判定。**唯一的「某天是否上课、算第几周、算星期几」入口。**
 *
 * 纯函数，不依赖 Android / Room / DAO，可在普通 JUnit 里直接测。
 *
 * 未配置学期（[semester] == null）时的降级路径，保证与改造前行为逐像素一致：
 * - [totalWeeks] 回落 [WeekSet.FALLBACK_TOTAL_WEEKS]（与改造前 `coerceIn(1, 25)` 同上界）
 * - [weekOf] 返回 0（表示「无周次概念」），调用方据此忽略开课周次过滤
 * - 停课 / 补课仍生效（它们是日期型数据，不依赖学期）
 */
class AcademicCalendar(
    val semester: Semester? = null,
    noClassDays: Set<Long> = emptySet(),
    makeupDays: Map<Long, MakeupShift> = emptyMap()
) {
    private val noClass: Set<Long> = noClassDays
    private val makeup: Map<Long, MakeupShift> = makeupDays

    val hasSemester: Boolean get() = semester != null

    val totalWeeks: Int get() = semester?.totalWeeks ?: WeekSet.FALLBACK_TOTAL_WEEKS

    /**
     * 这个日期**在日历上**是第几周。补课日按它自己所在的那一周算。
     *
     * 排课要用的不是这个，见 [teachingWeekOf]。
     */
    fun weekOf(epochDay: Long): Int {
        val s = semester ?: return 0
        if (epochDay < s.firstMondayEpochDay) return 0
        val weeks = ((epochDay - s.firstMondayEpochDay) / 7L) + 1L
        if (weeks > s.totalWeeks) return 0
        return weeks.toInt()
    }

    /**
     * 这一天的课**算第几周**，也就是筛「开课周次」时该用的周号。
     *
     * 补课日归属**来源日**那一周，不是补课当天那一周。
     *
     * 2026 国庆的实例：10-02（周五）的课挪到 10-17（周六）补，
     * 10-02 在第 3 周、10-17 在第 5 周。用 5 的话，这场补上来的周五课
     * 会去匹配第 5 周的单双周——要么凭空多出一次课，要么一次都不出，
     * 而且界面上看不出哪里不对。
     *
     * 不知道来源日时（[MakeupShift.sourceEpochDay] 为 0，v1 旧文档与手工填的行）
     * 退回 [weekOf]，也就是旧有行为。
     */
    fun teachingWeekOf(epochDay: Long): Int {
        val source = makeup[epochDay]?.sourceEpochDay?.takeIf { it > 0L }
        return weekOf(source ?: epochDay)
    }

    fun isNoClass(epochDay: Long): Boolean = epochDay in noClass

    fun isMakeup(epochDay: Long): Boolean = epochDay in makeup

    fun makeupFor(epochDay: Long): Int? = makeup[epochDay]?.dayOfWeek

    /** 这次补课的完整搬运信息（周几 + 来源日）。 */
    fun shiftFor(epochDay: Long): MakeupShift? = makeup[epochDay]

    /**
     * 这天的课被搬到别的日子去了吗（`Move` 的 From 那天）。
     *
     * 上游写的是「10月2日（周五）停课，安排在10月17日（周六）补课」——
     * From 那天要让出课。但**不把它抄进 `noClassDays`**：同一事实存两处，
     * 导出时会被合成一条 CLEAR 加一条 MOVE，导回来凭空多出一个停课日。
     * 这里从 `makeup` 推导，唯一真源只有一张表。
     *
     * 判据是「它是某次搬运的来源，且自己不是任何一次搬运的目标」：
     * - `Move`（10-02 → 10-17）：10-02 是来源、不是目标 → 让出 ✓
     * - `Exchange`（10-06 ↔ 09-20）：两侧互为来源与目标 → 都不让出 ✓
     *   （它俩当天照常上课，只是上的不是自己那套课）
     * - 来源日未知的老数据（`sourceEpochDay == 0`）：无从判断，一律不让出，
     *   也就是维持升级前的行为。
     */
    fun isVacatedForShift(epochDay: Long): Boolean {
        if (makeup.containsKey(epochDay)) return false
        return makeup.values.any { it.sourceEpochDay == epochDay }
    }

    /**
     * 是否上课日：不在停课日；让出课的日子不上；周末只有标了补课才算；学期内才算。
     *
     * 注意周末判断先于学期判断，这样「未配置学期」时也仍然保留
     * 「周六周日不上课」这条与学期无关的常识。
     *
     * 周次用 [weekOf]（补课当天那一周）而不是 [teachingWeekOf]：这里判的是
     * 「这一天要不要显示课表」，来源周次只影响显示**哪些**课，不影响那天有没有课。
     */
    fun isSchoolDay(epochDay: Long): Boolean {
        if (isNoClass(epochDay)) return false
        if (isVacatedForShift(epochDay)) return false
        if (EpochDays.isWeekend(epochDay) && !isMakeup(epochDay)) return false
        val s = semester ?: return true
        val w = weekOf(epochDay)
        return w in 1..s.totalWeeks
    }

    /**
     * 这天算星期几。补课日返回「补星期几」的课，**这是补课语义的核心**：
     * 10 月 17 日（周六）补的是周五 的课，课表上就该出周五的课。
     */
    fun effectiveDayOfWeek(epochDay: Long): Int =
        makeup[epochDay]?.dayOfWeek ?: EpochDays.dayOfWeek1To7(epochDay)

    companion object {
        val EMPTY = AcademicCalendar()

        fun of(semester: Semester?, noClass: Set<Long>, makeup: Map<Long, MakeupShift>) =
            AcademicCalendar(semester, noClass, makeup)
    }
}
