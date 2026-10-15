package com.example.data.transfer

import com.example.data.calendar.EpochDays
import com.example.data.calendar.TweakKind
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.AutomationBundle
import com.example.data.codec.CalendarBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.PeriodBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.codec.TweakBundle
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.timetable.WeekSet
import java.time.format.DateTimeFormatter

/**
 * 把库里的当前状态导成同一份 JSON 契约（schemaVersion = 2）。
 *
 * 与导入共用 [com.example.data.codec.ScheduleCodec]，所以「导出 → 导入」天然闭环：
 * 用户换机、重装、备份都不需要 App 之外的任何知识。
 *
 * v2 是**单文件、顶层分块**：导出的停课与补课被反写回 `calendar.tweaks`
 * （而不是 v1 的两段列表），termConfigs 写当前这个学年学期的两半，
 * termRef 指回被查询的那一半。
 */
class ScheduleExportService(
    private val codec: com.example.data.codec.ScheduleCodec,
    private val courseRepo: CourseRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val settingsRepo: ScheduleSettingsRepository
) {

    /**
     * 一个半学期占的周数：浙大秋冬学期的秋是第 1–8 周、冬是第 9–16 周。
     * 与 [com.example.data.source.zju.ZjuScheduleMapper] 的 `SUBTERM_WEEKS` 同值。
     */
    private companion object {
        const val HALF_WEEKS = 8
    }

    suspend fun exportJson(): String {
        val courses = courseRepo.getAllCoursesOnce()
        val slots = courseRepo.getAllSlotsOnce()
        val weekRows = courseRepo.getAllSlotWeeksOnce()
        val weeksBySlot = weekRows.groupBy { it.courseSlotId }
            .mapValues { (_, list) -> list.map { it.week }.toSortedSet() }

        val slotsByCourse = slots.groupBy { it.courseId }

        val semester = calendarRepo.semesterOnce()
        val periods = calendarRepo.periodsOnce()
        val noClass = calendarRepo.noClassDaysOnce()
        val makeup = calendarRepo.makeupDaysOnce()
        val rules = settingsRepo.getCategoryRulesOnce()

        // 未配置学期时导出「每周」也要有个明确的周数范围，沿用 25 这个上界
        val totalWeeks = semester?.totalWeeks ?: WeekSet.FALLBACK_TOTAL_WEEKS

        val bundle = ScheduleBundle(
            provenance = ProvenanceBundle(
                source = "manual",
                generator = "MiuList 1.0",
                generatedAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(java.time.LocalDateTime.now()),
                note = "由应用内「导出课表」生成"
            ),
            calendar = CalendarBundle(
                termConfigs = semester?.toTermConfigs().orEmpty(),
                classTerms = semester?.toTermConfigs()?.mapNotNull { it.key }.orEmpty(),
                tweaks = toTweaks(noClass, makeup),
                periods = periods.map {
                    PeriodBundle(
                        period = it.period,
                        startTime = com.example.data.timetable.formatMinute(it.startMinute),
                        endTime = com.example.data.timetable.formatMinute(it.endMinute),
                        section = it.section
                    )
                }
            ),
            timetable = TimetableBlockBundle(
                termRef = semester?.let { TermRefBundle(it.yearSpan, it.termCode()) },
                courses = courses.map { c ->
                    CourseBundle(
                        key = c.dedupeKey,
                        name = c.name,
                        code = c.code,
                        teacher = c.teacher,
                        category = c.category,
                        classroom = c.classroom,
                        colorHex = c.colorHex,
                        automation = AutomationBundle(
                            preStudy = c.autoPreStudy,
                            postHomework = c.autoPostHomework,
                            labReport = c.autoLabReport,
                            review = c.autoReview,
                            defaultPostDueDays = c.defaultPostDueDays,
                            preStudyMinutes = c.preStudyEstimatedMinutes,
                            postHomeworkMinutes = c.postHomeworkEstimatedMinutes,
                            labReportMinutes = c.labReportEstimatedMinutes
                        ),
                        slots = slotsByCourse[c.id].orEmpty().map { s ->
                            com.example.data.codec.SlotBundle(
                                dayOfWeek = s.dayOfWeek,
                                startPeriod = s.startPeriod,
                                endPeriod = s.endPeriod,
                                classroom = s.classroom,
                                // 导出统一写整数数组（规范形式）；缺 key = 每周 = 全周
                                weeks = weeksBySlot[s.id]?.toList()
                                    ?: (1..totalWeeks).toList()
                            )
                        }
                    )
                }
            ),
            categoryRules = rules
        )

        return codec.encode(bundle)
    }

    /**
     * 学期 → `termConfigs`：**一个学年学期拆成秋 + 冬两条**。
     *
     * 库里的 `Semester` 存的是整个学年学期（秋冬 16 周），而上游 `termConfigs`
     * 一条只描述一个 8 周的半学期。导出时若只写一条，导回去就退化成一个没有季节名的
     * 8 周（或 16 周但叫「秋学期」）的学期，「秋冬学期」这个名会丢。
     *
     * 切分点用 [HALF_WEEKS] 而不是按日期猜：前半 = 第 1–8 周，后半 = 第 9–16 周，
     * 与 [com.example.data.codec.academicTermOf] 的口径一致。库里学期不足 8+8 周时
     * 只写前半一条，不凭空造出后半段。
     *
     * `Begin` 写的是**第一周周一的日期**，而不是库里的 `startEpochDay`：
     * 上游用它反推第一周是 `Begin − (Begin 的周几 − 1) − 7×(FirstWeekNo−1)`，
     * 只有当 Begin 本身是周一时才能无损还原。这里固定 `FirstWeekNo = 1`，
     * 导进去得到的周次与库里完全一致。
     */
    private fun com.example.data.local.SemesterEntity.toTermConfigs(): List<TermConfigBundle> {
        val base = termCode()
        val first = TermConfigBundle(
            year = yearSpan,
            term = base,
            begin = ZjuCalendarAdapter.epochDayToYyyymmdd(firstMondayEpochDay),
            end = ZjuCalendarAdapter.epochDayToYyyymmdd(firstHalfEnd()),
            firstWeekNo = 1
        )
        // 学期不足 16 周：没有后半段可写，写一条即可（import 侧会按单半处理）
        if (totalWeeks <= HALF_WEEKS) return listOf(first)
        val second = first.copy(
            term = base + 1, // 秋冬 0→1；春夏 4→5
            begin = ZjuCalendarAdapter.epochDayToYyyymmdd(firstHalfEnd() + 1L),
            end = ZjuCalendarAdapter.epochDayToYyyymmdd(endEpochDay)
        )
        return listOf(first, second)
    }

    /** 学期前半（第 1–8 周）的最后一天。不够 8 周时就是整个学期的最后一天。 */
    private fun com.example.data.local.SemesterEntity.firstHalfEnd(): Long {
        val byWeeks = firstMondayEpochDay + HALF_WEEKS * 7L - 1L
        return minOf(byWeeks, endEpochDay)
    }

    /**
     * 库里的 `term` 文案（「秋冬」「春夏」）→ zju-ical 的 `Term` 整数的**前半**代码。
     *
     * 只认「秋 / 春」两个字，其余一律当成 4（春）——文案本身是我们自己生成的，
     * 枚举不出更多情况，而猜错的后果只是学期名显示得不对，不影响周次。
     * 「秋冬」含「秋」→ 0，「春夏」不含 → 4，正好对上。
     */
    private fun com.example.data.local.SemesterEntity.termCode(): Int =
        if (term.contains("秋")) 0 else 4

    /**
     * 停课日 / 补课日 → `tweaks`。
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
    private fun toTweaks(
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