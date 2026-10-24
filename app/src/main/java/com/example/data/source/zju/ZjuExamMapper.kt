package com.example.data.source.zju

import com.example.data.calendar.AppZone
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 教务网「个人考试查询」接口的解析。
 *
 * 接口：`POST https://zdbk.zju.edu.cn/jwglxt/xskscx/kscx_cxXsgrksIndex.html`
 * 响应：`{"items": [ … ]}`，每一项是一门课的一次考试。
 *
 * ## 一门课可能出现**两场**考试
 *
 * 同一项里有两个时间字段：
 * - `kssj`（期末开始时间）→ 配 `jsmc`（教室）、`zwxh`（座位号）；
 * - `qzkssj`（期中开始时间）→ 配 `qzjsmc`（期中教室）、`qzzwxh`（期中座位号）。
 *
 * 两个字段的教室与座位**不是同一套**：期中用 `qzjsmc` / `qzzwxh`，
 * 期末用 `jsmc` / `zwxh`。所以解析时**按场次分别取**，
 * 不能「期中那套没取到就拿期末那套顶上」—— 座位号串了比没有更糟：
 * 用户会拿着一个属于另一场考试的座位号去考场。
 *
 * ## 时间串有三种形态
 *
 * ```
 * 2027年01月12日 09:00-11:00     ← 日期与时间都定了
 * 2027-01-12 09:00-11:00         ← 同上，只是日期写法不同
 * 秋季第3天(08:30-10:30)         ← **日期还没定**，只说了是考试周第几天
 * ```
 *
 * 第三种是「学校先排考试周，日期后定」时的形态，也是最容易被漏掉的一种：
 * 只按「有没有年月日」去切，第二种以外的整条会被当成没时间而丢掉。
 *
 * ## 「座位表还没出」是**常态**，不是异常
 *
 * 考试先公布时间，座位表与考场安排往往要晚几天甚至几周才出。
 * 这时 `jsmc` / `zwxh` 是**空串**（不是 null、不是「未知」），
 * 含义明确：**学校还没安排**。
 */
object ZjuExamMapper {

    /** 解析出来的一场考试。字段与 `ExamEntity` 一一对应，但还没落库。 */
    data class ExamRow(
        val xkkh: String,
        val courseCode: String,
        val courseName: String,
        val kind: String,
        val startAt: Long,
        val endAt: Long,
        val startMinute: Int,
        val endMinute: Int,
        val dateLabel: String,
        val classroom: String,
        val seat: String,
        val credit: Double,
        val termLabel: String
    )

    /**
     * 解析整个响应。
     *
     * 顶层结构有两种可能：`{"items":[…]}` 与直接一个数组。
     * 两种都收 —— 上游用的是前者，但接口偶尔会退回后者，
     * 而这两种情况下「解析不出来」与「没有考试」对用户是同一件事。
     */
    fun parse(rawJson: String): List<ExamRow> {
        val text = rawJson.trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[")) {
            return runCatching { parseItems(JSONArray(text)) }.getOrDefault(emptyList())
        }
        return runCatching {
            val json = JSONObject(text)
            val items = json.optJSONArray("items") ?: return@runCatching emptyList()
            parseItems(items)
        }.getOrDefault(emptyList())
    }

    /** 解析 `items` 数组。 */
    fun parseItems(items: JSONArray): List<ExamRow> {
        val rows = ArrayList<ExamRow>(items.length() * 2)
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val xkkh = item.optString("xkkh", "").trim()
            val courseName = item.optString("kcmc", "").trim()
            if (courseName.isEmpty() && xkkh.isEmpty()) continue

            val code = ZjuCourseCode.of(xkkh).orEmpty()
            val termLabel = ZjuCourseCode.termLabelOf(xkkh)
            val credit = optDouble(item, "xf")

            addIfPublished(
                rows = rows,
                item = item,
                xkkh = xkkh,
                code = code,
                courseName = courseName,
                credit = credit,
                termLabel = termLabel,
                kind = ExamKindConstants.FINAL,
                timeKey = "kssj",
                classroomKeys = listOf("jsmc", "qmksdd"),
                seatKeys = listOf("zwxh")
            )

            addIfPublished(
                rows = rows,
                item = item,
                xkkh = xkkh,
                code = code,
                courseName = courseName,
                credit = credit,
                termLabel = termLabel,
                kind = ExamKindConstants.MIDTERM,
                timeKey = "qzkssj",
                classroomKeys = listOf("qzjsmc", "qzksdd"),
                seatKeys = listOf("qzzwxh")
            )
        }
        return rows
    }

    /**
     * 一场考试「已公布」的判据。
     *
     * 时间、教室、座位**三者全空**才跳过：那时学校对这场考试什么也没说，
     * 造一条「时间待定、地点待定」的考试出来只会占位。
     *
     * 反过来只给了时间、没给座位 —— 那是「座位表还没出」，**要留**，
     * 而且要能让人一眼看出它还没定下来（见 `ExamEntity` 的注释）。
     */
    private fun addIfPublished(
        rows: MutableList<ExamRow>,
        item: JSONObject,
        xkkh: String,
        code: String,
        courseName: String,
        credit: Double,
        termLabel: String,
        kind: String,
        timeKey: String,
        classroomKeys: List<String>,
        seatKeys: List<String>
    ) {
        val rawTime = item.optString(timeKey, "").trim()
        val classroom = firstNonBlank(item, classroomKeys)
        val seat = firstNonBlank(item, seatKeys)
        if (rawTime.isEmpty() && classroom.isEmpty() && seat.isEmpty()) return

        val parsed = parseExamTime(rawTime)
        rows += ExamRow(
            xkkh = xkkh,
            courseCode = code,
            courseName = courseName.ifEmpty { "未命名课程" },
            kind = kind,
            startAt = parsed?.startAt ?: 0L,
            endAt = parsed?.endAt ?: 0L,
            startMinute = parsed?.startMinute ?: 0,
            endMinute = parsed?.endMinute ?: 0,
            dateLabel = parsed?.dateLabel ?: PENDING_TIME_LABEL,
            classroom = classroom,
            seat = seat,
            credit = credit,
            termLabel = termLabel
        )
    }

    /**
     * 解析考试时间串。
     *
     * 返回 null 表示**这个串里没有时间**（不是「有时间但解析不出来」——
     * 后者会退化成 [PENDING_TIME_LABEL]，前者整场考试都不该存在）。
     */
    fun parseExamTime(raw: String): ParsedExamTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val range = TIME_RANGE_REGEX.find(text)
        val startMinute: Int
        val endMinute: Int
        if (range != null) {
            startMinute = minuteOf(range.groupValues[1], range.groupValues[2])
            endMinute = minuteOf(range.groupValues[3], range.groupValues[4])
        } else {
            val single = SINGLE_TIME_REGEX.find(text) ?: return null
            startMinute = minuteOf(single.groupValues[1], single.groupValues[2])
            endMinute = startMinute
        }

        val date = CHINESE_DATE_REGEX.find(text) ?: NUMERIC_DATE_REGEX.find(text)
        if (date != null) {
            val year = date.groupValues[1].toIntOrNull() ?: return null
            val month = date.groupValues[2].toIntOrNull() ?: return null
            val day = date.groupValues[3].toIntOrNull() ?: return null
            val date0 = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            val midnight = date0.atStartOfDay(AppZone.ZONE).toInstant().toEpochMilli()
            return ParsedExamTime(
                startAt = midnight + startMinute * 60_000L,
                endAt = midnight + endMinute * 60_000L,
                startMinute = startMinute,
                endMinute = endMinute,
                dateLabel = ""
            )
        }

        // 日期未定：把学校给的那句标签原样留下（「秋季第 3 天」之类）。
        return ParsedExamTime(
            startAt = 0L,
            endAt = 0L,
            startMinute = startMinute,
            endMinute = endMinute,
            dateLabel = dateLabelOf(text)
        )
    }

    /** 解析结果。 */
    data class ParsedExamTime(
        /** 绝对开始时刻；0 = 日期未定。 */
        val startAt: Long,
        val endAt: Long,
        /** 当天第几分钟。日期未定也有值。 */
        val startMinute: Int,
        val endMinute: Int,
        /** 学校给出的日期文字；日期已知时为空串。 */
        val dateLabel: String
    )

    /**
     * 从「秋季第3天(08:30-10:30)」这种串里取出日期标签。
     *
     * 取的是时间部分**之前**的那一段，再把尾部残留的左括号与空白去掉。
     * 只有「第 N 天」开头时补上「考试周」三个字 —— 教务网上写的就是「考试周第 N 天」，
     * 而正文里写「第 3 天」会让用户以为是「第 3 周第 3 天」。
     */
    private fun dateLabelOf(text: String): String {
        val timeMatch = TIME_RANGE_REGEX.find(text) ?: SINGLE_TIME_REGEX.find(text)
        val head = if (timeMatch != null) text.substring(0, timeMatch.range.first) else text
        val cleaned = head.replace(TRAILING_BRACKET_REGEX, "").trim()
        if (cleaned.isEmpty()) return ""
        return if (cleaned.startsWith("第")) "考试周$cleaned" else cleaned
    }

    private fun minuteOf(hour: String, minute: String): Int {
        val h = hour.trim().toIntOrNull() ?: 0
        val m = minute.trim().toIntOrNull() ?: 0
        return h * 60 + m
    }

    private fun firstNonBlank(item: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = item.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** 学分可能是字符串（`"1.5"`）也可能是数字，两种都要收。 */
    private fun optDouble(item: JSONObject, key: String): Double {
        val asText = item.optString(key, "").trim()
        if (asText.isNotEmpty()) {
            asText.toDoubleOrNull()?.let { return it }
        }
        return item.optDouble(key, 0.0)
    }

    /** 期中 / 期末的取值。放在这里而不是 Room 实体里，是为了让解析层不依赖 `data.local`。 */
    object ExamKindConstants {
        const val MIDTERM = "MIDTERM"
        const val FINAL = "FINAL"
    }

    /** 连时间都没给时用的占位标签。 */
    const val PENDING_TIME_LABEL = "时间待定"

    private val CHINESE_DATE_REGEX = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
    private val NUMERIC_DATE_REGEX = Regex("""(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})""")
    private val TIME_RANGE_REGEX = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})\s*[-–—~～至]\s*(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val SINGLE_TIME_REGEX = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val TRAILING_BRACKET_REGEX = Regex("""[\s（(【]+$""")
}
