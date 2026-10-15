package com.example.data.codec

import com.example.data.calendar.ZjuCalendarAdapter

/**
 * ## 浙大的「学期」是两层的
 *
 * 上游 `configs/config.json` 的 `termConfigs` **一条 = 一个 8 周的半学期**：
 *
 * ```json
 * { "Year": "2026-2027", "Term": 0, "Begin": 20260914, "End": 20261108 },  // 秋
 * { "Year": "2026-2027", "Term": 1, "Begin": 20261109, "End": 20270103 }   // 冬
 * ```
 *
 * 而浙大嘴里的「一个学期」是**学年学期**：秋冬学期 / 春夏学期，一学期 16 周，
 * 秋（春）学期占第 1–8 周、冬（夏）学期占第 9–16 周。教务网选课、
 * 学生说的「这学期」用的都是这个口径。
 *
 * 之前本项目把 `termConfigs` 里的**一条**直接当成整个学期，于是：
 * - 导入「秋冬」只拿到秋那 8 周，冬的 8 周连同冬的调休一起被丢掉；
 * - `totalWeeks` 算成 8，而 [com.example.data.source.zju.ZjuScheduleMapper]
 *   已经按 16 周口径把课的周号平移过了（第 9–16 周），两端口径对不上，
 *   后半学期的课整段不出现。
 *
 * [AcademicTerm] 就是把两半拼回一个 16 周学期的那一步。
 *
 * ## 周次不写死 8，而是按实际日期算
 *
 * 「秋 = 1–8 周」是规律，不是格式保证。这里**不**假设冬的 `Begin` 正好等于
 * 「秋的起始周一 + 56 天」，而是只取两端的实际日期算周数：
 * `firstMonday` 由第一半的 `Begin` 定，[totalWeeks] 由最后一半的 `End` 定。
 * 这样两半之间有缺口或有交叠时，周次仍然连续而不会凭空多出或少掉一周。
 *
 * 纯函数，不依赖 Android / Room，可在普通 JUnit 里直接测。
 */

/** 一个 8 周的半学期，对应 `termConfigs` 里的一条。 */
data class TermHalf(
    /** 上游枚举值：0=秋 1=冬 2=短A 3=小学期 4=春 5=夏 6=短B */
    val term: Int,
    /** 「秋学期」/「冬学期」…… */
    val label: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val firstWeekNo: Int,
    val config: TermConfigBundle
)

/**
 * 一个学年学期（秋冬 / 春夏），16 周。
 *
 * [halves] 通常有两条（秋+冬 或 春+夏）；只有一条时是退化情形，
 * 例如上游配置里只给了秋而没给冬，此时 [label] 退回该半学期自己的名字。
 */
data class AcademicTerm(
    val yearSpan: String,
    /** 「秋冬」/「春夏」；短学期与退化情形为 null。 */
    val season: String?,
    val halves: List<TermHalf>,
    /** 整个学期的第一天 = 第一半的第一天。 */
    val startEpochDay: Long,
    val firstMondayEpochDay: Long,
    /** 整个学期的最后一天 = 最后半的最后一天。 */
    val endEpochDay: Long,
    val totalWeeks: Int
) {
    /** 第一半的学期代码 —— 教务网取课表就用它（秋冬用「1\|秋」，春夏用「2\|春」）。 */
    val queryTerm: Int get() = halves.first().term

    val label: String
        get() = if (season != null) "${yearSpan}学年 $season 学期" else "$yearSpan ${halves.first().label}"

    fun contains(epochDay: Long): Boolean = epochDay in startEpochDay..endEpochDay
}

/**
 * 半学期 → 学年学期。只认浙大真正成对的两个：秋(0)+冬(1)、春(4)+夏(5)。
 * 短学期A / 小学期 / 短学期B 各自独立成段，返回的季节为 null。
 */
private val SEASON_OF_TERM = mapOf(
    0 to "秋冬", 1 to "秋冬",
    4 to "春夏", 5 to "春夏"
)

/** `termConfigs` 里属于同一学年学期的两条。 */
fun List<TermConfigBundle>.halvesOfAcademicTerm(year: String, term: Int): List<TermConfigBundle> {
    val season = SEASON_OF_TERM[term] ?: return filter { it.year == year && it.term == term }
    return filter { it.year == year && SEASON_OF_TERM[it.term] == season }
        .sortedBy { it.term ?: Int.MAX_VALUE }
}

/**
 * 把 `termConfigs` 折成一个学年学期。
 *
 * @param term 指向**任意一半**即可：传 0（秋）与传 1（冬）得到的是同一个 16 周学期。
 * @return 解析不出日期时返回 null，由调用方决定是报错还是走降级。
 */
fun List<TermConfigBundle>.academicTermOf(year: String, term: Int): AcademicTerm? {
    val configs = halvesOfAcademicTerm(year, term)
    if (configs.isEmpty()) return null

    val parsed = configs.map { cfg ->
        val start = ZjuCalendarAdapter.yyyymmddToEpochDay(cfg.begin)
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(cfg.end)
        if (start == null || end == null || start > end) return null
        TermHalf(
            term = cfg.term ?: return null,
            label = ZjuCalendarAdapter.termLabel(cfg.term),
            startEpochDay = start,
            endEpochDay = end,
            firstWeekNo = cfg.firstWeekNo ?: 1,
            config = cfg
        )
    }.sortedBy { it.startEpochDay }

    val first = parsed.first()
    val last = parsed.last()
    // 周次锚点只认**第一半**：它是学期的真正起点，周数自然覆盖到最后一半为止
    val firstMonday = ZjuCalendarAdapter.firstMondayOf(first.startEpochDay, first.firstWeekNo)
    val totalWeeks = ZjuCalendarAdapter.totalWeeks(first.startEpochDay, last.endEpochDay, first.firstWeekNo)
    // 季节只有两半都在场、且确实同属一个季节时才敢叫「秋冬 / 春夏」：
    // 只拿到秋，无法断言这个学期有没有配套的冬。
    val season = parsed.mapNotNull { SEASON_OF_TERM[it.term] }
        .distinct()
        .singleOrNull()
        ?.takeIf { parsed.size >= 2 }

    return AcademicTerm(
        yearSpan = year,
        season = season,
        halves = parsed,
        startEpochDay = first.startEpochDay,
        firstMondayEpochDay = firstMonday,
        endEpochDay = last.endEpochDay,
        totalWeeks = totalWeeks
    )
}
