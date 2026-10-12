package com.example.data.source.zju

import com.example.data.codec.AutomationBundle
import com.example.data.codec.CourseBundle
import com.example.data.codec.ProvenanceBundle
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.SlotBundle
import com.example.data.codec.TermRefBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.model.COURSE_PALETTE
import com.example.data.model.CourseKeys
import com.example.data.model.DEFAULT_CATEGORY
import com.squareup.moshi.JsonClass

/**
 * 教务网返回的 `kblist` 一条课。
 *
 * 字段名是教务网的原始拼音缩写，**不能改**：改了就与接口脱节，
 * 而这些名字在上游 zju-ical 里也是这么用的。
 *
 * `xqj`（星期几）声明成 `Any?` 是有意的：本科数据是字符串 `"1"`，
 * 研究生数据是数字 `1`，两种形态都要能吃。
 */
@JsonClass(generateAdapter = true)
data class ZjuClassRow(
    val mc: String?,          // 课程名
    val kcdm: String?,        // 课程代码
    val jsxm: String?,        // 教师
    val skdd: String?,        // 上课地点
    val jc: List<String>?,    // 节次，如 ["1","2"]
    val xqj: Any?,            // 星期几：本科是字符串 "1"，研究生是数字
    val dsz: String?,         // 单双周："0" 单周 / "1" 双周 / 其他 每周
    val xq: String?,          // 学期串，含「秋」「冬」「春」「夏」
    val sfqd: Int?            // 是否确认（选课中 = 0）
)

/** 映射的结果。课程已经按自然键合并、时段已经归一化。 */
data class ZjuScheduleRows(
    val courses: List<CourseBundle>,
    /** 被跳过的条目数与原因，进导入预览的「问题」清单。 */
    val skipped: Int,
    val skipReason: String?
)

/**
 * 教务网 `kblist` → MiuList 的 `timetable` 块。
 *
 * 映射规则严格照抄上游 `pkg/zjuservice/ugrsical/class.go` 的 `ToZJUClass`，
 * 有三处反直觉但必须照抄：
 *
 * 1. `dsz == "0"` 是**单周**、`"1"` 是**双周**（不是直觉上的 0=每周）。
 *    写反了会让所有单双周课在另一半学期也出现，而且看不出错在哪。
 * 2. 未确认（`sfqd` 为 0 **或字段缺失**）且 `xqj` 不是带引号的字符串时整条丢掉 ——
 *    那是研究生数据。上游靠 Go 的 int 零值把「缺失」和「0」当成同一件事，
 *    我们用可空 Int 就得自己补 `(sfqd ?: 0)`，否则研究生那一整片会被放进来。
 * 3. `xq` 里秋/冬/春/夏一个都没有的整条丢掉。
 *
 * 这三条都不会崩，只会让课表多出不该有的行或整份消失，所以每一条都有单测钉住。
 *
 * 单双周要展开成具体周号，**必须先知道总周数**，所以入参里有 [totalWeeks]。
 * 纯函数，无 Android 依赖，可直接单测。
 */
object ZjuScheduleMapper {

    /**
     * @param totalWeeks 该学期总周数，用来把单双周展开成周号集合。
     * @param termCode 学期代码（0..6），只写进 provenance，不影响排课。
     */
    fun toCourses(
        rows: List<ZjuClassRow>,
        totalWeeks: Int,
        termCode: Int
    ): ZjuScheduleRows {
        val skipped = mutableListOf<String>()

        val parsed = rows.mapNotNull { row ->
            row.toParsed(totalWeeks) ?: run {
                skipped += "${row.mc.orEmpty()}（${row.skipReason()}）"
                null
            }
        }

        // 按自然键合并成课程：同一门课的多个时段要在同一门课下
        val byKey = LinkedHashMap<String, MutableList<SlotBundle>>()
        val meta = LinkedHashMap<String, CourseBundle>()

        parsed.forEach { p ->
            val key = CourseKeys.compute(p.code.orEmpty(), p.name.orEmpty(), p.teacher.orEmpty())
            val slot = SlotBundle(
                dayOfWeek = p.dayOfWeek,
                startPeriod = p.startPeriod,
                endPeriod = p.endPeriod,
                classroom = p.classroom.orEmpty(),
                // 单双周在这里就展开成周号集合，后面 WeekSet.parse 不再需要理解「单/双」
                weeks = p.weeks
            )
            byKey.getOrPut(key) { mutableListOf() } += slot
            if (key !in meta) {
                meta[key] = CourseBundle(
                    key = key,
                    name = p.name.orEmpty(),
                    code = p.code.orEmpty(),
                    teacher = p.teacher.orEmpty(),
                    category = DEFAULT_CATEGORY,
                    // 教务网不给课程颜色；按出现顺序从调色板取，保证同一门课每次抓都同色
                    colorHex = null,
                    automation = AutomationBundle(
                        preStudy = true,
                        postHomework = true,
                        // 实验报告不能靠「课程名里有实验」来猜，默认关，由用户在设置里开
                        labReport = false,
                        review = false,
                        defaultPostDueDays = 6,
                        preStudyMinutes = 30,
                        postHomeworkMinutes = 60,
                        labReportMinutes = 90
                    )
                )
            }
        }

        val courses = meta.entries.mapIndexed { index, (key, course) ->
            course.copy(
                colorHex = COURSE_PALETTE[index % COURSE_PALETTE.size],
                slots = byKey[key].orEmpty()
            )
        }

        return ZjuScheduleRows(
            courses = courses,
            skipped = skipped.size,
            skipReason = skipped.takeIf { it.isNotEmpty() }?.joinToString("；")
        )
    }

    /** 学期代码 → provenance 里的一句话。 */
    fun termNote(termCode: Int, termQuery: String) =
        "${ZjuTerms.labelOf(termCode)}（$termQuery）"

    /** 组装成可以直接交给导入管线的 v2 Bundle。 */
    fun toBundle(
        rows: ZjuScheduleRows,
        calendarBundle: com.example.data.codec.CalendarBundle,
        termYear: String,
        termCode: Int,
        studentId: String
    ): ScheduleBundle = ScheduleBundle(
        provenance = ProvenanceBundle(
            source = "zju",
            generator = "MiuList 内置浙大导入",
            studentId = studentId,
            note = termNote(termCode, ZjuTerms.queryOf(termCode))
        ),
        calendar = calendarBundle,
        timetable = TimetableBlockBundle(
            termRef = TermRefBundle(termYear, termCode),
            courses = rows.courses
        )
    )

    // ---------------------------------------------------------------- 内部

    private data class ParsedClass(
        val name: String?,
        val code: String?,
        val teacher: String?,
        val classroom: String?,
        val dayOfWeek: Int?,
        val startPeriod: Int?,
        val endPeriod: Int?,
        /** 展开后的周号集合；null = 每周 */
        val weeks: List<Int>?
    )

    private fun ZjuClassRow.toParsed(totalWeeks: Int): ParsedClass? {
        // 上游第一道门：
        //   if IsConfirmed == 0 { if DayNumber 为空 return nil; if DayNumber[0] != '"' return nil }
        // 即「未确认 且 星期不是带引号的字符串」整条丢掉 —— 那是研究生数据，
        // 它们没有 sfqd 字段、星期是裸数字。
        //
        // **`(sfqd ?: 0)` 这个回退不能省**：Go 的 int 零值把「字段缺失」和「字段为 0」
        // 当成同一件事，我们用可空 Int 就必须自己补上，否则研究生那一整片数据会被放进来。
        if ((sfqd ?: 0) == 0 && xqj !is String) return null

        // 上游第二道门：`xq` 里秋/冬/春/夏一个都没有就 return nil。
        // 命中不了任何学期的东西不是我们要的课表行，放进来只会污染统计与周次计算。
        if (!hasRecognisedTerm()) return null

        val day = when (val raw = xqj) {
            is String -> raw.trim().toIntOrNull()
            is Number -> raw.toInt()
            else -> null
        } ?: return null

        val periods = (jc.orEmpty()).mapNotNull { it.trim().toIntOrNull() }.sorted()
        if (periods.isEmpty()) return null

        val weeks = when (dsz?.trim()) {
            "0" -> (1..totalWeeks).filter { it % 2 == 1 }.takeIf { it.isNotEmpty() }
            "1" -> (1..totalWeeks).filter { it % 2 == 0 }.takeIf { it.isNotEmpty() }
            else -> null
        }

        return ParsedClass(
            name = mc?.trim(),
            code = kcdm?.trim(),
            teacher = jsxm?.trim(),
            classroom = skdd?.trim(),
            dayOfWeek = day,
            startPeriod = periods.first(),
            endPeriod = periods.last(),
            weeks = weeks
        )
    }

    /** `xq` 里是否含「秋/冬/春/夏」任一字，与上游 `TermArrangements` 的判定完全一致。 */
    private fun ZjuClassRow.hasRecognisedTerm(): Boolean {
        val raw = xq.orEmpty()
        return TERM_KEYWORDS.any { it in raw }
    }

    /** 跳过的原因要写进导入预览，让用户知道哪几行没进来、为什么。 */
    private fun ZjuClassRow.skipReason(): String = when {
        (sfqd ?: 0) == 0 && xqj !is String -> "未确认"
        !hasRecognisedTerm() -> "学期无法识别"
        else -> "星期或节次无法解析"
    }

    private val TERM_KEYWORDS = listOf("秋", "冬", "春", "夏")
}