package com.example.data.source.zju

import com.example.data.codec.DecodeResult
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.SlotBundle
import com.example.data.codec.academicTermOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一个学年学期要**查两半**。
 *
 * 教务网的 `xqm` 是按半学期分的：`1|秋` 只回 `xxq` 为「秋」和「秋冬」的课，
 * `xxq` 为「冬」的课只在 `1|冬` 里。实测（2026-10-13，`xq=1|秋`，19 条 `kbList`）
 * 那份响应里**每一条**都写「第1-8周」—— 冬那半学期的课根本没在里面。
 *
 * 下拉里选的是「秋冬」这一个 16 周的学期，只查前半等于后半学期整段空着，
 * 而界面上没有任何提示：这正是「无法正常解析冬学期课程」的成因。
 *
 * 全部离线：`ZjuCasClient` 的 `login` / `fetchSchedule` 都是 `open`，
 * 这里用一份按实测结构手写的响应把它替掉，不必出网。
 * 课程名 / 教师 / 教室一律虚构，夹具里不放真实学生的课表。
 */
class ZjuImportServiceTest {

    /** 按 `xqm` 返回不同内容，并记下实际发出过哪些查询。 */
    private class FakeCas(
        private val byQuery: Map<String, String>,
        private val calendar: String
    ) : ZjuCasClient() {
        val queries = mutableListOf<String>()

        override suspend fun login(studentId: String, password: String) = Unit

        override suspend fun fetchSchedule(academicYear: String, termQuery: String): String {
            queries += termQuery
            return byQuery[termQuery]
                ?: throw ZjuException("不该发出查询：$termQuery", ZjuFailureKind.TERM)
        }
    }

    /** 一门课。`weeks` 写进 `kcb` 第二段，形如 `秋冬{第1-8周|3节/周}`。 */
    private fun row(
        name: String,
        code: String,
        teacher: String = "王教授",
        where: String = "教二-301",
        weeks: String = "第1-8周",
        start: Any? = "1",
        length: Any? = "2",
        day: Any? = "1",
        dsz: String? = "2",
        term: String = "秋冬",
        xkkh: String? = null
    ) = ZjuClassRow(
        xkkh = xkkh ?: "(2026-2027-1)-$code-$start$day",
        kcb = "$name<br>$term{$weeks|${length ?: 1}节/周}<br>$teacher<br>$where" +
            "zwf2026年10月12日(13:25-15:05)zwf",
        xqj = day, djj = start, skcd = length, dsz = dsz, xxq = term, sfqd = "1"
    )

    private fun response(vararg rows: ZjuClassRow): String =
        buildString {
            append("""{"xzb":"","xy":"","kbList":[""")
            rows.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append(
                    """{"xkkh":${q(r.xkkh)},"kcb":${q(r.kcb)},"xqj":${q(r.xqj?.toString())},""" +
                        """"djj":${q(r.djj?.toString())},"skcd":${q(r.skcd?.toString())},""" +
                        """"dsz":${q(r.dsz)},"xxq":${q(r.xxq)},"sfqd":${q(r.sfqd?.toString())}}"""
                )
            }
            append("]}")
        }

    private fun q(v: String?): String = v?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"

    /** 内置兜底那份校历：2026-2027 秋冬，秋 9/14–11/8、冬 11/9–2027/1/3。 */
    private val calendar = """
        {
          "lastUpdated": 20260917,
          "termConfigs": [
            { "Year": "2026-2027", "Term": "秋", "Begin": 20260914, "End": 20261108, "FirstWeekNo": 1 },
            { "Year": "2026-2027", "Term": "冬", "Begin": 20261109, "End": 20270103, "FirstWeekNo": 1 }
          ],
          "classTerms": ["2026-2027:秋", "2026-2027:冬"],
          "tweaks": []
        }
    """.trimIndent()

    private fun service(cas: ZjuCasClient) =
        ZjuImportService(cas = cas, calendarSource = ZjuCalendarSource(bundledText = calendar))

    private suspend fun decode(cas: FakeCas): com.example.data.codec.ScheduleBundle {
        val json = service(cas).fetchScheduleJson("3190000000", "pw", ZjuImportService.TermOption("2026-2027", 0))
        val decoded = JsonScheduleCodec().decode(json)
        return (decoded as? DecodeResult.Success)?.bundle ?: error("解码失败：$decoded")
    }

    /**
     * 秋冬学期必须**两个查询都发出去**，少一个就是后半学期整段空着。
     *
     * 方法名里不能带 `|` —— 反引号名字会原样进 lambda 的 class 文件名，Windows 建不出来。
     */
    @Test
    fun `秋冬学期要同时查秋和冬两个 xqm`() = runBlocking {
        val cas = FakeCas(
            byQuery = mapOf(
                "1|秋" to response(row("概率论", "MATH201")),
                "1|冬" to response(row("量子力学", "PHYS101", term = "冬"))
            ),
            calendar = calendar
        )

        val bundle = decode(cas)

        assertEquals(listOf("1|秋", "1|冬"), cas.queries)
        assertTrue("两个查询都该记进来源说明", bundle.provenance.note!!.contains("1|秋 + 1|冬"))
        assertEquals(16, bundle.calendar!!.termConfigs.autumnTerm("2026-2027").totalWeeks)
    }

    /**
     * 冬那半学期的课（`xxq=冬`）要落在第 9–16 周。
     *
     * `kcb` 里写的是「第1-8周」，那是**子学期**口径，要按 `xxq` 平移到后半。
     * 这门课只出现在 `1|冬` 的响应里 —— 只查 `1|秋` 的话它整门都不会被拿到。
     */
    @Test
    fun `冬的课落在第 9 到 16 周`() = runBlocking {
        val cas = FakeCas(
            byQuery = mapOf(
                "1|秋" to response(row("概率论", "MATH201")),
                "1|冬" to response(row("量子力学", "PHYS101", term = "冬"))
            ),
            calendar = calendar
        )

        val bundle = decode(cas)
        val courses = bundle.timetable!!.courses
        assertEquals(2, courses.size)

        val winter = courses.single { it.name == "量子力学" }
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16), winter.slots.single().weekNumbers())

        // 前半那门 秋冬 的课占满 16 周
        val both = courses.single { it.name == "概率论" }
        assertEquals((1..16).toList(), both.slots.single().weekNumbers())
    }

    /**
     * 「秋冬」的课在两份响应里**逐字重复**一次，不能变成两门课 / 两个时段。
     *
     * 去重不能按 `xkkh`（选课课号带半学期序号，两边不一样），
     * 只能按决定映射结果的那几个字段，见 `rowIdentity`。
     */
    @Test
    fun `两半都返回的秋冬课只留一份`() = runBlocking {
        val shared = row("概率论", "MATH201", teacher = "吴建军", where = "紫金港西2-101")
        val cas = FakeCas(
            byQuery = mapOf(
                "1|秋" to response(shared, row("量子力学", "PHYS101", term = "冬")),
                // 同一门课、同一时段，但选课课号的半学期序号不同
                "1|冬" to response(
                    shared.copy(xkkh = "(2026-2027-1)-MATH201-11"),
                    row("量子力学", "PHYS101", term = "冬")
                )
            ),
            calendar = calendar
        )

        val bundle = decode(cas)
        val courses = bundle.timetable!!.courses

        assertEquals("同一门课不能出两次", 2, courses.size)
        assertEquals("同一时段不能重复", 1, courses.single { it.name == "概率论" }.slots.size)
        // 冬那门也只留一份
        assertEquals(1, courses.single { it.name == "量子力学" }.slots.size)
    }

    /** 单双周要在两半里分别展开：前半的奇周 + 后半的奇周。 */
    @Test
    fun `秋冬的单周课前后两半都保留单周口径`() = runBlocking {
        val cas = FakeCas(
            byQuery = mapOf(
                "1|秋" to response(row("概率论", "MATH201", dsz = "0")),
                "1|冬" to response(row("量子力学", "PHYS101", term = "冬", dsz = "0"))
            ),
            calendar = calendar
        )

        val bundle = decode(cas)
        val both = bundle.timetable!!.courses.single { it.name == "概率论" }
        assertEquals(
            "前半 1,3,5,7 + 后半 9,11,13,15 —— 平移量是偶数所以单双周口径不变",
            listOf(1, 3, 5, 7, 9, 11, 13, 15),
            both.slots.single().weekNumbers()
        )
    }
}

/**
 * 取某个时段的周号集合。
 *
 * 编码成 JSON 再解回来后数字全成了 `Double`（Moshi 对 `Any?` 的行为），
 * 直接 `as Int` 会 ClassCastException；生产代码那边由 `WeekSet.parse` 处理。
 */
private fun SlotBundle.weekNumbers(): List<Int> =
    (weeks as List<*>).map { (it as Number).toInt() }

/** 只为上面两条断言存在的小工具：按第一半取出折好的学年学期。 */
private fun List<com.example.data.codec.TermConfigBundle>.autumnTerm(year: String) =
    academicTermOf(year, 0)!!
