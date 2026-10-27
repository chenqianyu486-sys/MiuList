package com.example.data.validate

import com.example.data.calendar.EpochDays
import com.example.data.calendar.TweakKind
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.AutomationBundle
import com.example.data.codec.CalendarBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.ExamBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.SlotBundle
import com.example.data.codec.academicTermOf
import com.example.data.local.MakeupDayEntity
import com.example.data.local.NoClassDayEntity
import com.example.data.local.ExamEntity
import com.example.data.local.SemesterEntity
import com.example.data.model.COURSE_PALETTE
import com.example.data.model.CourseCategory
import com.example.data.model.CourseKeys
import com.example.data.model.isValidHexColor
import com.example.data.model.formatPeriodRange
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
    /** 归一化后的考试。空列表 = 这份文件没带考试，导入时不动本机的考试。 */
    val exams: List<ExamBundle>,
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
 * 缺整块（无校历 / 无作息表 / 无类别规则 / 无考试）一律**不报整档失败**，
 * 按「未配置」处理：爬虫少抓一节不该让整个导入归零。只有两处无法推定的情况
 * 才整档失败：学期日期自相矛盾、`timetable.termRef` 对不上 `calendar.termConfigs`。
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

        val rawSemester = selectedTerm?.let { validatedSemesterOrNull(it, calendar?.termConfigs.orEmpty()) }
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
        // 区间判定用整个学年学期（rawSemester），不是选中那一条半学期 ——
        // 否则冬那半学期的元旦停课会落在秋的 End 之后被丢掉
        val (noClass, makeup) = expandCalendarDays(calendar, rawSemester, issues)

        // ---- 课程 ----
        val courses = validateCourses(raw.timetable?.courses.orEmpty(), periodsPerDay, totalWeeks, issues)

        // ---- 考试 ----
        val exams = validateExams(raw.exams, issues)

        return ValidationResult.Valid(
            ValidatedBundle(
                provenance = raw.provenance,
                semester = semester,
                timetable = periods,
                noClassDays = noClass,
                makeupDays = makeup,
                courses = courses,
                categoryRules = raw.categoryRules,
                exams = exams,
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

    /**
     * 由被选中的那一半学期，构造**整个学年学期**。
     *
     * [siblings] 是这份文件里的全部 `termConfigs`。秋(1–8 周)与冬(9–16 周)
     * 合起来才是浙大说的「一个学期」，所以这里要跨到同年的另一半：
     * 只按选中那一条算，学期就成了 8 周，而后半学期的课整段不出现。
     *
     * 同年没有另一半时（例如上游只给了秋）退回单半学期的口径 ——
     * 那时文件里确实只有 8 周的信息，不能凭空补出 8 周来。
     */
    private fun validatedSemesterOrNull(
        raw: TermConfigBundle,
        siblings: List<TermConfigBundle>
    ): ValidatedSemester? {
        val year = raw.year ?: return null
        val term = raw.term ?: return null

        val academic = siblings.academicTermOf(year, term)
        if (academic != null) {
            return ValidatedSemester(
                name = academic.label,
                yearSpan = academic.yearSpan,
                term = academic.season ?: academic.halves.first().label,
                startEpochDay = academic.startEpochDay,
                firstMondayEpochDay = academic.firstMondayEpochDay,
                endEpochDay = academic.endEpochDay,
                totalWeeks = academic.totalWeeks,
                periodsPerDay = 0 // 由调用方按实际作息回填
            )
        }

        // 没有同年的另一半（或日期坏了）：退回按这一条自己算，口径与改造前一致
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
     * v2 的规范形态是 `tweaks`（与 zju-ical 同构），三种语义见 [TweakKind]。
     * **每条规则的两端都是具体的某一天**，所以 `MOVE` / `EXCHANGE` 落库时
     * 只记来源日（补周几与第几周都由它算）：
     *
     * | TweakKind | 工作日与周末调休（借调公休） | 双工作日互换 |
     * |-----------|-----------------------------|--------------|
     * | Clear     | `noClassDays` 展开 From..To  | 同左 |
     * | Move      | 工作日让出课，周末上工作日的课 | 同左 |
     * | Exchange  | 工作日让出课，周末上工作日的课 | 双方互为补课日，互上对方星期的课 |
     *
     * 让出课的那天（`Move` 的工作日、或 `Exchange` 与周末调休时的工作日）**不在这里写进 `noClassDays`**：
     * 「这份课被搬到哪一天」已经完整记在 `makeupDays[周末].sourceEpochDay` 里，
     * 再抄一份进 `noClassDays` 就是同一事实存两处 —— 导出时会被合成一条
     * CLEAR + 一条 MOVE，导回来多出一个停课日，round-trip 不再稳定。
     * 「让出」这件事由 [AcademicCalendar.isVacatedForShift] 从 makeup 推导。
     *
     * 区间外判定用的是**整个学年学期**的范围，不是选中那一条半学期的：
     * 冬那半学期的元旦停课、12-31 补课都落在秋的 `End` 之后，
     * 按单半学期裁会把后半学期的调休整段丢掉。
     *
     * v1 旧文档的 `legacyNoClassRanges` / `legacyMakeupDays` 也在这里并进来，
     * 所以两条兼容路径共用同一份展开逻辑。
     */
    private fun expandCalendarDays(
        calendar: CalendarBundle?,
        term: ValidatedSemester?,
        issues: MutableList<ImportIssue>
    ): Pair<List<NoClassDayEntity>, List<MakeupDayEntity>> {
        val noClass = mutableListOf<NoClassDayEntity>()
        val makeup = mutableListOf<MakeupDayEntity>()
        if (calendar == null) return noClass to makeup

        val begin = term?.startEpochDay
        val end = term?.endEpochDay

        calendar.tweaks.forEachIndexed { index, tw ->
            val from = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.from)
            val to = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.to)
            if (from == null || to == null) {
                issues += ImportIssue("调休第 ${index + 1} 项", "日期无法解析，已忽略")
                return@forEachIndexed
            }
            val kind = tw.tweakType
            if (kind == null) {
                issues += ImportIssue(
                    "调休第 ${index + 1} 项",
                    "TweakType 无法识别（${tw.tweakType}），已忽略"
                )
                return@forEachIndexed
            }
            // **只有停课是「区间」，方向才有意义。**
            // 对调的两端是对称的，真实配置里就出现过倒的：
            // 「10月6日与9月20日的课对调」From=20261006 / To=20260920。
            // 早先对所有类型都查 `to < from`，这一条调休被当成「日期无法解析」整条丢掉。
            if (kind == TweakKind.CLEAR && to < from) {
                issues += ImportIssue("调休第 ${index + 1} 项", "停课区间结束早于开始，已忽略")
                return@forEachIndexed
            }
            if (begin != null && end != null && (to < begin || from > end)) {
                issues += ImportIssue("调休第 ${index + 1} 项", "不在学期区间内，已忽略")
                return@forEachIndexed
            }
            val note = tw.description.orEmpty()
            when (kind) {
                TweakKind.CLEAR ->
                    EpochDays.expandRange(from, to).forEach { noClass += NoClassDayEntity(it, note) }

                TweakKind.MOVE ->
                    makeup += MakeupDayEntity(to, EpochDays.dayOfWeek1To7(from), note, from)

                TweakKind.EXCHANGE -> {
                    val fromIsWeekend = EpochDays.isWeekend(from)
                    val toIsWeekend = EpochDays.isWeekend(to)
                    when {
                        // 工作日与周末对调（节假日借调公休）：
                        // 周末作为补课日（上工作日的课）；工作日让出课（作为法定休息日不上课，由 isVacatedForShift 推导）
                        !fromIsWeekend && toIsWeekend -> {
                            makeup += MakeupDayEntity(to, EpochDays.dayOfWeek1To7(from), note, from)
                        }
                        fromIsWeekend && !toIsWeekend -> {
                            makeup += MakeupDayEntity(from, EpochDays.dayOfWeek1To7(to), note, to)
                        }
                        else -> {
                            // 两天同为工作日（普通换课）或同为周末：常规双向互换
                            makeup += MakeupDayEntity(to, EpochDays.dayOfWeek1To7(from), note, from)
                            makeup += MakeupDayEntity(from, EpochDays.dayOfWeek1To7(to), note, to)
                        }
                    }
                }
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
            if (day == null) {
                issues += ImportIssue("补课第 ${index + 1} 项", "日期无法解析，已忽略")
                return@forEachIndexed
            }
            // 来源日是**必填**：补周几和算第几周都由它算出来，没有它这次补课无从解释。
            // 早先的兜底是「退回按补课当天算」，等于把一条说不清的搬运塞进课表：
            // 界面上看不出任何异常，直到单双周的课集体判错。
            val source = m.fromDate?.let { EpochDays.parse(it) }
            if (source == null) {
                issues += ImportIssue(
                    "补课 ${m.date ?: "?"}",
                    "缺少来源日（补的是哪一天的课），已忽略该条"
                )
                return@forEachIndexed
            }
            makeup += MakeupDayEntity(day, EpochDays.dayOfWeek1To7(source), m.note.orEmpty(), source)
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
            // 时段先归一化（补齐缺项、裁周次），再把同一天的相连时段并起来。
            val slots = mergeAdjacentSlots(
                validateSlots(course.slots, periodsPerDay, totalWeeks, scope, issues),
                scope,
                issues
            )
            // 文档写了类别就照用；没写才按课程名猜（实验课 → 实验实践，其余 → 默认类别）。
            val category = course.category?.trim()?.takeIf { it.isNotEmpty() }
                ?: CourseCategory.classifyOnImport(course.name)
            course.copy(
                colorHex = color,
                category = category,
                classroom = course.classroom.orEmpty(),
                slots = slots,
                automation = normalizeAutomation(course.automation)
            )
        }
    }

    // ---------------------------------------------------------------- 考试

    /**
     * 归一化考试。
     *
     * **一场考试没有课程名就认不出来**，那种直接跳过并报 issue ——
     * 界面上它只是一张没有名字的卡片，用户认不出是哪门课。
     *
     * **时刻与绝对时间各自独立**（与 `ExamEntity` 的类注释一致）：
     * 日期没公布（`startAt = 0`）时学校照样给了「几点考」，那个时刻要保住；
     * 反过来时刻越界（不在 0..1439）只会让这条考试在周课表里落不到任何一格，
     * 所以归零并报 issue。
     */
    private fun validateExams(
        raw: List<ExamBundle>,
        issues: MutableList<ImportIssue>
    ): List<ExamBundle> {
        if (raw.isEmpty()) return emptyList()

        val byKey = LinkedHashMap<String, ExamBundle>()
        raw.forEachIndexed { index, e ->
            val name = e.courseName?.trim().orEmpty()
            if (name.isEmpty()) {
                issues += ImportIssue("考试第 ${index + 1} 场", "缺少课程名，已忽略该场考试")
                return@forEachIndexed
            }
            val scope = "考试「$name」"

            val kind = when (e.kind?.trim()?.uppercase()) {
                null, "" -> ExamEntity.KIND_FINAL
                ExamEntity.KIND_MIDTERM -> ExamEntity.KIND_MIDTERM
                ExamEntity.KIND_FINAL -> ExamEntity.KIND_FINAL
                else -> {
                    issues += ImportIssue(scope, "考试类型 ${e.kind} 无法识别，已按期末考处理")
                    ExamEntity.KIND_FINAL
                }
            }

            val startMinute = e.startMinute?.takeIf { it in 0..1439 }
            if (e.startMinute != null && startMinute == null) {
                issues += ImportIssue(scope, "开考时刻 ${e.startMinute} 越界，已按未知处理")
            }
            var endMinute = e.endMinute?.takeIf { it in 0..1439 }
            if (e.endMinute != null && endMinute == null) {
                issues += ImportIssue(scope, "结束时刻 ${e.endMinute} 越界，已按未知处理")
            }
            if (endMinute == null) endMinute = 0
            if (startMinute != null && endMinute != 0 && endMinute < startMinute) {
                issues += ImportIssue(scope, "结束时刻早于开考时刻，已按开考时刻处理")
                endMinute = startMinute
            }

            // 日期没公布时两个绝对时刻都是 0；有日期时两个都要有值。
            val startAt = e.startAt?.takeIf { it > 0L } ?: 0L
            var endAt = e.endAt?.takeIf { it > 0L } ?: 0L
            if (startAt == 0L) {
                endAt = 0L
            } else if (endAt < startAt) {
                issues += ImportIssue(scope, "结束时间早于开始时间，已按开始时间处理")
                endAt = startAt
            }

            val credit = e.credit?.takeIf { it >= 0.0 } ?: 0.0

            val normalized = e.copy(
                xkkh = e.xkkh?.trim()?.takeIf { it.isNotEmpty() },
                courseCode = e.courseCode?.trim().orEmpty(),
                courseName = name,
                kind = kind,
                startAt = startAt,
                endAt = endAt,
                startMinute = startMinute ?: 0,
                endMinute = endMinute,
                dateLabel = e.dateLabel?.trim().orEmpty(),
                // 教室 / 座位的空串是「学校还没安排」，null 是「这份文件没带这一项」。
                // 落到库里统一成空串 —— 库里没有「没带」这个状态。
                classroom = e.classroom?.trim().orEmpty(),
                seat = e.seat?.trim().orEmpty(),
                credit = credit,
                termLabel = e.termLabel?.trim().orEmpty(),
                updatedAt = e.updatedAt ?: 0L
            )

            // 同一场考试在文件里出现两次：留**较新**的那条（同一天里教务网补发了座位号）。
            val key = normalized.naturalKey
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = normalized
            } else {
                val winner = if ((normalized.updatedAt ?: 0L) >= (existing.updatedAt ?: 0L)) {
                    normalized
                } else {
                    existing
                }
                byKey[key] = winner
                issues += ImportIssue(scope, "文件中出现两次，已保留较新的一条")
            }
        }
        return byKey.values.toList()
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

    /**
     * 把「同一门课 + 同一天 + 同一地点 + 周次相同 + 节次相连」的时段并成一条。
     *
     * 教务系统常把连堂课拆成多行发（第 6 节、第 7 节各一条），导入后课表上就成了
     * 上下两块挤在一起的卡，看着像两节课，其实是一节连堂。
     *
     * **周次必须进分组键**：同一地点的第 1-8 节与第 9-16 节是学期前后两段，
     * 并起来周次就变成 1-16 —— 那是凭空多出一半课，比不合并错得离谱。
     * 地点不同的两段（同一节课换了教室）同样不并。
     *
     * 只有一段的组**原样按原顺序输出**：合并只在确实能并时发生，不顺手重排时段。
     * 合并过就往 [issues] 记一条，与本文件「不做静默变换」的规矩一致。
     */
    private fun mergeAdjacentSlots(
        slots: List<SlotBundle>,
        scope: String,
        issues: MutableList<ImportIssue>
    ): List<SlotBundle> {
        if (slots.size < 2) return slots

        val groups = LinkedHashMap<SlotGroupKey, MutableList<SlotBundle>>()
        slots.forEach { s ->
            val key = SlotGroupKey(
                dayOfWeek = s.dayOfWeek ?: 0,
                classroom = s.classroom.orEmpty().trim(),
                weeks = s.weeksKey()
            )
            groups.getOrPut(key) { mutableListOf() } += s
        }

        val out = mutableListOf<SlotBundle>()
        groups.forEach { _, group ->
            val folded = foldAdjacent(group.sortedBy { it.startPeriod ?: 0 })
            if (folded.size < group.size) {
                issues += ImportIssue(
                    scope,
                    "同一天的同一地点有 ${group.size} 段相连课时，已合并为 " +
                        folded.joinToString("、") {
                            formatPeriodRange(it.startPeriod ?: 0, it.endPeriod ?: it.startPeriod ?: 0)
                        }
                )
            }
            out += folded
        }
        return out
    }

    /** 已按起始节排好序的一组时段折叠成不相邻的几段。相接与重叠都算相连。 */
    private fun foldAdjacent(sorted: List<SlotBundle>): List<SlotBundle> {
        if (sorted.isEmpty()) return emptyList()
        val folded = mutableListOf<SlotBundle>()
        var current = sorted.first()
        sorted.drop(1).forEach { next ->
            val curEnd = current.endPeriod ?: current.startPeriod ?: 0
            val nextStart = next.startPeriod ?: 0
            current = if (nextStart <= curEnd + 1) {
                current.copy(endPeriod = maxOf(curEnd, next.endPeriod ?: curEnd))
            } else {
                folded += current
                next
            }
        }
        folded += current
        return folded
    }

    /**
     * 周次分组键。经过 [WeekSet.parse] 之后一定是 `Set<Int>`，这里仍按集合取而不是
     * 直接 cast：JSON 解出来的周次是 `List<Number>`，写死 `as Set<Int>` 会在某天
     * 形状变了时抛 ClassCastException，把整档导入带崩。
     */
    private fun SlotBundle.weeksKey(): Set<Int> = when (val raw = weeks) {
        is Collection<*> -> raw.mapNotNull { (it as? Number)?.toInt() }.toSet()
        else -> emptySet()
    }

    /** 「能不能并成一段」的分组键：同一天 + 同一地点 + 同一组周次。 */
    private data class SlotGroupKey(
        val dayOfWeek: Int,
        val classroom: String,
        val weeks: Set<Int>
    )
}