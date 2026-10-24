package com.example.data.source.zju

/**
 * 从教务网选课课号（`xkkh`）里抽课程代码。
 *
 * ## 为什么要单独抽出来
 *
 * 课表接口与考试接口**都**带 `xkkh`，而且是同一门课的同一个串
 * （实测：课表里的 `(2026-2027-1)-MATH2432F-0083261-1` 与考试接口里的一模一样）。
 * 两边要能对上，靠的就是「从同一串里抽出同一个课程代码」这件事 ——
 * 而这件事**只能有一处实现**。
 *
 * 两边各写一份正则的后果不是「重复代码」这么轻：哪天正则改了只改了一边，
 * 于是课表与考试对不上，而界面上看不出任何异常 —— 考试只是悄悄退化成
 * 「一门认不出是哪门课的考试」。
 */
internal object ZjuCourseCode {

    /**
     * `xkkh` 的形状是 `(学年-学年-学期)-课程代码-教师工号-班号`。
     *
     * 课程代码本身不含连字符，所以 `^(\([^)]*\))-([^-]+)-.+` 能稳定切出前两段。
     * 切不出来就退回整串（而不是切错位置）—— 形状变了要能看出来，不能错位。
     */
    private val COURSE_CODE_REGEX = Regex("""^(\([^)]*\))-([^-]+)-.+""")

    /** 课程代码，如 `(2026-2027-1)-MATH2432F`。认不出时返回 null。 */
    fun of(xkkh: String?): String? {
        val raw = xkkh?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val m = COURSE_CODE_REGEX.find(raw) ?: return raw
        return "${m.groupValues[1]}-${m.groupValues[2]}"
    }

    /**
     * 学年学期标签，如「2026-2027 秋冬」。
     *
     * `xkkh` 的括号里是 `(学年-学年-学期号)`，学期号 1 = 秋冬、2 = 春夏
     * —— 与教务网 `xqm` 的 `1|秋` / `1|冬`（秋冬）与 `2|春` / `2|夏`（春夏）同源。
     *
     * 认不出时返回空串：这一列只用来把不同学期的考试分开显示，
     * 认不出来时把考试混在一堆里，好过显示一个编出来的学期。
     */
    fun termLabelOf(xkkh: String?): String {
        val raw = xkkh?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val m = Regex("""^\(([^)]*)\)""").find(raw) ?: return ""
        val parts = m.groupValues[1].split("-")
        if (parts.size < 3) return ""
        val first = parts[0].trim()
        val second = parts[1].trim()
        val termNo = parts[2].trim()
        if (first.isEmpty() || second.isEmpty()) return ""
        val yearSpan = "$first-$second"
        val season = when (termNo) {
            "1" -> "秋冬"
            "2" -> "春夏"
            else -> ""
        }
        return if (season.isEmpty()) yearSpan else "$yearSpan $season"
    }
}
