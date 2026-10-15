package com.example.data.codec

import com.example.data.calendar.TweakKind
import com.example.data.calendar.TweakKindJsonAdapter
import com.example.data.model.CategoryRule
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

/**
 * MiuList 自有 JSON 格式（schemaVersion = 2）的实现。
 *
 * **这份格式同时是三种数据源之间的契约**，也是教务网爬虫的接口说明书：
 * 爬虫按 [ScheduleDocumentDto] 的字段表产出 JSON，走的是和用户导入文件
 * 完全相同的管线。
 *
 * v2 的形状是「**单文件、顶层分块**」：`calendar` / `timetable` / `categoryRules`。
 * 分块的理由是**课程与学期绑定**——课表块自带 `termRef`，必须能在校历块的
 * `termConfigs` 里对上，校验期对不上就整档失败。
 *
 * `calendar` 块与 zju-ical 的 `configs/config.json` 同构同字段名，
 * 所以那份 config.json 本身就是一份合法的「只有 calendar 的 v2 文档」。
 *
 * 本类只做「JSON ↔ Bundle」的搬运，归一化与判错全部交给
 * `ScheduleBundleValidator` —— 解析层不该懂「Begin + FirstWeekNo 怎么展开成周次」，
 * 那套语义只有一处实现。
 */
class JsonScheduleCodec : ScheduleCodec {

    // TweakKind 的适配器必须显式注册：Moshi 的代码生成只对非基本类型向 Moshi 实例
    // 查适配器，不注册的话 TweakType 会按 TweakKind 生成一个反射适配器，
    // 遇到上游的字符串形态直接抛 JsonDataException。
    private val moshi: Moshi = Moshi.Builder()
        .add(TweakKind::class.java, TweakKindJsonAdapter())
        .build()

    private val adapter by lazy { moshi.adapter(ScheduleDocumentDto::class.java) }

    override fun decode(text: String): DecodeResult {
        if (text.isBlank()) return DecodeResult.Failure("文件内容为空")
        val dto = try {
            adapter.fromJson(text)
        } catch (e: Exception) {
            return DecodeResult.Failure("无法解析为课表 JSON：${e.message ?: e::class.java.simpleName}")
        } ?: return DecodeResult.Failure("文件内容不是合法的课表 JSON 对象")

        val version = dto.schemaVersion ?: SCHEMA_VERSION
        if (version > SCHEMA_VERSION) {
            return DecodeResult.Failure("文件版本为 v$version，高于本应用支持的 v$SCHEMA_VERSION，请先升级应用")
        }
        if (version <= 1) return decodeLegacyV1(text)

        return DecodeResult.Success(dto.toBundle())
    }

    override fun encode(bundle: ScheduleBundle): String =
        adapter.toJson(bundle.toDto())

    /**
     * 读 v1 旧文档。
     *
     * v1 是**扁平**结构：`semester` / `timetable` / `noClassRanges` / `makeupDays` /
     * `courses` / `categoryRules` 全在顶层，没有 `termRef`。
     *
     * 为什么要留着：老用户手上已经有导出过的文件，直接报「格式过旧」等于让他们
     * 手工重录一遍课表。代价只是一个独立分支，映射规则本身很短。
     * 导出**永远**写 v2。
     */
    private fun decodeLegacyV1(text: String): DecodeResult {
        val legacy = try {
            moshi.adapter(LegacyV1Document::class.java).fromJson(text)
        } catch (e: Exception) {
            return DecodeResult.Failure("旧格式（v1）解析失败：${e.message ?: e::class.java.simpleName}")
        } ?: return DecodeResult.Failure("旧格式（v1）解析失败：内容为空")

        val calendar = CalendarBundle(
            // v1 没有 termConfigs / tweaks，学期信息直接落在 legacy 两段上
            periods = legacy.timetable?.periods.orEmpty().map {
                PeriodBundle(it.period, it.startTime, it.endTime, it.section)
            },
            legacyNoClassRanges = legacy.noClassRanges.orEmpty().map {
                NoClassRangeBundle(it.startDate, it.endDate, it.note)
            },
            legacyMakeupDays = legacy.makeupDays.orEmpty().map {
                MakeupDayBundle(it.date, it.makeupForDayOfWeek, it.fromDate, it.note)
            },
            termConfigs = listOf(
                TermConfigBundle(
                    year = legacy.semester?.yearSpan,
                    term = null,
                    begin = legacy.semester?.startDate
                        ?.let { runCatching { it.replace("-", "").toInt() }.getOrNull() },
                    end = legacy.semester?.endDate
                        ?.let { runCatching { it.replace("-", "").toInt() }.getOrNull() },
                    firstWeekNo = 1
                )
            )
        )

        val bundle = ScheduleBundle(
            provenance = legacy.provenance?.let {
                ProvenanceBundle(it.source, it.generator, it.generatedAt, it.schoolId, it.studentId, it.note)
            } ?: ProvenanceBundle(),
            calendar = calendar,
            timetable = TimetableBlockBundle(
                termRef = TermRefBundle(legacy.semester?.yearSpan, null),
                courses = legacy.courses.orEmpty().map { it.toCourseBundle() }
            ),
            categoryRules = legacy.categoryRules.orEmpty().mapNotNull { dto ->
                val cat = dto.category?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CategoryRule(
                    category = cat,
                    autoPreStudy = dto.preStudy ?: true,
                    autoPostHomework = dto.postHomework ?: true,
                    autoLabReport = dto.labReport ?: false,
                    defaultPostDays = dto.defaultPostDays ?: 6,
                    defaultMinutes = dto.defaultMinutes ?: 45
                )
            }
        )
        return DecodeResult.Success(bundle)
    }

    // ---------------------------------------------------------------- DTO → Bundle

    private fun ScheduleDocumentDto.toBundle() = ScheduleBundle(
        provenance = provenance?.toBundle() ?: ProvenanceBundle(),
        calendar = calendar?.toBundle(),
        timetable = timetable?.toBundle(),
        categoryRules = categoryRules.orEmpty().mapNotNull { dto ->
            val cat = dto.category?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CategoryRule(
                category = cat,
                autoPreStudy = dto.preStudy ?: true,
                autoPostHomework = dto.postHomework ?: true,
                autoLabReport = dto.labReport ?: false,
                defaultPostDays = dto.defaultPostDays ?: 6,
                defaultMinutes = dto.defaultMinutes ?: 45,
                preDueBeforeMinutes = dto.preDueBeforeMinutes ?: 5
            )
        }
    )

    private fun CalendarDto.toBundle() = CalendarBundle(
        lastUpdated = lastUpdated,
        termConfigs = termConfigs.orEmpty().map {
            TermConfigBundle(it.year, it.term, it.begin, it.end, it.firstWeekNo)
        },
        classTerms = classTerms.orEmpty(),
        examTerms = examTerms.orEmpty(),
        tweaks = tweaks.orEmpty().map {
            // 认不出来的 TweakType 原样留 null，交给校验期报 issue 并忽略整条；
            // 在这里默认成 CLEAR 会把一条看不懂的规则直接执行成停课。
            TweakBundle(it.tweakType, it.description, it.from, it.to)
        },
        periods = periods.orEmpty().map { PeriodBundle(it.period, it.startTime, it.endTime, it.section) },
        legacyNoClassRanges = noClassRanges.orEmpty().map {
            NoClassRangeBundle(it.startDate, it.endDate, it.note)
        },
        legacyMakeupDays = makeupDays.orEmpty().map {
            MakeupDayBundle(it.date, it.makeupForDayOfWeek, it.fromDate, it.note)
        }
    )

    private fun TimetableBlockDto.toBundle() = TimetableBlockBundle(
        termRef = termRef?.let { TermRefBundle(it.year, it.term) },
        courses = courses.orEmpty().map { it.toCourseBundle() }
    )

    private fun CourseDto.toCourseBundle() = CourseBundle(
        key = key,
        name = name,
        code = code,
        teacher = teacher,
        category = category,
        classroom = classroom,
        colorHex = colorHex,
        automation = automation?.let {
            AutomationBundle(
                preStudy = it.preStudy,
                postHomework = it.postHomework,
                labReport = it.labReport,
                review = it.review,
                defaultPostDueDays = it.defaultPostDueDays,
                preStudyMinutes = it.preStudyMinutes,
                postHomeworkMinutes = it.postHomeworkMinutes,
                labReportMinutes = it.labReportMinutes
            )
        },
        slots = slots.orEmpty().map {
            SlotBundle(it.dayOfWeek, it.startPeriod, it.endPeriod, it.classroom, it.weeks)
        },
        extra = extra
    )

    private fun ProvenanceDto.toBundle() =
        ProvenanceBundle(source, generator, generatedAt, schoolId, studentId, note)

    // ---------------------------------------------------------------- Bundle → DTO

    private fun ScheduleBundle.toDto() = ScheduleDocumentDto(
        schemaVersion = SCHEMA_VERSION,
        provenance = ProvenanceDto(
            source = provenance.source,
            generator = provenance.generator ?: DEFAULT_GENERATOR,
            generatedAt = provenance.generatedAt ?: DEFAULT_GENERATED_AT,
            schoolId = provenance.schoolId,
            studentId = provenance.studentId,
            note = provenance.note
        ),
        calendar = calendar?.let {
            CalendarDto(
                lastUpdated = it.lastUpdated,
                termConfigs = it.termConfigs.map { tc ->
                    TermConfigDto(tc.year, tc.term, tc.begin, tc.end, tc.firstWeekNo)
                },
                classTerms = it.classTerms,
                examTerms = it.examTerms,
                tweaks = it.tweaks.map { tw ->
                    TweakDto(tw.tweakType, tw.description, tw.from, tw.to)
                },
                periods = it.periods.map { p ->
                    PeriodDto(p.period, p.startTime, p.endTime, p.section)
                },
                // legacy 两段只读不写：导出统一用 tweaks 形态
                noClassRanges = null,
                makeupDays = null
            )
        },
        timetable = timetable?.let {
            TimetableBlockDto(
                termRef = it.termRef?.let { ref -> TermRefDto(ref.year, ref.term) },
                courses = it.courses.map { c ->
                    CourseDto(
                        key = c.key,
                        name = c.name,
                        code = c.code,
                        teacher = c.teacher,
                        category = c.category,
                        classroom = c.classroom,
                        colorHex = c.colorHex,
                        automation = c.automation?.let {
                            AutomationDto(
                                preStudy = it.preStudy,
                                postHomework = it.postHomework,
                                labReport = it.labReport,
                                review = it.review,
                                defaultPostDueDays = it.defaultPostDueDays,
                                preStudyMinutes = it.preStudyMinutes,
                                postHomeworkMinutes = it.postHomeworkMinutes,
                                labReportMinutes = it.labReportMinutes
                            )
                        },
                        slots = c.slots.map { s ->
                            SlotDto(s.dayOfWeek, s.startPeriod, s.endPeriod, s.classroom, s.weeks)
                        },
                        extra = c.extra
                    )
                }
            )
        },
        categoryRules = categoryRules.map {
            CategoryRuleDto(
                category = it.category,
                preStudy = it.autoPreStudy,
                postHomework = it.autoPostHomework,
                labReport = it.autoLabReport,
                defaultPostDays = it.defaultPostDays,
                defaultMinutes = it.defaultMinutes,
                preDueBeforeMinutes = it.preDueBeforeMinutes
            )
        }
    )

    companion object {
        const val SCHEMA_VERSION = 2
        const val DEFAULT_GENERATOR = "MiuList 1.0"
        const val DEFAULT_GENERATED_AT = ""
    }
}

/**
 * v1 旧文档的形状。
 *
 * 只在 [JsonScheduleCodec.decodeLegacyV1] 里用一次，单独放文件末尾而不是
 * 留在 [ScheduleDocumentDto] 里，是为了让「当前格式」在代码里只有一处定义 ——
 * 搜索 `ScheduleDocumentDto` 应当只能找到 v2。
 */
@JsonClass(generateAdapter = true)
internal data class LegacyV1Document(
    val schemaVersion: Int? = null,
    val provenance: ProvenanceDto? = null,
    val semester: LegacySemesterDto? = null,
    val timetable: LegacyTimetableDto? = null,
    val noClassRanges: List<NoClassRangeDto>? = null,
    val makeupDays: List<MakeupDayDto>? = null,
    val courses: List<CourseDto>? = null,
    val categoryRules: List<CategoryRuleDto>? = null
)

@JsonClass(generateAdapter = true)
internal data class LegacySemesterDto(
    val name: String? = null,
    val yearSpan: String? = null,
    val term: String? = null,
    val startDate: String? = null,
    val firstMonday: String? = null,
    val endDate: String? = null,
    val totalWeeks: Int? = null
)

@JsonClass(generateAdapter = true)
internal data class LegacyTimetableDto(
    val periodsPerDay: Int? = null,
    val periods: List<PeriodDto>? = null
)