package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务网 `kblist` → 课表块的映射。
 *
 * 这里钉住的是三条**反直觉但必须照抄上游 `ToZJUClass`** 的规则：
 * 1. `dsz == "0"` 是单周、`"1"` 是双周（不是 0=每周）；
 * 2. 未确认的丢弃条件是「`sfqd` 为 0 **或缺失**」且「星期不是带引号的字符串」——
 *    也就是研究生那批数字星期的数据会被丢，而不是被放过；
 * 3. `xq` 里秋/冬/春/夏一个都没有的也丢。
 *
 * 三条写错了都不会崩，只会让课表多出不该有的行、或者整份研究生课表消失。
 */
class ZjuScheduleMapperTest {

    private fun row(
        name: String = "高等数学",
        code: String = "MATH101",
        teacher: String = "王教授",
        where: String = "教二-301",
        periods: List<String> = listOf("1", "2"),
        day: Any? = "1",
        dsz: String? = null,
        confirmed: Int? = 1,
        term: String = "2024-2025|春夏"
    ) = ZjuClassRow(
        mc = name, kcdm = code, jsxm = teacher, skdd = where,
        jc = periods, xqj = day, dsz = dsz, xq = term, sfqd = confirmed
    )

    // ---------------------------------------------------------------- 单双周

    @Test
    fun `dsz zero means odd weeks, which is the reverse of the obvious reading`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(dsz = "0")), totalWeeks = 8, termCode = 4)
        val weeks = out.courses.first().slots.first().weeks as List<*>
        assertEquals(listOf(1, 3, 5, 7), weeks.map { (it as Number).toInt() })
    }

    @Test
    fun `dsz one means even weeks`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(dsz = "1")), totalWeeks = 8, termCode = 4)
        val weeks = out.courses.first().slots.first().weeks as List<*>
        assertEquals(listOf(2, 4, 6, 8), weeks.map { (it as Number).toInt() })
    }

    @Test
    fun `no dsz means every week`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(dsz = null)), totalWeeks = 8, termCode = 4)
        assertEquals(null, out.courses.first().slots.first().weeks)
    }

    // ---------------------------------------------------------------- 星期与节次

    @Test
    fun `the weekday parses from a string, which is what the undergrad API returns`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(day = "3")), totalWeeks = 16, termCode = 4)
        assertEquals(3, out.courses.first().slots.first().dayOfWeek)
    }

    @Test
    fun `the weekday also parses from a bare number, which is what the grad API returns`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(day = 5)), totalWeeks = 16, termCode = 4)
        assertEquals(5, out.courses.first().slots.first().dayOfWeek)
    }

    @Test
    fun `periods are sorted so the slot runs from the earliest to the latest`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(periods = listOf("4", "2", "3"))), totalWeeks = 16, termCode = 4
        )
        val slot = out.courses.first().slots.first()
        assertEquals(2, slot.startPeriod)
        assertEquals(4, slot.endPeriod)
    }

    // ---------------------------------------------------------------- 跳过规则

    @Test
    fun `an unconfirmed undergrad row is still kept, because the weekday is a quoted string`() {
        // 上游 `if IsConfirmed == 0 { if DayNumber[0] != '"' return nil }`：
        // sfqd=0 只有在星期**不是**字符串时才丢。选课中的本科行星期是 `"3"`，照上游留下。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(confirmed = 0)), totalWeeks = 16, termCode = 4
        )
        assertEquals("上游不会因为 sfqd=0 就丢掉字符串星期的行", 1, out.courses.size)
        assertEquals(0, out.skipped)
    }

    @Test
    fun `a grad row is skipped because its weekday is a bare number`() {
        // 研究生数据没有 sfqd 字段（Go 零值 = 0）且 xqj 是裸数字 → 上游 return nil。
        // `sfqd` 缺失必须和 `sfqd == 0` 同等对待，用可空 Int 时 `(sfqd ?: 0)` 这个回退不能省。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(confirmed = null, day = 2)), totalWeeks = 16, termCode = 4
        )
        assertTrue("研究生行不该进课表", out.courses.isEmpty())
        assertEquals(1, out.skipped)
        assertTrue(out.skipReason!!.contains("未确认"))
    }

    @Test
    fun `a confirmed grad row with a bare weekday still gets through`() {
        // 有 sfqd=1 时上游根本不看星期是什么形态，裸数字照收。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(confirmed = 1, day = 5)), totalWeeks = 16, termCode = 4
        )
        assertEquals(1, out.courses.size)
        assertEquals(5, out.courses.first().slots.first().dayOfWeek)
    }

    @Test
    fun `a row whose term string holds none of the four seasons is skipped`() {
        // 上游 `if len(TermArrangements) == 0 { return nil }`：xq 里没有秋/冬/春/夏。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(term = "2024-2025")), totalWeeks = 16, termCode = 4
        )
        assertTrue(out.courses.isEmpty())
        assertTrue(out.skipReason!!.contains("学期无法识别"))
    }

    @Test
    fun `a row whose weekday cannot be read at all is skipped rather than crashing`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(day = "不是数字")), totalWeeks = 16, termCode = 4
        )
        assertTrue(out.courses.isEmpty())
        assertEquals(1, out.skipped)
    }

    @Test
    fun `a row with no periods is skipped`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(periods = emptyList())), totalWeeks = 16, termCode = 4
        )
        assertTrue(out.courses.isEmpty())
    }

    // ---------------------------------------------------------------- 合并与配色

    @Test
    fun `slots of the same course collapse into one course`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(
                row(periods = listOf("1"), day = "1"),
                row(periods = listOf("3"), day = "3")
            ),
            totalWeeks = 16, termCode = 4
        )
        assertEquals(1, out.courses.size)
        assertEquals(2, out.courses.first().slots.size)
    }

    @Test
    fun `different teachers of the same named course stay separate courses`() {
        // 自然键是「代码|名称|教师」，同一个「大学物理」两个老师是两门课。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(teacher = "王教授"), row(teacher = "李教授")),
            totalWeeks = 16, termCode = 4
        )
        assertEquals(2, out.courses.size)
    }

    @Test
    fun `colours come from the palette and are stable across runs`() {
        val first = ZjuScheduleMapper.toCourses(listOf(row()), totalWeeks = 16, termCode = 4)
        val second = ZjuScheduleMapper.toCourses(listOf(row()), totalWeeks = 16, termCode = 4)
        assertEquals(first.courses.first().colorHex, second.courses.first().colorHex)
        assertTrue(first.courses.first().colorHex!!.startsWith("#"))
    }

    // ---------------------------------------------------------------- 学期查询串

    @Test
    fun `the term query string is the one the registrar endpoint actually expects`() {
        // 形状不是数字，是 `1|秋` 这种带汉字的串。
        assertEquals("1|秋", ZjuTerms.queryOf(0))
        assertEquals("1|冬", ZjuTerms.queryOf(1))
        assertEquals("2|春", ZjuTerms.queryOf(4))
        assertEquals("2|夏", ZjuTerms.queryOf(5))
        assertEquals("1|短", ZjuTerms.queryOf(2))
        assertEquals("2|短", ZjuTerms.queryOf(6))
        assertEquals("", ZjuTerms.queryOf(99))
    }
}