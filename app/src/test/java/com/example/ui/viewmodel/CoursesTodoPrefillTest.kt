package com.example.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.local.TaskEntity
import com.example.data.model.SlotWithWeeks
import com.example.data.source.zju.CoursesTodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「从学在浙大加一条待办」要挂到**匹配上的那门课**上。
 *
 * ## 这一层为什么必须真的建 ViewModel
 *
 * 曾经出过这么一个 bug：弹窗上方那颗课程色圆点是对上的（读的是界面的 state），
 * 点「添加到待办」进来却显示「未关联」。
 *
 * 原因不在匹配算法，在**读的是哪一份状态**：
 * `_uiState` 只装「一次性动作」（弹层开关、userMessage），
 * 数据库派生的字段（`courses`）一个都不写它 —— 只存在于 `uiState`
 * 那条 combine 的结果里。读 `_uiState.value.courses` 拿到的是**恒为空的默认表**，
 * 于是按课程名匹配必然落空。
 *
 * 而这种错**在纯函数层测不出来**：[buildCoursesTodoDraft] 拿到的课程表是对的，
 * 错的是调用它的人传了哪张表。所以这条测试必须真的建一个 ViewModel、
 * 真的让它从 Room 里把课程表读出来，再断言搬过来的待办挂上了那门课。
 *
 * 跑的是 Robolectric + 真实 Room：课程表得真的在库里，combine 才真的能读到它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// 用 SDK 34 而不是工程的 targetSdk 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建环境固定是 JDK 17。这里验的是「待办挂到了哪门课」，与 API 36 无关。
@Config(sdk = [34])
class CoursesTodoPrefillTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        // ViewModel 里的协程全挂在 Dispatchers.Main 上；测试里得给它一个能立刻跑的
        // 调度器，否则 viewModelScope 里的活儿永远排在 Robolectric 那个暂停的
        // 主线程队列里等着。
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = AppContainer(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        // Robolectric 里 AppDatabase 是单例，**测试类之间共用同一个库文件**：
        // 本类插进去的课程不清掉，会漏到别的类里。
        runBlocking { container.courseRepository.clearAllCourses() }
        Dispatchers.resetMain()
    }

    private suspend fun seedCourse(name: String): Long =
        container.courseRepository.saveCourseWithSlots(
            CourseEntity(
                name = name,
                category = "专业必修",
                teacher = "王教授",
                classroom = "教二-301",
                colorHex = "#93C5FD"
            ),
            listOf(
                SlotWithWeeks(
                    slot = CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
                    weeks = setOf(1, 2)
                )
            )
        )

    private fun zjuItem(courseName: String, title: String = "HW06 二极管及其应用电路") = CoursesTodoItem(
        id = "1165132",
        title = title,
        courseName = courseName,
        dueTimestamp = 1794754800000L,
        dueFormatted = "11月15日 23:00",
        daysRemainingText = "7天后",
        isUrgent = false
    )

    @Test
    fun `搬过来的待办挂到同名的那门课上`() = runTest {
        val courseId = seedCourse("电子电路基础")
        val vm = MainViewModel(container)
        // 订阅着 uiState，combine 才会真的把课程表读出来（WhileSubscribed）。
        val collector = launch(UnconfinedTestDispatcher()) { vm.uiState.collect {} }
        try {
            vm.uiState.first { it.courses.any { c -> c.name == "电子电路基础" } }

            vm.addCoursesTodoToMiuList(zjuItem(courseName = "电子电路基础"))

            val editor = vm.uiState.first { it.taskEditor != null }.taskEditor!!
            assertEquals(
                "关联课程必须挂到匹配上的那门课（courseId）",
                courseId,
                editor.task?.courseId
            )
            assertEquals(
                "编辑弹层的预选课程要跟草稿一致，否则弹层里显示的还是「未关联」",
                courseId,
                editor.preselectedCourseId
            )
            assertEquals("电子电路基础", editor.task?.courseName)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `课程名只是格式不同时也挂上去`() = runTest {
        val courseId = seedCourse("大学物理")
        val vm = MainViewModel(container)
        val collector = launch(UnconfinedTestDispatcher()) { vm.uiState.collect {} }
        try {
            vm.uiState.first { it.courses.any { c -> c.name == "大学物理" } }

            vm.addCoursesTodoToMiuList(zjuItem(courseName = "大学物理（甲）Ⅱ"))

            val editor = vm.uiState.first { it.taskEditor != null }.taskEditor!!
            assertEquals(courseId, editor.task?.courseId)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `对不上的课程不乱挂`() = runTest {
        seedCourse("大学化学")
        val vm = MainViewModel(container)
        val collector = launch(UnconfinedTestDispatcher()) { vm.uiState.collect {} }
        try {
            vm.uiState.first { it.courses.any { c -> c.name == "大学化学" } }

            vm.addCoursesTodoToMiuList(zjuItem(courseName = "大学物理"))

            val editor = vm.uiState.first { it.taskEditor != null }.taskEditor!!
            // 挂错课比不挂更糟：那会让这条作业出现在一门不相干的课的时间线里。
            assertEquals(
                "对不上就留空",
                TaskEntity.UNLINKED_COURSE_ID,
                editor.task?.courseId
            )
            assertEquals(null, editor.preselectedCourseId)
        } finally {
            collector.cancel()
        }
    }
}
