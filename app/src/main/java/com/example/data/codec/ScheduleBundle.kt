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
 *
 * v3 起再加三块 [tasks] / [taskTypes] / [taskRules]：**待办是这台设备上的用户数据，
 * 而课表可以随时从浙大重新拉一遍**。这两件事的来源不同，混在一块就分不清
 * 「同步进来的是我的待办」还是「服务端说的课表」。三块全为 null 的 v3 文档
 * 仍然是一份合法的「只有课表」文档 —— zju-ical 的 `config.json` 就走这条。
 */
data class ScheduleBundle(
    val provenance: ProvenanceBundle = ProvenanceBundle(),
    val calendar: CalendarBundle? = null,
    val timetable: TimetableBlockBundle? = null,
    val categoryRules: List<CategoryRule> = emptyList(),
    val tasks: List<TaskBundle> = emptyList(),
    val taskTypes: List<TaskTypeBundle> = emptyList(),
    val taskRules: List<TaskRuleBundle> = emptyList()
) {
    /** 三块是否整体缺席。浙大同步产出的文档就是这种形状。 */
    val hasNoUserData: Boolean
        get() = tasks.isEmpty() && taskTypes.isEmpty() && taskRules.isEmpty()
}

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
 * v1 形态的补课日。**[fromDate] 是唯一真源**。
 *
 * 补周几和「这节课算第几周」都由 [fromDate] 推出来（补课时 From 与 To 常常差两周
 * 以上：2026 国庆把 10-02 的周五课补到 10-17），所以 [makeupForDayOfWeek] 只在
 * **导出**时写（v1 格式要求有它），导入时已经不再读。
 *
 * [fromDate] 为 null 表示「来源日未知」——那条记录**会被导入层拒掉并报 issue**：
 * 早先的兜底是「退回按补课当天的日期算周次」，看着向后兼容，实际是把一条说不清
 * 补的是哪一天的课的搬运塞进课表，而界面上看不出任何异常。
 */
data class MakeupDayBundle(
    val date: String?,
    /** 导出用的派生值；导入不读它（见上）。 */
    val makeupForDayOfWeek: Int?,
    /** `YYYY-MM-DD`；null = 来源日未知，该条会被拒。 */
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
    /**
     * 课程备注（用户自己写的自由文本）。
     *
     * **null 与空串是两件事**：null = 这份文件根本没带备注这一项
     * （zju-ical 的 config.json、浙大同步产出的文档都是这种形状），
     * 空串 = 带了，而且明确是空的。
     *
     * 这个区别是导入侧「包里没有就别动」那条规则的根据：文件没带备注，
     * 说明**这份文件不知道备注**，不能拿它去盖掉本机已经写好的备注；
     * 而文件明确带了空串，那就是一份权威的「清空」。
     */
    val note: String? = null,
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

// ------------------------------------------------------------------ v3：用户数据

/**
 * 一条待办。**跨设备同步的最小单位。**
 *
 * [syncUid] 是它唯一的身份，UUID，由创建时随机生成、此后不变。两台设备上
 * 「同一条待办」靠它认出，而不是靠本机自增 id 或内容：
 * - id 各机自增，写进文件等于把一端的主键强加给另一端；
 * - 内容（标题 / 正文）用户随时会改，改了就不再相等。
 *
 * [courseKey] 用课程的自然键（`CourseKeys.compute(code, name, teacher)`）而不是
 * `courseId`：后者同样是本机自增值。本机没有这门课时落成「未关联课程」，
 * 待办本身照旧保留。
 *
 * **不导出课程的三个快照字段**（名称 / 类别 / 颜色）：它们在导入时从本机课程行
 * 重新取。与 `ScheduleImportService.applyTaskLinks` 现有做法一致 —— 一门课改了配色
 * 之后它的待办要跟着改，不该永远挂着导入那一刻的旧颜色。
 */
data class TaskBundle(
    val syncUid: String? = null,
    val courseKey: String? = null,
    val title: String? = null,
    val description: String? = null,
    /** 原样存储：枚举名 / `CUSTOM:<id>` / 空串（未分类）。 */
    val taskType: String? = null,
    val startTimeTimestamp: Long? = null,
    val dueDateTimestamp: Long? = null,
    val startTimeString: String? = null,
    val dueDateString: String? = null,
    /** 星标：`STAR` / 空串，见 `com.example.data.model.TaskStar`。 */
    val priority: String? = null,
    val isCompleted: Boolean? = null,
    val completedAt: Long? = null,
    val isAutoGenerated: Boolean? = null,
    /** 仅作溯源显示，去重不依赖它。 */
    val generatedDate: String? = null,
    /** 自动生成待办的去重键组成部分。 */
    val week: Int? = null,
    val dayOfWeek: Int? = null,
    val createdAt: Long? = null,
    /** 后写覆盖的仲裁依据。 */
    val updatedAt: Long? = null
)

/** 用户新建的待办分类。id 本来就是随机 UUID，**跨设备已经稳定**。 */
data class TaskTypeBundle(
    val id: String? = null,
    val label: String? = null,
    val colorHex: String? = null,
    val inkHex: String? = null,
    val sortOrder: Int? = null
)

/**
 * 一条「课程 × 类型」的生成规则。库里的主键是本地 `courseId + taskType`，
 * 这里同样把课程换成 [courseKey]，理由见 [TaskBundle.courseKey]。
 */
data class TaskRuleBundle(
    val courseKey: String? = null,
    val taskType: String? = null,
    /** PER_CLASS / PER_WEEK。 */
    val cadence: String? = null,
    val dueOffsetDays: Int? = null,
    val dueBeforeMinutes: Int? = null
)