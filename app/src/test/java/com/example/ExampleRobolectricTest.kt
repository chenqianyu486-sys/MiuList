package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.sample.SampleDataProvider
import com.example.data.timetable.PeriodTable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 装配与数据库行为的冒烟测试。
 *
 * 纯逻辑（开课周次、作息表、校历判定、JSON 编解码、校验器）都有各自的
 * 专用测试文件，跑起来不需要 Robolectric；这里只留**必须有真实 Room 才能测**的部分。
 * 依赖一律从 [AppContainer] 取 —— 改造前是手工 `CourseTodoRepository(...)` 拼装，
 * 仓库拆分后那种写法必然编译失败。
 */
@RunWith(RobolectricTestRunner::class)
// 用 SDK 34 而不是工程的 targetSdk 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建环境固定是 JDK 17（AGP 也要求 17）。这里测的是 Room / 协程 / 迁移，
// 与 API 36 的新增行为无关，降到 34 不影响结论。
@Config(sdk = [34])
class ExampleRobolectricTest {

    private fun container(): AppContainer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppContainer(context)
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("MiuList", context.getString(R.string.app_name))
    }

    @Test
    fun `a fresh install gets the built-in timetable without anyone importing anything`() = runBlocking {
        val container = container()
        // 迁移只给升级用户写作息种子，全新安装得靠 ensureDefaultPeriods。
        // 少了这一步，periodsPerDay 会是 0，课表栅格一节都画不出来。
        container.calendarRepository.saveCalendar(
            semester = null,
            periods = emptyList(),
            noClass = emptyList(),
            makeup = emptyList(),
            replacePeriods = true,
            replaceNoClass = true,
            replaceMakeup = true
        )
        assertTrue("前置条件：表被清空了", container.calendarRepository.periodsOnce().isEmpty())

        container.calendarRepository.ensureDefaultPeriods()

        val periods = container.calendarRepository.periodsOnce()
        assertEquals(15, periods.size)
        assertEquals(
            // 全新安装的种子是浙大作息（15 节），不是通用的 13 节表。
            // 通用表仍留给 v3→v4 迁移写给升级用户，两者并存，见 PeriodTable 的注释。
            "全新安装的作息必须与内置默认逐字段一致",
            PeriodTable.ZJU_PERIODS.map { listOf(it.period, it.startMinute, it.endMinute, it.section) },
            periods.map { listOf(it.period, it.startMinute, it.endMinute, it.section) }
        )
    }

    @Test
    fun `the seeding step never overwrites a timetable the user already configured`() = runBlocking {
        val container = container()
        container.calendarRepository.saveCalendar(
            semester = null,
            periods = listOf(
                com.example.data.local.TimetablePeriodEntity(1, 500, 545, "上午"),
                com.example.data.local.TimetablePeriodEntity(2, 555, 600, "上午")
            ),
            noClass = emptyList(),
            makeup = emptyList(),
            replacePeriods = true,
            replaceNoClass = true,
            replaceMakeup = true
        )

        container.calendarRepository.ensureDefaultPeriods()

        val periods = container.calendarRepository.periodsOnce()
        assertEquals("只有两节的自定义作息不许被内置作息盖掉", 2, periods.size)
        assertEquals(500, periods.first().startMinute)
    }

    @Test
    fun `repository initialization and multi-slot course schedule`() = runBlocking {
        val container = container()
        val courseRepo = container.courseRepository
        // Robolectric 里同一个类的用例共用同一个库文件，而上一个用例会把作息换成
        // 只有两节的 —— 时段一旦超出节数就会被过滤掉，于是这条断言会因为「谁先跑」
        // 而失败，而不是因为被测行为有问题。这里先恢复成内置作息。
        container.calendarRepository.saveCalendar(
            semester = null,
            periods = emptyList(),
            noClass = emptyList(),
            makeup = emptyList(),
            replacePeriods = true,
            replaceNoClass = true,
            replaceMakeup = true
        )
        container.calendarRepository.ensureDefaultPeriods()

        // 同类的「删时段级联」那条用例会真删掉课表的时段，所以**不能只看「有没有课程」**
        // 来决定要不要播种：这两种顺序下这个判断都会给出「已经播好了」，然后断言挂在
        // 一份没有时段的课表上。
        val courses = courseRepo.getAllCoursesOnce()
        val slotsMissing = courses.isNotEmpty() &&
            courseRepo.getAllSlotsOnce().none { it.courseId == courses.first().id }
        if (courses.isEmpty() || slotsMissing) {
            courseRepo.resetToDemoData(
                SampleDataProvider.sampleCourses,
                SampleDataProvider.sampleCourseSlots,
                SampleDataProvider.createSampleTasks()
            )
        }

        val coursesWithSlots = courseRepo.getAllCoursesWithSlots().first()
        assertTrue("Courses should be pre-seeded", coursesWithSlots.isNotEmpty())

        val allSlots = courseRepo.getAllSlots().first()
        assertTrue("Slots should be pre-seeded", allSlots.isNotEmpty())

        val higherMath = coursesWithSlots.find { it.course.name.contains("高等数学") }
        assertTrue("Higher Math should have at least 2 weekly slots", (higherMath?.slots?.size ?: 0) >= 2)

        val customSpanSlot = allSlots.find { (it.endPeriod - it.startPeriod) >= 2 }
        assertTrue("Should contain custom span slot", customSpanSlot != null)
    }

    @Test
    fun `deleting a slot cascades its week rows`() = runBlocking {
        val container = container()
        // DAO 从 container 拿：CourseRepository 的 db 是私有的，
        // 测试不该为了拿个 DAO 去撬仓库的内部字段。
        val slotDao = container.database.courseSlotDao()
        val weekDao = container.database.courseSlotWeekDao()

        val slot = slotDao.getAllSlotsOnce().firstOrNull() ?: return@runBlocking
        weekDao.insertAll(
            listOf(com.example.data.local.CourseSlotWeekEntity(slot.id, 1))
        )
        assertTrue("前置条件：周次行已写入", weekDao.getForSlot(slot.id).isNotEmpty())

        slotDao.deleteSlotsByCourseId(slot.courseId)

        assertTrue(
            "时段删除后开课周次必须随之消失（ON DELETE CASCADE），否则会留下指向不存在时段的孤儿行",
            weekDao.getForSlot(slot.id).isEmpty()
        )
    }

    @Test
    fun `saving a course replaces all of its slots in one transaction`() = runBlocking {
        val container = container()
        val courseRepo = container.courseRepository

        val id = courseRepo.saveCourseWithSlots(
            com.example.data.local.CourseEntity(
                name = "事务测试课", code = "TX001", category = "公共基础",
                teacher = "王教授", classroom = "教二-301", colorHex = "#93C5FD"
            ),
            listOf(
                com.example.data.model.SlotWithWeeks(
                    com.example.data.local.CourseSlotEntity(
                        courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2
                    ),
                    weeks = setOf(1, 3)
                ),
                com.example.data.model.SlotWithWeeks(
                    com.example.data.local.CourseSlotEntity(
                        courseId = 0, dayOfWeek = 3, startPeriod = 3, endPeriod = 4
                    ),
                    weeks = setOf(2, 4)
                )
            )
        )

        assertEquals(2, courseRepo.getSlotsForCourseSync(id).size)
        val firstSlotIds = courseRepo.getSlotsForCourseSync(id).map { it.id }.toSet()
        assertEquals(
            "4 行周次 = 两个时段各两行（1,3 与 2,4）",
            4,
            courseRepo.getAllSlotWeeksOnce().count { it.courseSlotId in firstSlotIds }
        )

        // 改成只剩一个时段：旧的两个时段和它们的周次都该消失
        courseRepo.saveCourseWithSlots(
            com.example.data.local.CourseEntity(
                id = id, name = "事务测试课", code = "TX001", category = "公共基础",
                teacher = "王教授", classroom = "教二-301", colorHex = "#93C5FD"
            ),
            listOf(
                com.example.data.model.SlotWithWeeks(
                    com.example.data.local.CourseSlotEntity(
                        courseId = id, dayOfWeek = 5, startPeriod = 5, endPeriod = 6
                    ),
                    weeks = setOf(7)
                )
            )
        )

        val slots = courseRepo.getSlotsForCourseSync(id)
        assertEquals("时段是整体重建，不是追加", 1, slots.size)
        assertEquals(5, slots.first().dayOfWeek)
        assertEquals(setOf(7), courseRepo.getWeeksBySlotIdOnce()[slots.first().id])
        assertEquals(
            "旧的两个时段的周次行必须被一起清掉，不能留下孤儿",
            1,
            courseRepo.getAllSlotWeeksOnce().count { it.courseSlotId == slots.first().id }
        )
    }
}
