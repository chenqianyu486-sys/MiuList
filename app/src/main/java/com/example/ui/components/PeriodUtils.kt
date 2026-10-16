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

/**
 * 课表时间的时区。**固定 Asia/Shanghai，不跟随设备时区。**
 *
 * 课表上的「08:00–09:35」是学校的**墙上时间**，它表达的是「在浙大校区的钟面上第几小时」，
 * 不是「用户设备所在时区的第几小时」。跟着设备走会得到两种错法：
 *
 *  * 设备不在 UTC+8 时，界面上明明写着 08:00，上课提醒却按设备本地时间算；
 *  * 「今天是几号」也会跟着偏：北京 00:30 时 GMT 还是前一天，列表会把昨天当成今天。
 *
 * 固定成 Asia/Shanghai 之后，北京用户看到的完全不变（本来就是 UTC+8），
 * 而设备在别的时区时课表依然正确。判断某一时刻属于哪一节课时，
 * `System.currentTimeMillis()` 本身是绝对时刻、与时区无关，可以照旧用它。
 */
val ScheduleZone: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")

/** Calendar → epochDay。校历判定统一走 epochDay，不受夏令时影响；时区按 [ScheduleZone]。 */
fun epochDayOf(cal: java.util.Calendar): Long =
    cal.toInstant().atZone(ScheduleZone).toLocalDate().toEpochDay()

/** 今天 00:00（[ScheduleZone]）。列表页与总览的「当日」锚点。 */
fun todayCalendar(): java.util.Calendar =
    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(ScheduleZone)).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }

/**
 * 把某个日期的 00:00 挪到 [minuteOfDay] 这个钟点（epoch millis）。
 *
 * **前提是 [date] 自带 [ScheduleZone]**——`set(HOUR_OF_DAY, …)` 按 Calendar 自己的
 * 时区解释，换成系统时区就会整体偏移。所有传进来的 date 都来自 [todayCalendar]
 * 或由它克隆，不再另行取 `Calendar.getInstance()`。
 *
 * 返回 0 表示取不到该节次（越界）：不该静默兜底成一个「看起来正常」的假时间。
 */
fun minuteOfDayMillis(
    date: java.util.Calendar,
    minuteOfDay: Int
): Long = (date.clone() as java.util.Calendar).apply {
    set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    set(java.util.Calendar.MINUTE, minuteOfDay % 60)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** 某个时段在指定日期的开课时刻（epoch millis）。取不到该节次时返回 0。 */
fun periodStartMillis(
    date: java.util.Calendar,
    slot: com.example.data.local.CourseSlotEntity,
    periodTable: com.example.data.timetable.PeriodTable
): Long {
    val minute = periodTable.startMinuteOf(slot.startPeriod) ?: return 0L
    return minuteOfDayMillis(date, minute)
}

/** 某个时段在指定日期的结课时刻（epoch millis）。取不到该节次时返回 0。 */
fun periodEndMillis(
    date: java.util.Calendar,
    slot: com.example.data.local.CourseSlotEntity,
    periodTable: com.example.data.timetable.PeriodTable
): Long {
    val minute = periodTable.endMinuteOf(slot.endPeriod) ?: return 0L
    return minuteOfDayMillis(date, minute)
}

/** 今天最后一节课已经上完（或这天没排出任何课）时，挂在后面的收尾文案。 */
const val ALL_DAY_DONE_TEXT = "课都上完啦，休息一下吧~"

/**
 * 「今日」后面的状态后缀，按**现在落在哪一节课的哪一段**分三种。
 *
 * 三态：
 *  1. **正在上课**（now 落在某一节课的 `[start, end)`）→「还有 X 下课」。
 *     数的是**当前这节课的结束时刻**减 now。
 *  2. **还没到下一节课**（有课的 start > now）→「下一节课还有 X 开始」。
 *  3. **课全上完 / 今天没课**（没有任何一节的 end > now）→「课都上完啦，休息一下吧~」。
 *
 * 半开区间 `[start, end)` 的两端是有讲究的：下课那一刻就该切换成「下一节课还有
 * 多久开始」，而不是继续显示「还有 0 分下课」。
 *
 * [now] 是绝对时刻，与时区无关；和课表钟点的对齐由 [ScheduleZone] 负责。
 */
fun todayStatusLabel(
    date: java.util.Calendar,
    slots: List<com.example.data.local.CourseSlotEntity>,
    now: Long,
    periodTable: com.example.data.timetable.PeriodTable
): String {
    val windows = slots.mapNotNull { slot ->
        val start = periodStartMillis(date, slot, periodTable)
        val end = periodEndMillis(date, slot, periodTable)
        if (start <= 0L || end <= start) null else start to end
    }
    if (windows.isEmpty()) return ALL_DAY_DONE_TEXT

    windows.firstOrNull { (start, end) -> now >= start && now < end }
        ?.let { (_, end) -> return "还有 ${formatRemaining(end - now)} 下课" }

    windows.firstOrNull { (start, _) -> start > now }
        ?.let { (start, _) -> return "下节课还有 ${formatRemaining(start - now)} 开始" }

    return ALL_DAY_DONE_TEXT
}

/**
 * 把剩余时长写成中文。
 *
 * 不要写成固定的「X 小时 Y 分」：剩 45 分钟时「0 小时 45 分」既难看又像坏了。
 * 不足一小时只报分钟，整小时只报小时。
 */
fun formatRemaining(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h <= 0L -> "$m 分"
        m == 0L -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}

