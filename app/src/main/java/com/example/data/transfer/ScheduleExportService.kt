package com.example.data.transfer

import com.example.data.calendar.AppZone
import com.example.data.calendar.WeekPlanBuilder
import com.example.data.codec.AutomationBundle
import com.example.data.codec.CalendarBundleBuilder
import com.example.data.codec.CourseBundle
import com.example.data.codec.ExamBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TaskBundle
import com.example.data.codec.TaskRuleBundle
import com.example.data.codec.TaskTypeBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.local.TaskEntity
import com.example.data.model.CourseKeys
import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ExamRepository
import com.example.data.repository.ScheduleSettingsRepository
import com.example.data.repository.TaskRepository
import com.example.data.repository.TaskTypeRepository
import com.example.data.timetable.WeekSet
import kotlinx.coroutines.flow.first
import java.time.format.DateTimeFormatter

/**
 * 把库里的当前状态导成同一份 JSON 契约（schemaVersion = 4）——**整机快照**。
 *
 * 与导入共用 [com.example.data.codec.ScheduleCodec]，所以「导出 → 导入」天然闭环：
 * 用户换机、重装、备份都不需要 App 之外的任何知识。
 *
 * 快照装的是**这台设备上的全部用户状态**，不只是课表：
 * 课程 / 校历 / 作息 / 类别规则 / **待办** / **自定义待办分类** / **课程级生成规则** /
 * **考试**。
 * 待办尤其不能少 —— 课表随时能从浙大重新拉一遍，勾没勾、打没打星标、拉高亮没拉高亮，
 * 服务端根本不知道。换机丢待办是最贵的一种丢。
 * 考试同理：教务网在还没到考试季时回空，**上一份缓存才是此刻唯一有用的信息**，
 * 少带一场考试就是少一场。
 *
 * 顶层分块（`calendar` / `timetable` / `exams` / `categoryRules` / `tasks` /
 * `taskTypes` / `taskRules`）是因为**来源不同**：前四块可以随时由浙大同步覆盖，
 * 后三块只能靠这份文件。
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
    private val taskTypeRepo: TaskTypeRepository,
    private val examRepo: ExamRepository
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
        // 校历的**合成态**（学期 + 作息 + 停课 + 补课 合成一个 AcademicCalendar）。
        // 取一次就好：它是 Flow，两次 `first()` 会取到两个不同时刻的值，
        // 而周课表与学期得来自同一份。
        val calendarState = calendarRepo.calendarState.first()

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
            calendar = CalendarBundleBuilder.calendar(semester, periods, noClass, makeup),
            timetable = TimetableBlockBundle(
                termRef = semester?.let { TermRefBundle(it.yearSpan, CalendarBundleBuilder.termCode(it.term)) },
                courses = courses.map { c ->
                    CourseBundle(
                        key = c.dedupeKey,
                        name = c.name,
                        code = c.code,
                        teacher = c.teacher,
                        category = c.category,
                        classroom = c.classroom,
                        colorHex = c.colorHex,
                        // 备注是用户自己的数据，导出时必须带上：这份文件常常就是
                        // 「换一台设备」的手段，备注只在本机留着等于白写。
                        // 空备注写成空串（而不是 null）：**这份文件带了这一项，
                        // 且明确是空的**，导入侧据此清空对方机器上的旧备注。
                        note = c.note,
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
            },
            exams = examRepo.getAllExamsOnce().map { e ->
                ExamBundle(
                    xkkh = e.xkkh,
                    courseCode = e.courseCode,
                    courseName = e.courseName,
                    kind = e.kind,
                    startAt = e.startAt,
                    endAt = e.endAt,
                    startMinute = e.startMinute,
                    endMinute = e.endMinute,
                    dateLabel = e.dateLabel,
                    // 教室 / 座位写成空串 = 「学校还没安排」。
                    // 空串不是「未知」：界面要把这两种显示成不同的话。
                    classroom = e.classroom,
                    seat = e.seat,
                    credit = e.credit,
                    termLabel = e.termLabel,
                    updatedAt = e.updatedAt
                )
            },
            // 渲染好的周课表。原始规则在上面两块里，这份是**派生结果** ——
            // 带着它是为了网页端：它只会画、不会算，而排课判定只有这一份实现。
            weekPlan = WeekPlanBuilder.build(
                calendar = calendarState.calendar,
                slots = slots,
                weeksBySlotId = weeksBySlot,
                coursesById = courses.associateBy { it.id },
                exams = examRepo.getExamEntries().first(),
                periodTable = calendarState.periodTable
            )
        )

        return codec.encode(bundle)
    }

}
