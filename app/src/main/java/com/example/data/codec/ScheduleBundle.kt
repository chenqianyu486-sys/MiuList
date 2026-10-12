package com.example.data.codec

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
 * 调休规则。
 *
 * 0 = 停课（清除 From..To 的课）、1 = 补课（To 那天上 From 的课）、
 * 2 = 对调（From 与 To 互换）。
 */
data class TweakBundle(
    val tweakType: Int,
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

data class MakeupDayBundle(
    val date: String?,
    val makeupForDayOfWeek: Int?,
    val note: String?
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