package com.example.data.timetable

/** 自 00:00 起的分钟数 → `HH:mm`。 */
fun formatMinute(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    return "%02d:%02d".format(h, m)
}

/**
 * 一节课在某校作息下的时间定义。
 *
 * [startMinute] / [endMinute] 是自 00:00 起的分钟数，不存字符串：排序、比较、
 * 渲染都免去字符串解析。
 */
data class PeriodSlot(
    val period: Int,
    val startMinute: Int,
    val endMinute: Int,
    val section: String
) {
    val startTime: String get() = formatMinute(startMinute)
    val endTime: String get() = formatMinute(endMinute)
}

/**
 * 作息表。所有显示时间的地方都从这里取，**不再有全局常量**。
 *
 * 改造前 `PeriodUtils.ALL_PERIODS` + `getPeriodTimeRange()` 读全局，并且越界时
 * 静默兜底到 `"08:00"` / `"22:50"`——那正好是「改了作息但某个调用点漏改」的藏身处。
 * 现在越界一律返回 null，由调用方显式处理（UI 显示占位符，而不是显示一个错的时间）。
 *
 * 本文件不依赖 Android / Room，可在普通 JUnit 里直接测。
 */
class PeriodTable(
    val periodsPerDay: Int,
    val periods: List<PeriodSlot>
) {

    private val byPeriod: Map<Int, PeriodSlot> = periods.associateBy { it.period }

    fun slotOf(period: Int): PeriodSlot? = byPeriod[period]

    fun startMinuteOf(period: Int): Int? = byPeriod[period]?.startMinute

    fun endMinuteOf(period: Int): Int? = byPeriod[period]?.endMinute

    fun sectionOf(period: Int): String = byPeriod[period]?.section.orEmpty()

    /**
     * 某个时刻落在哪一节。
     *
     * 落在两节之间的**空档**里（比如浙大作息的 09:35–10:00）时取**下一节** ——
     * 那段时间属于「快到下一节了」，归到下一节才对。
     *
     * 用在哪：考试只有钟点、没有节号，要落进周课表的格子就得靠这个换算。
     */
    fun periodOfMinute(minute: Int): Int? {
        periods.firstOrNull { minute >= it.startMinute && minute < it.endMinute }
            ?.let { return it.period }
        // 空档里：取下一节。
        return periods.firstOrNull { minute < it.startMinute }?.period
    }

    /**
     * 一段时间占哪几节：起始时刻所在的那一节，到**结束时刻之前**的最后一节。
     *
     * 结束那一拍用「结束时刻之前」而不是「结束时刻所在」：
     * 08:00–09:00 的考试只占第 1 节 —— 它在第 2 节开课时就已经结束了，
     * 占到第 2 节会让栅格上多出一节「其实没在考」的时间。
     */
    fun periodSpanOf(startMinute: Int, endMinute: Int): IntRange? {
        val start = periodOfMinute(startMinute) ?: return null
        val end = periods.lastOrNull { it.startMinute < endMinute }?.period ?: start
        return start..maxOf(start, end)
    }

    /**
     * 大节分隔：**由 [PeriodSlot.section] 相邻差异推导**，不再硬编码 `period == 5 || period == 10`。
     * 改造前 `PeriodUtils.kt` 顶部那条「改这里要同步改 WeekTimetableGrid13 的分隔线」
     * 的警告，根因就是这两处需要人工同步；现在只有这一处数据源。
     */
    fun isSectionEnd(period: Int): Boolean {
        val current = byPeriod[period] ?: return false
        val next = byPeriod[period + 1] ?: return false
        return current.section != next.section
    }

    /**
     * 取 [startPeriod]–[endPeriod] 的起止时刻。
     *
     * @return 节号越界时返回 `null`，**不静默兜底**。调用方需显式处理。
     */
    fun timeRange(startPeriod: Int, endPeriod: Int): Pair<String, String>? {
        val s = byPeriod[startPeriod] ?: return null
        val e = byPeriod[endPeriod] ?: return null
        return s.startTime to e.endTime
    }

    fun timeRangeText(startPeriod: Int, endPeriod: Int): String =
        timeRange(startPeriod, endPeriod)?.let { (s, e) -> "$s ~ $e" } ?: "时间待定"

    /**
     * [startPeriod]–[endPeriod] 这一节课的时长（分钟）。
     *
     * 节号越界返回 0，**不静默兜底成一个假时长** —— 总览的「本周 x.x 小时」
     * 拿它求和，一旦某处传了越界节号而我们悄悄给个 45，这个数字就永远错一点，
     * 而且没人看得出错在哪。
     */
    fun durationMinutesOf(startPeriod: Int, endPeriod: Int): Int {
        val s = startMinuteOf(startPeriod) ?: return 0
        val e = endMinuteOf(endPeriod) ?: return 0
        return (e - s).coerceAtLeast(0)
    }

    val isEmpty: Boolean get() = periods.isEmpty()

    companion object {
        /**
         * 内置默认作息，与改造前 `PeriodUtils.ALL_PERIODS` 逐值相同。
         *
         * 两处用途：① 新建库时的种子；② 导入的 `timetable` 整段缺失时的兜底。
         * 迁移脚本里另有一份同样的 INSERT，保证「升级即用、不看代码」。
         *
         * **这一份不能再改**：它是 `MIGRATION_3_4` 写给升级用户的种子值，
         * 而迁移测试逐字段比对它。改了就等于把已升级用户已经写进库里的 13 节作息
         * 和代码里的定义拆成两套，而没人会在升级路径上发现。
         * 浙大自己的作息见 [ZJU_PERIODS]：节数同为 13，但时刻不同（那份是浙大
         * 真实的压缩作息，第 5 节 12:25 结课、第 6 节 13:25 上课），两者并存。
         */
        val DEFAULT_PERIODS: List<PeriodSlot> = listOf(
            PeriodSlot(1, 8 * 60, 8 * 60 + 45, "上午"),
            PeriodSlot(2, 8 * 60 + 55, 9 * 60 + 40, "上午"),
            PeriodSlot(3, 10 * 60, 10 * 60 + 45, "上午"),
            PeriodSlot(4, 10 * 60 + 55, 11 * 60 + 40, "上午"),
            PeriodSlot(5, 14 * 60, 14 * 60 + 45, "上午"),
            PeriodSlot(6, 14 * 60 + 55, 15 * 60 + 40, "下午"),
            PeriodSlot(7, 16 * 60, 16 * 60 + 45, "下午"),
            PeriodSlot(8, 16 * 60 + 55, 17 * 60 + 40, "下午"),
            PeriodSlot(9, 18 * 60 + 30, 19 * 60 + 15, "下午"),
            PeriodSlot(10, 19 * 60 + 25, 20 * 60 + 10, "下午"),
            PeriodSlot(11, 20 * 60 + 20, 21 * 60 + 5, "晚上"),
            PeriodSlot(12, 21 * 60 + 15, 22 * 60, "晚上"),
            PeriodSlot(13, 22 * 60 + 5, 22 * 60 + 50, "晚上")
        )

        /**
         * 浙大作息：**13 节**，每节 45 分钟。
         *
         * 来源是浙大官方学年校历里逐节列出的上课时间（上午 1–5 / 下午 6–10 /
         * 晚上 11–13，到第 13 节 20:30–21:15 为止），与图灵班选课指南那份作息表
         * 逐格一致。第 5 节 12:25 结课、第 6 节 13:25 上课，这个压缩作息是真的，
         * 和本文件 [DEFAULT_PERIODS] 那张通用表不一样。
         *
         * **这里曾经是 15 节**：早先照抄第三方库 zju-ical 的 Go 常量时，多带进来
         * 第 14、15 节（21:20–22:05 / 22:10–22:55）。浙大没有这两节 ——
         * 照抄来的常量不等于学校的事实，判据只能是官方校历。三处独立来源
         * （校历、选课指南、浙大选课工具 MOTIS「一天 13 节课」）都只到 13。
         * 删掉那两节时一并改掉来源注释，否则下一个照着注释去核对的人
         * 会把上游的 15 节又当成「已逐值对齐」。
         *
         * 大节分段（`section`）是本项目自己的：学校不提供分段概念，而栅格分隔线要用它。
         * 这里按 1–5 上午 / 6–10 下午 / 11–13 晚上切，与校历的分组一致，
         * 也与 [DEFAULT_PERIODS] 的切法保持一致。
         */
        val ZJU_PERIODS: List<PeriodSlot> = listOf(
            PeriodSlot(1, 8 * 60, 8 * 60 + 45, "上午"),
            PeriodSlot(2, 8 * 60 + 50, 9 * 60 + 35, "上午"),
            PeriodSlot(3, 10 * 60, 10 * 60 + 45, "上午"),
            PeriodSlot(4, 10 * 60 + 50, 11 * 60 + 35, "上午"),
            PeriodSlot(5, 11 * 60 + 40, 12 * 60 + 25, "上午"),
            PeriodSlot(6, 13 * 60 + 25, 14 * 60 + 10, "下午"),
            PeriodSlot(7, 14 * 60 + 15, 15 * 60, "下午"),
            PeriodSlot(8, 15 * 60 + 5, 15 * 60 + 50, "下午"),
            PeriodSlot(9, 16 * 60 + 15, 17 * 60, "下午"),
            PeriodSlot(10, 17 * 60 + 5, 17 * 60 + 50, "下午"),
            PeriodSlot(11, 18 * 60 + 50, 19 * 60 + 35, "晚上"),
            PeriodSlot(12, 19 * 60 + 40, 20 * 60 + 25, "晚上"),
            PeriodSlot(13, 20 * 60 + 30, 21 * 60 + 15, "晚上")
        )

        val DEFAULT: PeriodTable = PeriodTable(DEFAULT_PERIODS.size, DEFAULT_PERIODS)

        val ZJU: PeriodTable = PeriodTable(ZJU_PERIODS.size, ZJU_PERIODS)

        fun from(periods: List<PeriodSlot>): PeriodTable {
            val sorted = periods.sortedBy { it.period }
            return PeriodTable(sorted.size, sorted)
        }
    }
}