package com.example.data.model

/** 星期与节次的展示文案。放在 data 层：任务生成引擎也要用，不该反过来依赖 ui 包。 */
fun getDayName(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "周$dayOfWeek"
}

fun formatPeriodRange(startPeriod: Int, endPeriod: Int): String =
    if (startPeriod == endPeriod) "第${startPeriod}节" else "第${startPeriod}-${endPeriod}节"