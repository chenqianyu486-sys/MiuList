package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 校历，单例（[id] 恒为 1）。
 *
 * **迁移不插默认行**：未配置时走 `AcademicCalendar` 的降级路径（忽略开课周次、
 * 保留「周末不上课」），对现有用户零行为变化。宁可暂不生效，也不产生一个
 * 看起来对、实际错的单双周结果。
 *
 * [firstMondayEpochDay] 显式存而不是每次猜：猜错就是整体周次偏移。
 */
@Entity(tableName = "semester")
data class SemesterEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val name: String = "",
    val yearSpan: String = "",
    val term: String = "",
    val startEpochDay: Long = 0L,
    val firstMondayEpochDay: Long = 0L,
    val endEpochDay: Long = 0L,
    val totalWeeks: Int = 20,
    val periodsPerDay: Int = 13
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** 作息表：每天第 N 节的起止时刻与所属段（上午 / 下午 / 晚上）。 */
@Entity(tableName = "timetable_periods")
data class TimetablePeriodEntity(
    @PrimaryKey val period: Int,
    val startMinute: Int,
    val endMinute: Int,
    val section: String
)

/** 停课日（不含课）。导入时区间已按天展开落库。 */
@Entity(tableName = "no_class_days")
data class NoClassDayEntity(
    @PrimaryKey val epochDay: Long,
    val note: String = ""
)

/**
 * 补课日。[makeupForDayOfWeek] 表示「这天要上星期几的课」——
 * 10 月 10 日（周六）补周一，记 `makeupForDayOfWeek = 1`。
 */
@Entity(tableName = "makeup_days")
data class MakeupDayEntity(
    @PrimaryKey val epochDay: Long,
    val makeupForDayOfWeek: Int,
    val note: String = ""
)