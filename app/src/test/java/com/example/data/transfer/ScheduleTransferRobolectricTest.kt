package com.example.data.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.CourseSlotWeekEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.SlotWithWeeks
import com.example.data.timetable.PeriodTable
import com.example.data.calendar.EpochDays
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    /**
     * 收尾清空。
     *
     * Robolectric 里 `AppContainer` 拿的是 `AppDatabase` 单例，**测试类之间共用同一个库文件**。
     * 本类的用例都会留下课程（导入 / 联动规则都要建课），而
     * `ExampleRobolectricTest.repository initialization and multi-slot course schedule`
     * 的播种判据是「courses 为空，或第一门课的时段被删光」——库里躺着别类测试建的课时，
     * 那条判据会认为演示数据已经播好，于是断言挂在一份没有「高等数学」的课表上，
     * 报错信息里完全看不出是谁弄脏的。
     *
     * 规则行一起清：表非空时 `ensureDefaultRules` 整个不播，
     * 残留的规则会让别的类拿到一份不是自己设的默认值。
     */
    @After
    fun tearDown() {
        runBlocking { reset(container()) }
    }

    /** 每个测试从空库开始，免得 Room 的单例在方法之间串状态。 */
    private suspend fun reset(c: AppContainer) {
        c.database.taskDao().clearTasks()
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

    private suspend fun seedTask(
        c: AppContainer,
        courseId: Long,
        title: String,
        courseName: String = "高等数学"
    ) {
        val unlinked = courseId == TaskEntity.UNLINKED_COURSE_ID
        c.database.taskDao().insertTask(
            TaskEntity(
                courseId = courseId,
                courseName = if (unlinked) TaskEntity.UNLINKED_COURSE_NAME else courseName,
                courseCategory = if (unlinked) "" else "公共基础",
                courseColorHex = if (unlinked) TaskEntity.UNLINKED_COLOR_HEX else "#93C5FD",
                title = title, taskType = "POST_CLASS",
                dueDateTimestamp = 1L, dueDateString = "2026-10-12 23:59"
            )
        )
    }

    /**
     * 一份 v2 文档。`calendar` 与 `termRef` 默认留空 ——
     * 「只带课程、不带校历」是最常见的一种形状，必须默认能导进来。
     */
private fun document(
        courses: String,
        calendar: String = "",
        termRef: String = "",
        categoryRules: String = ""
    ): String {
        val timetableBody = listOf(termRef, """"courses": [ $courses ]""")
            .filter { it.isNotBlank() }
            .joinToString(", ")
        val rulesBody = if (categoryRules.isBlank()) "" else """, "categoryRules": [ $categoryRules ]"""
        return """
        {
          "schemaVersion": 2,
          "provenance": { "source": "manual", "generator": "test" },
          "calendar": { $calendar },
          "timetable": { $timetableBody } $rulesBody
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

    // ------------------------------------------- 导入不得清空用户配好的类别规则

    private suspend fun rulesOf(c: AppContainer) =
        c.settingsRepository.getCategoryRulesOnce().associate { it.category to it }

    private suspend fun switchesOf(c: AppContainer, name: String): Triple<Boolean, Boolean, Boolean> {
        val course = c.courseRepository.getAllCoursesOnce().first { it.name == name }
        return Triple(course.autoPreStudy, course.autoPostHomework, course.autoLabReport)
    }

    private fun ruleJson(category: String, pre: Boolean, post: Boolean, lab: Boolean) = """
        { "category": "$category", "preStudy": $pre, "postHomework": $post,
          "labReport": $lab, "defaultPostDays": 6, "defaultMinutes": 45 }
    """.trimIndent()

    @Test
    fun `an import without category rules keeps the rules the user already set`() = runBlocking {
        // 浙大教务系统从不提供类别规则，所以每次同步包里这一节都是空的。
        // 空列表曾经直接进了 replaceAll（整表替换 = 先 clear 再插），
        // 于是「课程联动规则」弹层里一张类别卡都不剩 —— 这条守的就是那个回归。
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(
            CategoryRule("公共基础", autoPreStudy = false, autoPostHomework = true, autoLabReport = false)
        )

        val preview = c.importService.preview(
            document(courseJson("新课", weeks = """, "weeks": [1]""")),
            ImportStrategy.REPLACE
        )
        assertTrue("$preview", preview is PreviewResult.Ready)
        c.importService.commit(preview as PreviewResult.Ready)

        val rules = rulesOf(c)
        assertEquals("规则表不该被导入清空", 1, rules.size)
        assertEquals(
            Triple(false, true, false),
            Triple(
                rules.getValue("公共基础").autoPreStudy,
                rules.getValue("公共基础").autoPostHomework,
                rules.getValue("公共基础").autoLabReport
            )
        )
    }

    @Test
    fun `courses arriving from an import are initialised from the rules already on file`() = runBlocking {
        // 拿不到规则行时 saveCourseWithSlots 会原样落库，于是课程退回 CourseEntity
        // 的实体默认值（预习 + 作业）—— 上一条那条 bug 的第二现场。
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(
            CategoryRule("公共基础", autoPreStudy = false, autoPostHomework = true, autoLabReport = false)
        )

        val preview = c.importService.preview(document(courseJson("新课")), ImportStrategy.REPLACE)
        assertTrue("$preview", preview is PreviewResult.Ready)
        c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(
            "新课应按已有规则初始化，而不是实体默认值",
            Triple(false, true, false),
            switchesOf(c, "新课")
        )
    }

    @Test
    fun `an import that does carry category rules still overwrites and writes through`() = runBlocking {
        // 反向也要守住：包里真的带了规则，它依然是权威（换机备份靠的就是这条）。
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(
            CategoryRule("公共基础", autoPreStudy = false, autoPostHomework = true)
        )

        val preview = c.importService.preview(
            document(
                courseJson("新课"),
                categoryRules = ruleJson("公共基础", pre = false, post = false, lab = true)
            ),
            ImportStrategy.REPLACE
        )
        assertTrue("$preview", preview is PreviewResult.Ready)
        c.importService.commit(preview as PreviewResult.Ready)

        val rule = rulesOf(c).getValue("公共基础")
        assertEquals(Triple(false, false, true), Triple(rule.autoPreStudy, rule.autoPostHomework, rule.autoLabReport))
        assertEquals(
            "规则替换排在课程之后，写穿才作用得到新课（原先排在开头，恒定 0 门）",
            Triple(false, false, true),
            switchesOf(c, "新课")
        )
    }

    @Test
    fun `a merge import also leaves existing rules alone when the file has none`() = runBlocking {
        val c = container()
        reset(c)
        c.settingsRepository.saveRule(CategoryRule("通识公共", autoPreStudy = false, autoLabReport = true))

        val preview = c.importService.preview(document(courseJson("新课")), ImportStrategy.MERGE)
        assertTrue("$preview", preview is PreviewResult.Ready)
        c.importService.commit(preview as PreviewResult.Ready)

        // CategoryRule 的 autoPostHomework 默认是 true，所以这条规则本身就是
        // (预习关, 作业开, 实验开)；要守住的是它一个格子都没被导入动过。
        val rule = rulesOf(c).getValue("通识公共")
        assertEquals(Triple(false, true, true), Triple(rule.autoPreStudy, rule.autoPostHomework, rule.autoLabReport))
    }

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

    // ---------------------------------------------------------------- 解析规则落到库里

    @Test
    fun `导入落库后 实验课进实验实践 相连时段并成一条`() = runBlocking {
        // 端到端串一遍「解析 → 校验 → 提交」：解析层的两条新规则必须真的进 DB，
        // 而不只是在校验器的返回值里对。
        val c = container()
        reset(c)

        val lab = """
            {
              "name": "大学物理实验", "code": "PHY101", "teacher": "李教授",
              "slots": [
                { "dayOfWeek": 1, "startPeriod": 6, "endPeriod": 6, "classroom": "教二-301" },
                { "dayOfWeek": 1, "startPeriod": 7, "endPeriod": 7, "classroom": "教二-301" }
              ]
            }
        """.trimIndent()
        val plain = """
            {
              "name": "高等数学", "code": "MATH101", "teacher": "王教授",
              "slots": [ { "dayOfWeek": 2, "startPeriod": 1, "endPeriod": 2, "classroom": "教二-101" } ]
            }
        """.trimIndent()

        val result = c.importService.preview(document("$lab,$plain"), ImportStrategy.MERGE)
        assertTrue("$result", result is PreviewResult.Ready)
        c.importService.commit(result as PreviewResult.Ready)

        val courses = c.courseRepository.getAllCoursesOnce()
        val physics = courses.first { it.name == "大学物理实验" }
        assertEquals("实验实践", physics.category)
        val slots = c.courseRepository.getSlotsForCourseSync(physics.id)
        assertEquals("同一地点的相连两段应并成一条", 1, slots.size)
        assertEquals(6, slots.first().startPeriod)
        assertEquals(7, slots.first().endPeriod)

        assertEquals("公共基础", courses.first { it.name == "高等数学" }.category)
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
    fun `replace preserves tasks and moves them to unlinked when course is gone`() = runBlocking {
        val c = container()
        reset(c)
        val old = seedCourse(c, "原有课程")
        seedTask(c, old, "原有待办")

        val preview = c.importService.preview(document(courseJson("新课程")), ImportStrategy.REPLACE)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(1, report.removed)
        assertEquals(1, c.courseRepository.countCourses())
        assertEquals("新课程", c.courseRepository.getAllCoursesOnce().first().name)
        assertEquals("REPLACE 策略下原有课程消失，待办移入未关联课程保留", 1, c.database.taskDao().countTasks())
        val task = c.database.taskDao().getAllTasksOnce().first()
        assertEquals(TaskEntity.UNLINKED_COURSE_ID, task.courseId)
        assertEquals(1, report.tasksUnlinked)
    }

    /**
     * 三种本地待办，三种归宿：
     * ①课程还在 → 改挂到新课程（新 id）上；②课程没了 → 移入「未关联课程」；
     * ③本来就未关联 → 一个字段都不动。
     *
     * ①是最容易被漏掉的一条：REPLACE 删掉课程行后重新插入，新 id 与旧 id 无关，
     * 而待办的 courseId 指向旧 id。什么都不做的话这条待办会挂在一门已经不存在的课上
     * ——既不在课程页里，也进不了「未关联课程」分组，等于凭空消失。
     */
    @Test
    fun `replace re-homes tasks of surviving courses and leaves unlinked ones alone`() = runBlocking {
        val c = container()
        reset(c)
        val surviving = seedCourse(c, "高等数学", code = "MATH101")
        val goneCourse = seedCourse(c, "已退的课", code = "GONE1001")
        seedTask(c, surviving, "课还在的待办", "高等数学")
        seedTask(c, goneCourse, "课没了的待办", "已退的课")
        seedTask(c, TaskEntity.UNLINKED_COURSE_ID, "本来就未关联的待办")

        val preview = c.importService.preview(
            document(courseJson("高等数学", code = "MATH101")),
            ImportStrategy.REPLACE
        ) as PreviewResult.Ready

        // 预览先把话说在前面：提交后的结果必须与这里承诺的一致
        assertEquals("课没了的那条应在预览里就被预告", 1, preview.preview.tasksLosingCourse)
        assertEquals("课还在的那条应在预览里就被预告", 1, preview.preview.tasksKeepingCourse)

        val report = c.importService.commit(preview)
        assertEquals(1, report.tasksUnlinked)
        assertEquals(1, report.tasksRelinked)

        val tasks = c.database.taskDao().getAllTasksOnce().associateBy { it.title }
        assertEquals("三条待办一条都不能少", 3, tasks.size)

        val newCourseId = c.courseRepository.getAllCoursesOnce().single().id
        assertEquals("REPLACE 后课程是新插入的行，id 不该复用", 1, c.courseRepository.countCourses())
        assertEquals(
            "同一门课应当改挂到新 id 上，而不是掉进未关联",
            newCourseId,
            tasks.getValue("课还在的待办").courseId
        )
        assertTrue(TaskEntity.isUnlinked(tasks.getValue("课没了的待办")))

        val untouched = tasks.getValue("本来就未关联的待办")
        assertEquals(TaskEntity.UNLINKED_COURSE_ID, untouched.courseId)
        assertEquals(TaskEntity.UNLINKED_COURSE_NAME, untouched.courseName)
        assertEquals("原本没关联课程的待办不该被这次导入碰过", 1L, untouched.dueDateTimestamp)
    }

    /** MERGE 从不删课程，所以待办的归属天然一条都不该动。 */
    @Test
    fun `merge never re-homes a task`() = runBlocking {
        val c = container()
        reset(c)
        val kept = seedCourse(c, "原有课程")
        seedTask(c, kept, "原有待办", "原有课程")

        val preview = c.importService.preview(document(courseJson("新课程")), ImportStrategy.MERGE)
        val report = c.importService.commit(preview as PreviewResult.Ready)

        assertEquals(0, report.tasksUnlinked)
        assertEquals(0, report.tasksRelinked)
        val task = c.database.taskDao().getAllTasksOnce().single()
        assertEquals("MERGE 下课程 id 不变，待办不该被改写", kept, task.courseId)
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
