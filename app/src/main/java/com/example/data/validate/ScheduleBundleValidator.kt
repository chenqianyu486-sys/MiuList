package com.example.data.validate

import com.example.data.calendar.EpochDays
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.AutomationBundle
import com.example.data.codec.CalendarBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.SlotBundle
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.SemesterEntity
import com.example.data.model.COURSE_PALETTE
import com.example.data.model.CourseKeys
import com.example.data.model.DEFAULT_CATEGORY
import com.example.data.model.isValidHexColor
import com.example.data.model.CategoryRule
import com.example.data.timetable.PeriodSlot
import com.example.data.timetable.PeriodTable
import com.example.data.timetable.WeekSet

/** 一条可展示的问题。永远随预览一起呈现，不做静默丢数据。 */
data class ImportIssue(val scope: String, val detail: String)

sealed interface ValidationResult {
    /** 整档不可用：JSON 语法错、版本过高、或学期日期自相矛盾。 */
    data class Invalid(val message: String) : ValidationResult

    /** 可导入。行级问题都在 [ValidatedBundle.issues] 里。 */
    data class Valid(val bundle: ValidatedBundle) : ValidationResult
}

data class ValidatedBundle(
    val provenance: ProvenanceBundle,
    /** null = 未配置学期，走 AcademicCalendar 的降级路径 */
    val semester: ValidatedSemester?,
    /** null = 文件没带作息表，沿用库里的（首次则为内置默认 13 节） */
    val timetable: List<PeriodSlot>?,
    val noClassDays: List<NoClassDayEntity>,
    val makeupDays: List<MakeupDayEntity>,
    val courses: List<CourseBundle>,
    val categoryRules: List<CategoryRule>,
    val issues: List<ImportIssue>,
    /** 归一化后的总周数，供开课周次裁剪使用 */
    val totalWeeks: Int,
    val periodsPerDay: Int
)

data class ValidatedSemester(
    val name: String,
    val yearSpan: String,
    val term: String,
    val startEpochDay: Long,
    val firstMondayEpochDay: Long,
    val endEpochDay: Long,
    val totalWeeks: Int,
    val periodsPerDay: Int
) {
    fun toEntity() = SemesterEntity(
        id = SemesterEntity.SINGLETON_ID,
        name = name,
        yearSpan = yearSpan,
        term = term,
        startEpochDay = startEpochDay,
        firstMondayEpochDay = firstMondayEpochDay,
        endEpochDay = endEpochDay,
        totalWeeks = totalWeeks,
        periodsPerDay = periodsPerDay
    )
}

/**
 * 课表数据归一化与校验。
 *
 * **刻意不认识 JSON**：只吃 [ScheduleBundle]、只吐 [ValidatedBundle]。因此
 * - 手动编辑与导入文件必然走同一套规则
 * - 浙大教务网爬虫（在 App 内跑的）产出的 JSON，复用同一份判定
 *
 * 缺整块（无校历 / 无作息表 / 无类别规则）一律**不报整档失败**，按「未配置」
 * 处理：爬虫少抓一节不该让整个导入归零。只有两处无法推定的情况才整档失败：
 * 学期日期自相矛盾、`timetable.termRef` 对不上 `calendar.termConfigs`。
 */
class ScheduleBundleValidator {

    fun validate(raw: ScheduleBundle): ValidationResult {
        val issues = mutableListOf<ImportIssue>()
        val calendar = raw.calendar

        // ---- 学期：从 termConfigs 里选出被这一份文档使用的那一期 ----
        // 三态而不是「选出 / 没选出」两态：没有学期配置是**合法的降级**，
        // 而 termRef 对不上、多期学期又没有 termRef 才是无法推定的整档失败。
        // 合成两态会让「只带课程、不带校历」的文件一律导不进来 —— 那是最常见的一种。
        val selection = selectTerm(calendar, raw.timetable?.termRef)
        if (selection is TermSelection.Unresolved) {
            return ValidationResult.Invalid(selection.reason)
        }
        val selectedTerm = (selection as? TermSelection.Picked)?.term

        // 起始晚于结束是**自相矛盾**，不是「缺信息」：没有可以推定的取值，
        // 硬猜一个会让整个学期的周次全错，所以整档失败。
        // （日期根本解析不出来是另一回事，那条走下面的 issue：按未配置学期处理。）
        if (selectedTerm != null && selectedTerm.hasContradictoryRange()) {
            return ValidationResult.Invalid(
                "学期 ${selectedTerm.year}:${selectedTerm.term} 的起始日期晚于结束日期（" +
                    "${selectedTerm.begin} → ${selectedTerm.end}）"
            )
        }

        val rawSemester = selectedTerm?.let { validatedSemesterOrNull(it) }
        if (rawSemester == null && selectedTerm != null) {
            issues += ImportIssue("校历", "学期起止日期无法解析，已按未配置学期处理")
        }
        val totalWeeks = rawSemester?.totalWeeks ?: WeekSet.FALLBACK_TOTAL_WEEKS

        // ---- 作息表 ----
        val periods = validateTimetable(calendar, issues)
        val periodsPerDay = calendar?.periods?.size?.takeIf { it >= 1 }
            ?: periods?.size
            ?: PeriodTable.DEFAULT_PERIODS.size
        // periodsPerDay 取决于这份文件带了什么作息，所以学期要等作息算完再定稿。
        val semester = rawSemester?.copy(periodsPerDay = periodsPerDay)

        // ---- 停课 / 补课：tweaks 是 v2 的规范形态，legacy 两段是 v1 兼容 ----
        val (noClass, makeup) = expandCalendarDays(calendar, selectedTerm, issues)

        // ---- 课程 ----
        val courses = validateCourses(raw.timetable?.courses.orEmpty(), periodsPerDay, totalWeeks, issues)

        return ValidationResult.Valid(
            ValidatedBundle(
                provenance = raw.provenance,
                semester = semester,
                timetable = periods,
                noClassDays = noClass,
                makeupDays = makeup,
                courses = courses,
                categoryRules = raw.categoryRules,
                issues = issues.toList(),
                totalWeeks = totalWeeks,
                periodsPerDay = periodsPerDay
            )
        )
    }

    // ---------------------------------------------------------------- 学期选择

    /**
     * 学期选择的结果。**必须是三态**：把「文档没写学期」和「学期写错了」都表示成
     * `null` 的话，上层只能一律判失败，于是最常见的「只带一份课表」的文件也导不进来。
     */
    private sealed interface TermSelection {
        data class Picked(val term: TermConfigBundle) : TermSelection

        /** 文档里没有任何可用学期配置 —— 按「未配置学期」处理，不是错误。 */
        object None : TermSelection

        /** termRef 对不上，或多期学期却没有 termRef：无法推定，整档失败。 */
        data class Unresolved(val reason: String) : TermSelection
    }

    /**
     * 选出这份文档真正使用的学期配置。
     *
     * 规则：
     * - 没有 termConfigs → [TermSelection.None]（按「未配置学期」处理，不是错误）
     * - `timetable.termRef` 给了就必须精确对上，否则整档失败
     * - 没给 termRef 但只有一期学期 → 就用那一期
     * - 没给 termRef 且有多期 → 无法判断这些课属于哪一期，
     *   猜一个的后果是把这学期的课挂到另一个学期的周次上
     */
    private fun selectTerm(
        calendar: CalendarBundle?,
        termRef: TermRefBundle?
    ): TermSelection {
        // **这里不能预先滤掉日期缺失的项**：那种学期仍然该被选中，
        // 选中之后才谈得上报「学期起止日期无法解析」这条 issue。先滤掉的话，
        // 一份只有坏日期的学期会静悄悄退化成「未配置学期」，用户什么都看不到。
        val configs = calendar?.termConfigs.orEmpty()
        if (configs.isEmpty()) {
            // 没有任何学期配置时，唯一说不通的是「文档却指着某学期」——那才自相矛盾。
            return if (termRef != null) {
                TermSelection.Unresolved(termRefError(calendar, termRef))
            } else {
                TermSelection.None
            }
        }
        if (termRef != null) {
            return configs.firstOrNull { it.year == termRef.year && it.term == termRef.term }
                ?.let { TermSelection.Picked(it) }
                ?: TermSelection.Unresolved(termRefError(calendar, termRef))
        }
        return configs.singleOrNull()?.let { TermSelection.Picked(it) }
            ?: TermSelection.Unresolved(multiTermError(configs))
    }

    private fun multiTermError(configs: List<TermConfigBundle>): String {
        val available = configs.joinToString("、") { "${it.year}:${it.term}" }
        return "文件里有 $available 多期学期，却没有说明这份课表属于哪一期，请补上 timetable.termRef"
    }

    /** 起始晚于结束。解析不出来的日期返回 false —— 那条交给 issue 处理。 */
    private fun TermConfigBundle.hasContradictoryRange(): Boolean {
        val b = ZjuCalendarAdapter.yyyymmddToEpochDay(begin) ?: return false
        val e = ZjuCalendarAdapter.yyyymmddToEpochDay(end) ?: return false
        return b > e
    }

    private fun termRefError(calendar: CalendarBundle?, termRef: TermRefBundle?): String {
        val available = calendar?.termConfigs.orEmpty()
            .map { "${it.year}:${it.term}" }
        return if (available.isEmpty()) {
            "课表块带了学期引用，但校历块里没有任何可用的学期配置"
        } else {
            "课表的学期引用 ${termRef?.year}:${termRef?.term} 在校历里不存在（可用：${available.joinToString("、")}）"
        }
    }

    private fun validatedSemesterOrNull(raw: TermConfigBundle): ValidatedSemester? {
        val start = ZjuCalendarAdapter.yyyymmddToEpochDay(raw.begin) ?: return null
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(raw.end) ?: return null
        if (start > end) return null

        val firstMonday = ZjuCalendarAdapter.firstMondayOf(start, raw.firstWeekNo ?: 1)
        val totalWeeks = ZjuCalendarAdapter.totalWeeks(start, end, raw.firstWeekNo ?: 1)
        val termLabel = ZjuCalendarAdapter.termLabel(raw.term)
        val yearSpan = raw.year.orEmpty()
        return ValidatedSemester(
            name = if (termLabel.isBlank()) yearSpan else "$yearSpan $termLabel",
            yearSpan = yearSpan,
            term = termLabel,
            startEpochDay = start,
            firstMondayEpochDay = firstMonday,
            endEpochDay = end,
            totalWeeks = totalWeeks,
            periodsPerDay = 0 // 由调用方按实际作息回填
        )
    }

    // ---------------------------------------------------------------- 作息表

    /** @return null = 文件没带（或整段不可用），沿用库里的作息 */
    private fun validateTimetable(calendar: CalendarBundle?, issues: MutableList<ImportIssue>): List<PeriodSlot>? {
        val raw = calendar?.periods.orEmpty()
        if (raw.isEmpty()) return null
        val out = mutableListOf<PeriodSlot>()
        val seen = mutableSetOf<Int>()
        raw.forEachIndexed { index, p ->
            val period = p.period
            if (period == null || period < 1) {
                issues += ImportIssue("作息表第 ${index + 1} 项", "节号非法，已忽略")
                return@forEachIndexed
            }
            if (!seen.add(period)) {
                issues += ImportIssue("作息表第 $period 节", "节号重复，已忽略后一条")
                return@forEachIndexed
            }
            val s = parseMinute(p.startTime)
            val e = parseMinute(p.endTime)
            if (s == null || e == null) {
                issues += ImportIssue("作息表第 $period 节", "起止时间非法，已忽略该节")
                return@forEachIndexed
            }
            if (e <= s) {
                issues += ImportIssue("作息表第 $period 节", "结束时间不晚于开始时间，已忽略该节")
                return@forEachIndexed
            }
            out += PeriodSlot(period, s, e, p.section.orEmpty())
        }
        if (out.isEmpty()) {
            issues += ImportIssue("作息表", "没有任何合法节次，沿用现有作息表")
            return null
        }
        // 归一化成 1..n 连续：节号有缺口会让「第 N 节」在界面上对不上
        val sorted = out.sortedBy { it.period }
        if (sorted.map { it.period } != (1..sorted.size).toList()) {
            issues += ImportIssue("作息表", "节号存在缺口或乱序，已按顺序重新编号")
        }
        return sorted.mapIndexed { i, slot -> slot.copy(period = i + 1) }
    }

    private fun parseMinute(text: String?): Int? {
        val t = text?.trim() ?: return null
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(t) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return h * 60 + min
    }

    // ------------------------------------------------- 停课 / 补课（调休展开）

    /**
     * 把校历块展开成「停课日」与「补课日」两张表。
     *
     * v2 的规范形态是 `tweaks`（与 zju-ical 同构），三条语义照抄上游
     * `ClassToVEvents` 里的 `shadowDates` 处理：
     *
     * | TweakType | 上游动作                          | 本项目落到            |
     * |-----------|-----------------------------------|-----------------------|
     * | 0 Clear   | 删除 From..To 这几天的全部课        | `noClassDays`        |
     * | 1 Copy    | `To 那天的课 = From 那天的课`       | `makeupDays(To, From 的周几)` |
     * | 2 Exchange| From 与 To 两天互换                 | 两条 `makeupDays`   |
     *
     * `From` / `To` 整段落在学期区间外的直接忽略 —— 上游也是这么处理的
     * （`if tweak.To.Before(begin) || tweak.To.After(end) { continue }`）。
     *
     * v1 旧文档的 `legacyNoClassRanges` / `legacyMakeupDays` 也在这里并进来，
     * 所以两条兼容路径共用同一份展开逻辑。
     */
    private fun expandCalendarDays(
        calendar: CalendarBundle?,
        term: TermConfigBundle?,
        issues: MutableList<ImportIssue>
    ): Pair<List<NoClassDayEntity>, List<MakeupDayEntity>> {
        val noClass = mutableListOf<NoClassDayEntity>()
        val makeup = mutableListOf<MakeupDayEntity>()
        if (calendar == null) return noClass to makeup

        val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(term?.begin)
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(term?.end)

        calendar.tweaks.forEachIndexed { index, tw ->
            val from = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.from)
            val to = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.to)
            if (from == null || to == null || to < from) {
                issues += ImportIssue("调休第 ${index + 1} 项", "日期无法解析，已忽略")
                return@forEachIndexed
            }
            if (begin != null && end != null && (to < begin || from > end)) {
                issues += ImportIssue("调休第 ${index + 1} 项", "不在学期区间内，已忽略")
                return@forEachIndexed
            }
            val note = tw.description.orEmpty()
            when (tw.tweakType) {
                0 -> EpochDays.expandRange(from, to).forEach { noClass += NoClassDayEntity(it, note) }

                1 -> makeup += MakeupDayEntity(to, EpochDays.dayOfWeek1To7(from), note)

                2 -> {
                    makeup += MakeupDayEntity(to, EpochDays.dayOfWeek1To7(from), note)
                    makeup += MakeupDayEntity(from, EpochDays.dayOfWeek1To7(to), note)
                }

                else -> issues += ImportIssue(
                    "调休第 ${index + 1} 项",
                    "TweakType ${tw.tweakType} 无法识别，已忽略"
                )
            }
        }

        // v1 兼容
        calendar.legacyNoClassRanges.forEachIndexed { index, r ->
            val start = EpochDays.parse(r.startDate.orEmpty())
            val end2 = EpochDays.parse(r.endDate.orEmpty()) ?: start
            if (start == null || end2 == null || end2 < start) {
                issues += ImportIssue("停课区间第 ${index + 1} 项", "日期无法解析，已忽略")
                return@forEachIndexed
            }
            EpochDays.expandRange(start, end2).forEach {
                noClass += NoClassDayEntity(it, r.note.orEmpty())
            }
        }
        calendar.legacyMakeupDays.forEachIndexed { index, m ->
            val day = EpochDays.parse(m.date.orEmpty())
            val dow = m.makeupForDayOfWeek
            if (day == null) {
                issues += ImportIssue("补课第 ${index + 1} 项", "日期无法解析，已忽略")
                return@forEachIndexed
            }
            if (dow == null || dow !in 1..7) {
                issues += ImportIssue("补课 ${m.date ?: "?"}", "补周几必须是 1–7，已忽略该条")
                return@forEachIndexed
            }
            makeup += MakeupDayEntity(day, dow, m.note.orEmpty())
        }

        return noClass.distinctBy { it.epochDay } to makeup.distinctBy { it.epochDay }
    }

    // ---------------------------------------------------------------- 课程

    private fun validateCourses(
        raw: List<CourseBundle>,
        periodsPerDay: Int,
        totalWeeks: Int,
        issues: MutableList<ImportIssue>
    ): List<CourseBundle> {
        // 先按自然键合并文件内的重复课程：合并时段，保留首条标量字段
        val byKey = LinkedHashMap<String, CourseBundle>()
        raw.forEach { c ->
            val name = c.name?.trim().orEmpty()
            if (name.isEmpty()) {
                issues += ImportIssue("课程", "缺少名称，已跳过该门课")
                return@forEach
            }
            val key = c.key?.takeIf { it.isNotBlank() }
                ?: CourseKeys.compute(c.code.orEmpty(), name, c.teacher.orEmpty())
            val normalized = c.copy(name = name, key = key)
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = normalized
            } else {
                byKey[key] = existing.copy(slots = existing.slots + normalized.slots)
                issues += ImportIssue("课程「$name」", "文件中出现两次（自然键 $key），已合并其上课时段")
            }
        }

        return byKey.values.mapIndexed { index, course ->
            val scope = "课程「${course.name}」"
            val color = if (isValidHexColor(course.colorHex)) {
                course.colorHex!!.trim().uppercase()
            } else {
                if (!course.colorHex.isNullOrBlank()) {
                    issues += ImportIssue(scope, "颜色 ${course.colorHex} 非法，已改用调色板取色")
                }
                COURSE_PALETTE[index % COURSE_PALETTE.size]
            }
            val slots = validateSlots(course.slots, periodsPerDay, totalWeeks, scope, issues)
            val category = course.category?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CATEGORY
            course.copy(
                colorHex = color,
                category = category,
                classroom = course.classroom.orEmpty(),
                slots = slots,
                automation = normalizeAutomation(course.automation)
            )
        }
    }

    private fun normalizeAutomation(raw: AutomationBundle?): AutomationBundle = AutomationBundle(
        preStudy = raw?.preStudy ?: true,
        postHomework = raw?.postHomework ?: true,
        labReport = raw?.labReport ?: false,
        review = raw?.review ?: false,
        defaultPostDueDays = raw?.defaultPostDueDays ?: 3,
        preStudyMinutes = raw?.preStudyMinutes ?: 30,
        postHomeworkMinutes = raw?.postHomeworkMinutes ?: 60,
        labReportMinutes = raw?.labReportMinutes ?: 90
    )

    private fun validateSlots(
        raw: List<SlotBundle>,
        periodsPerDay: Int,
        totalWeeks: Int,
        scope: String,
        issues: MutableList<ImportIssue>
    ): List<SlotBundle> {
        val out = mutableListOf<SlotBundle>()
        raw.forEachIndexed { index, s ->
            val dow = s.dayOfWeek
            val sp = s.startPeriod
            if (dow == null || dow !in 1..7) {
                issues += ImportIssue(scope, "第 ${index + 1} 个时段星期非法，已跳过")
                return@forEachIndexed
            }
            if (sp == null || sp !in 1..periodsPerDay) {
                issues += ImportIssue(scope, "第 ${index + 1} 个时段起始节超出 1–$periodsPerDay，已跳过")
                return@forEachIndexed
            }
            val ep = (s.endPeriod ?: sp).let { if (it > periodsPerDay) periodsPerDay else it }
            if (ep < sp) {
                issues += ImportIssue(scope, "第 ${index + 1} 个时段结束节早于起始节，已跳过")
                return@forEachIndexed
            }
            val parsed = WeekSet.parse(s.weeks, totalWeeks)
            parsed.issue?.let { issues += ImportIssue(scope, "第 ${index + 1} 个时段：$it") }
            out += s.copy(
                dayOfWeek = dow,
                startPeriod = sp,
                endPeriod = ep,
                classroom = s.classroom.orEmpty(),
                weeks = parsed.weeks
            )
        }
        return out
    }
}