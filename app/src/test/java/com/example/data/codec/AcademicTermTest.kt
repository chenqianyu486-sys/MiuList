package com.example.data.codec

import com.example.data.calendar.EpochDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学年学期：秋(1–8 周) + 冬(9–16 周) 拼成一个 16 周的学期。
 *
 * 日期全部取自 zju-ical `configs/config.json` 的真实值：
 * 秋 `2026-09-14 ~ 2026-11-08`、冬 `2026-11-09 ~ 2027-01-03`，
 * 两段首尾都落在周一/周日，正好各 8 周。
 */
class AcademicTermTest {

    /** `20270103` → epochDay。注意零填充，`LocalDate.parse` 不吃 `2027-1-3`。 */
    private fun day(yyyymmdd: Int): Long =
        EpochDays.parse("%04d-%02d-%02d".format(yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100))!!

    /** 真实配置的两条：秋与冬。 */
    private val realConfig = listOf(
        TermConfigBundle("2026-2027", 0, 20260914, 20261108, 1),
        TermConfigBundle("2026-2027", 1, 20261109, 20270103, 1)
    )

    @Test
    fun `秋与冬折成同一个 16 周的学年学期`() {
        val term = realConfig.academicTermOf("2026-2027", 0)!!

        assertEquals("2026-2027学年 秋冬 学期", term.label)
        assertEquals("秋冬", term.season)
        assertEquals(16, term.totalWeeks)
        assertEquals(day(20260914), term.startEpochDay)
        assertEquals(day(20270103), term.endEpochDay)
        assertEquals(2, term.halves.size)
    }

    /**
     * 传秋或传冬拿到的是**同一个**学期。
     *
     * 这是「学期以学年学期为单位」的核心：用户在教务网选的是「秋冬」，
     * 校历配置里却是秋、冬两条。指哪一半都不该改变这个学期的形状。
     */
    @Test
    fun `指向任意一半都得到同一个学期`() {
        val byAutumn = realConfig.academicTermOf("2026-2027", 0)!!
        val byWinter = realConfig.academicTermOf("2026-2027", 1)!!

        assertEquals(byAutumn.startEpochDay, byWinter.startEpochDay)
        assertEquals(byAutumn.endEpochDay, byWinter.endEpochDay)
        assertEquals(byAutumn.totalWeeks, byWinter.totalWeeks)
        assertEquals(byAutumn.label, byWinter.label)
    }

    /** 春夏同样两段，各 8 周：春 2/22–4/18、夏 4/19–6/13。 */
    private val springConfig = listOf(
        TermConfigBundle("2026-2027", 4, 20270222, 20270418, 1),
        TermConfigBundle("2026-2027", 5, 20270419, 20270613, 1)
    )

    /** 教务网取课表用第一半：秋冬查「1|秋」，春夏查「2|春」。 */
    @Test
    fun `查询串用第一半的学期代码`() {
        val autumn = requireNotNull(realConfig.academicTermOf("2026-2027", 1)) {
            "秋冬折不出来：${realConfig.halvesOfAcademicTerm("2026-2027", 1)}"
        }
        assertEquals(0, autumn.queryTerm)

        val spring = requireNotNull(springConfig.academicTermOf("2026-2027", 5)) {
            "春夏折不出来：${springConfig.halvesOfAcademicTerm("2026-2027", 5)}"
        }
        assertEquals(4, spring.queryTerm)
        assertEquals("春夏", spring.season)
    }

    /**
     * 冬的日期必须落在第 9–16 周。
     *
     * 这是整个改造要保住的东西：之前学期只算 8 周，冬那 8 周的日期全部越界，
     * 课表后半段整段空白。
     */
    @Test
    fun `冬的日期落在第 9 到 16 周`() {
        val term = realConfig.academicTermOf("2026-2027", 0)!!
        fun weekOf(epochDay: Long): Int =
            ((epochDay - term.firstMondayEpochDay) / 7L + 1L).toInt()

        assertEquals("秋的第一天是第 1 周", 1, weekOf(day(20260914)))
        assertEquals("秋的最后一天仍是第 8 周", 8, weekOf(day(20261108)))
        assertEquals("冬的第一天就是第 9 周", 9, weekOf(day(20261109)))
        assertEquals("冬的最后一天是第 16 周", 16, weekOf(day(20270103)))
    }

    /**
     * 只有秋、没配冬时**不能**自称秋冬学期。
     *
     * 文件里确实只有 8 周的信息，叫「秋冬」等于凭空断言了后半段的存在。
     */
    @Test
    fun `只有一半时不猜季节`() {
        val onlyAutumn = listOf(TermConfigBundle("2026-2027", 0, 20260914, 20261108, 1))
        val term = onlyAutumn.academicTermOf("2026-2027", 0)!!

        assertNull(term.season)
        assertEquals("2026-2027 秋学期", term.label)
        assertEquals(8, term.totalWeeks)
    }

    @Test
    fun `短学期独立成段 不与别的凑成学年学期`() {
        val configs = listOf(
            TermConfigBundle("2026-2027", 0, 20260914, 20261108, 1),
            TermConfigBundle("2026-2027", 1, 20261109, 20270103, 1),
            TermConfigBundle("2026-2027", 2, 20270111, 20270221, 1)
        )
        val shortTerm = configs.academicTermOf("2026-2027", 2)!!

        assertEquals(1, shortTerm.halves.size)
        assertNull(shortTerm.season)
        assertEquals("2026-2027 短学期A", shortTerm.label)
    }

    /**
     * 两半之间有缺口时，周次锚点仍取第一半，**不重置**。
     *
     * 如果按「冬自己算一个学期」或者把缺口当成一周，冬的第一天就会被推到第 10 周，
     * 后半学期的课整体错位。缺口只影响最后够不够得着，不影响前面的编号。
     */
    @Test
    fun `两半之间有缺口也不会让周次重置`() {
        val gapped = listOf(
            TermConfigBundle("2026-2027", 0, 20260914, 20261108, 1),
            TermConfigBundle("2026-2027", 1, 20261111, 20270105, 1)  // 11-09/10 缺两天
        )
        val term = gapped.academicTermOf("2026-2027", 0)!!
        fun weekOf(epochDay: Long): Int =
            ((epochDay - term.firstMondayEpochDay) / 7L + 1L).toInt()

        assertEquals(day(20260914), term.firstMondayEpochDay)
        assertEquals("缺口后的冬第一天仍在第 9 周", 9, weekOf(day(20261111)))
        assertTrue("学期要盖得住冬的最后一天", term.totalWeeks >= 16)
        assertTrue(term.contains(day(20270105)))
    }

    @Test
    fun `学年学期里没有任何一段时返回 null`() {
        assertNull(realConfig.academicTermOf("2025-2026", 0))
        assertNull(realConfig.academicTermOf("2026-2027", 4))
    }

    /** 日期坏掉的半学期不能被悄悄跳过 —— 少算 8 周比整个失败更危险。 */
    @Test
    fun `日期解析不出来的半学期让整段拼装失败`() {
        val broken = listOf(
            TermConfigBundle("2026-2027", 0, 20260914, 20261108, 1),
            TermConfigBundle("2026-2027", 1, null, 20270103, 1)
        )
        assertNull(broken.academicTermOf("2026-2027", 0))
    }
}
