package com.example.data.timetable

/**
 * 校历弹层里「每天 N 节 · 每节起止时刻」的可编辑草稿。
 *
 * 单独成文件（不放在 UI 包里）是因为它承载两条**必须可测**的纯逻辑：
 * 时间解析、以及改节数时怎么增删行。这两条一旦算错，用户敲进去的时间会静默存错值，
 * 而界面上看不出任何异常。
 *
 * 存 [startText] / [endText] 字符串而不是 `Int`：文本框中间态（"8:"、"0830"）也是合法编辑状态，
 * 存 `Int` 就得在每次输入时做一次失败即丢的转换，输入框会跳字符。这里保留原文，
 * 校验只发生在**保存那一刻**。
 */
data class PeriodDraft(
    val period: Int,
    val startText: String,
    val endText: String,
    /** 上午 / 下午 / 晚上。课表栅格的大节分隔线由它推导，不是写死的第 5、10 节。 */
    val section: String
) {
    val startMinute: Int? get() = parseMinuteOfDay(startText)
    val endMinute: Int? get() = parseMinuteOfDay(endText)

    /** 校验用：两个时刻都能解析，且开始严格早于结束。 */
    val isValid: Boolean
        get() {
            val s = startMinute
            val e = endMinute
            return s != null && e != null && s < e
        }

    fun toPeriodSlot(): PeriodSlot? {
        val s = startMinute ?: return null
        val e = endMinute ?: return null
        if (s >= e) return null
        return PeriodSlot(period, s, e, section)
    }
}

/** 每天节数上限。够用了，再多是把一天的作息当成课表列。 */
const val MAX_PERIODS_PER_DAY = 20

private val SECTION_ORDER = listOf("上午", "下午", "晚上")

/** 起始时刻不可解析的行，取这个作为新一节的基准，保证新行一定落在合法时刻上。 */
private const val FALLBACK_FIRST_START = 8 * 60

private const val GAP_MINUTES = 10
private const val LENGTH_MINUTES = 45

/**
 * 一天里能表示的最晚时刻：23:59，**不是 24:00**。
 *
 * `HH:mm` 的小时位只有 00–23，所以 `24:00` 是一行永远存不下去的时间 ——
 * 草稿里出现它，这行就既没法保存也没法改（用户看不出它错在哪，只会以为弹层坏了）。
 * 要表达「一直上到午夜」只能用 23:59。
 */
private const val END_OF_DAY = 23 * 60 + 59

/** 一节的时长下限。区间被压得极窄时（一天硬排 20 节）至少给到这么多分钟。 */
private const val MIN_LENGTH_MINUTES = 5

/**
 * `HH:mm` → 自 00:00 起的分钟数。
 *
 * 宽松解析是有意的：手输时 `8:00`、`08:0` 都先照原样留着，让用户补完，
 * 而不是输入框当场把字符吃掉。**只有保存时才要求严格合法**。
 *
 * 无分隔符的写法只认**恰好 4 位**的纯数字（`0800`）。`8` 这种也放行会被当成 08:00，
 * 而用户八成是想接着补成 `08:xx` —— 猜错一次就等于把这一节静默存成了早八。
 *
 * @return 解析不出来返回 `null`（含 `25:00`、负数、`"abc"`）
 */
fun parseMinuteOfDay(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(':')
    val h: Int
    val m: Int
    when (parts.size) {
        2 -> {
            val hh = parts[0].trim()
            val mm = parts[1].trim()
            if (hh.isEmpty() || mm.isEmpty()) return null
            if (!hh.all { it.isDigit() } || !mm.all { it.isDigit() }) return null
            h = hh.toInt()
            m = mm.toInt()
        }
        // 纯 4 位数字：`0800`
        1 -> {
            val d = parts[0].trim()
            if (d.length != 4 || !d.all { it.isDigit() }) return null
            h = d.substring(0, 2).toInt()
            m = d.substring(2, 4).toInt()
        }
        else -> return null
    }
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 按开始时刻推断大节。用户在弹层里可以逐行改，这里只负责**新增行**的初值。 */
fun sectionOfMinute(minute: Int): String = when {
    minute < 12 * 60 -> SECTION_ORDER[0]
    minute < 18 * 60 -> SECTION_ORDER[1]
    else -> SECTION_ORDER[2]
}

/**
 * 把草稿行调整到 [count] 行。
 *
 * 改节数时**保留用户已经敲过的时间**：加节只在末尾追加，减节只截断末尾。
 * 整表重置会让用户每改一次节数就把一屏时间清空，那比不能改还糟。
 *
 * 追加的节有两种排法：
 * - 末尾还有 [LENGTH_MINUTES] + 间隔的余量 → 正常顺延（上一节结束后 [GAP_MINUTES] 起，每节 45 分钟）。
 * - 余量已经排不下（从早八排到 20 节必然如此）→ 把剩下的节**压进剩余时段**，
 *   每节均分。逐行顺延在这里会撞到 24:00 之后，而把起点硬夹回 24:00 - 45 会让
 *   新行的开始时刻**早于上一行的结束时刻**——时间倒流，而且每行单独看都合法，
 *   保存时才不会被发现。这种表存进去，课表上的时间读出来是乱的。
 */
fun resizePeriodDrafts(current: List<PeriodDraft>, count: Int): List<PeriodDraft> {
    val target = count.coerceIn(1, MAX_PERIODS_PER_DAY)
    val kept = (1..minOf(target, current.size)).map { p ->
        current[p - 1].copy(period = p)
    }
    if (kept.size >= target) return kept

    val extra = target - kept.size
    val rows = ArrayList<PeriodDraft>(target)
    rows += kept

    // 没有任何已存行时，第一节直接从基准时刻起算，不加间隔 ——
    // 加了会让「从空表开始」得到 08:10 开头，而基准本身就是 08:00
    val prevEnd = kept.lastOrNull()?.endMinute
    val sectionStart = (prevEnd?.plus(GAP_MINUTES) ?: FALLBACK_FIRST_START).coerceAtMost(END_OF_DAY)
    val available = END_OF_DAY - sectionStart

    // 余量够每节 45 分钟才顺延；否则整段均分，且保证每节至少 MIN_LENGTH_MINUTES
    if (available >= extra * (LENGTH_MINUTES + GAP_MINUTES)) {
        for (i in 0 until extra) {
            val start = sectionStart + i * (LENGTH_MINUTES + GAP_MINUTES)
            rows += PeriodDraft(
                period = rows.size + 1,
                startText = formatMinute(start),
                endText = formatMinute(start + LENGTH_MINUTES),
                section = sectionOfMinute(start)
            )
        }
    } else {
        // 可用时段切 extra 份，每份至少 MIN_LENGTH_MINUTES。
        //
        // 末节刻意不吃满一份：`end` 直接取 END_OF_DAY。按「每份都等长」算，
        // 末节的 end 会落到 END_OF_DAY 之后，于是被截断成和 start 相同 ——
        // 零长度的课，每行单独校验才报得出错，而那正是这张表看起来能存、存下去却错的情形。
        val length = (available / extra).coerceAtLeast(MIN_LENGTH_MINUTES)
        for (i in 0 until extra) {
            val isLast = i == extra - 1
            val start = sectionStart + i * length
            val end = if (isLast) END_OF_DAY else (start + length)
            // start 必须严格小于 end；均分被 MIN_LENGTH 抬高后可能越过末界
            val safeEnd = end.coerceAtLeast(start + 1).coerceAtMost(END_OF_DAY)
            rows += PeriodDraft(
                period = rows.size + 1,
                startText = formatMinute(start.coerceAtMost(END_OF_DAY - 1)),
                endText = formatMinute(safeEnd),
                section = sectionOfMinute(start)
            )
        }
    }
    return rows
}

/** 由一份已有作息表生成草稿（列号重排为 1..n）。 */
fun PeriodTable.toDrafts(): List<PeriodDraft> = periods.map {
    PeriodDraft(it.period, it.startTime, it.endTime, it.section)
}

/** 由预设作息（如浙大 15 节）生成草稿。 */
fun List<PeriodSlot>.toDrafts(): List<PeriodDraft> = sortedBy { it.period }.map {
    PeriodDraft(it.period, it.startTime, it.endTime, it.section)
}