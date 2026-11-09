package com.example.data.source.zju

import com.example.data.local.CourseEntity

/**
 * 学在浙大上的课程名 → MiuList 里的课程。
 *
 * ## 为什么只能按名字对
 *
 * 两边的课程 id 不是一回事（学在浙大用的是平台自己的 id，MiuList 用的是本地库的自增主键），
 * 没有共同的键，唯一能用的连接点就是**课程名**。
 *
 * ## 为什么是「模糊」匹配
 *
 * 早先只有两条规则：完全相等、一方包含另一方。看上去够用，实际数据里有一批
 * **格式差异**的老对不上：
 *
 * | 学在浙大 | 本地课表 | 老规则 | 为什么对不上 |
 * |---|---|---|---|
 * | `大学物理（下）` | `大学物理` | 命中 | —— |
 * | `数据结构(上)` | `数据结构` | **落空** | 括号没剥掉 |
 * | `大学英语Ⅰ` | `大学英语I` | **落空** | 罗马数字不是字母 |
 * | `高等数` | `高等数学` | **落空** | 「高等数」不是「高等数学」的子串 |
 * | `马克思主义原理` | `马克思主义基本原理` | **落空** | 两边互不包含 |
 *
 * 于是规则放宽成四档（从严到宽）：完全相等 → 归一化后相等 → 一方包含另一方 →
 * 字符二元组相似度够高。**放宽的是命中面，不是把握**：每一档都有自己的下限，
 * 达不到就还是不挂。
 *
 * ## 对不上就留空
 *
 * 匹配不上时返回 null，由调用方把待办建成「不关联课程」。
 * 宁可空着，也不要把作业挂到一门不相干的课上 —— 那比不挂更麻烦：
 * 它会让这条待办出现在那门课的时间线里，而它根本不是那门课的作业。
 *
 * 这条原则同样约束模糊匹配：**相似度不够就不匹配**，不允许「差不多就算了」。
 */

/** 匹配的方式，从严到宽。 */
enum class CourseMatchKind {
    /** 去掉首尾空白后完全一样。 */
    EXACT,

    /**
     * 剥掉括号里的补充说明、全角转半角、去空白、大小写归一之后一样。
     *
     * 「大学物理（下）」对「大学物理」就落在这里 —— 它不是同一串字，
     * 但说的是同一门课。
     */
    NORMALIZED,

    /**
     * 一方的名字是另一方的子串。
     *
     * 「数据结构」对「数据结构与算法」、「大学物」对「大学物理」都在这里。
     */
    CONTAINS,

    /**
     * 既不包含、归一化后也不一样，但字符二元组相似度过了线。
     *
     * 「马克思主义原理」对「马克思主义基本原理」就落在这里。
     */
    FUZZY
}

/**
 * 一次匹配的结果。
 *
 * [kind] 告诉调用方「这是怎么对上的」，[score] 告诉它「有多像」。
 * 界面要用这两样东西把匹配讲清楚，而不是让用户猜为什么这条作业被挂到了某门课上。
 */
data class CourseMatch(
    val course: CourseEntity,
    val kind: CourseMatchKind,
    /** 0~1。同一档里越高越像；跨档比较没有意义（档位本身比分数重要）。 */
    val score: Float
)

/**
 * 按名字匹配一门本地课程。
 *
 * @param zjuCourseName 学在浙大那边写的课程名。
 * @param courses 本地课程表（**本学期**的课表）。传进来的就是候选池：
 *   浙大同步走整表替换，本地这张表本身就是当前学期的课。
 * @return 匹配到的课程与匹配方式；一个都对不上时返回 null。
 */
fun matchCourse(zjuCourseName: String, courses: List<CourseEntity>): CourseMatch? {
    val target = zjuCourseName.trim()
    if (target.isEmpty()) return null

    // 空名字的课不参与匹配：任何课程名都「包含」空串，
    // 那会让它变成万能匹配。
    val candidates = courses.filter { it.name.isNotBlank() }
    if (candidates.isEmpty()) return null

    // ---- 1. 完全相等 ----
    // 它一定是用户心里想的那门课，后面的规则都排在它后面。
    candidates.firstOrNull { it.name.trim() == target }
        ?.let { return CourseMatch(it, CourseMatchKind.EXACT, 1f) }

    val targetBase = normalizeCourseName(stripBrackets(target))
    if (targetBase.isEmpty()) return null

    // ---- 2. 归一化后相等 ----
    // 「大学物理（下）」对「大学物理」、「大学英语Ⅰ」对「大学英语I」都在这里。
    val normalized = candidates.filter {
        normalizeCourseName(stripBrackets(it.name)) == targetBase
    }
    if (normalized.isNotEmpty()) {
        return CourseMatch(pickLongest(normalized), CourseMatchKind.NORMALIZED, NormalizedScore)
    }

    // ---- 3. 一方包含另一方 ----
    // 多个候选里挑**名字更长**的那个：「数字电路」和「数字电路实验」同时在课表里时，
    // 学在浙大上的「数字电路实验」应该对到后者，而不是碰巧排在前面那个。
    val contains = candidates.filter { course ->
        val base = normalizeCourseName(stripBrackets(course.name))
        if (base.isEmpty()) return@filter false
        // 短的那一方至少要有 [MinContainLength] 个字：
        // 一个字的课程名能被任何名字包含，那不叫匹配。
        minOf(base.length, targetBase.length) >= MinContainLength &&
            (base.contains(targetBase) || targetBase.contains(base))
    }
    if (contains.isNotEmpty()) {
        return CourseMatch(pickLongest(contains), CourseMatchKind.CONTAINS, ContainsScore)
    }

    // ---- 4. 字符二元组相似度 ----
    // 前三档都对不上时才走这里 —— 它是最后一档网，阈值定得高。
    val fuzzy = candidates.mapNotNull { course ->
        val base = normalizeCourseName(stripBrackets(course.name))
        if (base.isEmpty()) return@mapNotNull null
        val score = diceCoefficient(targetBase, base)
        if (score >= FuzzyThreshold) course to score else null
    }
    if (fuzzy.isEmpty()) return null
    val best = fuzzy.maxWith(
        compareBy({ it.second }, { it.first.name.trim().length }, { -it.first.id })
    )
    return CourseMatch(best.first, CourseMatchKind.FUZZY, best.second)
}

/**
 * 只要课程、不要匹配方式的老入口。
 *
 * 保留它是因为调用点里有一处只想知道「是哪门课」（总览页的小卡配色），
 * 那儿不需要展示匹配方式。**新代码要讲清楚匹配结果的，请用 [matchCourse]。**
 */
fun matchCourseByName(zjuCourseName: String, courses: List<CourseEntity>): CourseEntity? =
    matchCourse(zjuCourseName, courses)?.course

/** 候选里挑名字最长的那个；一样长就挑 id 小的，保证结果稳定。 */
private fun pickLongest(candidates: List<CourseEntity>): CourseEntity =
    candidates.maxWith(compareBy({ it.name.trim().length }, { -it.id }))

/**
 * 剥掉括号里的补充说明。
 *
 * 「数据结构(上)」「大学物理（下）」「马克思主义基本原理[学分2]」剥完分别是
 * 「数据结构」「大学物理」「马克思主义基本原理」。
 *
 * 括号没配对时（只有左括号）后面的内容会一起被丢掉 —— 这是刻意的：
 * 半截名字本来就不可信，宁可剥干净也不要留一半。
 */
private fun stripBrackets(raw: String): String {
    val sb = StringBuilder(raw.length)
    var depth = 0
    for (ch in raw) {
        when {
            ch == '(' || ch == '（' || ch == '[' || ch == '【' || ch == '《' -> depth++
            ch == ')' || ch == '）' || ch == ']' || ch == '】' || ch == '》' -> {
                if (depth > 0) depth--
            }
            else -> if (depth == 0) sb.append(ch)
        }
    }
    return sb.toString()
}

/**
 * 归一化课程名：全角转半角、罗马数字转字母、去空白、去标点、转小写。
 *
 * 只留下字母和数字（含汉字），其余一律丢掉 —— 于是
 * 「大学 物理」「大学·物理」「大学物理」「大学物理（一）」归一化后是同一个串。
 *
 * 罗马数字单独处理，是因为「大学英语Ⅰ」「大学英语I」在教务系统里是同一门课的两个写法，
 * 而 Ⅰ 的字符类别是「数字字母」，`isLetterOrDigit` 会把它留下，
 * 不映射的话这两个名字永远对不上。
 */
private fun normalizeCourseName(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        val mapped = when {
            // 全角 ASCII（！到～）减 0xFEE0 就是对应的半角字符。
            ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar().toString()
            else -> ROMAN_NUMERALS[ch] ?: ch.toString()
        }
        for (c in mapped) {
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
    }
    return sb.toString()
}

/**
 * 罗马数字 → 拉丁字母（大小写各一份）。
 *
 * 映射到**字符串**而不是单个字符：「Ⅱ」不是一个字母，是两个（II）。
 */
private val ROMAN_NUMERALS: Map<Char, String> = mapOf(
    'Ⅰ' to "I", 'Ⅱ' to "II", 'Ⅲ' to "III", 'Ⅳ' to "IV", 'Ⅴ' to "V",
    'Ⅵ' to "VI", 'Ⅶ' to "VII", 'Ⅷ' to "VIII", 'Ⅸ' to "IX", 'Ⅹ' to "X",
    'ⅰ' to "i", 'ⅱ' to "ii", 'ⅲ' to "iii", 'ⅳ' to "iv", 'ⅴ' to "v",
    'ⅵ' to "vi", 'ⅶ' to "vii", 'ⅷ' to "viii", 'ⅸ' to "ix", 'ⅹ' to "x"
)

/**
 * 字符二元组的 Dice 系数。
 *
 * 取相邻两字一组（「高等数学」→ 高等/等数/数学），算两个串的公共二元组占比：
 * `2 × 公共数 ÷ (甲的组数 + 乙的组数)`。
 *
 * 为什么用二元组而不是整串比：中文课程名没有词边界，
 * 「马克思主义原理」和「马克思主义基本原理」整体只差两个字，
 * 但按二元组比就能看出它们共享「马克思主义」这一大段（实测 0.71），
 * 而「大学物理」和「大学化学」只共享「大学」两个字（实测 0.33）。
 *
 * 公共组按**重数**算（两串里出现两次的组算两次），不是只算有没有出现过。
 */
private fun diceCoefficient(a: String, b: String): Float {
    if (a == b) return 1f
    // 一个字的串没有二元组可比。单字课程名（极少）只走前面三档。
    if (a.length < 2 || b.length < 2) return 0f

    val counts = HashMap<String, Int>(a.length)
    for (i in 0 until a.length - 1) {
        val gram = a.substring(i, i + 2)
        counts[gram] = (counts[gram] ?: 0) + 1
    }
    var overlap = 0
    for (i in 0 until b.length - 1) {
        val gram = b.substring(i, i + 2)
        val left = counts[gram] ?: 0
        if (left > 0) {
            overlap++
            counts[gram] = left - 1
        }
    }
    return 2f * overlap / ((a.length - 1) + (b.length - 1))
}

/**
 * 模糊匹配的下限。
 *
 * 0.62 是量出来的：真样本里该对上的那几对（0.71~0.80）都在线以上，
 * 不该对上的那几对（0.29~0.40）都远在线下。
 *
 * 别为了「多命中几个」往下调 —— 低于 0.5 之后，「大学物理」和「大学化学」
 * 这类只共享前两个字的名字会开始被硬凑到一起，那比不挂更糟。
 */
private const val FuzzyThreshold = 0.62f

/** 归一化相等这一档的固定分。 */
private const val NormalizedScore = 0.95f

/** 包含这一档的固定分。 */
private const val ContainsScore = 0.90f

/** 包含这一档里，短的那一方至少要有几个字。 */
private const val MinContainLength = 2
