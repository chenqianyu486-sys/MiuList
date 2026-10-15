package com.example.data.codec

import com.example.data.calendar.TweakKind
import com.example.data.model.CategoryRule

/**
 * 课表的中间模型。
 *
 * **三种数据源全部先变成这个 Bundle，再过同一个 [com.example.data.validate.ScheduleBundleValidator]，
 * 最后才允许触达数据库。** 这是管道唯一的入口不变量：
 * - 手动编辑：直接构造 `CourseBundle`，不过 DTO
 * - JSON 文件：`JsonScheduleCodec.decode` → Bundle
 * - 浙大教务网：App 内爬虫产出 JSON → 与文件同一路径
 *
 * 这里存的是「还没归一化」的原始值（`weeks` 可能是 `"1-16周(单)"`、`colorHex` 可能为空、
 * 校历里的 `Begin` 可能是 0）。归一化与判错是 Validator 的职责，刻意与解析分开：
 * 爬虫产出的 JSON 同样不可信，必须和用户导入的文件走同一道关。
 *
 * v2 把顶层分成 [calendar] 与 [timetable] 两块：课表是与学期绑定的，
 * [TimetableBlockBundle.termRef] 指明这些课属于 [CalendarBundle.termConfigs] 里的哪一期。
 */
data class ScheduleBundle(
    val provenance: ProvenanceBundle = ProvenanceBundle(),
    val calendar: CalendarBundle? = null,
    val timetable: TimetableBlockBundle? = null,
    val categoryRules: List<CategoryRule> = emptyList()
)

data class ProvenanceBundle(
    val source: String? = null,
    val generator: String? = null,
    val generatedAt: String? = null,
    val schoolId: String? = null,
    val studentId: String? = null,
    val note: String? = null
)

/**
 * 校历块。字段与 zju-ical `configs/config.json` 同构。
 *
 * [legacyNoClassRanges] / [legacyMakeupDays] 只在读 v1 旧文档时非空：
 * 旧格式没有 tweaks 形态的调休。导出永远只写 tweaks。
 */
data class CalendarBundle(
    val lastUpdated: Int? = null,
    val termConfigs: List<TermConfigBundle> = emptyList(),
    val classTerms: List<String> = emptyList(),
    val examTerms: List<String> = emptyList(),
    val tweaks: List<TweakBundle> = emptyList(),
    val periods: List<PeriodBundle> = emptyList(),
    val legacyNoClassRanges: List<NoClassRangeBundle> = emptyList(),
    val legacyMakeupDays: List<MakeupDayBundle> = emptyList()
)

/** 一期学期的起止。`begin` / `end` 是 `YYYYMMDD` 的整数（上游原格式）。 */
data class TermConfigBundle(
    val year: String?,
    val term: Int?,
    val begin: Int?,
    val end: Int?,
    val firstWeekNo: Int?
) {
    /** `"2024-2025:4"` —— zju-ical `classTerms` 用的就是这个写法。 */
    val key: String get() = "$year:$term"
}

/**
 * 调休规则。语义见 [TweakKind]。
 *
 * [from] / [to] 是 `YYYYMMDD` 整数（上游原格式），**两端都是具体的某一天**，
 * 不是「星期几」。这是调休唯一正确的粒度：同一条规则里 From 与 To 可以相隔
 * 两周以上，只记周几就丢掉了「这是哪一周的课」这一半信息。
 *
 * [tweakType] 可空：认不出来的类型**不能默认成 [TweakKind.CLEAR]**——
 * 那等于把一条看不懂的规则当成停课执行，用户会莫名其妙少一天的课。
 * 保留成 null 交给校验期报 issue、整条忽略。
 */
data class TweakBundle(
    val tweakType: TweakKind?,
    val description: String?,
    val from: Int?,
    val to: Int?
)

data class PeriodBundle(
    val period: Int?,
    val startTime: String?,
    val endTime: String?,
    val section: String?
)

data class NoClassRangeBundle(
    val startDate: String?,
    val endDate: String?,
    val note: String?
)

/**
 * v1 形态的补课日。
 *
 * [makeupForDayOfWeek] 是「这天上星期几的课」，[fromDate] 是「课原本属于哪一天」。
 * 两者回答的不是同一个问题：前者决定界面上出哪门课，后者决定它算第几周。
 *
 * 补课时 From 与 To 常常差两周以上（2026 国庆：10-02 的周五课补到 10-17），
 * 只留 [makeupForDayOfWeek] 会把补上的课算进补课当天的周次，单双周课就集体判错。
 * [fromDate] 为 null 表示「来源日未知」（v1 文档没有这个字段），
 * 此时退回按补课当天的日期算周次——这是旧有的、也是仅有的兼容路径。
 */
data class MakeupDayBundle(
    val date: String?,
    val makeupForDayOfWeek: Int?,
    /** `YYYY-MM-DD`；null = 未知。 */
    val fromDate: String? = null,
    val note: String? = null
)

data class TimetableBlockBundle(
    /** 这些课属于哪一期学期。对不上 termConfigs 就整档失败。 */
    val termRef: TermRefBundle? = null,
    val courses: List<CourseBundle> = emptyList()
)

data class TermRefBundle(
    val year: String?,
    val term: Int?
)

data class CourseBundle(
    val key: String? = null,
    val name: String? = null,
    val code: String? = null,
    val teacher: String? = null,
    val category: String? = null,
    val classroom: String? = null,
    val colorHex: String? = null,
    val automation: AutomationBundle? = null,
    val slots: List<SlotBundle> = emptyList(),
    val extra: String? = null
)

data class AutomationBundle(
    val preStudy: Boolean? = null,
    val postHomework: Boolean? = null,
    val labReport: Boolean? = null,
    val review: Boolean? = null,
    val defaultPostDueDays: Int? = null,
    val preStudyMinutes: Int? = null,
    val postHomeworkMinutes: Int? = null,
    val labReportMinutes: Int? = null
)

data class SlotBundle(
    val dayOfWeek: Int? = null,
    val startPeriod: Int? = null,
    val endPeriod: Int? = null,
    val classroom: String? = null,
    /** 原始值：整数数组或紧凑字符串（`"1-16周(单)"`）。null / 空 = 每周。 */
    val weeks: Any? = null
)