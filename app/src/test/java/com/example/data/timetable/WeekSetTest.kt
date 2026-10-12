package com.example.data.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开课周次归一化。纯 JUnit，不碰 Android。
 *
 * 这里覆盖的写法都是从教务系统实际输出里抄来的：`1-16周(单)`、`第1,3,5周`、
 * `1-8,10-12`。解析错了不会报错，只会让课在错的周次出现或消失。
 */
class WeekSetTest {

    // ---------------------------------------------------------------- 集合构造

    @Test
    fun `all odd even respect the semester bound`() {
        assertEquals((1..20).toSet(), WeekSet.all(20))
        assertEquals(setOf(1, 3, 5), WeekSet.odd(5))
        assertEquals(setOf(2, 4), WeekSet.even(5))
    }

    @Test
    fun `a bound below one still yields one week instead of an empty semester`() {
        // 学期配成 0 周时不能让整张课表空掉
        assertEquals(setOf(1), WeekSet.all(0))
        assertEquals(setOf(1), WeekSet.all(-3))
        assertEquals(setOf(1), WeekSet.odd(1))
        assertTrue(WeekSet.even(1).isEmpty())
    }

    @Test
    fun `range normalizes order and rejects a lower bound below one`() {
        assertEquals((3..7).toSet(), WeekSet.range(3, 7))
        assertEquals((3..7).toSet(), WeekSet.range(7, 3))
        assertTrue(WeekSet.range(0, 5).isEmpty())
    }

    @Test
    fun `clamp reports whether it had to drop anything`() {
        val r = WeekSet.clamp(setOf(1, 5, 30), 20)
        assertEquals(setOf(1, 5), r.weeks)
        assertTrue(r.truncated)
        assertFalse(WeekSet.clamp(setOf(1, 5), 20).truncated)
    }

    @Test
    fun `expand sorts and pairs every week with the slot id`() {
        assertEquals(
            listOf(9L to 1, 9L to 3, 9L to 8),
            WeekSet.expand(9L, setOf(8, 1, 3))
        )
    }

    // ---------------------------------------------------------------- parse

    @Test
    fun `missing or unparsable weeks fall back to every week instead of dropping the course`() {
        assertEquals((1..20).toSet(), WeekSet.parse(null, 20).weeks)
        assertEquals((1..20).toSet(), WeekSet.parse("", 20).weeks)
        assertEquals((1..20).toSet(), WeekSet.parse("   ", 20).weeks)
        val junk = WeekSet.parse("下学期", 20)
        assertEquals((1..20).toSet(), junk.weeks)
        assertNotNull("无法解析时必须留下 issue 供预览页展示", junk.issue)
    }

    @Test
    fun `bare odd and even markers are recognized`() {
        // 教务系统里 "单" / "双" 单独出现是常态，不能只认 "单周"
        assertEquals(setOf(1, 3, 5, 7), WeekSet.parse("单", 7).weeks)
        assertEquals(setOf(2, 4, 6), WeekSet.parse("双", 7).weeks)
        assertEquals(setOf(1, 3, 5, 7), WeekSet.parse("单周", 7).weeks)
        assertEquals(setOf(2, 4, 6), WeekSet.parse("双周", 7).weeks)
        assertEquals(setOf(1, 3, 5, 7), WeekSet.parse("odd", 7).weeks)
        assertEquals(setOf(2, 4, 6), WeekSet.parse("even", 7).weeks)
    }

    @Test
    fun `a range combined with an odd or even marker intersects the two`() {
        // "1-16周(单)" = 1-16 周里的单周。丢掉标记会让课在双周也出现，且看不出来。
        assertEquals(
            "1-16周(单) 必须落在单周上",
            WeekSet.odd(16),
            WeekSet.parse("1-16周(单)", 20).weeks
        )
        assertEquals(
            "全角括号同样要认",
            WeekSet.odd(16),
            WeekSet.parse("1-16周（单）", 20).weeks
        )
        assertEquals(
            "双周同理",
            WeekSet.even(16),
            WeekSet.parse("1-16周(双)", 20).weeks
        )
        // 区间起点本身是偶数时，交集不该退化成「原样保留区间」
        assertEquals(setOf(3, 5, 7), WeekSet.parse("2-8周(单)", 20).weeks)
    }

    @Test
    fun `plain ranges and lists parse without a marker`() {
        assertEquals((1..16).toSet(), WeekSet.parse("1-16", 20).weeks)
        assertEquals((1..16).toSet(), WeekSet.parse("1-16周", 20).weeks)
        assertEquals(setOf(1, 3, 5, 8), WeekSet.parse("第1,3,5,8周", 20).weeks)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 10, 11, 12), WeekSet.parse("1-6,10-12", 20).weeks)
        assertEquals(setOf(1, 3, 5), WeekSet.parse("1~5(单)", 20).weeks)
    }

    @Test
    fun `integer arrays are taken as-is and out-of-range numbers are dropped`() {
        assertEquals(setOf(1, 3, 5, 8, 12), WeekSet.parse(listOf(1, 3, 5, 8, 12), 20).weeks)
        val over = WeekSet.parse(listOf(1, 30), 20)
        assertEquals(setOf(1), over.weeks)
        assertNotNull(over.issue)
        // 全部越界 → 退回全周，而不是留一个空集合把课藏起来
        assertEquals((1..20).toSet(), WeekSet.parse(listOf(30, 40), 20).weeks)
        // 字符串形式的数组元素也要认（Moshi 反序列化可能给字符串）
        assertEquals(setOf(2, 4), WeekSet.parse(listOf("2", "4"), 20).weeks)
    }

    @Test
    fun `out of range weeks are truncated and reported once`() {
        val r = WeekSet.parse("1-30", 20)
        assertEquals((1..20).toSet(), r.weeks)
        assertNotNull(r.issue)
    }

    @Test
    fun `an odd marker with no intersection keeps the explicit numbers`() {
        // 「第2周(单)」自相矛盾。显式周号比标记更具体，按显式周号处理并留 issue，
        // 而不是让这门课整学期消失。
        val r = WeekSet.parse("2周(单)", 20)
        assertEquals(setOf(2), r.weeks)
        assertNotNull(r.issue)
    }

    @Test
    fun `an odd marker in a one-week semester falls back to every week`() {
        // 学期只有 1 周，双周为空。不能返回空集合。
        val r = WeekSet.parse("双", 1)
        assertEquals(setOf(1), r.weeks)
        assertNotNull(r.issue)
    }

    // ---------------------------------------------------------------- describe

    @Test
    fun `describe reverse-engineers the human label without storing one`() {
        assertEquals(WeekSet.LABEL_EVERY_WEEK, WeekSet.describe(WeekSet.all(20), 20))
        assertEquals(WeekSet.LABEL_ODD_WEEK, WeekSet.describe(WeekSet.odd(20), 20))
        assertEquals(WeekSet.LABEL_EVEN_WEEK, WeekSet.describe(WeekSet.even(20), 20))
        assertEquals("第1-8周", WeekSet.describe((1..8).toSet(), 20))
        assertEquals("共5周 (1,3,5,7,9)", WeekSet.describe(setOf(1, 3, 5, 7, 9), 20))
        assertEquals("未指定周次", WeekSet.describe(emptySet(), 20))
    }

    @Test
    fun `describe truncates a long irregular list instead of printing every number`() {
        val weeks = setOf(1, 3, 5, 7, 9, 11, 15)
        val text = WeekSet.describe(weeks, 20)
        assertTrue("必须标出被省略的尾巴：$text", text.contains("…"))
        assertTrue(text.contains("共7周"))
    }

    @Test
    fun `describe ignores weeks beyond the semester`() {
        assertEquals(WeekSet.LABEL_EVERY_WEEK, WeekSet.describe(WeekSet.all(20), 20))
        assertEquals("第1-2周", WeekSet.describe(setOf(1, 2, 30), 20))
    }

    @Test
    fun `fallback total weeks matches the legacy current week ceiling`() {
        // 与改造前 setCurrentWeek 的 coerceIn(1, 25) 同一个上界
        assertEquals(25, WeekSet.FALLBACK_TOTAL_WEEKS)
        assertNull(WeekSet.parse("1-25", 25).issue)
    }
}
