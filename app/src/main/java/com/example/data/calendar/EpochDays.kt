package com.example.data.calendar

import java.time.LocalDate

/**
 * 日期一律以 [LocalDate.toEpochDay] 存储与传递。
 *
 * 用 epochDay 而不是 epoch millis：`epochDay` 是纯日期值，不受时区与夏令时影响。
 * `TaskEntity` 里那些确实需要时刻的字段（`startTimeTimestamp` 等）仍用 millis。
 */
object EpochDays {

    /** 周一=1 … 周日=7，与 `CourseSlotEntity.dayOfWeek` 对齐。 */
    fun dayOfWeek1To7(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value

    fun isWeekend(epochDay: Long): Boolean {
        val v = dayOfWeek1To7(epochDay)
        return v == 6 || v == 7
    }

    /** 该日期所在周的周一（epochDay）。 */
    fun mondayOf(epochDay: Long): Long = epochDay - (dayOfWeek1To7(epochDay) - 1L)

    fun today(): Long = LocalDate.now().toEpochDay()

    fun parse(text: String): Long? =
        runCatching { LocalDate.parse(text).toEpochDay() }.getOrNull()

    fun format(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    fun formatCn(epochDay: Long): String {
        val d = LocalDate.ofEpochDay(epochDay)
        return "${d.monthValue}月${d.dayOfMonth}日"
    }

    /**
     * 「10/12」这样的短日期。
     *
     * 周课表的列头只有约 52.dp 宽，完整日期（10月12日）必然折行或截断；
     * 去掉「月」「日」两个字是最省的一刀，省下的宽度正好留给「放假 / 补周五」这类
     * 状态徽标——那两个字才是用户要看这列的原因。
     */
    fun formatShort(epochDay: Long): String {
        val d = LocalDate.ofEpochDay(epochDay)
        return "${d.monthValue}/${d.dayOfMonth}"
    }

    /** 周次条的日期区间，「10/12 – 10/18」。 */
    fun formatShortRange(from: Long, to: Long): String = "${formatShort(from)} – ${formatShort(to)}"

    /** [from]..[to] 闭区间展开。区间非法时返回空列表。 */
    fun expandRange(from: Long, to: Long): List<Long> {
        if (to < from) return emptyList()
        val size = (to - from + 1).coerceAtMost(400L).toInt()   // 上限防御，避免手滑写成 1970–9999
        return (0 until size).map { from + it }
    }
}