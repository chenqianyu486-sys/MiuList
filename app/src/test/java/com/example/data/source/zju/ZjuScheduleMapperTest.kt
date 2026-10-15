package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * zdbk `kbList` → 课表块的映射。
 *
 * 样本字段名与形状取自 2026-10-13 对
 * `https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html` 的实抓响应，
 * 但课程名/教师/教室一律换成虚构值 —— 测试夹具里不放真实学生的课表。
 *
 * 这里钉住的是四处最容易写错、而且**错了只会安静地少课或排错课**的规则：
 *
 * 1. `kcb` 必须**先按 `zwf` 切、再按 `<br>` 切**，否则教室名后面会粘上调课说明；
 * 2. `kcb` 里的「第1-8周」是**子学期**口径，要按 `xxq` 平移成学期坐标；
 * 3. 平移之后，周次仍是 `kcb` 的区间与 `dsz` 单双周的**交集**，只看一层都会让课多出或少掉；
 * 4. `dsz == "0"` 是**单周**、`"1"` 是**双周**（不是直觉上的 0=每周）。
 */
class ZjuScheduleMapperTest {

    /**
     * 造一行课。`weeks` 写进 `kcb` 的第二段，形如 `秋冬{第1-8周|3节/周}`。
     */
    private fun row(
        name: String = "高等数学",
        code: String = "(2026-2027-1)-MATH101-1",
        teacher: String = "王教授",
        where: String = "教二-301",
        weeks: String = "第1-16周",
        start: Any? = "1",
        length: Any? = "2",
        day: Any? = "1",
        dsz: String? = "2",
        term: String? = "秋冬",
        tail: String = "zwf2026年10月12日(13:25-15:05)zwf"
    ) = ZjuClassRow(
        xkkh = code,
        kcb = "$name<br>$term{$weeks|${length ?: 1}节/周}<br>$teacher<br>$where$tail",
        xqj = day,
        djj = start,
        skcd = length,
        dsz = dsz,
        xxq = term,
        sfqd = "1"
    )

    // ---------------------------------------------------------------- kcb 切分

    @Test
    fun `kcb splits into name teacher and classroom`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row()), totalWeeks = 16, termCode = 0)
        val c = out.courses.first()
        assertEquals("高等数学", c.name)
        assertEquals("王教授", c.teacher)
        assertEquals("教二-301", c.slots.first().classroom)
    }

    @Test
    fun `the trailing zwf makeup note never reaches the classroom`() {
        // 这条最容易漏：`kcb` 末尾缀着教学周/调课说明，
        // 不切掉的话教室名会变成「教二-301zwf2026年10月12日(13:25-15:05)zwf」。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(tail = "zwf2026年11月03日(13:25-15:05)zwf2026年11月24日(13:25-15:05)zwf")),
            totalWeeks = 16, termCode = 0
        )
        val classroom = out.courses.first().slots.first().classroom.orEmpty()
        assertEquals("教二-301", classroom)
        assertTrue("教室名不该含 zwf：$classroom", !classroom.contains("zwf"))
    }

    @Test
    fun `a course whose kcb has no teacher segment still parses`() {
        // 上游对第 3 段判空是有理由的：直接 `[3]` 会越界。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(teacher = "", weeks = "第1-16周")), totalWeeks = 16, termCode = 0
        )
        assertEquals(1, out.courses.size)
    }

    // ---------------------------------------------------------------- 周次

    @Test
    fun `秋冬两周期的课铺满 1-16 周 而不是 1-8 周`() {
        // 本次修掉的错：实抓响应里秋冬课也一律写「第1-8周」，
        // 照字面取 1..8 会让它们在第 9 周集体消失，界面上却看不出任何异常。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周")), totalWeeks = 16, termCode = 0
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals((1..16).toList(), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `秋学期的课只占前半学期`() {
        // 1–8 周就是秋，**不该**被拉长成 16 周。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周", term = "秋")), totalWeeks = 16, termCode = 0
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals((1..8).toList(), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `冬学期的课落在第 9 到 16 周`() {
        // 子学期口径的「第1-8周」要整体 +8，不能直接当学期周次用。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周", term = "冬")), totalWeeks = 16, termCode = 1
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals((9..16).toList(), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `春夏两周期的课同样铺满 1-16 周`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周", term = "春夏")), totalWeeks = 16, termCode = 4
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals((1..16).toList(), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `odd weeks win over the declared range and are intersected with it`() {
        // 第1-8周 且 单周 → 秋冬学期的 1,3,5,7 与 9,11,13,15。
        // 平移量 8 是偶数，单双周口径跨子学期不变。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周", dsz = "0")), totalWeeks = 16, termCode = 0
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `even weeks mean dsz one which is the reverse of the obvious reading`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周", dsz = "1")), totalWeeks = 16, termCode = 0
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals(
            listOf(2, 4, 6, 8, 10, 12, 14, 16),
            (weeks as List<*>).map { (it as Number).toInt() }
        )
    }

    @Test
    fun `只有单双周没有周次说明时 也要按子学期铺开`() {
        // 没有「第1-8周」可依时，秋学期的单周课只能铺前半学期，
        // 不能因为手里有 16 周就铺满整个学期。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "无", term = "秋", dsz = "0")), totalWeeks = 16, termCode = 0
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals(listOf(1, 3, 5, 7), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `学期本身只有 8 周时不平移`() {
        // 上游 config.json 把春夏拆成两段时会出现 8 周的学期配置。
        // 这种情况下导入的这一期**就是**那个子学期，再 +8 会把课全推到学期外。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-8周", term = "夏")), totalWeeks = 8, termCode = 5
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals((1..8).toList(), (weeks as List<*>).map { (it as Number).toInt() })
    }

    @Test
    fun `an enumerated week list such as 1,3,5 is honoured`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1,3,5,7周")), totalWeeks = 16, termCode = 0
        )
        val weeks = out.courses.first().slots.first().weeks
        assertEquals(
            listOf(1, 3, 5, 7, 9, 11, 13, 15),
            (weeks as List<*>).map { (it as Number).toInt() }
        )
    }

    @Test
    fun `an unparseable week spec falls back to parity rather than dropping the course`() {
        // 宁可可解析性差，也不能整门课消失 —— 少一节课用户看得出来，丢一门课更糟。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(weeks = "第1-16周单双周", dsz = "0")), totalWeeks = 16, termCode = 0
        )
        assertEquals(1, out.courses.size)
    }

    // ---------------------------------------------------------------- 星期与节次

    @Test
    fun `the weekday parses from a string which is what the registrar returns`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(day = "3")), totalWeeks = 16, termCode = 0)
        assertEquals(3, out.courses.first().slots.first().dayOfWeek)
    }

    @Test
    fun `the weekday also parses from a bare number`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(day = 5)), totalWeeks = 16, termCode = 0)
        assertEquals(5, out.courses.first().slots.first().dayOfWeek)
    }

    @Test
    fun `the last period is start plus length minus one`() {
        // 上游把 skcd 当「长度」而不是「末节」，所以 end 是开区间。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(start = "3", length = "3")), totalWeeks = 16, termCode = 0
        )
        val slot = out.courses.first().slots.first()
        assertEquals(3, slot.startPeriod)
        assertEquals(5, slot.endPeriod)
    }

    @Test
    fun `a single period course spans exactly one period`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(start = "6", length = "1")), totalWeeks = 16, termCode = 0
        )
        val slot = out.courses.first().slots.first()
        assertEquals(6, slot.startPeriod)
        assertEquals(6, slot.endPeriod)
    }

    // ---------------------------------------------------------------- 跳过规则

    @Test
    fun `a row whose term holds none of the four seasons is skipped`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(term = "")), totalWeeks = 16, termCode = 0
        )
        assertTrue(out.courses.isEmpty())
        assertEquals(1, out.skipped)
        assertTrue(out.skipReason!!.contains("学期无法识别"))
    }

    @Test
    fun `a row whose weekday cannot be read is skipped rather than crashing`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(day = "不是数字")), totalWeeks = 16, termCode = 0
        )
        assertTrue(out.courses.isEmpty())
        assertEquals(1, out.skipped)
    }

    @Test
    fun `a weekday outside one to seven is skipped`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(day = "0")), totalWeeks = 16, termCode = 0)
        assertTrue(out.courses.isEmpty())
        assertTrue(out.skipReason!!.contains("星期无法解析"))
    }

    @Test
    fun `a row with no start period is skipped`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(start = null)), totalWeeks = 16, termCode = 0
        )
        assertTrue(out.courses.isEmpty())
        assertTrue(out.skipReason!!.contains("节次无法解析"))
    }

    @Test
    fun `an empty course name is skipped`() {
        val out = ZjuScheduleMapper.toCourses(listOf(row(name = "")), totalWeeks = 16, termCode = 0)
        assertTrue(out.courses.isEmpty())
    }

    // ---------------------------------------------------------------- 合并与配色

    @Test
    fun `slots of the same course collapse into one course`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(day = "1", start = "1"), row(day = "3", start = "3")),
            totalWeeks = 16, termCode = 0
        )
        assertEquals(1, out.courses.size)
        assertEquals(2, out.courses.first().slots.size)
    }

    @Test
    fun `different teachers of the same named course stay separate courses`() {
        // 自然键是「代码|名称|教师」，同一个「大学物理」两个老师是两门课。
        val out = ZjuScheduleMapper.toCourses(
            listOf(
                row(teacher = "王教授", code = "A"),
                row(teacher = "李教授", code = "B")
            ),
            totalWeeks = 16, termCode = 0
        )
        assertEquals(2, out.courses.size)
    }

    @Test
    fun `同一门课被教务系统按班拆成多条课号时 应合并成一门`() {
        // 实测：`(2026-2027-1)-ISEE2020M-0088111-1` 与 `-1A` 是同一门课的两个时段组，
        // 尾号多一个字母。不做归并的话界面上同一门课会出现两次、各带一半时段。
        val out = ZjuScheduleMapper.toCourses(
            listOf(
                row(name = "智能物联网系统设计", code = "(2026-2027-1)-ISEE2020M-0088111-1",
                    teacher = "楼东武", start = "6", length = "1"),
                row(name = "智能物联网系统设计", code = "(2026-2027-1)-ISEE2020M-0088111-1A",
                    teacher = "楼东武", start = "9", length = "2")
            ),
            totalWeeks = 16, termCode = 0
        )
        assertEquals("同一门课不该被拆成两门", 1, out.courses.size)
        assertEquals(2, out.courses.first().slots.size)
    }

    @Test
    fun `课程代码相同但教师不同 仍是两门课`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(
                row(teacher = "王教授", code = "(2026-2027-1)-MATH101-008-1"),
                row(teacher = "李教授", code = "(2026-2027-1)-MATH101-009-1")
            ),
            totalWeeks = 16, termCode = 0
        )
        assertEquals(2, out.courses.size)
    }

    @Test
    fun `课程代码不同的同名课仍是两门课`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(
                row(teacher = "王教授", code = "(2026-2027-1)-ISEE2017M-0019025-1"),
                row(teacher = "王教授", code = "(2026-2027-1)-ISEE2019M-0020061-1")
            ),
            totalWeeks = 16, termCode = 0
        )
        assertEquals(2, out.courses.size)
    }

    @Test
    fun `课号格式不认识时退回整串 而不是切错位置`() {
        // 上游那个 xkkh[:22] 的下标是按 `(2026-2027-1)` 正好 13 字符算的，
        // 学年写法一变就会错位。这里必须能安全退回整串。
        val odd = row(code = "SOMETHING-ELSE")
        val out = ZjuScheduleMapper.toCourses(listOf(odd), totalWeeks = 16, termCode = 0)
        assertEquals(1, out.courses.size)
        assertEquals("SOMETHING-ELSE", out.courses.first().code)
    }

    @Test
    fun `colours come from the palette and are stable across runs`() {
        val first = ZjuScheduleMapper.toCourses(listOf(row()), totalWeeks = 16, termCode = 0)
        val second = ZjuScheduleMapper.toCourses(listOf(row()), totalWeeks = 16, termCode = 0)
        assertEquals(first.courses.first().colorHex, second.courses.first().colorHex)
        assertTrue(first.courses.first().colorHex!!.startsWith("#"))
    }

    @Test
    fun `an empty input yields an empty result and no skip noise`() {
        val out = ZjuScheduleMapper.toCourses(emptyList(), totalWeeks = 16, termCode = 0)
        assertTrue(out.courses.isEmpty())
        assertEquals(0, out.skipped)
        assertNull(out.skipReason)
    }

    // ---------------------------------------------------------------- 类别判定

    @Test
    fun `新导入的课默认落公共基础`() {
        // 教务网不给类别，默认值不该替用户断言「这是专业课」。
        val out = ZjuScheduleMapper.toCourses(listOf(row(name = "高等数学")), totalWeeks = 16, termCode = 0)
        assertEquals("公共基础", out.courses.first().category)
    }

    @Test
    fun `名字带实验的课归入实验实践`() {
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(name = "大学物理实验")), totalWeeks = 16, termCode = 0
        )
        assertEquals("实验实践", out.courses.first().category)
    }

    @Test
    fun `实训 实践 实习 同样归入实验实践`() {
        listOf("程序设计实训", "工程实践", "生产实习").forEach { name ->
            val out = ZjuScheduleMapper.toCourses(listOf(row(name = name)), totalWeeks = 16, termCode = 0)
            assertEquals("$name 应该进实验实践", "实验实践", out.courses.first().category)
        }
    }

    @Test
    fun `解析层只改类别 不替用户打开实验报告开关`() {
        // 实验报告由「实验实践」的类别规则兜底。解析层写死 true 等于凭空
        // 决定这门课要产出多少条待办 —— 同名带「实验」的课未必真交实验报告。
        val out = ZjuScheduleMapper.toCourses(
            listOf(row(name = "大学物理实验")), totalWeeks = 16, termCode = 0
        )
        val c = out.courses.first()
        assertEquals("实验实践", c.category)
        assertEquals(false, c.automation?.labReport)
    }

    // ---------------------------------------------------------------- 学期查询串

    @Test
    fun `the term query string is the one the zdbk endpoint actually expects`() {
        // 与上游 `course/convert.py:ugrsClassTermToQueryString` 逐字一致。
        // 注意**没有** `1|秋冬` 这种值——秋冬是两个独立学期（0 秋 / 1 冬）。
        assertEquals("1|秋", ZjuTerms.queryOf(0))
        assertEquals("1|冬", ZjuTerms.queryOf(1))
        assertEquals("2|春", ZjuTerms.queryOf(4))
        assertEquals("2|夏", ZjuTerms.queryOf(5))
        assertEquals("1|短", ZjuTerms.queryOf(2))
        assertEquals("2|短", ZjuTerms.queryOf(6))
        assertEquals("", ZjuTerms.queryOf(99))
    }
}
