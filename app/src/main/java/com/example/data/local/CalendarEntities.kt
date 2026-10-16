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
 * 补课日。**[sourceEpochDay] 是唯一真源**：「这份课原本属于哪一天」（10 月 2 日）。
 *
 * 补周几和算第几周都由它推出来，见 `AcademicCalendar.makeupDayOfWeek` 与
 * `teachingWeekOf`。早先这里另存了一个 [makeupForDayOfWeek]，两份事实可以互相矛盾
 * （写着补周五、来源日却是周三），而界面上看不出任何异常 —— 直到单双周的课集体判错。
 *
 * [makeupForDayOfWeek] 现在是**由来源日算出来写进去的派生值**，保留它只为两件事：
 * 数据库列不删（删了要迁移），以及 v1 旧文档的导出格式不变。
 * **判定逻辑一律不读它** —— 一读，两份事实就又能各说各话。
 *
 * [sourceEpochDay] <= 0 是「不知道补的是哪一天的课」，不构成一次搬运；
 * 导入与手工填写两处已把它拦成必填，老库里残留的由 `AcademicCalendar` 滤掉。
 */
@Entity(tableName = "makeup_days")
data class MakeupDayEntity(
    @PrimaryKey val epochDay: Long,
    /** 由 [sourceEpochDay] 推出的派生值，仅供展示与 v1 导出。判定不读它。 */
    val makeupForDayOfWeek: Int,
    val note: String = "",
    val sourceEpochDay: Long = 0L
)