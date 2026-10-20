package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.SlotWithWeeks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 旧版本播种的示例数据，在功能取消（2026-10-20）之后被清掉。
 *
 * 这条链路是**在用户没点头的情况下动他的库**，所以判据必须宁可漏清、不可误删：
 * 真实课程、手写待办一根汗毛都不许动。
 *
 * Robolectric + 真实 Room。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacySampleDataCleanupTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() = runBlocking {
        container = AppContainer(ApplicationProvider.getApplicationContext<Context>())
        container.database.taskDao().clearTasks()
        container.courseRepository.clearAllCourses()
    }

    /**
     * 收尾清空。
     *
     * Robolectric 里 `AppDatabase` 是单例，**测试类之间共用同一个库文件**。
     * 本类会在库里留下待办（清理逻辑要验「手写待办留着」，
     * 那几条正是它留下的），不清掉就会漏到别的类里 ——
     * `TaskCategoryDeletionRobolectricTest` 只在收尾清、没有开场清，
     * 于是它的条数断言会被本类的残留撑多一条。
     */
    @After
    fun tearDown() = runBlocking {
        container.database.taskDao().clearTasks()
        container.courseRepository.clearAllCourses()
    }

    private suspend fun seedCourse(name: String, code: String): Long =
        container.courseRepository.saveCourseWithSlots(
            CourseEntity(
                name = name, code = code, category = "公共基础",
                teacher = "王教授", classroom = "教二-301", colorHex = "#93C5FD"
            ),
            listOf(
                SlotWithWeeks(
                    slot = CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
                    weeks = emptySet()
                )
            )
        )

    private suspend fun seedTask(courseId: Long, title: String) {
        container.database.taskDao().insertTask(
            TaskEntity(
                courseId = courseId,
                courseName = "高等数学(上)",
                courseCategory = "公共基础",
                courseColorHex = "#93C5FD",
                title = title,
                taskType = "POST_CLASS",
                dueDateTimestamp = 1L
            )
        )
    }

    @Test
    fun `示例课表与示例待办被一并清掉`() = runBlocking {
        val courseId = seedCourse("高等数学(上)", "MATH101")
        seedTask(courseId, "【作业】高数课后习题集 P134 A组 1-8题")

        val cleared = container.courseRepository.clearLegacySampleData()

        assertEquals("清掉的课程数要如实报出来", 1, cleared)
        assertTrue("示例课程不该还在", container.courseRepository.getAllCoursesOnce().isEmpty())
        assertTrue("示例待办不该还在", container.database.taskDao().getAllTasksOnce().isEmpty())
        assertTrue(
            "示例课程的时段也该跟着消失",
            container.courseRepository.getAllSlotsOnce().isEmpty()
        )
    }

    @Test
    fun `用户自己写在示例课程上的待办保留下来`() = runBlocking {
        val courseId = seedCourse("高等数学(上)", "MATH101")
        seedTask(courseId, "高数第三章习题 3.2")

        container.courseRepository.clearLegacySampleData()

        val tasks = container.database.taskDao().getAllTasksOnce()
        assertEquals("待办是用户自己的数据，删课程不该顺手删掉它", 1, tasks.size)
        assertEquals("高数第三章习题 3.2", tasks.single().title)
        assertEquals(
            "它该跟着课程一起失去归属，移进「未关联课程」",
            TaskEntity.UNLINKED_COURSE_ID,
            tasks.single().courseId
        )
    }

    @Test
    fun `课程代码相同但名字不同的真实课程不动`() = runBlocking {
        // 浙大的课号形如 CS201 是完全可能的，只看代码会连真实课程一起删掉。
        val courseId = seedCourse("计算机网络（真实课）", "CS201")
        seedTask(courseId, "抓包实验准备")

        val cleared = container.courseRepository.clearLegacySampleData()

        assertEquals("不该清任何课程", 0, cleared)
        assertEquals(1, container.courseRepository.getAllCoursesOnce().size)
        assertEquals(1, container.database.taskDao().getAllTasksOnce().size)
    }

    @Test
    fun `示例待办即使换了归属也照样清掉`() = runBlocking {
        // 删过示例课程的话，它的待办已经挂到「未关联课程」上了 ——
        // 那时候 courseId 已经不是示例课程，但标题还是那条，认的是标题不是归属。
        seedTask(TaskEntity.UNLINKED_COURSE_ID, "【预习】高等数学 第4章 定积分与微积分基本定理")

        container.courseRepository.clearLegacySampleData()

        assertTrue(container.database.taskDao().getAllTasksOnce().isEmpty())
    }

    @Test
    fun `库里没有示例数据时什么都不做`() = runBlocking {
        seedCourse("操作系统实验（旧课）", "CS302L")
        seedTask(1L, "手写待办")

        val cleared = container.courseRepository.clearLegacySampleData()

        assertEquals(0, cleared)
        assertEquals(1, container.courseRepository.getAllCoursesOnce().size)
        assertEquals(1, container.database.taskDao().getAllTasksOnce().size)
    }
}
