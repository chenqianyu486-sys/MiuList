package com.example.data.calendar

import java.time.LocalDate

/**
 * zju-ical 的校历配置 → 本项目内部模型的映射。
 *
 * 上游 `configs/config.json` 的两段关键语义照抄自
 * `pkg/zjuservice/zjuconst/class.go` 的 `ClassToVEvents`：
 *
 * ```
 * mondayOfFirstWeek = Begin − 距最近一个周过的天数 − 7 × (FirstWeekNo − 1)
 * ```
 *
 * 为什么不能「简化」成「取 Begin 所在周的周一」：`Begin` 通常是开学第一天，
 * 而第一周往往**跨到上一个自然周**（例如 9 月 22 日开学，而 9 月 22 日是周一，
 * 但也有学校是周日或周三开学）。钉死周一，周次就会整体偏移一天，
 * 随后单双周、第几周集体判错，而且错得很隐蔽——课还在，只是周次不对。
 *
 * 纯函数，不依赖 Android / Room，可在普通 JUnit 里直接测。
 */
object ZjuCalendarAdapter {

    /** zju-ical 的 `ClassTerm` 枚举（顺序即数值）。 */
    private val TERM_LABELS = listOf(
        "秋学期", "冬学期", "短学期A", "小学期", "春学期", "夏学期", "短学期B"
    )

    /** `Term` 整数 → 文案。与上游 `ClassTermStrToStr` 一致。 */
    fun termLabel(term: Int?): String =
        if (term != null && term in TERM_LABELS.indices) TERM_LABELS[term] else ""

    /** `YYYYMMDD` 整数 → epochDay。解析不了返回 null。 */
    fun yyyymmddToEpochDay(value: Int?): Long? {
        if (value == null) return null
        val text = value.toString()
        if (text.length != 8) return null
        return runCatching {
            LocalDate.of(
                text.substring(0, 4).toInt(),
                text.substring(4, 6).toInt(),
                text.substring(6, 8).toInt()
            ).toEpochDay()
        }.getOrNull()
    }

    /** epochDay → `YYYYMMDD` 整数。 */
    fun epochDayToYyyymmdd(epochDay: Long): Int {
        val d = LocalDate.ofEpochDay(epochDay)
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    /**
     * 上游公式：`mondayOfFirstWeek = Begin − (Begin 的周几 − 1) − 7 × (FirstWeekNo − 1)`。
     *
     * @param beginEpochDay 学期第一天，未必是周一。
     * @param firstWeekNo 这份校历里「第一周」的编号（浙大一直是 1）。
     */
    fun firstMondayOf(
        beginEpochDay: Long,
        firstWeekNo: Int
    ): Long {
        val dow = EpochDays.dayOfWeek1To7(beginEpochDay) // 1 = 周一
        return beginEpochDay - (dow - 1L) - 7L * (firstWeekNo - 1L)
    }

    /** 总周数 = `(End − firstMonday) / 7` 向下取整 + 1。 */
    fun totalWeeks(beginEpochDay: Long, endEpochDay: Long, firstWeekNo: Int): Int {
        val firstMonday = firstMondayOf(beginEpochDay, firstWeekNo)
        val span = endEpochDay - firstMonday
        if (span < 0) return 1
        return (span / 7L + 1L).toInt().coerceAtLeast(1)
    }
}