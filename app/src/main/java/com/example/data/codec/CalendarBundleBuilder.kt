package com.example.data.codec

import com.example.data.calendar.EpochDays
import com.example.data.calendar.TweakKind
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.local.TimetablePeriodEntity
import com.example.data.timetable.formatMinute

/**
 * 库里的校历 → 格式里的校历块。
 *
 * ## 为什么是「一份转换，两处用」
 *
 * 整机快照**文件**（`transfer`）与局域网同步**快照**（`sync/web`）都要把
 * 库里的学期 / 作息 / 停课 / 补课变成同一份形状 ——
 * 文件要的是「导出后能导回来」，同步要的是「网页端存下来、导出时带上」。
 *
 * 复制两份的话，改了一处就会漏另一处，而漏掉的那处**不会报错**：
 * 只是「网页端导出的校历和手机上的不一样」，
 * 要到用户拿这份备份去恢复时才发现。
 *
 * ## 学期为什么拆成两条
 *
 * 库里存的是**整个学年学期**（秋冬 16 周），而上游的 `termConfigs`
 * 一条只描述一个 8 周的半学期。只写一条的话，导回去就退化成一个没有季节名的
 * 8 周（或 16 周但叫「秋学期」）的学期，「秋冬学期」这个名会丢。
 */
object CalendarBundleBuilder {

    /** 一个半学期占的周数：浙大秋冬学期的秋是第 1–8 周、冬是第 9–16 周。 */
    private const val HALF_WEEKS = 8

    /** 学期 + 作息 + 停课 + 补课 → 一整块校历。 */
    fun calendar(
        semester: SemesterEntity?,
        periods: List<TimetablePeriodEntity>,
        noClass: List<NoClassDayEntity>,
        makeup: List<MakeupDayEntity>,
    ): CalendarBundle = CalendarBundle(
        termConfigs = termConfigs(semester),
        classTerms = termConfigs(semester).mapNotNull { it.key },
        tweaks = tweaks(noClass, makeup),
        periods = periods.map {
            PeriodBundle(
                period = it.period,
                startTime = formatMinute(it.startMinute),
                endTime = formatMinute(it.endMinute),
                section = it.section
            )
        }
    )

    /**
     * 一个学年学期**拆成秋 + 冬两条**。
     *
     * 切分点用 [HALF_WEEKS] 而不是按日期猜：前半 = 第 1–8 周，后半 = 第 9–16 周。
     * 库里学期不足 8+8 周时只写前半一条，不凭空造出后半段。
     *
     * `Begin` 写的是**第一周周一的日期**，而不是库里的 `startEpochDay`：
     * 上游用它反推第一周是 `Begin − (Begin 的周几 − 1) − 7×(FirstWeekNo−1)`，
     * 只有当 Begin 本身是周一时才能无损还原。这里固定 `FirstWeekNo = 1`，
     * 导进去得到的周次与库里完全一致。
     */
    fun termConfigs(semester: SemesterEntity?): List<TermConfigBundle> {
        val base = semester?.termCode() ?: return emptyList()
        val first = TermConfigBundle(
            year = semester.yearSpan,
            term = base,
            begin = ZjuCalendarAdapter.epochDayToYyyymmdd(semester.firstMondayEpochDay),
            end = ZjuCalendarAdapter.epochDayToYyyymmdd(semester.firstHalfEnd()),
            firstWeekNo = 1
        )
        // 学期不足 16 周：没有后半段可写，写一条即可（import 侧会按单半处理）
        if (semester.totalWeeks <= HALF_WEEKS) return listOf(first)
        val second = first.copy(
            term = base + 1, // 秋冬 0→1；春夏 4→5
            begin = ZjuCalendarAdapter.epochDayToYyyymmdd(semester.firstHalfEnd() + 1L),
            end = ZjuCalendarAdapter.epochDayToYyyymmdd(semester.endEpochDay)
        )
        return listOf(first, second)
    }

    /** 学期前半（第 1–8 周）的最后一天。不够 8 周时就是整个学期的最后一天。 */
    private fun SemesterEntity.firstHalfEnd(): Long {
        val byWeeks = firstMondayEpochDay + HALF_WEEKS * 7L - 1L
        return minOf(byWeeks, endEpochDay)
    }

    /**
     * 库里的 `term` 文案（「秋冬」「春夏」）→ zju-ical `Term` 整数的**前半**代码。
     *
     * 只认「秋 / 春」两个字，其余一律当成 4（春）——文案本身是我们自己生成的，
     * 枚举不出更多情况，而猜错的后果只是学期名显示得不对，不影响周次。
     * 「秋冬」含「秋」→ 0，「春夏」不含 → 4，正好对上。
     */
    fun termCode(term: String): Int = if (term.contains("秋")) 0 else 4

    private fun SemesterEntity.termCode(): Int = termCode(term)

    /**
     * 停课日 / 补课日 → 调休规则。
     *
     * - 连续的停课日合成一条 [TweakKind.CLEAR]；不相邻的各成一条（区间里夹着上课日，
     *   合成一条会把那些天也停掉）。
     * - 补课日合成 [TweakKind.MOVE]：`From` 写**真实的来源日**。
     *
     * 这里以前是反推的 —— 拿 `makeupForDayOfWeek` 去「同一周里」拼一个同周几的日子。
     * 那个日期是编出来的：真实的 10-17 补课来自 10-02（隔了两周），反推只会得到
     * 10-12，于是「导出 → 导入」之后这场补课的周次归属就变了。
     * 有 `sourceEpochDay` 就用它；没有（v1 旧数据、校历弹层手工填的行）才退回反推。
     */
    fun tweaks(
        noClass: List<NoClassDayEntity>,
        makeup: List<MakeupDayEntity>
    ): List<TweakBundle> {
        val out = mutableListOf<TweakBundle>()

        noClass.sortedBy { it.epochDay }.groupBy { it.note }
            .forEach { (note, days) ->
                val sorted = days.map { it.epochDay }.distinct().sorted()
                if (sorted.isEmpty()) return@forEach
                var runStart = sorted.first()
                var prev = sorted.first()
                for (d in sorted.drop(1)) {
                    if (d != prev + 1) {
                        // 一段连续停课结束：合成一条 CLEAR
                        out += TweakBundle(TweakKind.CLEAR, note, startOf(runStart), startOf(prev))
                        runStart = d
                    }
                    prev = d
                }
                out += TweakBundle(TweakKind.CLEAR, note, startOf(runStart), startOf(prev))
            }

        makeup.forEach {
            val from = if (it.sourceEpochDay > 0L) {
                it.sourceEpochDay
            } else {
                // 来源日未知：按旧办法在同周里拼一个同周几的日子，
                // 至少让 dayOfWeek 能还原，闭环靠它
                it.epochDay - (EpochDays.dayOfWeek1To7(it.epochDay) - 1L) +
                    (it.makeupForDayOfWeek - 1L)
            }
            out += TweakBundle(TweakKind.MOVE, it.note, startOf(from), startOf(it.epochDay))
        }
        return out
    }

    /** epochDay → `YYYYMMDD` 整数；epochDay 越界（0 之类）返回 null。 */
    private fun startOf(epochDay: Long): Int? =
        if (epochDay <= 0) null else ZjuCalendarAdapter.epochDayToYyyymmdd(epochDay)
}
