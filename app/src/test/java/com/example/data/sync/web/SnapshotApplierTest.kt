package com.example.data.sync.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.SlotWithWeeks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 接收远端快照的落库语义。
 *
 * 两条路径的差别是这一整套功能的核心：
 * - **merge（增量）** —— 只并进对方给的那几条，别的一概不动，**不删任何东西**；
 * - **replace（整份）** —— 本机整体变成对方那一份，**本机多出来的要删掉**。
 *
 * 删除能不能传播全靠后者；自动同步会不会误删全靠前者。
 */
@RunWith(RobolectricTestRunner::class)
// 用 SDK 34 而不是工程的 targetSdk 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建环境固定是 JDK 17。这里测的是 Room 的落库语义，与 API 36 无关。
@Config(sdk = [34])
class SnapshotApplierTest {

    private lateinit var container: AppContainer
    private lateinit var applier: SnapshotApplier

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
        applier = SnapshotApplier(container.database, container.courseRepository)
        // Robolectric 里 AppContainer 拿到的是数据库单例，**测试类之间共用同一个库**。
        // 不清一下，上一条用例留下的待办会让「本来该被删掉」这条断言失去意义。
        container.database.taskDao().clearTasks()
        container.courseRepository.clearAllCourses()
    }

    /**
     * 播一条本机待办。
     *
     * **直接走 DAO，不用 `TaskRepository.insertTask`** ——
     * 那个入口会盖上 `updatedAt = 当前时刻`（它就是「现在写的」那个语义），
     * 于是种子里的 `updatedAt = 100` 会被换成 1.7e12，
     * 「远端 300 更新」这条用例就会因为「远端其实更旧」而假绿。
     */
    private suspend fun seedTask(
        syncUid: String,
        title: String,
        updatedAt: Long,
        completed: Boolean = false,
    ) {
        container.database.taskDao().insertTask(
            TaskEntity(
                courseId = TaskEntity.UNLINKED_COURSE_ID,
                courseName = TaskEntity.UNLINKED_COURSE_NAME,
                courseCategory = "",
                courseColorHex = TaskEntity.UNLINKED_COLOR_HEX,
                title = title,
                taskType = "",
                dueDateTimestamp = TaskEntity.NO_DUE_TIMESTAMP,
                isCompleted = completed,
                updatedAt = updatedAt,
                syncUid = syncUid,
            )
        )
    }

    private suspend fun remoteTask(
        syncUid: String,
        title: String,
        updatedAt: Long,
        completed: Boolean = false,
        courseKey: String = "",
    ) = WebTaskDto(
        syncUid = syncUid,
        title = title,
        isCompleted = completed,
        completedAt = if (completed) updatedAt else null,
        dueDateTimestamp = TaskEntity.NO_DUE_TIMESTAMP,
        updatedAt = updatedAt,
        courseKey = courseKey,
    )

    @Test
    fun `merge 模式并进新的一条 - 本机原来没有它`() = runBlocking {
        seedTask("local-1", "本机原有", updatedAt = 100L)

        val report = applier.apply(
            WebSnapshotDto(tasks = listOf(remoteTask("web-1", "网页端新增", updatedAt = 200L))),
            SyncMode.MERGE,
        )

        val tasks = container.taskRepository.getAllTasksOnce()
        assertEquals(2, tasks.size)
        assertNotNull("远端新增的那条要进来了", tasks.firstOrNull { it.syncUid == "web-1" })
        assertTrue(report.appliedUids.contains("web-1"))
        assertEquals("merge 不该删任何东西", 0, report.tasksRemoved)
    }

    @Test
    fun `merge 模式里远端更新 - 远端那份赢 - 时间戳原样保留`() = runBlocking {
        seedTask("u1", "旧标题", updatedAt = 100L)

        applier.apply(
            WebSnapshotDto(tasks = listOf(remoteTask("u1", "新标题", updatedAt = 300L))),
            SyncMode.MERGE,
        )

        val task = container.taskRepository.getAllTasksOnce().first { it.syncUid == "u1" }
        assertEquals("新标题", task.title)
        assertEquals(
            "远端的时间戳必须原样保留 - 它是下一次合并的仲裁依据",
            300L,
            task.updatedAt
        )
    }

    @Test
    fun `merge 模式里远端更旧 - 保留本机那份 - 并且报出来`() = runBlocking {
        seedTask("u1", "本机更新的标题", updatedAt = 500L)

        val report = applier.apply(
            WebSnapshotDto(tasks = listOf(remoteTask("u1", "远端旧的标题", updatedAt = 100L))),
            SyncMode.MERGE,
        )

        val task = container.taskRepository.getAllTasksOnce().first { it.syncUid == "u1" }
        assertEquals("本机更新的标题", task.title)
        assertTrue("本机赢的条目必须报出来 - 那是冲突的信号", report.keptUids.contains("u1"))
        assertTrue(report.hasConflict)
    }

    @Test
    fun `merge 模式不删本机多出来的那些`() = runBlocking {
        seedTask("keep-1", "本机独有", updatedAt = 100L)

        applier.apply(
            WebSnapshotDto(tasks = listOf(remoteTask("web-1", "远端那条", updatedAt = 200L))),
            SyncMode.MERGE,
        )

        val uids = container.taskRepository.getAllTasksOnce().map { it.syncUid }.toSet()
        assertTrue("增量路径下本机独有的那条必须还在", "keep-1" in uids)
        assertTrue("web-1" in uids)
    }

    @Test
    fun `replace 模式把本机整体换成远端那一份 - 多出来的删掉`() = runBlocking {
        seedTask("only-local", "本机独有", updatedAt = 100L)
        seedTask("both", "两边都有", updatedAt = 100L)

        val report = applier.apply(
            WebSnapshotDto(tasks = listOf(remoteTask("both", "远端版本", updatedAt = 200L))),
            SyncMode.REPLACE,
        )

        val tasks = container.taskRepository.getAllTasksOnce()
        assertEquals("整份替换之后只剩远端那些", 1, tasks.size)
        assertEquals("both", tasks.first().syncUid)
        assertEquals("远端版本", tasks.first().title)
        assertEquals(1, report.tasksRemoved)
    }

    @Test
    fun `replace 模式空集合 - 意思是要全部清掉`() = runBlocking {
        seedTask("only-local", "本机独有", updatedAt = 100L)

        applier.apply(WebSnapshotDto(tasks = emptyList()), SyncMode.REPLACE)

        assertEquals(0, container.taskRepository.getAllTasksOnce().size)
    }

    @Test
    fun `课程块没有标记时不许动本机的课表 - 否则刚打开网页端就点同步会清空课表`() = runBlocking {
        container.courseRepository.saveCourseWithSlots(
            CourseEntity(
                name = "本机的课", code = "KEEP1", category = "公共基础",
                teacher = "王教授", classroom = "教二-301", colorHex = "#93C5FD"
            ),
            listOf(SlotWithWeeks(CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2), emptySet()))
        )

        applier.apply(
            WebSnapshotDto(
                hasCourses = false,
                tasks = listOf(remoteTask("u1", "一条待办", updatedAt = 100L)),
            ),
            SyncMode.REPLACE,
        )

        val courses = container.courseRepository.getAllCoursesOnce()
        assertEquals("本机的课表不该被一份没标记的快照动掉", 1, courses.size)
        assertEquals("本机的课", courses.first().name)
    }

    @Test
    fun `课程块有标记时整档替换 - 按自然键认人`() = runBlocking {
        applier.apply(
            WebSnapshotDto(
                hasCourses = true,
                courses = listOf(
                    WebCourseDto(
                        key = "MATH2432F|微积分|王",
                        name = "微积分",
                        code = "MATH2432F",
                        category = "公共基础",
                        teacher = "王",
                        classroom = "教十 201",
                        colorHex = "#7aa2ff",
                        slots = listOf(WebSlotDto(dayOfWeek = 1, startPeriod = 3, endPeriod = 4)),
                    )
                ),
                tasks = emptyList(),
            ),
            SyncMode.REPLACE,
        )

        val courses = container.courseRepository.getAllCoursesOnce()
        assertEquals(1, courses.size)
        assertEquals("微积分", courses.first().name)
        assertEquals("自然键要落库 - 下一次同步还要靠它认人", "MATH2432F|微积分|王", courses.first().dedupeKey)
        assertEquals(1, container.courseRepository.getSlotsForCourseSync(courses.first().id).size)
    }

    @Test
    fun `待办按 courseKey 挂到刚换新的课程 id 上`() = runBlocking {
        applier.apply(
            WebSnapshotDto(
                hasCourses = true,
                courses = listOf(
                    WebCourseDto(key = "K1", name = "新课程", code = "C1", category = "公共基础", teacher = "李", colorHex = "#fff")
                ),
                tasks = listOf(remoteTask("u1", "挂在这门课上", updatedAt = 100L, courseKey = "K1")),
            ),
            SyncMode.REPLACE,
        )

        val course = container.courseRepository.getAllCoursesOnce().first { it.dedupeKey == "K1" }
        val task = container.taskRepository.getAllTasksOnce().first { it.syncUid == "u1" }
        assertEquals("待办要挂到课程行上", course.id, task.courseId)
        assertEquals("新课程", task.courseName)
    }
}
