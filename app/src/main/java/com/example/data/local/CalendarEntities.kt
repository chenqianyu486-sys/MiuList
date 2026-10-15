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
 * 10 月 17 日（周六）补周五 的课，记 `makeupForDayOfWeek = 5`。
 *
 * [sourceEpochDay] 记「那份课原本属于哪一天」（10 月 2 日）。
 *
 * **两者回答的不是同一个问题，不能只留前者。** 界面上出哪门课由周几决定，
 * 而**它算第几周**由来源日决定：2026 国庆把 10-02 的周五课补到 10-17，
 * 10-02 在第 3 周、10-17 在第 5 周，按补课当天的日期算周次的话，
 * 这一场补上来的课会被当成第 5 周的课，单双周课就集体判错。
 *
 * 0 表示「来源日未知」（v1 旧文档、以及校历弹层里手工填的行），
 * 此时退回按补课当天算周次 —— 这是仅存的兼容路径。
 */
@Entity(tableName = "makeup_days")
data class MakeupDayEntity(
    @PrimaryKey val epochDay: Long,
    val makeupForDayOfWeek: Int,
    val note: String = "",
    val sourceEpochDay: Long = 0L
)