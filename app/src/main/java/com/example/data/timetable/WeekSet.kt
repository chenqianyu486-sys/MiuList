package com.example.data.timetable

import kotlin.math.max

/**
 * 「开课周次」是本项目里**唯一**的周次表达。
 *
 * 每周 / 单周 / 双周 / 第 1–8 周 / `{1,3,5,8,12}` 任意组合，全都是同一个形状：
 * 一个周次号集合。这样教务系统给出「1-16周(单)」「第1,3,5周」这类不规则表述时
 * 不需要改模型，只需归一化（见 [parse]）。
 *
 * 本文件不依赖 Android / Room / 协程，可在普通 JUnit 里直接测。
 */
object WeekSet {

    const val LABEL_EVERY_WEEK = "每周"
    const val LABEL_ODD_WEEK = "单周"
    const val LABEL_EVEN_WEEK = "双周"

    /** 未配置学期时沿用的周数上界，和改造前 `setCurrentWeek` 的 `coerceIn(1, 25)` 一致。 */
    const val FALLBACK_TOTAL_WEEKS = 25

    fun all(totalWeeks: Int): Set<Int> = (1..max(1, totalWeeks)).toSet()

    fun odd(totalWeeks: Int): Set<Int> = all(totalWeeks).filter { it % 2 == 1 }.toSet()

    fun even(totalWeeks: Int): Set<Int> = all(totalWeeks).filter { it % 2 == 0 }.toSet()

    fun range(from: Int, to: Int): Set<Int> {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        if (lo < 1) return emptySet()
        return (lo..hi).toSet()
    }

    /** 去掉超出学期范围的周次；返回值第二项表示是否发生过截断。 */
    fun clamp(weeks: Set<Int>, totalWeeks: Int): ClampedWeekSet {
        val bound = max(1, totalWeeks)
        val kept = weeks.filter { it in 1..bound }.toSet()
        return ClampedWeekSet(kept, kept.size != weeks.size)
    }

    data class ClampedWeekSet(val weeks: Set<Int>, val truncated: Boolean)

    data class ParseResult(val weeks: Set<Int>, val issue: String? = null)

    /**
     * 把任意来源的开课周次表述归一化成周次集合。
     *
     * 支持：整数数组、`"1-16"`、`"1-8,10-12"`、`"odd"` / `"单"`、`"双周"`、
     * `"1-16周(单)"`、`"第1,3,5周"`。
     *
     * 区间与单双周标记同时出现时（`"1-16周(单)"`，教务系统最常见的写法）
     * **取交集**：区间定范围，标记在范围里筛。丢掉标记会让课在双周也出现，
     * 而且看不出哪里错了。
     *
     * **缺省 / 空 / 解析失败一律退回全周**：宁可多显示几周，也不因为一个字段
     * 解析不了就让整门课从课表上消失。失败原因通过 [ParseResult.issue] 交给
     * 导入预览页的问题清单展示。
     */
    fun parse(raw: Any?, totalWeeks: Int): ParseResult {
        val bound = max(1, totalWeeks)
        val full = all(bound)

        if (raw == null) return ParseResult(full)

        // 显式整数数组：原样采用
        if (raw is Collection<*>) {
            val nums = raw.mapNotNull { (it as? Number)?.toInt() ?: it?.toString()?.trim()?.toIntOrNull() }
            if (nums.isEmpty()) return ParseResult(full, "开课周次数组为空或全部无法解析，已按每周处理")
            val kept = nums.filter { it in 1..bound }.toSet()
            if (kept.isEmpty()) return ParseResult(full, "开课周次 $nums 全部超出 1–$bound，已按每周处理")
            return ParseResult(kept, if (kept.size != nums.size) "开课周次超出 1–$bound 的部分已截断" else null)
        }

        val text = raw.toString().trim()
        if (text.isEmpty()) return ParseResult(full)

        // 先剥掉奇偶标记，剩下的再按「数字 / 区间」解析
        var work = text.lowercase()
        var parity: Int? = null
        // 顺序有意义：先长后短。裸 "单" / "双" 放最后，否则 "1-16周(单)"
        // 会被裸 "单" 抢先匹配、只留下 "1-16周" 的残缺写法。
        val parityPatterns = listOf(
            "(单)" to 1, "（单）" to 1, "(双)" to 2, "（双）" to 2,
            "单周" to 1, "双周" to 2, "odd" to 1, "even" to 2,
            "单" to 1, "双" to 2
        )
        for ((needle, value) in parityPatterns) {
            if (work.contains(needle)) {
                parity = value
                work = work.replace(needle, " ")
                break
            }
        }
        work = work.replace("第", " ").replace("周", " ").replace("次", " ")
            .replace("(", " ").replace(")", " ").replace("（", " ").replace("）", " ")
            .replace("-", "..").replace("~", "..").replace("至", "..").replace("—", "..")
            .trim()

        val parsed = mutableSetOf<Int>()
        work.split(',', '，', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { token ->
                if (token.contains("..")) {
                    val bounds = token.split("..").map { it.trim() }
                    if (bounds.size == 2) {
                        val lo = bounds[0].toIntOrNull()
                        val hi = bounds[1].toIntOrNull()
                        if (lo != null && hi != null) parsed.addAll(range(lo, hi))
                    }
                } else {
                    token.toIntOrNull()?.let { if (it >= 1) parsed.add(it) }
                }
            }

        val kept = parsed.filter { it in 1..bound }.toSet()

        // 只有奇偶标记（"单" / "odd" / "1-16周(单)" 去掉区间后也可能只剩奇偶）
        if (kept.isEmpty()) {
            if (parity != null) {
                val weeks = if (parity == 1) odd(bound) else even(bound)
                val issue = if (weeks.isEmpty()) "学期仅 $bound 周，${parityLabel(parity)}在本学期为空，已按每周处理" else null
                return ParseResult(if (weeks.isEmpty()) full else weeks, issue)
            }
            return ParseResult(full, "开课周次「$text」无法解析，已按每周处理")
        }

        // 「1-16周(单)」：区间定范围，单双周在范围里取交集
        val p = parity
        val narrowed = if (p == null) kept else kept.filter { it % 2 == p % 2 }.toSet()
        if (narrowed.isEmpty()) {
            return ParseResult(kept, "开课周次「$text」的显式周号与${parityLabel(p)}没有交集，已按显式周号处理")
        }

        val truncated = parsed.any { it !in 1..bound }
        return ParseResult(narrowed, if (truncated) "开课周次超出 1–$bound 的部分已截断" else null)
    }

    private fun parityLabel(parity: Int?) = when (parity) {
        1 -> LABEL_ODD_WEEK
        2 -> LABEL_EVEN_WEEK
        else -> "每周"
    }

    /**
     * 反推展示标签。**只做展示，不落库**——课表和课程卡片上显示的「每周 / 单周 /
     * 双周 / 第 a–b 周」都是从集合现算出来的，不额外存一列。
     */
    fun describe(weeks: Set<Int>, totalWeeks: Int): String {
        val bound = max(1, totalWeeks)
        val kept = weeks.filter { it in 1..bound }.toSet()
        if (kept.isEmpty()) return "未指定周次"
        if (kept == all(bound)) return LABEL_EVERY_WEEK
        if (kept == odd(bound)) return LABEL_ODD_WEEK
        if (kept == even(bound)) return LABEL_EVEN_WEEK
        val sorted = kept.sorted()
        if (sorted.last() - sorted.first() + 1 == sorted.size) {
            return "第${sorted.first()}-${sorted.last()}周"
        }
        val preview = sorted.take(6).joinToString(",")
        val tail = if (sorted.size > 6) ",…" else ""
        return "共${sorted.size}周 ($preview$tail)"
    }

    /** 展开成一串可直接写进 `course_slot_weeks` 的行。 */
    fun expand(slotId: Long, weeks: Set<Int>): List<Pair<Long, Int>> =
        weeks.sorted().map { slotId to it }
}