package com.example.data.calendar

import com.example.data.local.CourseSlotEntity

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

    /** 这天是否要显示课表。停课日 / 普通周末返回 false。 */
    fun isSchoolDay(epochDay: Long): Boolean = calendar.isSchoolDay(epochDay)

    /**
     * 这天的课。空时段列表由调用方自行跳过成组（列表页要靠这个判断是否出分组）。
     */
    fun slotsOn(
        epochDay: Long,
        allSlots: List<CourseSlotEntity>,
        weeksBySlotId: Map<Long, Set<Int>> = emptyMap()
    ): List<CourseSlotEntity> {
        if (!calendar.isSchoolDay(epochDay)) return emptyList()
        val targetDow = calendar.effectiveDayOfWeek(epochDay)
        val candidates = allSlots.filter { it.dayOfWeek == targetDow }
        if (!calendar.hasSemester) {
            // 未配置学期 → 忽略开课周次，行为与改造前完全一致
            return candidates.sortedBy { it.startPeriod }
        }
        val week = calendar.weekOf(epochDay)
        return candidates.filter { occursInWeek(it.id, week, weeksBySlotId) }
            .sortedBy { it.startPeriod }
    }

    /**
     * 周课表：返回 [week] 这一周按星期分组的时段。
     *
     * 不套 [AcademicCalendar.effectiveDayOfWeek]——周课表是「这一周的结构」，
     * 补课只影响具体日期，不改变周的形状。
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