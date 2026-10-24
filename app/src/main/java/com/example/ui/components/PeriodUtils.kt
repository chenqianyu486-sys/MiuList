package com.example.ui.components

import com.example.data.calendar.AppZone

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
 * 课表时间是**北京墙上时间**，时区真相见 [com.example.data.calendar.AppZone]
 * （以前这里自己定义过一份 `ScheduleZone`，与 data 层的 `AppZone` 构成双份真相，已合并）。
 */

/** Calendar → epochDay。校历判定统一走 epochDay，不受夏令时影响；时区按 [AppZone]。 */
fun epochDayOf(cal: java.util.Calendar): Long =
    cal.toInstant().atZone(AppZone.ZONE).toLocalDate().toEpochDay()

/** 今天 00:00（北京时间）。列表页与总览的「当日」锚点。 */
fun todayCalendar(): java.util.Calendar = AppZone.todayStart()

/**
 * 把某个日期的 00:00 挪到 [minuteOfDay] 这个钟点（epoch millis）。
 *
 * **前提是 [date] 自带 [AppZone] 的时区**——`set(HOUR_OF_DAY, …)` 按 Calendar 自己的
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
 * 「今日」后面的状态后缀，按**现在落在哪一件事的哪一段**分三种。
 *
 * 三态：
 *  1. **正在进行**（now 落在某一件事的 `[start, end)`）→「还有 X 下课」。
 *     数的是**这件事的结束时刻**减 now。
 *  2. **还没到下一件事**（start > now）→「下节课还有 X 开始」。
 *  3. **都上完 / 今天没事**（没有任何一件的 end > now）→「课都上完啦，休息一下吧~」。
 *
 * 半开区间 `[start, end)` 的两端是有讲究的：下课那一刻就该切换成「下一节课还有
 * 多久开始」，而不是继续显示「还有 0 分下课」。
 *
 * [now] 是绝对时刻，与时区无关；和课表钟点的对齐由 [AppZone] 负责。
 *
 * @param compact 大字号下的短句。长句「下节课还有 7 小时 49 分 开始」在系统字号
 *   2.0 时放不进一行，会断在「开 / 始」之间，最后一个字单独占一行。
 *   短句「距下节课 7 小时 49 分」少四个字，411dp 屏上能收回一行。
 *   默认 false：基准字号与所有既有测试文案保持不变。
 * @param examWindows 今天的考试时间窗（开始, 结束）。**与课一起参与「下一件事」的判定**：
 *   考试和课摆在同一条时间线上，组头只盯着课就会说错 ——
 *   「下节课还有 3 小时开始」而那场考试两小时后就要考，是假话。
 * @param examCountToday 今天有几场考试。**大于 0 时第 3 态要改口**：
 *   「课都上完啦，休息一下吧~」在有考试的日子里是**说假话** ——
 *   那句是在说「今天没有事了」，而今天恰恰有一件最该做的事。
 */
fun todayStatusLabel(
    date: java.util.Calendar,
    slots: List<com.example.data.local.CourseSlotEntity>,
    now: Long,
    periodTable: com.example.data.timetable.PeriodTable,
    compact: Boolean = false,
    examWindows: List<Pair<Long, Long>> = emptyList(),
    examCountToday: Int = 0
): String {
    val windows = slots.mapNotNull { slot ->
        val start = periodStartMillis(date, slot, periodTable)
        val end = periodEndMillis(date, slot, periodTable)
        if (start <= 0L || end <= start) null else start to end
    }
    val exams = examWindows.filter { it.second > it.first }

    // 正在进行：课与考试谁先算谁，但**同一时刻**以课为准（课是默认项）。
    windows.firstOrNull { (start, end) -> now >= start && now < end }
        ?.let { (_, end) -> return "还有 ${formatRemaining(end - now)} 下课" }
    exams.firstOrNull { (start, end) -> now >= start && now < end }
        ?.let { (_, end) -> return "考试还有 ${formatRemaining(end - now)} 结束" }

    // 下一件事：课与考试里**更早**的那个。
    val nextClass = windows.firstOrNull { (start, _) -> start > now }
    val nextExam = exams.firstOrNull { (start, _) -> start > now }
    when {
        nextClass == null && nextExam == null -> Unit
        nextExam == null -> return classStartText(nextClass!!.first, now, compact)
        nextClass == null -> return examStartText(nextExam!!.first, now, compact)
        nextClass.first <= nextExam.first -> return classStartText(nextClass.first, now, compact)
        else -> return examStartText(nextExam.first, now, compact)
    }

    return dayDoneLabel(examCountToday)
}

/** 「下节课还有 X 开始」。 */
private fun classStartText(start: Long, now: Long, compact: Boolean): String =
    if (compact) {
        "距下节课 ${formatRemaining(start - now)}"
    } else {
        "下节课还有 ${formatRemaining(start - now)} 开始"
    }

/**
 * 「考试还有 X 开始」。
 *
 * 考试与课在同一条时间线上，但**不能叫「下节课」** ——
 * 考试不是一节课，说成「课」会让用户去找教室。
 */
private fun examStartText(start: Long, now: Long, compact: Boolean): String =
    if (compact) {
        "距考试 ${formatRemaining(start - now)}"
    } else {
        "考试还有 ${formatRemaining(start - now)} 开始"
    }

/**
 * 「今天没课了」那一句。有考试时换成考试的说法。
 *
 * 抽成独立函数是为了让**组头**与**空态卡**说同一件事：
 * 组头说「今天有 1 场考试」、下面的空态卡也写「今天有 1 场考试」，
 * 整屏不会出现两套说法。
 */
internal fun dayDoneLabel(examCountToday: Int): String =
    if (examCountToday > 0) "今天有 $examCountToday 场考试" else ALL_DAY_DONE_TEXT

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

