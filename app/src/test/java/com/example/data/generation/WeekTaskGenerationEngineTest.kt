package com.example.data.generation

import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.CategoryRule
import com.example.data.model.TaskType
import com.example.data.timetable.PeriodTable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「一键生成本周待办」的引擎测试。纯 JUnit（用 runTest 提供协程环境）。
 *
 * 这里钉住的是三件最容易悄悄写错、而且错了不崩的事：
 * 1. 「每周一次」到底产出几条、锚点落在哪天；
 * 2. 去重判据是不是真的按「同课程 + 同类型 + 同周次 + 同周几」；
 * 3. 两个截止时间是不是「上课前 5 分钟」与「上课日 +6 天 23:59」。
 */
class WeekTaskGenerationEngineTest {

    private val zone = ZoneId.systemDefault()
    private val periodTable = PeriodTable.ZJU

    /** 2026-09-07 是周一，正好当第 1 周的起点。 */
    private val week1Monday = LocalDate.of(2026, 9, 7).toEpochDay()

    /**
     * 第 [week] 周的周一。写期望值时必须走它，不要拿 `week1Monday` 直接加减：
     * `build()` 默认 week = 3，锚点已经往后推了两周，直接加减会算出两周前的日子。
     */
    private fun mondayOf(week: Int): LocalDate =
        LocalDate.ofEpochDay(week1Monday + (week - 1) * 7L)

    private val math = CourseEntity(
        id = 1L,
        name = "高等数学",
        code = "MATH101",
        category = "公共基础",
        teacher = "王教授",
        classroom = "教二-301",
        colorHex = "#93C5FD",
        autoPreStudy = true,
        autoPostHomework = true,
        autoLabReport = true
    )

    private fun slot(id: Long, courseId: Long, dow: Int, start: Int, end: Int = start) =
        CourseSlotEntity(id = id, courseId = courseId, dayOfWeek = dow, startPeriod = start, endPeriod = end)

    private fun engine() = WeekTaskGenerationEngine(periodTable)

    private suspend fun build(
        slots: List<CourseSlotEntity>,
        week: Int = 3,
        monday: Long = week1Monday + (week - 1) * 7L,
        courseRules: Map<Long, Map<TaskType, CourseRuleSpec>> = emptyMap(),
        categoryRules: Map<String, CategoryRule> = emptyMap(),
        existing: Set<Triple<Long, String, Int>> = emptySet()
    ): List<TaskEntity> {
        var built: List<TaskEntity> = emptyList()
        built = engine().build(
            WeekTaskGenerationEngine.Input(
                week = week,
                weekMondayEpochDay = monday,
                slots = slots,
                coursesById = mapOf(1L to math),
                courseRules = courseRules,
                categoryRules = categoryRules
            )
        ) { courseId, type, w, day -> Triple(courseId, type.name, w) in existing }
        return built
    }

    // ---------------------------------------------------------------- 节奏

    @Test
    fun `per week yields exactly one task per type, anchored on the earliest class`() = runTest {
        // 周三和周五各一节高数
        val out = build(
            slots = listOf(slot(1, 1L, 3, 2), slot(2, 1L, 5, 4)),
            courseRules = mapOf(
                1L to mapOf(
                    TaskType.POST_CLASS to CourseRuleSpec("PER_WEEK", 6, 5),
                    TaskType.PRE_STUDY to CourseRuleSpec("PER_CLASS", 0, 5)
                )
            )
        )
        val homework = out.filter { it.taskType == TaskType.POST_CLASS.name }
        val prestudy = out.filter { it.taskType == TaskType.PRE_STUDY.name }

        assertEquals("每周一次的作业只出一条", 1, homework.size)
        assertEquals("锚点是该周最早那节课（周三）", 3, homework.first().dayOfWeek)

        assertEquals("每次课一次的预习出两条", 2, prestudy.size)
        assertEquals(listOf(3, 5), prestudy.map { it.dayOfWeek }.sorted())
    }

    @Test
    fun `per class yields one task per slot`() = runTest {
        val out = build(
            slots = listOf(slot(1, 1L, 3, 2), slot(2, 1L, 5, 4)),
            courseRules = mapOf(
                1L to mapOf(TaskType.POST_CLASS to CourseRuleSpec("PER_CLASS", 6, 5))
            )
        )
        val homework = out.filter { it.taskType == TaskType.POST_CLASS.name }
        assertEquals(2, homework.size)
        assertEquals(listOf(3, 5), homework.map { it.dayOfWeek }.sorted())
    }

    // ---------------------------------------------------------------- 去重

    @Test
    fun `the same course type and week is not generated twice`() = runTest {
        val slots = listOf(slot(1, 1L, 3, 2))
        val first = build(slots, existing = setOf(Triple(1L, TaskType.POST_CLASS.name, 3)))
        assertTrue("已存在的那一条不该再产出", first.none { it.taskType == TaskType.POST_CLASS.name })

        // 换一周就该产出
        val nextWeek = build(slots, week = 4, existing = setOf(Triple(1L, TaskType.POST_CLASS.name, 3)))
        assertTrue("周次不同就该产出", nextWeek.any { it.taskType == TaskType.POST_CLASS.name })
    }

    @Test
    fun `the dedup key covers the type too, not just the course and week`() = runTest {
        val out = build(
            slots = listOf(slot(1, 1L, 3, 2)),
            courseRules = mapOf(
                1L to mapOf(
                    TaskType.POST_CLASS to CourseRuleSpec("PER_WEEK", 6, 5),
                    TaskType.PRE_STUDY to CourseRuleSpec("PER_WEEK", 0, 5)
                )
            ),
            existing = setOf(Triple(1L, TaskType.POST_CLASS.name, 3))
        )
        assertTrue("作业被去掉了", out.none { it.taskType == TaskType.POST_CLASS.name })
        assertTrue("预习不该被作业的去重键误伤", out.any { it.taskType == TaskType.PRE_STUDY.name })
    }

    // ---------------------------------------------------------------- 截止时间

    @Test
    fun `pre-study is due five minutes before the class starts`() = runTest {
        val out = build(
            slots = listOf(slot(1, 1L, 3, 1)),
            week = 1,
            courseRules = mapOf(
                1L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_WEEK", 0, 5))
            )
        )
        val task = out.first { it.taskType == TaskType.PRE_STUDY.name }
        // 第 1 节 08:00 开始，周三 = 该周周一 + 2 天
        val classStart = mondayOf(1).plusDays(2)
            .atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("上课前 5 分钟", classStart - 5 * 60_000L, task.dueDateTimestamp)
    }

    @Test
    fun `pre-study before minutes is driven by the rule rather than hardcoded`() = runTest {
        val out = build(
            slots = listOf(slot(1, 1L, 3, 1)),
            week = 1,
            courseRules = mapOf(
                1L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_WEEK", 0, 15))
            )
        )
        val task = out.first { it.taskType == TaskType.PRE_STUDY.name }
        val classStart = mondayOf(1).plusDays(2)
            .atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("规则改成 15 分钟就该跟着变", classStart - 15 * 60_000L, task.dueDateTimestamp)
    }

    @Test
    fun `homework and lab reports are due at 2359 six days after the class`() = runTest {
        val out = build(
            slots = listOf(slot(1, 1L, 1, 1)),
            week = 1,
            courseRules = mapOf(
                1L to mapOf(
                    TaskType.POST_CLASS to CourseRuleSpec("PER_WEEK", 6, 5),
                    TaskType.LAB_REPORT to CourseRuleSpec("PER_WEEK", 6, 5)
                )
            )
        )
        val monday = mondayOf(1)
        listOf(TaskType.POST_CLASS.name, TaskType.LAB_REPORT.name).forEach { type ->
            val task = out.first { it.taskType == type }
            val expected = monday.plusDays(6).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
            assertEquals("$type 的截止是上课日 +6 天 23:59", expected, task.dueDateTimestamp)
        }
    }

    @Test
    fun `the post offset is read from the rule too`() = runTest {
        val out = build(
            slots = listOf(slot(1, 1L, 1, 1)),
            week = 1,
            courseRules = mapOf(
                1L to mapOf(TaskType.POST_CLASS to CourseRuleSpec("PER_WEEK", 2, 5))
            )
        )
        val task = out.first { it.taskType == TaskType.POST_CLASS.name }
        val expected = mondayOf(1).plusDays(2).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals("规则改成 +2 天就该跟着变", expected, task.dueDateTimestamp)
    }

    // ---------------------------------------------------------------- 标题与字段

    @Test
    fun `the title reads as course plus week plus type`() = runTest {
        val out = build(slots = listOf(slot(1, 1L, 1, 1)), week = 3)
        assertEquals("高等数学 第3周作业", out.first { it.taskType == TaskType.POST_CLASS.name }.title)
        assertEquals("高等数学 第3周预习", out.first { it.taskType == TaskType.PRE_STUDY.name }.title)
        assertEquals("高等数学 第3周实验报告", out.first { it.taskType == TaskType.LAB_REPORT.name }.title)
    }

    @Test
    fun `the week and the weekday are both written onto the task`() = runTest {
        val out = build(slots = listOf(slot(1, 1L, 4, 1)), week = 5)
        val task = out.first { it.taskType == TaskType.POST_CLASS.name }
        assertEquals(5, task.week)
        assertEquals(4, task.dayOfWeek)
        // generatedDate 只作溯源显示，去重已经不依赖它。
        // 它记的是那节课本身的日期（该周周一 + 周几 - 1），不是生成按钮按下的那天。
        assertEquals(
            mondayOf(5).plusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE),
            task.generatedDate
        )
    }

    // ---------------------------------------------------------------- 开关与兜底

    @Test
    fun `a type the course has turned off produces nothing at any layer`() = runTest {
        val off = math.copy(autoLabReport = false)
        var built: List<TaskEntity> = emptyList()
        built = WeekTaskGenerationEngine(periodTable).build(
            WeekTaskGenerationEngine.Input(
                week = 1,
                weekMondayEpochDay = week1Monday,
                slots = listOf(slot(1, 1L, 1, 1)),
                coursesById = mapOf(1L to off)
            )
        ) { _, _, _, _ -> false }
        assertTrue(built.none { it.taskType == TaskType.LAB_REPORT.name })
    }

    @Test
    fun `with no rules anywhere the built-in defaults apply`() = runTest {
        val out = build(slots = listOf(slot(1, 1L, 1, 1)))
        // 内置：预习每次课、作业与实验每周
        assertEquals(1, out.count { it.taskType == TaskType.PRE_STUDY.name })
        assertEquals(1, out.count { it.taskType == TaskType.POST_CLASS.name })
        assertEquals(1, out.count { it.taskType == TaskType.LAB_REPORT.name })
    }

    @Test
    fun `a course with no class this week produces nothing`() = runTest {
        val out = build(slots = emptyList())
        assertTrue(out.isEmpty())
    }
}