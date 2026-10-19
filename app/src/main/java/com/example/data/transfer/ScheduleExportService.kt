package com.example.data.transfer

import com.example.data.calendar.AppZone
import com.example.data.calendar.EpochDays
import com.example.data.calendar.TweakKind
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.AutomationBundle
import com.example.data.codec.CalendarBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.PeriodBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TaskBundle
import com.example.data.codec.TaskRuleBundle
import com.example.data.codec.TaskTypeBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.codec.TweakBundle
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CourseKeys
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.repository.TaskRepository
import com.example.data.repository.TaskTypeRepository
import com.example.data.timetable.WeekSet
import java.time.format.DateTimeFormatter

/**
 * 把库里的当前状态导成同一份 JSON 契约（schemaVersion = 3）——**整机快照**。
 *
 * 与导入共用 [com.example.data.codec.ScheduleCodec]，所以「导出 → 导入」天然闭环：
 * 用户换机、重装、备份都不需要 App 之外的任何知识。
 *
 * 快照装的是**这台设备上的全部用户状态**，不只是课表：
 * 课程 / 校历 / 作息 / 类别规则 / **待办** / **自定义待办分类** / **课程级生成规则**。
 * 待办尤其不能少 —— 课表随时能从浙大重新拉一遍，勾没勾、打没打星标、拉高亮没拉高亮，
 * 服务端根本不知道。换机丢待办是最贵的一种丢。
 *
 * 顶层分块（`calendar` / `timetable` / `categoryRules` / `tasks` / `taskTypes` / `taskRules`）
 * 是因为**来源不同**：前三块可以随时由浙大同步覆盖，后三块只能靠这份文件。
 *
 * v3 的 `calendar` / `timetable` 与 v2 逐字段相同，v2 里分两段写的停课与补课在
 * v3 仍反写回 `calendar.tweaks`。
 *
 * **绝不导出凭据**：学号密码在 `SecureAccountStorage`（Keystore）里，不经过这里，
 * `provenance.studentId` 在手工导出路径上保持为空。
 */
class ScheduleExportService(
    private val codec: com.example.data.codec.ScheduleCodec,
    private val courseRepo: CourseRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val settingsRepo: ScheduleSettingsRepository,
    private val taskRepo: TaskRepository,
    private val taskTypeRepo: TaskTypeRepository
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

        // ---- v3：用户数据。课程的自然键在待办与生成规则里替代本地 courseId ----
        //
        // 自然键**现算**而不是直接读 dedupeKey：老数据可能没补上这一列，而导入侧
        // 是拿算法算出来的键去匹配的。导出写空串、导入按算法找，两边口径一差，
        // 这门课的待办就静默变成「未关联课程」。
        val courseKeyById = courses.associate { course ->
            course.id to course.dedupeKey.ifBlank {
                CourseKeys.compute(course.code, course.name, course.teacher)
            }
        }
        val tasks = taskRepo.getAllTasksOnce()
        // 没补上 uid 的历史行**跳过**，不写一份空身份进文件：导入侧看到空 uid
        // 会整条忽略（那是对的），但如果在这里给它编一个 uid，那条待办就会
        // 变成一个「看起来能同步、实际上和本机那条是两条」的东西。
        val exportableTasks = tasks.filter { it.syncUid.isNotBlank() }
        val taskRules = settingsRepo.getTaskRulesOnce()

        // 未配置学期时导出「每周」也要有个明确的周数范围，沿用 25 这个上界
        val totalWeeks = semester?.totalWeeks ?: WeekSet.FALLBACK_TOTAL_WEEKS

        val bundle = ScheduleBundle(
            provenance = ProvenanceBundle(
                source = "snapshot",
                generator = "MiuList 1.0",
                // 快照时间写的是北京墙上时间：这份文件是给「同一台机器 / 同一个校园」看的，
                // 用设备时区会让导出方在 GMT 机器上写下一串比真实时间差 8 小时的值。
                generatedAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .format(java.time.LocalDateTime.now(AppZone.ZONE)),
                note = "由应用内「导出数据」生成的整机快照"
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
            categoryRules = rules,
            tasks = exportableTasks.map { t ->
                TaskBundle(
                    syncUid = t.syncUid,
                    // 未关联课程的待办 courseId = 0，写 null 而不是空串
                    courseKey = if (t.courseId == TaskEntity.UNLINKED_COURSE_ID) {
                        null
                    } else {
                        courseKeyById[t.courseId]
                    },
                    title = t.title,
                    description = t.description,
                    taskType = t.taskType,
                    startTimeTimestamp = t.startTimeTimestamp,
                    dueDateTimestamp = t.dueDateTimestamp,
                    startTimeString = t.startTimeString,
                    dueDateString = t.dueDateString,
                    priority = t.priority,
                    isCompleted = t.isCompleted,
                    completedAt = t.completedAt,
                    isAutoGenerated = t.isAutoGenerated,
                    generatedDate = t.generatedDate,
                    week = t.week,
                    dayOfWeek = t.dayOfWeek,
                    createdAt = t.createdAt,
                    updatedAt = t.updatedAt
                )
            },
            taskTypes = taskTypeRepo.getAllOnce().map {
                TaskTypeBundle(
                    id = it.id,
                    label = it.label,
                    colorHex = it.colorHex,
                    inkHex = it.inkHex,
                    sortOrder = it.sortOrder
                )
            },
            taskRules = taskRules.flatMap { (courseId, byType) ->
                val courseKey = courseKeyById[courseId] ?: return@flatMap emptyList()
                byType.map { (type, spec) ->
                    TaskRuleBundle(
                        courseKey = courseKey,
                        taskType = type.name,
                        cadence = spec.cadence,
                        dueOffsetDays = spec.dueOffsetDays,
                        dueBeforeMinutes = spec.dueBeforeMinutes
                    )
                }
            }
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