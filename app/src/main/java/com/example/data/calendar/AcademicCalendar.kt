package com.example.data.calendar

import com.example.data.timetable.WeekSet

/**
 * 一个学期的校历。字段与 JSON Schema 的 `semester` 段一一对应。
 *
 * [firstMondayEpochDay] 显式存而不是每次猜：学期起始日通常是「第一天上课」，
 * 未必落在周一，靠「取起始日所在周的周一」去猜，猜错就是**整体周次偏移**——
 * 单双周、第几周会集体判错且不易察觉。
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
    makeupDays: Map<Long, Int> = emptyMap()
) {
    private val noClass: Set<Long> = noClassDays
    private val makeup: Map<Long, Int> = makeupDays

    val hasSemester: Boolean get() = semester != null

    val totalWeeks: Int get() = semester?.totalWeeks ?: WeekSet.FALLBACK_TOTAL_WEEKS

    /** 第几周；不在学期内返回 0。 */
    fun weekOf(epochDay: Long): Int {
        val s = semester ?: return 0
        if (epochDay < s.firstMondayEpochDay) return 0
        val weeks = ((epochDay - s.firstMondayEpochDay) / 7L) + 1L
        if (weeks > s.totalWeeks) return 0
        return weeks.toInt()
    }

    fun isNoClass(epochDay: Long): Boolean = epochDay in noClass

    fun isMakeup(epochDay: Long): Boolean = epochDay in makeup

    fun makeupFor(epochDay: Long): Int? = makeup[epochDay]

    /**
     * 是否上课日：不在停课日；周末只有标了补课才算；学期内才算。
     *
     * 注意周末判断先于学期判断，这样「未配置学期」时也仍然保留
     * 「周六周日不上课」这条与学期无关的常识。
     */
    fun isSchoolDay(epochDay: Long): Boolean {
        if (isNoClass(epochDay)) return false
        if (EpochDays.isWeekend(epochDay) && !isMakeup(epochDay)) return false
        val s = semester ?: return true
        val w = weekOf(epochDay)
        return w in 1..s.totalWeeks
    }

    /**
     * 这天算星期几。补课日返回「补星期几」的课，**这是补课语义的核心**：
     * 10 月 10 日（周六）补的是周一 的课，课表上就该出周一的课。
     */
    fun effectiveDayOfWeek(epochDay: Long): Int =
        makeup[epochDay] ?: EpochDays.dayOfWeek1To7(epochDay)

    companion object {
        val EMPTY = AcademicCalendar()

        fun of(semester: Semester?, noClass: Set<Long>, makeup: Map<Long, Int>) =
            AcademicCalendar(semester, noClass, makeup)
    }
}