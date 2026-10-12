package com.example.ui.components

/**
 * 只剩两个纯字符串助手。
 *
 * 这里**曾经**放着 `ALL_PERIODS` + `getPeriodTimeRange()`，是全校作息的双份真相：
 * 一处改作息，另一处会静默留着旧值；`getPeriodTimeRange` 节号越界时还会兜底到
 * "08:00" / "22:50"，把「漏改」伪装成「数据本来就这样」。
 *
 * 现唯一真相在 `com.example.data.timetable.PeriodTable`（含 `DEFAULT_PERIODS` 种子，
 * 越界返回 null）。UI 一律读 ViewModel 下发的那一份；新建库/升级迁移用的种子另见
 * `Migrations.MIGRATION_3_4` 的 INSERT，与 `DEFAULT_PERIODS` 的逐字段一致性由
 * `MigrationSeedTest` 守住。
 */

/** `第3节` / `第3-5节`。 */
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
