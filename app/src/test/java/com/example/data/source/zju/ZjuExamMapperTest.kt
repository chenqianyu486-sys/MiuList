package com.example.data.source.zju

import com.example.data.calendar.AppZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 教务网「个人考试查询」响应的解析。
 *
 * ## 为什么这些用例是手写的样本，而不是抓一次真响应
 *
 * 真实响应只有联网才拿得到，而这一层的价值恰恰全在**分支**上：
 * 座位表出了没出、日期定了没定、期中考用的是哪套字段。
 * 那些分支在真机上要等几个月才轮得到一次，用样本钉住才划算。
 *
 * ## 为什么要 Robolectric
 *
 * 解析用的是 `org.json`，而 `android.jar` 里那份是**桩**：直接调会抛
 * `RuntimeException("Stub!")`。而解析层把异常兜成「没有考试」，
 * 于是症状是「一条都解析不出来」而不是报错 —— 很容易被当成规则写错了。
 * Robolectric 提供的是真的 org.json。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZjuExamMapperTest {

    private fun at(year: Int, month: Int, day: Int, minute: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(AppZone.ZONE).toInstant().toEpochMilli() +
            minute * 60_000L

    private fun parseOne(item: String): ZjuExamMapper.ExamRow {
        val rows = ZjuExamMapper.parse("""{"items":[$item]}""")
        assertEquals("应当恰好解析出一场考试", 1, rows.size)
        return rows.single()
    }

    /** 时间、考场、座位都齐了 —— 数据齐全的那种情况。 */
    @Test
    fun `期末考试的时间考场座位齐全时全部解析出来`() {
        val row = parseOne(
            """
            {
              "xkkh": "(2026-2027-1)-MATH2432F-0083261-1",
              "kcmc": "高等数学(甲)II",
              "xf": "5.0",
              "kssj": "2027年01月12日 09:00-11:00",
              "jsmc": "紫金港东2-201",
              "zwxh": "12-3-15"
            }
            """.trimIndent()
        )

        assertEquals("高等数学(甲)II", row.courseName)
        assertEquals(ZjuExamMapper.ExamKindConstants.FINAL, row.kind)
        assertEquals(at(2027, 1, 12, 9 * 60), row.startAt)
        assertEquals(at(2027, 1, 12, 11 * 60), row.endAt)
        assertEquals(9 * 60, row.startMinute)
        assertEquals(11 * 60, row.endMinute)
        assertEquals("日期已知时不该留标签", "", row.dateLabel)
        assertEquals("紫金港东2-201", row.classroom)
        assertEquals("12-3-15", row.seat)
        assertEquals(5.0, row.credit, 0.001)
    }

    /**
     * 座位表还没出 —— 这是**常态**，不是异常。
     *
     * 学校先公布考试时间，座位与考场往往要晚几天甚至几周。
     * 这时要保留这场考试（时间是实打实的），只是考场与座位留空。
     */
    @Test
    fun `座位表还没出时保留考试并把考场与座位留空`() {
        val row = parseOne(
            """
            {
              "xkkh": "(2026-2027-1)-PHYS1001M-0012345-1",
              "kcmc": "大学物理",
              "xf": "3.0",
              "kssj": "2027-01-13 13:30-15:30"
            }
            """.trimIndent()
        )

        assertEquals("这场考试存在就该留着", "大学物理", row.courseName)
        assertEquals(at(2027, 1, 13, 13 * 60 + 30), row.startAt)
        assertEquals(13 * 60 + 30, row.startMinute)
        assertEquals("考场还没定就该是空串，而不是 null 也不是「未知」", "", row.classroom)
        assertEquals("座位还没定就该是空串", "", row.seat)
    }

    /**
     * 期中考与期末考用的是**两套**教室与座位字段。
     *
     * 串了座位号比没有座位号更糟：用户会拿着属于另一场考试的座位号去考场。
     */
    @Test
    fun `期中考用自己那套教室与座位字段而不是期末那套`() {
        // 一门课两场考试 → 解析出两条
        val rows = ZjuExamMapper.parse(
            """{"items":[{"xkkh":"(2026-2027-1)-INFO2001M-0033333-1","kcmc":"信号与系统","qzkssj":"2026年11月02日 09:00-11:00","qzjsmc":"紫金港东1-101","qzzwxh":"3排5座","kssj":"2027-01-15 09:00-11:00","jsmc":"紫金港东2-303","zwxh":"8排2座"}]}"""
        )
        assertEquals("同一门课的期中考与期末考是两条", 2, rows.size)

        val midterm = rows.single { it.kind == ZjuExamMapper.ExamKindConstants.MIDTERM }
        assertEquals("紫金港东1-101", midterm.classroom)
        assertEquals("3排5座", midterm.seat)

        val final = rows.single { it.kind == ZjuExamMapper.ExamKindConstants.FINAL }
        assertEquals("紫金港东2-303", final.classroom)
        assertEquals("8排2座", final.seat)
    }

    /**
     * 日期还没公布 —— 教务网只说「考试周第几天」。
     *
     * 这时候**时刻是有的**（学校总是先定几点考，再定哪天考），
     * 丢掉它就只剩一个课程名了。而学校给的那句标签要原样留着，
     * 不能自己编一套说法 —— 用户要拿它和教务网上的原文对上。
     */
    @Test
    fun `日期还没公布时保留时刻并保留学校给的标签`() {
        val row = parseOne(
            """
            {
              "xkkh": "(2026-2027-1)-MARX1001G-0007275-1",
              "kcmc": "形势与政策",
              "kssj": "秋季第3天(08:30-10:30)"
            }
            """.trimIndent()
        )

        assertEquals("日期未知时不该有绝对时刻", 0L, row.startAt)
        assertEquals("时刻仍要保住", 8 * 60 + 30, row.startMinute)
        assertEquals(10 * 60 + 30, row.endMinute)
        assertEquals("学校给的标签要原样留着", "秋季第3天", row.dateLabel)
    }

    /** 学校对这场考试什么也没说 → 不该造出一场「时间待定、地点待定」的考试占位。 */
    @Test
    fun `学校什么都没公布时不算一场考试`() {
        val rows = ZjuExamMapper.parse(
            """{"items":[{"xkkh":"(2026-2027-1)-XXXX0001M-0000001-1","kcmc":"某门课","xf":"1.0"}]}"""
        )
        assertTrue("什么也没公布就不该造出考试", rows.isEmpty())
    }

    /** 课程代码要与课表那边**同一套抽取规则**，否则考试认不出属于哪门课。 */
    @Test
    fun `课程代码从选课课号里抽出来与课表同一口径`() {
        val row = parseOne(
            """
            {
              "xkkh": "(2026-2027-1)-MATH2432F-0083261-1",
              "kcmc": "高等数学(甲)II",
              "kssj": "2027年01月12日 09:00-11:00"
            }
            """.trimIndent()
        )

        assertEquals("(2026-2027-1)-MATH2432F", row.courseCode)
        assertEquals("2026-2027 秋冬", row.termLabel)
        assertEquals(
            "课表那边必须用同一套规则",
            ZjuScheduleMapper.toCourses(
                rows = listOf(
                    ZjuClassRow(
                        xkkh = "(2026-2027-1)-MATH2432F-0083261-1",
                        kcb = "高等数学(甲)II<br>秋冬{第1-8周|3节/周}<br>吴建军<br>紫金港西2-101",
                        xqj = 1, djj = 1, skcd = 3, dsz = "2", xxq = "秋冬", sfqd = "1"
                    )
                ),
                totalWeeks = 16,
                termCode = 0
            ).courses.single().code,
            row.courseCode
        )
    }

    /** 顶层是数组而不是 `{"items":[…]}` 时也要认 —— 两种形态都出现过。 */
    @Test
    fun `响应是裸数组时也能解析`() {
        val rows = ZjuExamMapper.parse(
            """[{"xkkh":"(2026-2027-1)-MATH2432F-0083261-1","kcmc":"高数","kssj":"2027-01-12 09:00-11:00"}]"""
        )
        assertEquals(1, rows.size)
        assertEquals("高数", rows.single().courseName)
    }
}
