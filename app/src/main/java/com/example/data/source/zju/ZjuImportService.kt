package com.example.data.source.zju

import com.example.data.calendar.CalendarCache
import com.example.data.calendar.CalendarOrigin
import com.example.data.codec.AcademicTerm
import com.example.data.codec.CalendarBundle
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
    private val calendarSource: ZjuCalendarSource = ZjuCalendarSource(),
    /**
     * 校历的**三层来源**（远程 → 缓存 → 内置）。
     *
     * 单独注入而不是自己造一个：取哪一份的决策只该有一处，而弹层那边读的是
     * 同一个服务 —— 这正是原来两条链路分叉的地方（弹层只读内置、导入才联网），
     * 于是界面上显示的官方校历和真正落库的那份可以不是同一份。
     *
     * 默认值是**明确不缓存**的降级配置（远程 → 内置两层），单测与「只想读一次」
     * 的场景用它。图省事在生产代码里省略这个参数，会悄悄让用户每次开 App 都
     * 重新拉一次、断网时只剩内置那份 —— 真正的缓存走 AppContainer 注入。
     */
    private val calendarUpdates: CalendarUpdateService = CalendarUpdateService(
        source = calendarSource,
        cache = CalendarCache(null)
    )
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
        val configs = calendarUpdates.loadForImport().termConfigs
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
        //    走三层来源，**不联网**：校历换没换是用户在「本学期校历」里决定的，
        //    把这里也联网会让一次同步因为校历源不通而整条失败。
        //    用户点过「检查更新」的话，缓存里已经是最新那份。
        val fullCalendar = calendarUpdates.loadForImport()
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
     * 用户当前所在的那一学期，用来决定弹层摆哪一份。
     *
     * **两个字段都要，不能只有学年。**
     *
     * 一个学年有 16 周的学期**两个**（秋冬 / 春夏），而两者的 `yearSpan` 一样
     * 都是 `2026-2027`。只按学年选的话，用户明明在用春夏，弹层却永远摆秋冬 ——
     * 点「应用」就把正在用的学期整个换成了另一个，而且界面上看不出任何异样。
     * 这正是「加新学期」时会踩的坑。
     *
     * @param season `秋冬` / `春夏`；短学期（`AcademicTerm.season` 为 null）传 null，
     *   此时退回只按学年匹配。
     */
    data class PreferredTerm(val yearSpan: String, val season: String?)

    /**
     * 把一份校历 Bundle 折成弹层要显示的 [ZjuPresetSemester]。
     *
     * **读哪一份由调用方决定**（`bundle` 参数），这里只管折算。
     * 这正是原来两条链路分叉的地方：老签名自己 `parseBundled()` 读内置，
     * 于是弹层显示的那份与导入时真正用的那份可以不是同一份，而两边都"看起来对"。
     *
     * @param prefer 当前正在用的学期；null 或配置里没有这一期，就给**最新**的一期
     *   （让用户看得见「官方现在是哪一期」，而不是干等下一次学年）。
     * @param origin 这份 bundle 的来源，透传给界面显示。
     *
     * ## 选哪一期的顺序
     *
     * 学年 + 季节都对上 → 同学年的另一季 → 最新的一期。
     * 三级而不是一级，是因为配置里可能只有半截学期（[AcademicTerm.season] 为 null），
     * 那时季节对不上是正常的，不该因此什么都选不到。
     *
     * ## 为什么走一遍 [ScheduleBundleValidator]
     *
     * 弹层上显示的停课 / 补课，必须与真导入时落库的**一模一样**。
     * 若这里自己展开一遍 `tweaks`，两套展开逻辑迟早会漂移：显示着 8 天停课，
     * 一导入变成 7 天，而两边都「看起来对」。
     * 所以这里复用校验期那一份，界面上看到的就是导入会写下的。
     */
    suspend fun presetFrom(
        bundle: CalendarBundle,
        prefer: PreferredTerm?,
        origin: CalendarOrigin
    ): ZjuPresetSemester? =
        withContext(Dispatchers.IO) {
            val configs = bundle.termConfigs

            // 每一半都能折出一个学年学期；按「学年 + 季节」去重后就是可选的学期列表
            val candidates = configs
                .mapNotNull { tc ->
                    val year = tc.year?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val term = tc.term ?: return@mapNotNull null
                    configs.academicTermOf(year, term)
                }
                .distinctBy { it.yearSpan to it.season }

            val academic = candidates.firstOrNull {
                prefer != null && it.yearSpan == prefer.yearSpan && it.season == prefer.season
            } ?: candidates.firstOrNull {
                prefer != null && it.yearSpan == prefer.yearSpan
            } ?: candidates.maxByOrNull { it.startEpochDay }
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
                origin = origin,
                lastUpdated = bundle.lastUpdated
            )
        }

    /**
     * 这份配置里**有哪些**学年学期，按开始时间从早到晚。
     *
     * 弹层顶部用它在「秋冬 / 春夏 / 新学年」之间切换 —— 加了新学期之后，
     * 用户得有个办法把它切过去；只按当前学期自动选一的话，
     * 新加的那一期永远不会被摆出来，用户会以为「检查更新没生效」。
     */
    suspend fun academicTermsIn(bundle: CalendarBundle): List<AcademicTerm> {
        val configs = bundle.termConfigs
        return configs
            .mapNotNull { tc ->
                val year = tc.year?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val term = tc.term ?: return@mapNotNull null
                configs.academicTermOf(year, term)
            }
            .distinctBy { it.yearSpan to it.season }
            .sortedBy { it.startEpochDay }
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