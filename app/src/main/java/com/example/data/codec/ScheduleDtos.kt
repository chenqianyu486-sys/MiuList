package com.example.data.codec

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * JSON 文档的 DTO（schemaVersion = 2）。
 *
 * v2 把文档**顶层分块**：`calendar` / `timetable` / `categoryRules` 三块互不嵌套。
 * 课表是与学期绑定的，所以 `timetable` 块自己带一个 `termRef`，指明这些课属于
 * `calendar.termConfigs` 里的哪一个；校验期必须能对上，对不上整档失败。
 *
 * `calendar` 块的字段名与类型**与 zju-ical 的 `configs/config.json` 逐字段同名同义**
 * （只多一个 `periods`：上游把作息硬编码在 Go 里，没有对应的 JSON 字段）。
 * 这么设计的收益是那份 config.json 本身就能当一份「只有 calendar 的 v2 文档」直接导入。
 *
 * 全部字段可空且带默认值：**缺整块不能导致整档失败**。爬虫少抓一节不应让整个
 * 导入归零，未知字段一律忽略（Moshi 默认行为）。
 */

@JsonClass(generateAdapter = true)
data class ScheduleDocumentDto(
    val schemaVersion: Int? = null,
    val provenance: ProvenanceDto? = null,
    val calendar: CalendarDto? = null,
    val timetable: TimetableBlockDto? = null,
    val categoryRules: List<CategoryRuleDto>? = null
)

@JsonClass(generateAdapter = true)
data class ProvenanceDto(
    val source: String? = null,
    val generator: String? = null,
    val generatedAt: String? = null,
    val schoolId: String? = null,
    val studentId: String? = null,
    val note: String? = null
)

// ------------------------------------------------------------------ calendar

/**
 * 校历块。字段与 zju-ical `configs/config.json` 对齐。
 *
 * `Begin` / `End` / `From` / `To` 都是 `YYYYMMDD` 的**整数**（上游原格式），
 * 不改成字符串日期：改了就多一层转换，而这份文件是要被上游直接产出的。
 */
@JsonClass(generateAdapter = true)
data class CalendarDto(
    @Json(name = "lastUpdated") val lastUpdated: Int? = null,
    @Json(name = "termConfigs") val termConfigs: List<TermConfigDto>? = null,
    @Json(name = "classTerms") val classTerms: List<String>? = null,
    @Json(name = "examTerms") val examTerms: List<String>? = null,
    @Json(name = "tweaks") val tweaks: List<TweakDto>? = null,
    /** 本项目独有的作息表（上游把作息硬编码在 Go 里，没有 JSON 字段） */
    val periods: List<PeriodDto>? = null,
    /** v1 兼容：旧文档的停课区间，没有 tweaks 形态。导入时与 tweaks 合并生效。 */
    @Json(name = "noClassRanges") val noClassRanges: List<NoClassRangeDto>? = null,
    /** v1 兼容 */
    @Json(name = "makeupDays") val makeupDays: List<MakeupDayDto>? = null
)

@JsonClass(generateAdapter = true)
data class TermConfigDto(
    @Json(name = "Year") val year: String? = null,
    @Json(name = "Term") val term: Int? = null,
    /** YYYYMMDD */
    @Json(name = "Begin") val begin: Int? = null,
    /** YYYYMMDD */
    @Json(name = "End") val end: Int? = null,
    @Json(name = "FirstWeekNo") val firstWeekNo: Int? = null
)

/**
 * 调休规则。上游 `TweakType`：0 = 无条件清除 From..To 的课（停课），
 * 1 = 把 From 那天的课复制到 To（补课），2 = From 与 To 两天课对调。
 */
@JsonClass(generateAdapter = true)
data class TweakDto(
    @Json(name = "TweakType") val tweakType: Int? = null,
    @Json(name = "Description") val description: String? = null,
    /** YYYYMMDD */
    @Json(name = "From") val from: Int? = null,
    /** YYYYMMDD */
    @Json(name = "To") val to: Int? = null
)

@JsonClass(generateAdapter = true)
data class NoClassRangeDto(
    val startDate: String? = null,
    val endDate: String? = null,
    val note: String? = null
)

@JsonClass(generateAdapter = true)
data class MakeupDayDto(
    val date: String? = null,
    val makeupForDayOfWeek: Int? = null,
    val note: String? = null
)

@JsonClass(generateAdapter = true)
data class PeriodDto(
    val period: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val section: String? = null
)

// ------------------------------------------------------------------ timetable

/**
 * 课表块。
 *
 * @param termRef 这些课属于哪一期学期。必须能在 `calendar.termConfigs` 里对上，
 *   这是「课程是与学期绑定的」在格式层面的落地。
 */
@JsonClass(generateAdapter = true)
data class TimetableBlockDto(
    val termRef: TermRefDto? = null,
    val courses: List<CourseDto>? = null
)

@JsonClass(generateAdapter = true)
data class TermRefDto(
    @Json(name = "Year") val year: String? = null,
    @Json(name = "Term") val term: Int? = null
)

@JsonClass(generateAdapter = true)
data class CourseDto(
    val key: String? = null,
    val name: String? = null,
    val code: String? = null,
    val teacher: String? = null,
    val category: String? = null,
    val classroom: String? = null,
    val colorHex: String? = null,
    val automation: AutomationDto? = null,
    val slots: List<SlotDto>? = null,
    val extra: String? = null
)

@JsonClass(generateAdapter = true)
data class AutomationDto(
    val preStudy: Boolean? = null,
    val postHomework: Boolean? = null,
    val labReport: Boolean? = null,
    val review: Boolean? = null,
    val defaultPostDueDays: Int? = null,
    val preStudyMinutes: Int? = null,
    val postHomeworkMinutes: Int? = null,
    val labReportMinutes: Int? = null
)

/**
 * 时段的 `weeks` 字段。
 *
 * 声明成 `Any?` 是有意的：教务系统发布的就是 `"1-16周(单)"` 这种紧凑字符串，
 * 而自己导出的是整数数组，两种形状都要能收。Moshi 对这种 `Any?` 会走内置的
 * `ObjectJsonAdapter`，字符串原样给出、数组给出 `List<Number>`，正好够用。
 *
 * **不要**给它挂自定义 `JsonAdapter`：Moshi 的 `Builder.add(Object)` 不认裸的
 * `JsonAdapter` 子类，只认 `JsonAdapter.Factory` 或带 `@ToJson`/`@FromJson` 的对象；
 * 而改用 `@JsonQualifier` 限定注解又会让 KSP 阶段的 Moshi codegen 直接报
 * `Error preparing SlotDto`。归一化本来就该在 `WeekSet.parse` 里做。
 */
@JsonClass(generateAdapter = true)
data class SlotDto(
    val dayOfWeek: Int? = null,
    val startPeriod: Int? = null,
    val endPeriod: Int? = null,
    val classroom: String? = null,
    @Json(name = "weeks") val weeks: Any? = null
)

@JsonClass(generateAdapter = true)
data class CategoryRuleDto(
    val category: String? = null,
    val preStudy: Boolean? = null,
    val postHomework: Boolean? = null,
    val labReport: Boolean? = null,
    val defaultPostDays: Int? = null,
    val defaultMinutes: Int? = null,
    /** 预习的截止：上课前 N 分钟 */
    val preDueBeforeMinutes: Int? = null
)