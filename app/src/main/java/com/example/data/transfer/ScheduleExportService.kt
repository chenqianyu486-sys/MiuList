package com.example.data.transfer

import com.example.data.calendar.EpochDays
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
 * （而不是 v1 的两段列表），termConfigs 只写当前这一期，termRef 指回它。
 */
class ScheduleExportService(
    private val codec: com.example.data.codec.ScheduleCodec,
    private val courseRepo: CourseRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val settingsRepo: ScheduleSettingsRepository
) {

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
                termConfigs = listOfNotNull(semester?.toTermConfig()),
                classTerms = semester?.let { listOf("${it.yearSpan}:${it.termCode()}") }.orEmpty(),
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
     * 学期 → `termConfig`。
     *
     * `Begin` 写的是**第一周周一的日期**，而不是库里的 `startEpochDay`：
     * 上游用它反推第一周是 `Begin − (Begin 的周几 − 1) − 7×(FirstWeekNo−1)`，
     * 只有当 Begin 本身是周一时才能无损还原。这里固定 `FirstWeekNo = 1`，
     * 导进去得到的周次与库里完全一致。
     */
    private fun com.example.data.local.SemesterEntity.toTermConfig() = TermConfigBundle(
        year = yearSpan,
        term = termCode(),
        begin = ZjuCalendarAdapter.epochDayToYyyymmdd(firstMondayEpochDay),
        end = ZjuCalendarAdapter.epochDayToYyyymmdd(endEpochDay),
        firstWeekNo = 1
    )

    /**
     * 库里的 `term` 文案（「秋季」「春季」）→ zju-ical 的 `Term` 整数。
     *
     * 只认两个常见值，其余一律当成 4（春学期）——因为文案本身是我们自己生成的，
     * 枚举不出更多情况，而猜错的后果只是学期名显示得不对，不影响周次。
     */
    private fun com.example.data.local.SemesterEntity.termCode(): Int =
        if (term.contains("秋")) 0 else 4

    /**
     * 停课日 / 补课日 → `tweaks`。
     *
     * - 连续的停课日合成一条 TweakType 0；不相邻的各成一条（区间里夹着上课日，
     *   合成一条会把那些天也停掉）。
     * - 补课日合成 TweakType 1：`From` 填「同一周里被补的那个周几」，
     *   导入时 `dayOfWeek1To7(From)` 正好还原出 makeupForDayOfWeek，闭环。
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
                        // 一段连续停课结束：合成一条 TweakType 0
                        out += TweakBundle(0, note, startOf(runStart), startOf(prev))
                        runStart = d
                    }
                    prev = d
                }
                out += TweakBundle(0, note, startOf(runStart), startOf(prev))
            }

        makeup.forEach {
            val from = it.epochDay - (EpochDays.dayOfWeek1To7(it.epochDay) - 1L) +
                (it.makeupForDayOfWeek - 1L)
            out += TweakBundle(1, it.note, startOf(from), startOf(it.epochDay))
        }
        return out
    }

    /** epochDay → `YYYYMMDD` 整数；epochDay 越界（0 之类）返回 null。 */
    private fun startOf(epochDay: Long): Int? =
        if (epochDay <= 0) null else ZjuCalendarAdapter.epochDayToYyyymmdd(epochDay)
}