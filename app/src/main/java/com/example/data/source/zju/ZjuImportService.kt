package com.example.data.source.zju

import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.JsonScheduleCodec
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
class ZjuImportService(
    private val cas: ZjuCasClient = ZjuCasClient(),
    private val calendarSource: ZjuCalendarSource = ZjuCalendarSource()
) {

    /** 学期下拉里的一项。 */
    data class TermOption(val year: String, val term: Int) {
        val label: String get() = "$year ${ZjuTerms.labelOf(term)}"
        val key: String get() = "$year:$term"
    }

    /** 只拉公共配置，用于填学期的下拉。不需要账号。 */
    suspend fun availableTerms(): List<TermOption> = withContext(Dispatchers.IO) {
        val calendar = calendarSource.fetch()
        calendar.termConfigs.mapNotNull { tc ->
            val year = tc.year?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val term = tc.term ?: return@mapNotNull null
            TermOption(year, term)
        }.distinct()
    }

    /**
     * 完整抓一次并产出 v2 JSON 文本。
     *
     * @param studentId 学号；**不会被写进 provenance 之外的任何持久化位置**，
     *   密码则完全不离开内存（只用于加密提交，见 `ZjuRsa`）。
     */
    suspend fun fetchScheduleJson(
        studentId: String,
        password: String,
        option: TermOption
    ): String = withContext(Dispatchers.IO) {
        // 1. 公共校历（人人相同，也决定单双周要展开到第几周）
        val fullCalendar = calendarSource.fetch()
        val calendar = calendarSource.selectTerm(fullCalendar, option.year, option.term)
        val termConfig = calendar.termConfigs.firstOrNull()
            ?: throw ZjuException("这份配置里没有 ${option.label}，换一期试试")

        val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(termConfig.begin)
            ?: throw ZjuException("校历起止日期无法解析，换一期试试")
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(termConfig.end)
            ?: throw ZjuException("校历起止日期无法解析，换一期试试")
        val totalWeeks = ZjuCalendarAdapter.totalWeeks(begin, end, termConfig.firstWeekNo ?: 1)

        // 2. 登录
        cas.login(studentId, password)

        // 3. 取课表。zdbk 这条接口靠 CAS 会话识别学生，不再需要传学号。
        val raw = cas.fetchSchedule(option.year, ZjuTerms.queryOf(option.term))
        val rows = parseRows(raw)
        if (rows.isEmpty()) {
            throw ZjuException("教务网没返回课表，试试在浙大钉的个人课表里确认")
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
            studentId = studentId
        )

        // 5. 走一次本项目自己的编码器 —— 产出的东西与手工导出完全同形，
        //    后续的解码/校验/提交管线不需要为 ZJU 开特例。
        JsonScheduleCodec().encode(bundle)
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