package com.example.ui.components

data class PeriodInfo(
    val period: Int,
    val startTime: String,
    val endTime: String,
    val section: String // "上午", "下午", "晚上"
)

val ALL_PERIODS: List<PeriodInfo> = listOf(
    PeriodInfo(1, "08:00", "08:45", "上午"),
    PeriodInfo(2, "08:55", "09:40", "上午"),
    PeriodInfo(3, "10:00", "10:45", "上午"),
    PeriodInfo(4, "10:55", "11:40", "上午"),
    PeriodInfo(5, "14:00", "14:45", "下午"),
    PeriodInfo(6, "14:55", "15:40", "下午"),
    PeriodInfo(7, "16:00", "16:45", "下午"),
    PeriodInfo(8, "16:55", "17:40", "下午"),
    PeriodInfo(9, "18:30", "19:15", "晚上"),
    PeriodInfo(10, "19:25", "20:10", "晚上"),
    PeriodInfo(11, "20:20", "21:05", "晚上"),
    PeriodInfo(12, "21:15", "22:00", "晚上"),
    PeriodInfo(13, "22:05", "22:50", "晚上")
)

fun getPeriodTimeRange(startPeriod: Int, endPeriod: Int): Pair<String, String> {
    val s = ALL_PERIODS.find { it.period == startPeriod }?.startTime ?: "08:00"
    val e = ALL_PERIODS.find { it.period == endPeriod }?.endTime ?: "22:50"
    return Pair(s, e)
}

fun formatPeriodRange(startPeriod: Int, endPeriod: Int): String {
    return if (startPeriod == endPeriod) "第${startPeriod}节" else "第${startPeriod}-${endPeriod}节"
}

fun getDayName(dayOfWeek: Int): String {
    return when (dayOfWeek) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "周$dayOfWeek"
    }
}
