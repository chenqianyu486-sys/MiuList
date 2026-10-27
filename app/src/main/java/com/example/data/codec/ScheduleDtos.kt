package com.example.data.codec

import com.example.data.calendar.TweakKind
import com.example.data.calendar.TweakKindJsonAdapter
import com.example.data.model.WeekPlanDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * JSON 文档的 DTO（schemaVersion = 4）。
 *
 * v2 把文档**顶层分块**：`calendar` / `timetable` / `categoryRules` 三块互不嵌套。
 * 课表是与学期绑定的，所以 `timetable` 块自己带一个 `termRef`，指明这些课属于
 * `calendar.termConfigs` 里的哪一个；校验期必须能对上，对不上整档失败。
 *
 * v3 再加 `tasks` / `taskTypes` / `taskRules` 三块用户数据（DTO 在
 * `SnapshotDtos.kt`）。**这三块全空时，这份文档仍然是一份合法的 v2 文档** ——
 * zju-ical 的 `config.json` 与浙大同步产出的东西都走这条路，兼容面没有变窄。
 *
 * v4 再加 `exams` 一块：考试是学校已经公布的事实，与课程同源但生命周期独立
 * （删课不该连带删考试），所以单独成块。**这一块全空时文档仍然合法** ——
 * 浙大同步产出的课表文档就走的这条路。
 *
 * `calendar` 块的字段名与类型**与 zju-ical 的 `configs/config.json` 逐字段同名同义**
 * （只多一个 `periods`：上游把作息硬编码在 Go 里，没有对应的 JSON 字段）。
 * 这么设计的收益是那份 config.json 本身就能当一份「只有 calendar 的文档」直接导入。
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
    val categoryRules: List<CategoryRuleDto>? = null,
    /** v3 起：待办。三块的用户数据，缺空都不是失败。 */
    val tasks: List<TaskDto>? = null,
    /** v3 起：用户新建的待办分类。 */
    val taskTypes: List<TaskTypeDto>? = null,
    /** v3 起：课程 × 类型的生成规则。 */
    val taskRules: List<TaskRuleDto>? = null,
    /** v4 起：考试。缺空 = 这份文档没带考试，导入时不动本机的考试。 */
    val exams: List<ExamDto>? = null,
    /**
     * v4 起：**渲染好的**周课表（一列 = 一个真实日期）。
     *
     * 它是**派生结果**，不是原始规则 —— 原始规则在 `calendar` 与 `timetable` 两块里。
     * 之所以还带着它，是因为另一端（网页端）只会画、不会算：
     * 排课判定只有手机那一份实现，让网页端重写一份迟早两边答案不一样。
     *
     * **只出不进**：手机自己算得出周课表，导入时这份块会被忽略。
     */
    val weekPlan: WeekPlanDto? = null
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
 * 调休规则。[tweakType] 收字符串（`"Clear"` / `"Move"` / `"Exchange"`）也收数字（`0/1/2`），
 * 写出时统一用字符串——上游 zju-ical `configs/config.json` 就是这个形态，
 * 导出文件拿回上游或别的工具能直接用。适配见 [TweakKindJsonAdapter]。
 */
@JsonClass(generateAdapter = true)
data class TweakDto(
    @Json(name = "TweakType") val tweakType: TweakKind? = null,
    @Json(name = "Description") val description: String? = null,
    /** YYYYMMDD，具体某一天 */
    @Json(name = "From") val from: Int? = null,
    /** YYYYMMDD，具体某一天 */
    @Json(name = "To") val to: Int? = null
)

@JsonClass(generateAdapter = true)
data class NoClassRangeDto(
    val startDate: String? = null,
    val endDate: String? = null,
    val note: String? = null
)

/**
 * v1 形态的补课日。[fromDate] 是「课原本属于哪一天」，v1 老文档里没有这个字段。
 */
@JsonClass(generateAdapter = true)
data class MakeupDayDto(
    val date: String? = null,
    val makeupForDayOfWeek: Int? = null,
    val fromDate: String? = null,
    val note: String? = null
)

@JsonClass(generateAdapter = true)
data class PeriodDto(
    val period: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val section: String? = null
)

/**
 * 校历块 → DTO。
 *
 * **提出来是为了给两条路共用**：整机快照文件（`transfer`）与局域网同步快照
 * （`sync/web`）发的是**同一份**校历，形状必须一模一样 ——
 * 不然网页端从手机同步来的校历和从手机文件里读来的校历会长得不一样，
 * 而用户看不出那两份是同一件事。
 */
internal fun CalendarBundle.toDto(): CalendarDto = CalendarDto(
    lastUpdated = lastUpdated,
    termConfigs = termConfigs.map {
        TermConfigDto(it.year, it.term, it.begin, it.end, it.firstWeekNo)
    },
    classTerms = classTerms,
    examTerms = examTerms,
    tweaks = tweaks.map { tw -> TweakDto(tw.tweakType, tw.description, tw.from, tw.to) },
    periods = periods.map { p -> PeriodDto(p.period, p.startTime, p.endTime, p.section) },
    // legacy 两段只读不写：导出统一用 tweaks 形态
    noClassRanges = null,
    makeupDays = null
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
    /**
     * 课程备注。语义见 [com.example.data.codec.CourseBundle.note]：
     * **null = 这份文档没带这一项**，空串 = 带了且明确为空。
     *
     * Moshi 默认不写 null 字段，所以「没带」在 JSON 里就是**键根本不存在**，
     * 读回来仍是 null —— 两边对得上。
     */
    val note: String? = null,
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

// ------------------------------------------------------------------ v4：考试

/**
 * 一场考试。
 *
 * **不写 `id`**：那是本机自增的行号，写进文件等于把一端的主键强加给另一端。
 * 跨设备的身份靠 [xkkh]（教务网选课课号原串）。
 *
 * 空串与 null 的区别与 `CourseDto.note` 同一套约定：
 * 教室 / 座位写成空串 = 「学校还没安排」，字段不存在 = 「这份文件没带这一项」。
 */
@JsonClass(generateAdapter = true)
data class ExamDto(
    /** 教务网选课课号原串，如 `(2026-2027-1)-MATH2432F-0083261-1`。 */
    val xkkh: String? = null,
    val courseCode: String? = null,
    val courseName: String? = null,
    /** MIDTERM / FINAL。 */
    val kind: String? = null,
    /** 绝对开始时间（epoch 毫秒）。0 = 学校还没公布具体日期。 */
    val startAt: Long? = null,
    val endAt: Long? = null,
    /** 开始时刻（当天第几分钟）。日期没公布时也有值。 */
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val dateLabel: String? = null,
    val classroom: String? = null,
    val seat: String? = null,
    val credit: Double? = null,
    val termLabel: String? = null,
    val updatedAt: Long? = null
)

internal fun ExamDto.toBundle() = ExamBundle(
    xkkh = xkkh,
    courseCode = courseCode,
    courseName = courseName,
    kind = kind,
    startAt = startAt,
    endAt = endAt,
    startMinute = startMinute,
    endMinute = endMinute,
    dateLabel = dateLabel,
    classroom = classroom,
    seat = seat,
    credit = credit,
    termLabel = termLabel,
    updatedAt = updatedAt
)

internal fun ExamBundle.toDto() = ExamDto(
    xkkh = xkkh,
    courseCode = courseCode,
    courseName = courseName,
    kind = kind,
    startAt = startAt,
    endAt = endAt,
    startMinute = startMinute,
    endMinute = endMinute,
    dateLabel = dateLabel,
    classroom = classroom,
    seat = seat,
    credit = credit,
    termLabel = termLabel,
    updatedAt = updatedAt
)