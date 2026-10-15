package com.example.data.source.zju

import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.codec.academicTermOf
import com.example.data.validate.ScheduleBundleValidator
import com.example.data.validate.ValidationResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.squareup.moshi.JsonClass

/**
 * 「ZJU」导入源的总编排。
 *
 * 一次调用的顺序是有讲究的：**先取公共校历，再登录取课表**。
 *
 * 因为展开单双周需要总周数，而总周数写在 config.json 的 `termConfigs` 里。
 * 反过来先取课表的话，手里只有 `dsz = "0"` 这个「单周」，却不知道学期有多长，
 * 就只能把它当成「每周」，于是单双周课在双周也出现 —— 这正是上游
 * zju-ical 的 issue 10/11 里反复被用户报的那类问题。
 *
 * 产出的 JSON **就是 v2 文档**，随后交给与文件导入完全相同的
 * 「解码 → 校验 → 预览 → 提交」管线，不另开一条写库的路。
 */
open class ZjuImportService(
    private val cas: ZjuCasClient = ZjuCasClient(),
    private val calendarSource: ZjuCalendarSource = ZjuCalendarSource()
) {

    /** 学期下拉里的一项。 */
    data class TermOption(
        val year: String,
        /** 教务网查询用哪一半：秋冬 → 0(秋)，春夏 → 4(春)。 */
        val term: Int,
        /** 「秋冬」/「春夏」；短学期等独立段为 null。 */
        val season: String? = null
    ) {
        val label: String
            get() = if (season != null) "${year}学年 $season 学期" else "$year ${ZjuTerms.labelOf(term)}"

        val key: String get() = "$year:$term"
    }

    /**
     * 只拉公共配置，用于填学期的下拉。不需要账号。
     *
     * 下拉里的一项是**一个学年学期**（秋冬 / 春夏，16 周），不是半学期。
     * 之前按 `termConfigs` 一条一项，于是「2026-2027 秋学期」与
     * 「2026-2027 冬学期」并排出现，用户选哪一个都只能拿到 8 周 ——
     * 而浙大嘴里的学期本来就是那 16 周。
     */
    suspend fun availableTerms(): List<TermOption> = withContext(Dispatchers.IO) {
        val configs = calendarSource.fetch().termConfigs
        configs.mapNotNull { tc ->
            val year = tc.year?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val term = tc.term ?: return@mapNotNull null
            val academic = configs.academicTermOf(year, term) ?: return@mapNotNull null
            TermOption(academic.yearSpan, academic.queryTerm, academic.season)
        }
            // 秋、冬两项会各自产出同一个学年学期，按 key 去重留一个
            .distinctBy { it.key }
            .sortedByDescending { it.label }
    }

    /**
     * 完整抓一次并产出 v2 JSON 文本。
     *
     * @param studentId 学号；**不会被写进 provenance 之外的任何持久化位置**，
     *   密码则完全不离开内存（只用于加密提交，见 `ZjuRsa`）。
     */
    open suspend fun fetchScheduleJson(
        studentId: String,
        password: String,
        option: TermOption
    ): String = withContext(Dispatchers.IO) {
        // 1. 公共校历（人人相同，也决定单双周要展开到第几周）
        val fullCalendar = calendarSource.fetch()
        val academic = fullCalendar.termConfigs.academicTermOf(option.year, option.term)
            ?: throw ZjuException("这份配置里没有 ${option.label}，换一期试试", ZjuFailureKind.TERM)

        // **总周数按整个学年学期算**（秋冬 = 16 周），不是选中那一半的 8 周。
        // [ZjuScheduleMapper] 已经把单的「第1-8周」按子学期平移进 1..16 的口径，
        // 校历这边再按 8 周算就与它对不上，第 9–16 周的课整段不出现。
        val totalWeeks = academic.totalWeeks
        val calendar = calendarSource.selectTerm(fullCalendar, option.year, option.term)

        // 2. 登录
        cas.login(studentId, password)

        // 3. 取课表。一个学年学期要查**两半**：教务网的 `xqm` 是按半学期分的，
        //    `1|秋` 只回 `xxq` 为「秋」和「秋冬」的课，`xxq` 为「冬」的课只在 `1|冬` 里。
        //    只查前半的话，后半学期的课整段拿不到 —— 下拉里明明选的是「秋冬」。
        //    zdbk 这条接口靠 CAS 会话识别学生，不再需要传学号。
        val termQueries = academic.halves.map { ZjuTerms.queryOf(it.term) }.filter { it.isNotEmpty() }
        if (termQueries.isEmpty()) {
            throw ZjuException("这份配置里没有 ${option.label}，换一期试试", ZjuFailureKind.TERM)
        }
        val rows = termQueries.flatMap { query -> parseRows(cas.fetchSchedule(option.year, query)) }
            // 两半的响应里都有「秋冬」的课，同一行会**逐字重复**一次。
            // 不去重的话同一门课的同一时段会出现两次，课表上直接变成重复行。
            .distinctBy(::rowIdentity)
        if (rows.isEmpty()) {
            throw ZjuException("教务网没返回课表，试试在浙大钉的个人课表里确认", ZjuFailureKind.EMPTY)
        }

        // 4. 映射成 v2 文档
        val mapped = ZjuScheduleMapper.toCourses(
            rows = rows,
            totalWeeks = totalWeeks,
            termCode = option.term
        )
        val bundle = ZjuScheduleMapper.toBundle(
            rows = mapped,
            calendarBundle = calendar,
            termYear = option.year,
            termCode = option.term,
            termQueries = termQueries,
            studentId = studentId
        )

        // 5. 走一次本项目自己的编码器 —— 产出的东西与手工导出完全同形，
        //    后续的解码/校验/提交管线不需要为 ZJU 开特例。
        JsonScheduleCodec().encode(bundle)
    }

    /**
     * 一行课表记录的身份 —— 用来把两半响应里**逐字重复**的那一行合成一条。
     *
     * 不能用 [ZjuClassRow.xkkh]（选课课号）：它带一个半学期序号，
     * 同一门「秋冬」的课在 `1|秋` 与 `1|冬` 的响应里课号不一样，
     * 按它去重等于没去重，课表上会出现同一门课的重复时段。
     *
     * 这里只取**决定映射结果**的那几个字段：课程信息全在 `kcb` 里，
     * 再加上星期、节次、单双周、学期。`xkkh` / `sfqd` 不影响输出，忽略。
     * 两行只要这些一样，映射出来的 slot 就完全相同，留一条就够。
     */
private fun rowIdentity(row: ZjuClassRow): List<String?> =
        listOf(row.kcb, row.xqj?.toString(), row.djj?.toString(), row.skcd?.toString(), row.dsz, row.xxq)

    /**
     * 取**内置**那份官方校历，供「本学期校历」弹层单独显示。**不联网。**
     *
     * @param preferYearSpan 当前自定义学期的学年；内置里有这一期就显示它，
     *   没有就给最新的一期（让用户看得见「官方现在是哪一期」）。
     *
     * ## 为什么走一遍 [ScheduleBundleValidator]
     *
     * 弹层上显示的停课 / 补课，必须与真导入时落库的**一模一样**。
     * 若这里自己展开一遍 `tweaks`，两套展开逻辑迟早会漂移：显示着 8 天停课，
     * 一导入变成 7 天，而两边都「看起来对」。
     * 所以这里复用校验期那一份，界面上看到的就是导入会写下的。
     */
    suspend fun bundledPreset(preferYearSpan: String?): ZjuPresetSemester? =
        withContext(Dispatchers.IO) {
            val bundle = calendarSource.parseBundled() ?: return@withContext null
            val configs = bundle.termConfigs

            // 每一半都能折出一个学年学期；按「学年 + 季节」去重后就是可选的学期列表
            val candidates = configs
                .mapNotNull { tc ->
                    val year = tc.year?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val term = tc.term ?: return@mapNotNull null
                    configs.academicTermOf(year, term)
                }
                .distinctBy { it.yearSpan to it.season }
            val academic = candidates.firstOrNull { it.yearSpan == preferYearSpan }
                ?: candidates.maxByOrNull { it.startEpochDay }
                ?: return@withContext null

            val termRef = TermRefBundle(academic.yearSpan, academic.queryTerm)
            val selected = calendarSource.selectTerm(bundle, academic.yearSpan, academic.queryTerm)
            val result = ScheduleBundleValidator().validate(
                ScheduleBundle(calendar = selected, timetable = TimetableBlockBundle(termRef = termRef))
            )
            val valid = result as? ValidationResult.Valid ?: return@withContext null
            val s = valid.bundle.semester ?: return@withContext null

            ZjuPresetSemester(
                yearSpan = academic.yearSpan,
                label = s.name,
                startEpochDay = s.startEpochDay,
                firstMondayEpochDay = s.firstMondayEpochDay,
                endEpochDay = s.endEpochDay,
                totalWeeks = s.totalWeeks,
                noClassDays = valid.bundle.noClassDays.sortedBy { it.epochDay },
                makeupDays = valid.bundle.makeupDays.sortedBy { it.epochDay },
                // 上游写的是 `20260917` 这样的整数，这里转成人读的日期再交给界面，
                // 否则弹层上会直接印一串「内置于 20260917」。
                updatedOn = bundle.lastUpdated?.let { "%04d-%02d-%02d".format(it / 10000, it / 100 % 100, it % 100) }
            )
        }

    /**
     * 解 zdbk 课表接口的响应。
     *
     * **响应是平铺的**：`kbList` 直接挂在顶层，和它一起返回的还有
     * `xzb`（行政班）、`xy`（学院）、`xnm`/`xqm`（回显的学年学期）等。
     * 老的 appservice 接口是 `{ data: { kblist: [...] } }` 套了两层，别照抄那个。
     *
     * 抽成 public 是为了能单测——真实的响应结构只有联网才拿得到，
     * 用一份按实测结构手写的样本就能把解析规则钉死，不必每次都出网。
     */
    fun parseRows(rawJson: String): List<ZjuClassRow> {
        val wrapper = runCatching {
            moshi.adapter(ZjuScheduleResponse::class.java).fromJson(rawJson)
        }.getOrNull()
        return wrapper?.kbList.orEmpty()
    }

    private val moshi = Moshi.Builder().build()
}

@JsonClass(generateAdapter = true)
internal data class ZjuScheduleResponse(
    val kbList: List<ZjuClassRow>? = null,
    val xnm: String? = null,
    val xqm: String? = null
)