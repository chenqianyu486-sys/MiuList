package com.example.data.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseSlotWeekEntity
import com.example.data.local.TaskEntity
import com.example.data.model.SlotWithWeeks
import com.example.data.timetable.PeriodTable
import com.example.data.calendar.EpochDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入 / 导出编排。Robolectric + 真实 Room。
 *
 * 这一层要守住的是**数据完整性的承诺**：
 * - 预览绝不写库（用户没点确认之前，DB 必须一动不动）
 * - 提交是单事务，失败不留半截状态
 * - MERGE 不碰文件里没提到的课与任何待办
 * - REPLACE 明确清空，且清空范围与预览里说的对得上
 * - 导出 → 导入闭环：换机备份靠的就是这条
 */
@RunWith(RobolectricTestRunner::class)
// 用 SDK 34 而不是工程的 targetSdk 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建环境固定是 JDK 17（AGP 也要求 17）。这里验的是导入编排与事务行为，
// 与 API 36 的新增行为无关，降到 34 不影响结论。
@Config(sdk = [34])
class ScheduleTransferRobolectricTest {

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Context>())

    /** 每个测试从空库开始，免得 Room 的单例在方法之间串状态。 */
    private suspend fun reset(c: AppContainer) {
        c.courseRepository.clearAllCourses()
        c.calendarRepository.saveCalendar(
            semester = null,
            periods = emptyList(),
            noClass = emptyList(),
            makeup = emptyList(),
            replacePeriods = false,
            replaceNoClass = true,
            replaceMakeup = true
        )
        c.settingsRepository.replaceAll(emptyList())
    }

    private suspend fun seedCourse(
        c: AppContainer,
        name: String,
        code: String = "",
        teacher: String = "王教授",
        weeks: Set<Int> = emptySet(),
        colorHex: String = "#93C5FD"
    ): Long = c.courseRepository.saveCourseWithSlots(
        CourseEntity(
            name = name, code = code, category = "公共基础", teacher = teacher,
            classroom = "教二-301", colorHex = colorHex
        ),
        listOf(
            SlotWithWeeks(
                slot = CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
                weeks = weeks
            )
        )
    )

    private suspend fun seedTask(c: AppContainer, courseId: Long, title: String) {
        c.database.taskDao().insertTask(
            TaskEntity(
                courseId = courseId, courseName = "高等数学", courseCategory = "公共基础",
                courseColorHex = "#93C5FD", title = title, taskType = "POST_CLASS",
                dueDateTimestamp = 1L, dueDateString = "2026-10-12 23:59"
            )
        )
    }

    /**
     * 一份 v2 文档。`calendar` 与 `termRef` 默认留空 ——
     * 「只带课程、不带校历」是最常见的一种形状，必须默认能导进来。
     */
private fun document(courses: String, calendar: String = "", termRef: String = ""): String {
        val timetableBody = listOf(termRef, """"courses": [ $courses ]""")
            .filter { it.isNotBlank() }
            .joinToString(", ")
        return """
        {
          "schemaVersion": 2,
          "provenance": { "source": "manual", "generator": "test" },
          "calendar": { $calendar },
          "timetable": { $timetableBody }
        }
    """.trimIndent()
    }

    /** 一期秋学期，2026-09-07 起、2027-01-24 止，正好 20 周（首周周一算第 1 周）。 */
    private fun autumnTerm() = """
        "termConfigs": [ { "Year": "2026-2027", "Term": 0,
            "Begin": 20260907, "End": 20270124, "FirstWeekNo": 1 } ]
    """.trimIndent()

    private val AUTUMN_TERM_REF = """"termRef": { "Year": "2026-2027", "Term": 0 }"""

    private fun courseJson(name: String, code: String = "", weeks: String = "", colorHex: String = "#93C5FD") = """
        {
          "name": "$name", "code": "$code", "teacher": "王教授",
          "category": "公共基础", "classroom": "教二-301", "colorHex": "$colorHex",
          "slots": [ { "dayOfWeek": 1, "startPeriod": 1, "endPeriod": 2 $weeks } ]
        }
    """.trimIndent()

    // ---------------------------------------------------------------- 预览不写库

    @Test
    fun `previewing an import writes absolutely nothing`() = runBlocking {
        val c = container()
        reset(c)
        val before = seedCourse(c, "原有课程")

        val result = c.importService.preview(document(courseJson("新课程")), ImportStrategy.MERGE)
        assertTrue(result is PreviewResult.Ready)

        assertEquals("预览阶段一行都不该写进去", 1, c.courseRepository.countCourses())
        val courses = c.courseRepository.getAllCoursesOnce()
        assertEquals("原有课程", courses.single().name)
        assertEquals(1, c.courseRepository.getSlotsForCourseSync(before).size)
    }

    @Test
    fun `a malformed document fails before touching the database`() = runBlocking {
        val c = container()
        reset(c)
        seedCourse(c, "原有课程")

        val result = c.importService.preview("{ not json", ImportStrategy.MERGE)
        assertTrue("坏文件应当停在预览阶段", result is PreviewResult.Failed)
        assertEquals(1, c.courseRepository.countCourses())
    }

    @Test
    fun `a document with an impossible semester is refused as a whole`() = runBlocking {
        val c = container()
        reset(c)
        seedCourse(c, "原有课程")

        val result = c.importService.preview(
            document(
                courseJson("新课程"),
                // 起始晚于结束：自相矛盾，没有任何可以推定的取值
                calendar = """"termConfigs": [ { "Year": "2026-2027", "Term": 0,
                    "Begin": 20270110, "End": 20260901, "FirstWeekNo": 1 } ]"""
            ),
            ImportStrategy.MERGE
        )
        assertTrue("$result", result is PreviewResult.Failed)
        assertEquals(1, c.courseRepository.countCourses())
    }

    @Test
    fun `a document with courses only and no calendar at all still imports`() = runBlocking {
        // 校历整块缺失是**降级**，不是失败。把「没写学期」和「学期写错了」都当成失败的话，
        // 最常见的一种文件会导不进来 —— 这正是「只带一份课表」的用户。
        val c = container()
        reset(c)
        seedCourse(c, "原有课程")

        val result = c.importService.preview(document(courseJson("新课程")), ImportStrategy.MERGE)
        assertTrue("$result", result is PreviewResult.Ready)
        val report = c.importService.commit(result as PreviewResult.Ready)
        assertEquals(1, report.inserted)
        assertNull(c.calendarRepository.semesterOnce())
    }

    // ---------------------------------------------------------------- 预览内容

    @Test
    fun `the preview separates new courses from ones that will be overwritten`() = runBlocking {
        val c = container()
        reset(c)
        seedCourse(c, "高等数学", code = "MATH101")

        val result = c.importService.preview(
            document(courseJson("高等数学", code = "MATH101") + "," + courseJson("线性代数")),
            ImportStrategy.MERGE
        ) as PreviewResult.Ready

        assertEquals(2, result.preview.fileCourseCount)
        assertEquals(1, result.preview.newCourses.size)
        assertEquals("线性代数", result.preview.newCourses.first().name)
        assertEquals(1, result.preview.updatedCourses.size)
        assertEquals("高等数学", result.preview.updatedCourses.first().first)
        assertEquals(1, result.preview.existingCourseCount)
    }

    @Test
    fun `the preview reports calendar changes so the user knows before confirming`() = runBlocking {
        val c = container()
        reset(c)

        val result = c.importService.preview(
            document(
                courseJson("高等数学"),
                // v2 的规范形态就是 tweaks：0 = 停课区间，1 = 那天上 From 的课
                calendar = autumnTerm() + "," + """
                "tweaks": [
                    { "TweakType": 0, "Description": "国庆", "From": 20261001, "To": 20261003 },
                    { "TweakType": 1, "Description": "补周一", "From": 20261005, "To": 20261010 }
                ]""",
                termRef = AUTUMN_TERM_REF
            ),
            ImportStrategy.MERGE
        ) as PreviewResult.Ready

        assertTrue(result.preview.semesterChanged)
        assertTrue(result.preview.hasCalendarChange)
        assertNotNull(result.preview.semesterSummary)
        assertEquals("2026-10-01..03 三天停课", 3, result.preview.noClassDayCount)
        assertEquals(1, result.preview.makeupDayCount)
    }

    // ---------------------------------------------------------------- MERGE

    @Test
    fun `merge adds new courses and leaves untold courses and tasks alone`() = runBlocking {
        val c = container()
        reset(c)
        val keep = seedCourse(c, "原有课程")
        seedTask(c, keep, "原有待办")

        val preview = c.importService.preview(document(courseJson("新课程")), ImportStrategy.MERGE)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(1, report.inserted)
        assertEquals(0, report.updated)
        assertEquals("MERGE 不该删任何东西", 0, report.removed)
        assertEquals(2, c.courseRepository.countCourses())
        assertEquals("文件没提到的课的待办必须原样留着", 1, c.database.taskDao().countTasks())
    }

    @Test
    fun `merge matches by natural key and replaces that course's slots`() = runBlocking {
        val c = container()
        reset(c)
        val id = seedCourse(c, "高等数学", code = "MATH101", weeks = setOf(1, 2, 3))

        val preview = c.importService.preview(
            document(courseJson("高等数学", code = "MATH101", weeks = """, "weeks": [5, 7]""")),
            ImportStrategy.MERGE
        )
        val report = c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(0, report.inserted)
        assertEquals(1, report.updated)
        assertEquals("按自然键匹配，不该变成两门课", 1, c.courseRepository.countCourses())

        val slots = c.courseRepository.getSlotsForCourseSync(id)
        assertEquals(1, slots.size)
        assertEquals(
            "旧的开课周次必须被整段替换，不能残留",
            setOf(5, 7),
            c.courseRepository.getWeeksBySlotIdOnce()[slots.first().id]
        )
    }

    @Test
    fun `merge twice with the same file is idempotent`() = runBlocking {
        val c = container()
        reset(c)
        val text = document(courseJson("高等数学", code = "MATH101", weeks = """, "weeks": [1, 3]"""))

        c.importService.commit(c.importService.preview(text, ImportStrategy.MERGE) as PreviewResult.Ready)
        val first = c.courseRepository.countCourses()
        c.importService.commit(c.importService.preview(text, ImportStrategy.MERGE) as PreviewResult.Ready)

        assertEquals("重复导入同一份文件不该越导越多", first, c.courseRepository.countCourses())
        assertEquals(1, c.courseRepository.countCourses())
    }

    @Test
    fun `importing a semester clamps weeks of courses the file never mentions`() = runBlocking {
        val c = container()
        reset(c)
        // 学期没配时随手填到第 30 周，这在改造前是允许的
        val untouched = seedCourse(c, "原有课程", weeks = setOf(1, 10, 30))

        val preview = c.importService.preview(
            document(
                courseJson("新课程"),
                // 16 周：原来随手填到第 30 周的越界周次要在这之后被裁掉
                calendar = """"termConfigs": [ { "Year": "2026-2027", "Term": 0,
                    "Begin": 20260907, "End": 20261227, "FirstWeekNo": 1 } ]""",
                termRef = AUTUMN_TERM_REF
            ),
            ImportStrategy.MERGE
        )
        c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(16, c.calendarRepository.semesterOnce()!!.totalWeeks)
        val slotId = c.courseRepository.getSlotsForCourseSync(untouched).first().id
        assertEquals(
            "学期一变短，库里所有时段的越界周次都要跟着裁掉，不只是文件里那几门",
            setOf(1, 10),
            c.courseRepository.getWeeksBySlotIdOnce()[slotId]
        )
        assertEquals("裁剪不该动课程本身", 2, c.courseRepository.countCourses())
    }

    // ---------------------------------------------------------------- REPLACE

    @Test
    fun `replace wipes the old schedule and its tasks`() = runBlocking {
        val c = container()
        reset(c)
        val old = seedCourse(c, "原有课程")
        seedTask(c, old, "原有待办")

        val preview = c.importService.preview(document(courseJson("新课程")), ImportStrategy.REPLACE)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(1, report.removed)
        assertEquals(1, c.courseRepository.countCourses())
        assertEquals("新课程", c.courseRepository.getAllCoursesOnce().first().name)
        assertEquals("REPLACE 会删掉全部待办，预览里也是这么说的", 0, c.database.taskDao().countTasks())
    }

    @Test
    fun `replace clears the calendar when the file carries none`() = runBlocking {
        val c = container()
        reset(c)
        c.calendarRepository.saveCalendar(
            semester = com.example.data.local.SemesterEntity(
                name = "旧学期", startEpochDay = 0L, firstMondayEpochDay = 0L,
                endEpochDay = 100L, totalWeeks = 20
            ),
            periods = emptyList(), noClass = emptyList(), makeup = emptyList(),
            replacePeriods = false, replaceNoClass = false, replaceMakeup = false
        )
        assertNotNull(c.calendarRepository.semesterOnce())

        val preview = c.importService.preview(document(courseJson("新课程")), ImportStrategy.REPLACE)
        c.importService.commit(preview as PreviewResult.Ready)

        assertNull("REPLACE 下文件没带校历 = 清空为未配置", c.calendarRepository.semesterOnce())
    }

    @Test
    fun `replace keeps the timetable when the file does not carry one`() = runBlocking {
        val c = container()
        reset(c)
        // 显式播种，别指望 AppContainer 初始化时那次异步播种已经跑完 ——
        // 同类用例共用一个库文件，谁先跑会让这条断言时灵时不灵。
        c.calendarRepository.ensureDefaultPeriods()
        val seeded = c.calendarRepository.periodsOnce()
        assertEquals("前置条件：内置作息已就位", PeriodTable.ZJU_PERIODS.size, seeded.size)

        val preview = c.importService.preview(document(courseJson("新课程")), ImportStrategy.REPLACE)
        c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(
            "文件没带作息表就不该动它，否则课表栅格会被清空",
            PeriodTable.ZJU_PERIODS.size,
            c.calendarRepository.periodsOnce().size
        )
    }

    // ---------------------------------------------------------------- 导出 → 导入闭环

    @Test
    fun `a full export re-imports into an identical schedule`() = runBlocking {
        val source = container()
        reset(source)
        val id = seedCourse(source, "高等数学", code = "MATH101", weeks = setOf(1, 3, 5))
        // 2026-09-07 到 2027-01-24 正好 20 周：导出会把 Begin 写成第一周周一，
        // 再按 Begin + FirstWeekNo 反推，总周数必须原样回来。
        source.calendarRepository.saveCalendar(
            semester = com.example.data.local.SemesterEntity(
                name = "2026-2027学年 秋季学期",
                startEpochDay = EpochDays.parse("2026-09-07")!!,
                firstMondayEpochDay = EpochDays.parse("2026-09-07")!!,
                endEpochDay = EpochDays.parse("2027-01-24")!!,
                totalWeeks = 20
            ),
            periods = emptyList(), noClass = emptyList(), makeup = emptyList(),
            replacePeriods = false, replaceNoClass = false, replaceMakeup = false
        )
        assertNotNull(id)
        val json = source.exportService.exportJson()

        // 导出时写的一律是整数数组，不应该出现紧凑字符串
        assertTrue("导出是规范形式：$json", !json.contains("周("))

        // 换一台「设备」导入
        val target = container()
        reset(target)
        val preview = target.importService.preview(json, ImportStrategy.REPLACE)
        assertTrue("自己导出的文件必须能被自己读回来：$preview", preview is PreviewResult.Ready)
        val report = target.importService.commit(preview as PreviewResult.Ready)

        assertEquals(1, report.inserted)
        assertEquals(0, report.issues.size)

        val course = target.courseRepository.getAllCoursesOnce().single()
        assertEquals("高等数学", course.name)
        assertEquals("MATH101", course.code)
        assertEquals("#93C5FD", course.colorHex)

        val slotId = target.courseRepository.getSlotsForCourseSync(course.id).single().id
        assertEquals(
            "开课周次必须原样回来",
            setOf(1, 3, 5),
            target.courseRepository.getWeeksBySlotIdOnce()[slotId]
        )
        assertEquals(20, target.calendarRepository.semesterOnce()!!.totalWeeks)
    }

    @Test
    fun `an exported schedule with no semester still round trips`() = runBlocking {
        val c = container()
        reset(c)
        c.calendarRepository.ensureDefaultPeriods()
        seedCourse(c, "高等数学", code = "MATH101")

        val json = c.exportService.exportJson()
        reset(c)
        c.calendarRepository.ensureDefaultPeriods()
        val preview = c.importService.preview(json, ImportStrategy.REPLACE)
        assertTrue("$preview", preview is PreviewResult.Ready)
        c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(1, c.courseRepository.countCourses())
        assertNull(c.calendarRepository.semesterOnce())
        assertEquals(
            "未配置学期时导出会把「每周」写全 25 周；导回来仍是每周的语义",
            PeriodTable.ZJU_PERIODS.size,
            c.calendarRepository.periodsOnce().size
        )
    }

    @Test
    fun `no class and makeup days survive the round trip`() = runBlocking {
        val c = container()
        reset(c)
        c.calendarRepository.saveCalendar(
            semester = null, periods = emptyList(),
            noClass = listOf(com.example.data.local.NoClassDayEntity(EpochDays.parse("2026-10-01")!!, "国庆")),
            makeup = listOf(com.example.data.local.MakeupDayEntity(EpochDays.parse("2026-10-10")!!, 1, "补周一")),
            replacePeriods = false, replaceNoClass = true, replaceMakeup = true
        )
        seedCourse(c, "高等数学", code = "MATH101")

        val json = c.exportService.exportJson()
        reset(c)
        c.importService.commit(c.importService.preview(json, ImportStrategy.REPLACE) as PreviewResult.Ready)

        assertEquals(1, c.calendarRepository.noClassDaysOnce().size)
        assertEquals("国庆", c.calendarRepository.noClassDaysOnce().first().note)
        val makeup = c.calendarRepository.makeupDaysOnce()
        assertEquals(1, makeup.size)
        assertEquals(1, makeup.first().makeupForDayOfWeek)
    }

    @Test
    fun `slots created manually carry their opening weeks through the export`() = runBlocking {
        val c = container()
        reset(c)
        val id = seedCourse(c, "高等数学", code = "MATH101")
        val slotId = c.courseRepository.getSlotsForCourseSync(id).first().id
        c.database.courseSlotWeekDao().insertAll(
            listOf(CourseSlotWeekEntity(slotId, 2), CourseSlotWeekEntity(slotId, 4))
        )

        val json = c.exportService.exportJson()
        assertTrue("手改出来的单双周也要导出：$json", json.contains("[2,4]"))
    }
}
