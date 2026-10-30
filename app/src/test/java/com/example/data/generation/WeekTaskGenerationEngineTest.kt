package com.example.data.generation

import com.example.data.calendar.AppZone
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

    // 期望值必须用**引擎同一个**时区算，不能用 systemDefault()：
    // 引擎按北京时间算开课/截止时刻，测试按设备时区算期望，跨时区环境下
    // 这条测试会因为「测试写错了」而不是「引擎写错了」而红，那是比没有测试更坏的结果。
    private val zone = AppZone.ZONE
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
                scopes = listOf(WeekScope(week = week, weekMondayEpochDay = monday, slots = slots)),
                coursesById = mapOf(1L to math),
                courseRules = courseRules,
                categoryRules = categoryRules
            )
        ) { courseId, type, w, _, _ -> Triple(courseId, type.name, w) in existing }
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
    fun `the title reads as bracketed course plus week plus type`() = runTest {
        val out = build(slots = listOf(slot(1, 1L, 1, 1)), week = 3)
        // 作业与实验报告默认「每周一次」，覆盖整周 —— 标题里不写周几。
        assertEquals("【高等数学】第3周作业", out.first { it.taskType == TaskType.POST_CLASS.name }.title)
        assertEquals("【高等数学】第3周实验报告", out.first { it.taskType == TaskType.LAB_REPORT.name }.title)
        // 预习默认「每次课」，对应的是某一节课 —— 落在周几是它的一部分。
        assertEquals("【高等数学】第3周周一预习", out.first { it.taskType == TaskType.PRE_STUDY.name }.title)
    }

    @Test
    fun `the weekday only shows up in the title for per class generation`() = runTest {
        val slots = listOf(slot(1, 1L, 3, 2))

        val perClass = build(
            slots = slots,
            week = 3,
            courseRules = mapOf(
                1L to mapOf(TaskType.POST_CLASS to CourseRuleSpec("PER_CLASS", 6, 5))
            )
        )
        assertEquals(
            "按节生成的作业要带周几",
            "【高等数学】第3周周三作业",
            perClass.first { it.taskType == TaskType.POST_CLASS.name }.title
        )

        val perWeek = build(slots = slots, week = 3)
        assertEquals(
            "每周一次的作业覆盖整周，写上某一天反而误导",
            "【高等数学】第3周作业",
            perWeek.first { it.taskType == TaskType.POST_CLASS.name }.title
        )
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

    @Test
    fun `the generated task carries no note`() = runTest {
        val out = build(slots = listOf(slot(1, 1L, 1, 1)), week = 3)
        // 备注是**用户自己写**的那部分（老师改了什么要求、作业交到哪、书留在哪）。
        // 引擎替用户写一句模板话，写出来却让人以为那是有用的信息；
        // 空着反而在编辑弹层里有一句「点击编辑备注」的邀请。
        assertTrue("生成的待办不该自带备注", out.all { it.description.isEmpty() })
    }

    // ---------------------------------------------------------------- 开关与兜底

    @Test
    fun `a type the course has turned off produces nothing at any layer`() = runTest {
        val off = math.copy(autoLabReport = false)
        var built: List<TaskEntity> = emptyList()
        built = WeekTaskGenerationEngine(periodTable).build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(WeekScope(week = 1, weekMondayEpochDay = week1Monday, slots = listOf(slot(1, 1L, 1, 1)))),
                coursesById = mapOf(1L to off)
            )
        ) { _, _, _, _, _ -> false }
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

    // ---------------------------------------------------------------- 按类型划范围

    @Test
    fun `a scope only produces the types it lists`() = runTest {
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 2)),
                        types = setOf(TaskType.POST_CLASS)
                    )
                ),
                coursesById = mapOf(1L to math)
            )
        ) { _, _, _, _, _ -> false }

        assertEquals(1, out.count { it.taskType == TaskType.POST_CLASS.name })
        assertTrue("限定了类型就不该出预习", out.none { it.taskType == TaskType.PRE_STUDY.name })
        assertTrue("限定了类型就不该出实验报告", out.none { it.taskType == TaskType.LAB_REPORT.name })
    }

    // ---------------------------------------------------------------- 今天起

    @Test
    fun `a scope with a lower bound drops the days before it`() = runTest {
        // 周一、周三、周五各一节；下界定在周三 00:00
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 1, 2), slot(2, 1L, 3, 2), slot(3, 1L, 5, 4)),
                        types = setOf(TaskType.PRE_STUDY),
                        fromStartTimestamp = mondayOf(3).plusDays(2)
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    )
                ),
                coursesById = mapOf(1L to math)
            )
        ) { _, _, _, _, _ -> false }

        assertEquals(listOf(3, 5), out.map { it.dayOfWeek })
    }

    @Test
    fun `the bound compares the class start instant, not just its date`() = runTest {
        // 周三两节课：第 1 节 08:00（早于下界）、第 6 节 13:25（晚于下界，ZJU 作息）。
        // 下界取周三 12:00 —— 按「日期」判会两节都留下，
        // 而预习的截止是开课前几分钟，上午那节补出来只会是一条当场逾期的待办。
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 6)),
                        types = setOf(TaskType.PRE_STUDY),
                        fromStartTimestamp = mondayOf(3).plusDays(2).atTime(12, 0)
                            .atZone(zone).toInstant().toEpochMilli()
                    )
                ),
                coursesById = mapOf(1L to math),
                courseRules = mapOf(
                    1L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_CLASS", 0, 5))
                )
            )
        ) { _, _, _, _, _ -> false }

        assertEquals("只剩下午那节", 1, out.size)
        // 截止 = 开课 13:25 前 5 分钟 = 13:20，确实晚于 12:00 那个下界
        val expected = mondayOf(3).plusDays(2).atTime(13, 20).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, out.first().dueDateTimestamp)
    }

    @Test
    fun `the lower bound is applied before the weekly anchor is picked`() = runTest {
        // 「每周一次」的锚点必须落在**过滤之后**剩下的课里。
        // 下界之后的范围里只剩周五那节，锚点就该是周五；
        // 若先锚周一再按时刻丢，锚点会停在已经上过的周一，产出日期也跟着错。
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 1, 2), slot(2, 1L, 5, 4)),
                        types = setOf(TaskType.PRE_STUDY),
                        fromStartTimestamp = mondayOf(3).plusDays(2).atStartOfDay(zone)
                            .toInstant().toEpochMilli(),
                        // 强制「每周一次」，让锚点选择真正参与
                    )
                ),
                coursesById = mapOf(1L to math),
                courseRules = mapOf(
                    1L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_WEEK", 0, 5))
                )
            )
        ) { _, _, _, _, _ -> false }

        assertEquals(1, out.size)
        assertEquals("锚点该是过滤后最早那节（周五）", 5, out.first().dayOfWeek)
        assertEquals(3, out.first().week)
    }

    @Test
    fun `a scope whose whole week is before the lower bound produces nothing`() = runTest {
        // 下界落在下一周：本周整周都早于它，不能为了凑数产出一条待办。
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 1, 2), slot(2, 1L, 3, 2)),
                        fromStartTimestamp = mondayOf(4).atStartOfDay(zone).toInstant().toEpochMilli()
                    )
                ),
                coursesById = mapOf(1L to math)
            )
        ) { _, _, _, _, _ -> false }

        assertTrue(out.isEmpty())
    }

    // ---------------------------------------------------------------- 跨周

    @Test
    fun `several scopes coexist and each keeps its own week number`() = runTest {
        // 本周：作业 + 实验报告；本周与下周：预习（这正是 MainViewModel 装配的形状）
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 2)),
                        types = setOf(TaskType.POST_CLASS, TaskType.LAB_REPORT)
                    ),
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 2)),
                        types = setOf(TaskType.PRE_STUDY),
                        fromStartTimestamp = mondayOf(3).plusDays(2).atStartOfDay(zone)
                            .toInstant().toEpochMilli()
                    ),
                    WeekScope(
                        week = 4,
                        weekMondayEpochDay = mondayOf(4).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 2)),
                        types = setOf(TaskType.PRE_STUDY)
                    )
                ),
                coursesById = mapOf(1L to math)
            )
        ) { _, _, _, _, _ -> false }

        val homework = out.filter { it.taskType == TaskType.POST_CLASS.name }
        assertEquals("作业只在第 3 周出一条", 1, homework.size)
        assertEquals(3, homework.first().week)

        val prestudy = out.filter { it.taskType == TaskType.PRE_STUDY.name }
        assertEquals("本周与下周各一条预习", 2, prestudy.size)
        assertEquals(setOf(3, 4), prestudy.map { it.week }.toSet())

        // 标题里的周次要跟着各自的范围走，否则下周那条会写成「第3周预习」。
        assertEquals("【高等数学】第3周周三预习", prestudy.first { it.week == 3 }.title)
        assertEquals("【高等数学】第4周周三预习", prestudy.first { it.week == 4 }.title)
    }

    @Test
    fun `tasks from several scopes come out in class-time order`() = runTest {
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    // 下周的范围先给：范围顺序不该决定列表顺序
                    WeekScope(
                        week = 4,
                        weekMondayEpochDay = mondayOf(4).toEpochDay(),
                        slots = listOf(slot(1, 1L, 2, 2)),
                        types = setOf(TaskType.PRE_STUDY)
                    ),
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(2, 1L, 5, 4)),
                        types = setOf(TaskType.PRE_STUDY)
                    )
                ),
                coursesById = mapOf(1L to math)
            )
        ) { _, _, _, _, _ -> false }

        assertEquals(listOf(3, 4), out.map { it.week })
    }

    @Test
    fun `the dedup key distinguishes the pre-study of this week from next week's`() = runTest {
        // 下周预习的去重键带的是它自己的周次（4），不能被本周那条（3）挡掉。
        val existing = setOf(Triple(1L, TaskType.PRE_STUDY.name, 3))
        val out = engine().build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = mondayOf(3).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 2)),
                        types = setOf(TaskType.PRE_STUDY),
                        fromStartTimestamp = mondayOf(3).atStartOfDay(zone).toInstant().toEpochMilli()
                    ),
                    WeekScope(
                        week = 4,
                        weekMondayEpochDay = mondayOf(4).toEpochDay(),
                        slots = listOf(slot(1, 1L, 3, 2)),
                        types = setOf(TaskType.PRE_STUDY)
                    )
                ),
                coursesById = mapOf(1L to math)
            )
        ) { courseId, type, w, _, _ -> Triple(courseId, type.name, w) in existing }

        assertTrue("本周那条已存在", out.none { it.week == 3 })
        assertEquals("下周那条该照常产出", 1, out.count { it.week == 4 })
    }
}