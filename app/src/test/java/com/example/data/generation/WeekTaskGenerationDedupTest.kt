package com.example.data.generation

import com.example.data.calendar.AppZone
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.TaskType
import com.example.data.timetable.PeriodTable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 去重键的测试：判据是**课程 + 类型 + 周次 + 周几 + 开课时刻**五元组。
 *
 * 修的是这么一件事：同一门课在同一个周几上排了两节课时，
 * 两条待办的去重键曾经完全一样（同课程 + 同类型 + 同周次 + 同周几），
 * 于是库里躺着两条同键的待办 —— 删掉其中一条之后，
 * 剩下那条还占着这个键，被删的那条**再也补不回来**。
 *
 * 这里用假的判据源（[FakeDb]）代替数据库：它按真实的五元组记行，
 * 于是「库里已有什么」这件事在测试里也是真的。
 */
class WeekTaskGenerationDedupTest {

    private val zone = AppZone.ZONE
    private val periodTable = PeriodTable.ZJU

    /** 2026-09-07 是周一，正好当第 1 周的起点。 */
    private val week1Monday = LocalDate.of(2026, 9, 7).toEpochDay()

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

    /**
     * 假数据库：按**五元组**记行，与 `TaskDao.findExistingWeekTask` 的判据一致。
     *
     * 它记下每一次被判重的键 —— 「引擎拿什么去问库」这件事本身就是被测的东西。
     */
    private class FakeDb : WeekTaskGenerationEngine.ExistingChecker {
        /** 库里已有的行。键的拼法与 [keyOf] 一致。 */
        val rows = mutableSetOf<String>()

        /** 引擎问过库的键，按问的顺序排。 */
        val asked = mutableListOf<String>()

        override suspend fun exists(
            courseId: Long,
            taskType: TaskType,
            week: Int,
            dayOfWeek: Int,
            startTimestamp: Long
        ): Boolean {
            val key = "$courseId|${taskType.name}|$week|$dayOfWeek|$startTimestamp"
            asked += key
            return key in rows
        }

        /** 把一条产出的待办写进「库」，模拟落库。 */
        fun insert(task: TaskEntity) {
            rows += keyOf(task)
        }

        /** 删掉一条待办，模拟用户在清单里删掉它。 */
        fun delete(task: TaskEntity) {
            rows -= keyOf(task)
        }
    }

    private companion object {
        /** 待办在库里的身份：课程 + 类型 + 周次 + 周几 + 开课时刻。 */
        fun keyOf(task: TaskEntity): String =
            "${task.courseId}|${task.taskType}|${task.week}|${task.dayOfWeek}|${task.startTimeTimestamp}"
    }

    private suspend fun build(
        db: FakeDb,
        slots: List<CourseSlotEntity>,
        week: Int = 3,
        types: Set<TaskType> = setOf(TaskType.PRE_STUDY)
    ): List<TaskEntity> =
        WeekTaskGenerationEngine(periodTable).build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = week,
                        weekMondayEpochDay = week1Monday + (week - 1) * 7L,
                        slots = slots,
                        types = types
                    )
                ),
                coursesById = mapOf(1L to math),
                courseRules = mapOf(
                    1L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_CLASS", 0, 5))
                )
            )
        ) { courseId, type, w, day, start -> db.exists(courseId, type, w, day, start) }

    // ---------------------------------------------------------------- 同周几的两个时段

    @Test
    fun `two classes on the same weekday produce two tasks`() = runTest {
        // 周三两节：第 1 节（08:00）与第 6 节（13:25）。
        // 开课时刻不同 ⇒ 两节课是两件事，各出一条待办。
        val out = build(
            db = FakeDb(),
            slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 6))
        )

        assertEquals("同一周几的两节课要出两条", 2, out.size)
        assertEquals("两条都落在周三", listOf(3, 3), out.map { it.dayOfWeek })
        assertEquals("两条的开课时刻必须不同", 2, out.map { it.startTimeTimestamp }.distinct().size)
    }

    @Test
    fun `the two classes of the same weekday are asked with different start times`() = runTest {
        // 判据的第五维：引擎问库时必须带上**开课时刻**，
        // 否则「周三第 1 节」与「周三第 6 节」在库里是同一条。
        val db = FakeDb()
        val out = build(db, slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 6)))

        assertEquals("两个时段要问两次库", 2, db.asked.size)
        assertEquals("两次问的键必须不同", 2, db.asked.distinct().size)
        // 问库用的键要跟待办在库里的身份**逐字一致**：
        // 问的是 A、落库的是 B，两者对不上，判重就等于没做。
        assertEquals(out.map { keyOf(it) }.toSet(), db.asked.toSet())
    }

    // ---------------------------------------------------------------- 库里已有其中一条

    @Test
    fun `a surviving task of the same weekday does not hide the deleted one`() = runTest {
        // 这就是那个 bug 的正面：库里剩下「周三第 1 节」那条，
        // 用户删掉的是「周三第 6 节」那条 —— 后者要能补回来。
        // 旧判据（同周几）下，剩下那条占着同一个键，被删的那条永远补不回来。
        val db = FakeDb()
        val slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 6))

        val first = build(db, slots)
        assertEquals(2, first.size)
        first.forEach { db.insert(it) }

        // build() 的结果是按开课时刻排的：第一条是第 1 节，第二条是第 6 节。
        val deleted = first[1]
        db.delete(deleted)

        val again = build(db, slots)
        assertEquals("被删的那条要补得回来", 1, again.size)
        assertEquals("补回来的正是被删掉的那条", keyOf(deleted), keyOf(again.first()))
    }

    @Test
    fun `nothing is generated again while every task is still in the list`() = runTest {
        // 反过来：两条都还在库里时，一次都不该重生成。
        val db = FakeDb()
        val slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 6))

        val first = build(db, slots)
        first.forEach { db.insert(it) }

        assertTrue("两条都已在库里，不该再产出", build(db, slots).isEmpty())
    }

    @Test
    fun `generating the same slot again yields the one task and nothing more`() = runTest {
        // 同一个时段反复生成：库里已经有那条了（假库如实返回「已存在」），
        // 于是总共只有那一条 —— 连点两次生成不会变成两条。
        val db = FakeDb()
        val slots = listOf(slot(1, 1L, 3, 1))

        val first = build(db, slots)
        assertEquals("第一次生成出一条", 1, first.size)
        db.insert(first.first())

        assertTrue("库里已经有了，不该再产出", build(db, slots).isEmpty())
    }

    // ---------------------------------------------------------------- 批内去重

    @Test
    fun `two slots of the same class in one batch produce one task`() = runTest {
        // 同一门课同一时刻在时段表里重复录了两遍。
        // 库里的判据看不见**本批还没落库**的那条，所以批内要自己挡一次，
        // 否则库里又会落下两条同键的待办。
        val out = build(
            db = FakeDb(),
            slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 1))
        )

        assertEquals("完全一样的两个时段只出一条", 1, out.size)
    }

    @Test
    fun `two different slots in one batch are not collapsed by the in-batch dedup`() = runTest {
        // 批内去重只挡「开课时刻一样」的，不挡「开课时刻不同」的 ——
        // 否则同一天的两节课会被吃掉一条。
        val db = FakeDb()
        val out = build(db, slots = listOf(slot(1, 1L, 3, 1), slot(2, 1L, 3, 6)))

        assertEquals("批内两个不同时段都要出", 2, out.size)
        assertNotEquals(
            "两条的开课时刻不能一样",
            out[0].startTimeTimestamp,
            out[1].startTimeTimestamp
        )
    }

    @Test
    fun `the same class of two different courses is not collapsed`() = runTest {
        // 两门课在同一时刻开课是常事（一堆课都在周一 8 点）。
        // 批内去重不能把别的课吃掉 —— 判据里有课程这一维。
        val other = math.copy(id = 2L, name = "线性代数", code = "MATH102")
        val db = FakeDb()
        val out = WeekTaskGenerationEngine(periodTable).build(
            WeekTaskGenerationEngine.Input(
                scopes = listOf(
                    WeekScope(
                        week = 3,
                        weekMondayEpochDay = week1Monday + (3 - 1) * 7L,
                        slots = listOf(slot(1, 1L, 3, 1), slot(2, 2L, 3, 1)),
                        types = setOf(TaskType.PRE_STUDY)
                    )
                ),
                coursesById = mapOf(1L to math, 2L to other),
                courseRules = mapOf(
                    1L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_CLASS", 0, 5)),
                    2L to mapOf(TaskType.PRE_STUDY to CourseRuleSpec("PER_CLASS", 0, 5))
                )
            )
        ) { courseId, type, w, day, start -> db.exists(courseId, type, w, day, start) }

        assertEquals("两门课各自的课都要出", 2, out.size)
        assertEquals(setOf(1L, 2L), out.map { it.courseId }.toSet())
    }
}
