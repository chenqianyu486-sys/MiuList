package com.example.data.codec

import com.example.data.model.CategoryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自有 JSON 格式（schemaVersion = 2）。纯 JUnit。
 *
 * 这份格式同时是三种数据源之间的契约，也是 App 内浙大导入产出的东西，
 * 所以两头都要钉住：**自己导出的必须能自己导回来**，而**别人写的不能因为一个字段
 * 写得怪就整份作废**。
 *
 * v2 的形状是「单文件、顶层分块」：`calendar` / `timetable` / `categoryRules`。
 * 课程与学期绑定这件事落在 `timetable.termRef` 上。
 *
 * 注意这里刻意不测归一化：解析层不懂「1-16周(单)」怎么展开、也不懂
 * 「Begin + FirstWeekNo 怎么变成第一周周一」，那些是校验器与适配器的事。
 */
class JsonScheduleCodecTest {

    private val codec = JsonScheduleCodec()

    private fun decode(text: String): ScheduleBundle {
        val r = codec.decode(text)
        assertTrue("本该解码成功：$r", r is DecodeResult.Success)
        return (r as DecodeResult.Success).bundle
    }

    private fun failure(text: String): String {
        val r = codec.decode(text)
        assertTrue("本该解码失败：$r", r is DecodeResult.Failure)
        return (r as DecodeResult.Failure).message
    }

    // ---------------------------------------------------------------- 整档失败

    @Test
    fun `blank input is rejected outright`() {
        assertNotNull(failure(""))
        assertNotNull(failure("   \n  "))
    }

    @Test
    fun `malformed json is rejected with a readable message`() {
        val msg = failure("""{ "timetable": [ """)
        assertTrue("错误信息要能指出这是 JSON 语法问题：$msg", msg.contains("JSON"))
    }

    @Test
    fun `a json array at the top level is not a schedule document`() {
        assertNotNull(failure("[]"))
    }

    @Test
    fun `a newer schema version is refused instead of half-understood`() {
        val msg = failure("""{ "schemaVersion": 99, "timetable": { "courses": [] } }""")
        assertTrue("要明确告诉用户升应用：$msg", msg.contains("v99"))
        assertTrue(msg.contains("v${JsonScheduleCodec.SCHEMA_VERSION}"))
    }

    // ---------------------------------------------------------------- v1 兼容

    @Test
    fun `a v1 document still imports instead of telling the user to retype their timetable`() {
        val b = decode(
            """
            {
              "schemaVersion": 1,
              "semester": {
                "name": "2026-2027学年 秋季学期", "yearSpan": "2026-2027", "term": "秋季",
                "startDate": "2026-09-07", "firstMonday": "2026-09-07",
                "endDate": "2027-01-17", "totalWeeks": 20
              },
              "timetable": { "periodsPerDay": 2, "periods": [
                { "period": 1, "startTime": "08:00", "endTime": "08:45", "section": "上午" }
              ] },
              "noClassRanges": [ { "startDate": "2026-10-01", "endDate": "2026-10-03", "note": "国庆" } ],
              "makeupDays": [ { "date": "2026-10-10", "makeupForDayOfWeek": 1 } ],
              "courses": [ { "name": "高等数学", "slots": [ { "dayOfWeek": 1, "startPeriod": 1 } ] } ]
            }
            """.trimIndent()
        )
        val calendar = b.calendar!!
        assertEquals(1, calendar.termConfigs.size)
        assertEquals("2026-2027", calendar.termConfigs.first().year)
        assertEquals(20260907, calendar.termConfigs.first().begin)
        assertEquals(1, calendar.legacyNoClassRanges.size)
        assertEquals(1, calendar.legacyMakeupDays.size)
        assertEquals(1, calendar.periods.size)
        assertEquals(1, b.timetable!!.courses.size)
    }

    @Test
    fun `export always writes v2 even when the bundle came from a v1 file`() {
        val json = codec.encode(ScheduleBundle())
        assertTrue(json.contains("\"schemaVersion\":${JsonScheduleCodec.SCHEMA_VERSION}"))
    }

    // ---------------------------------------------------------------- 顶层分块

    @Test
    fun `a missing schema version is assumed to be current`() {
        val b = decode("""{ "timetable": { "courses": [] } }""")
        assertTrue(b.timetable!!.courses.isEmpty())
    }

    @Test
    fun `every whole section may be missing`() {
        val b = decode("{}")
        assertNull(b.calendar)
        assertNull(b.timetable)
        assertTrue(b.categoryRules.isEmpty())
        assertNotNull(b.provenance)
    }

    @Test
    fun `explicit nulls in a section do not blow up the decode`() {
        val b = decode("""{ "calendar": null, "timetable": null, "categoryRules": null }""")
        assertNull(b.calendar)
        assertNull(b.timetable)
    }

    @Test
    fun `unknown fields are ignored so a scraper can ship ahead of the app`() {
        val b = decode(
            """
            {
              "schemaVersion": 2,
              "roomBookingUrl": "https://example.invalid/x",
              "timetable": { "courses": [ {
                "name": "高等数学",
                "credits": 4,
                "slots": [{ "dayOfWeek": 1, "startPeriod": 1, "endPeriod": 2, "building": "教二" }]
              } ] }
            }
            """.trimIndent()
        )
        assertEquals(1, b.timetable!!.courses.size)
        assertEquals("高等数学", b.timetable!!.courses.first().name)
        assertEquals(1, b.timetable!!.courses.first().slots.size)
    }

    @Test
    fun `the calendar block keeps the upstream zju-ical field names verbatim`() {
        // 这些名字必须逐字段与 zju-ical 的 configs/config.json 一致，
        // 否则那份 config.json 就不能被当成一份合法的「只有校历的 v2 文档」直接导入。
        val b = decode(
            """
            { "schemaVersion": 2, "calendar": {
                "lastUpdated": 20250213,
                "termConfigs": [ { "Year": "2024-2025", "Term": 4,
                                   "Begin": 20250414, "End": 20250608, "FirstWeekNo": 1 } ],
                "classTerms": [ "2024-2025:4" ],
                "tweaks": [ { "TweakType": 0, "Description": "劳动节",
                              "From": 20250502, "To": 20250505 } ]
            } }
            """.trimIndent()
        )
        val cal = b.calendar!!
        assertEquals(20250213, cal.lastUpdated)
        val tc = cal.termConfigs.first()
        assertEquals("2024-2025", tc.year)
        assertEquals(4, tc.term)
        assertEquals(20250414, tc.begin)
        assertEquals(20250608, tc.end)
        assertEquals(1, tc.firstWeekNo)
        assertEquals(listOf("2024-2025:4"), cal.classTerms)
        assertEquals(0, cal.tweaks.first().tweakType)
        assertEquals("劳动节", cal.tweaks.first().description)
        assertEquals(20250502, cal.tweaks.first().from)
    }

    @Test
    fun `a course with no slots decodes as a course with no slots`() {
        val b = decode("""{ "timetable": { "courses": [ { "name": "待定选修" } ] } }""")
        assertEquals(1, b.timetable!!.courses.size)
        assertTrue(b.timetable!!.courses.first().slots.isEmpty())
    }

    // ---------------------------------------------------------------- weeks 的两种写法

    @Test
    fun `weeks accepts a compact string exactly as the registrar publishes it`() {
        val b = decode(
            """
            { "timetable": { "courses": [ { "name": "大学物理", "slots": [
              { "dayOfWeek": 2, "startPeriod": 3, "endPeriod": 4, "weeks": "1-16周(单)" }
            ] } ] } }
            """.trimIndent()
        )
        // 原样搬运，归一化留给校验器
        assertEquals("1-16周(单)", b.timetable!!.courses.first().slots.first().weeks)
    }

    @Test
    fun `weeks accepts an integer array`() {
        val b = decode(
            """
            { "timetable": { "courses": [ { "name": "大学物理", "slots": [
              { "dayOfWeek": 2, "startPeriod": 3, "endPeriod": 4, "weeks": [1,3,5,8] }
            ] } ] } }
            """.trimIndent()
        )
        val weeks = b.timetable!!.courses.first().slots.first().weeks as List<*>
        assertEquals(listOf(1, 3, 5, 8), weeks.map { (it as Number).toInt() })
    }

    @Test
    fun `a weeks field of an unexpected shape survives as text rather than crashing`() {
        val b = decode(
            """
            { "timetable": { "courses": [ { "name": "大学物理", "slots": [
              { "dayOfWeek": 2, "startPeriod": 3, "endPeriod": 4, "weeks": "看心情" }
            ] } ] } }
            """.trimIndent()
        )
        assertEquals("看心情", b.timetable!!.courses.first().slots.first().weeks)
    }

    @Test
    fun `a slot with weeks absent decodes to null, meaning every week`() {
        val b = decode(
            """{ "timetable": { "courses": [ { "name": "甲",
                 "slots": [ { "dayOfWeek": 1, "startPeriod": 1 } ] } ] } }"""
        )
        assertNull(b.timetable!!.courses.first().slots.first().weeks)
    }

    // ---------------------------------------------------------------- 往返

    @Test
    fun `export then import reproduces the schedule`() {
        val original = ScheduleBundle(
            provenance = ProvenanceBundle(source = "manual", note = "测试"),
            calendar = CalendarBundle(
                lastUpdated = 20250213,
                termConfigs = listOf(
                    TermConfigBundle("2026-2027", 0, 20260907, 20270117, 1)
                ),
                classTerms = listOf("2026-2027:0"),
                tweaks = listOf(
                    TweakBundle(0, "国庆", 20261001, 20261003),
                    TweakBundle(1, "补周一", 20261005, 20261010)
                ),
                periods = listOf(
                    PeriodBundle(1, "08:00", "08:45", "上午"),
                    PeriodBundle(2, "08:55", "09:40", "上午")
                )
            ),
            timetable = TimetableBlockBundle(
                termRef = TermRefBundle("2026-2027", 0),
                courses = listOf(
                    CourseBundle(
                        key = "math101|高等数学|王教授",
                        name = "高等数学",
                        code = "MATH101",
                        teacher = "王教授",
                        category = "公共基础",
                        classroom = "教二-301",
                        colorHex = "#93C5FD",
                        automation = AutomationBundle(
                            preStudy = true, postHomework = false, labReport = false,
                            review = true, defaultPostDueDays = 6,
                            preStudyMinutes = 30, postHomeworkMinutes = 60, labReportMinutes = 90
                        ),
                        slots = listOf(
                            SlotBundle(1, 1, 2, "教二-301", listOf(1, 3, 5)),
                            SlotBundle(3, 3, 4, "教二-105", listOf(2, 4, 6))
                        )
                    )
                )
            ),
            categoryRules = listOf(CategoryRule("公共基础", true, false, false, 6, 45))
        )

        val roundTripped = decode(codec.encode(original))

        val calBefore = original.calendar!!
        val calAfter = roundTripped.calendar!!
        assertEquals(calBefore.lastUpdated, calAfter.lastUpdated)
        assertEquals(calBefore.termConfigs, calAfter.termConfigs)
        assertEquals(calBefore.classTerms, calAfter.classTerms)
        assertEquals(calBefore.tweaks, calAfter.tweaks)
        assertEquals(calBefore.periods, calAfter.periods)
        assertEquals(original.timetable!!.termRef, roundTripped.timetable!!.termRef)
        assertEquals(original.categoryRules, roundTripped.categoryRules)

        val before = original.timetable!!.courses.first()
        val after = roundTripped.timetable!!.courses.first()
        assertEquals(before.key, after.key)
        assertEquals(before.name, after.name)
        assertEquals(before.code, after.code)
        assertEquals(before.teacher, after.teacher)
        assertEquals(before.category, after.category)
        assertEquals(before.classroom, after.classroom)
        assertEquals(before.colorHex, after.colorHex)
        assertEquals(before.automation, after.automation)
        assertEquals(2, after.slots.size)
        assertEquals(1, after.slots[0].dayOfWeek)
        assertEquals("教二-105", after.slots[1].classroom)
    }

    @Test
    fun `weeks survive the round trip as a canonical integer array`() {
        val original = ScheduleBundle(
            timetable = TimetableBlockBundle(
                courses = listOf(
                    CourseBundle(name = "甲", slots = listOf(SlotBundle(1, 1, 2, "", listOf(1, 3, 5, 8))))
                )
            )
        )
        val json = codec.encode(original)
        assertTrue("导出统一写整数数组，规范形式只有一种：$json", json.contains("[1,3,5,8]"))

        val after = decode(json).timetable!!.courses.first().slots.first().weeks as List<*>
        assertEquals(listOf(1, 3, 5, 8), after.map { (it as Number).toInt() })
    }

    @Test
    fun `the codec transports weeks without interpreting them`() {
        // 归一化是 ScheduleBundleValidator 的职责。codec 这一层刻意不懂
        // 「1-16周(单)」——只把它搬过去。
        val original = ScheduleBundle(
            timetable = TimetableBlockBundle(
                courses = listOf(
                    CourseBundle(name = "甲", slots = listOf(SlotBundle(1, 1, 2, "", "1-16周(单)")))
                )
            )
        )
        val json = codec.encode(original)
        assertTrue("原样搬运：$json", json.contains("1-16周(单)"))
        assertEquals("1-16周(单)", decode(json).timetable!!.courses.first().slots.first().weeks)
    }

    @Test
    fun `the exported document declares the current schema version`() {
        val json = codec.encode(ScheduleBundle())
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains(JsonScheduleCodec.SCHEMA_VERSION.toString()))
    }

    @Test
    fun `export stamps the generator so a re-imported file says where it came from`() {
        val json = codec.encode(ScheduleBundle())
        assertTrue(json.contains(JsonScheduleCodec.DEFAULT_GENERATOR))
    }

    @Test
    fun `an empty schedule still encodes to a document that decodes back to empty`() {
        val after = decode(codec.encode(ScheduleBundle()))
        assertNull(after.timetable)
        assertNull(after.calendar)
    }
}