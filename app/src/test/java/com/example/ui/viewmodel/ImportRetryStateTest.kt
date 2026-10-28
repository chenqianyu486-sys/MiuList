package com.example.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.codec.CourseBundle
import com.example.data.codec.JsonScheduleCodec
import com.example.data.codec.ScheduleBundle
import com.example.data.codec.SlotBundle
import com.example.data.codec.TimetableBlockBundle
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.transfer.ImportStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import java.util.concurrent.TimeUnit

/**
 * 导入提交失败后重试的状态流。
 *
 * 用真实 [MainViewModel] + 真实 Room 跑：错误提示是界面上唯一告诉用户
 * 「刚才那次没成」的东西，它该出现时出现、该消失时消失，不能靠另一套
 * 状态转换来验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRetryStateTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var container: AppContainer
    private lateinit var viewModel: MainViewModel
    private lateinit var uiCollector: Job

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        container = AppContainer(ApplicationProvider.getApplicationContext<Context>())
        reset()
        viewModel = MainViewModel(container)
        // uiState 是 WhileSubscribed(5000)：没有订阅者就永远不会合并出导入状态。
        uiCollector = CoroutineScope(Dispatchers.IO).launch { viewModel.uiState.collect {} }
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        uiCollector.cancel()
        Dispatchers.resetMain()
        reset()
    }

    private fun reset() = runBlocking {
        container.database.taskDao().clearTasks()
        container.courseRepository.clearAllCourses()
        container.calendarRepository.saveCalendar(
            semester = null,
            periods = emptyList(),
            noClass = emptyList(),
            makeup = emptyList(),
            replacePeriods = false,
            replaceNoClass = true,
            replaceMakeup = true
        )
        container.settingsRepository.replaceAll(emptyList())
    }

    private fun seedCourse(category: String = "公共基础"): Long = runBlocking {
        container.courseRepository.saveCourseWithSlots(
            CourseEntity(
                name = "高等数学",
                code = "CS101",
                teacher = "王教授",
                category = category,
                classroom = "教二-301",
                colorHex = "#93C5FD"
            ),
            emptyList()
        )
    }

    /** 文件里不带类别：提交时才会读本机类别，于是能在预览之后制造冲突。 */
    private fun importJson(): String = JsonScheduleCodec().encode(
        ScheduleBundle(
            timetable = TimetableBlockBundle(
                courses = listOf(
                    CourseBundle(
                        name = "高等数学",
                        code = "CS101",
                        teacher = "王教授",
                        category = null,
                        slots = listOf(
                            SlotBundle(dayOfWeek = 1, startPeriod = 1, endPeriod = 2)
                        )
                    )
                )
            )
        )
    )

    private fun previewAndWait(): MainUiState {
        viewModel.openTransferDialog()
        viewModel.setImportStrategy(ImportStrategy.MERGE)
        viewModel.previewImportAndHold(importJson())
        return awaitState("预览完成") { it.importPreview != null && !it.isImporting }
    }

    private fun awaitState(what: String, predicate: (MainUiState) -> Boolean): MainUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            val state = viewModel.uiState.value
            if (predicate(state)) return state
            Thread.sleep(10)
        }
        throw AssertionError("等待「$what」超时，当前状态=${viewModel.uiState.value}")
    }

    @Test
    fun `失败后重试成功：旧错误清除、报告出现`() {
        seedCourse()
        val ready = previewAndWait()
        assertNull("预览成功时不该有错误", ready.importError)

        // 预览之后本机多出一条同键不同类别的课程：提交时无法决定继承哪个类别。
        val duplicate = seedCourse("专业必修")

        viewModel.commitImport()
        val failed = awaitState("第一次提交失败") { !it.isImporting && it.importError != null }
        assertNotNull("第一次失败必须把错误显示出来", failed.importError)
        assertTrue("错误应说明类别冲突", failed.importError!!.contains("类别"))
        assertNotNull("失败后必须保留预览，用户才能原地重试", failed.importPreview)

        runBlocking { container.courseRepository.deleteCourse(duplicate) }

        viewModel.commitImport()
        scheduler.runCurrent()
        val started = viewModel.uiState.value
        assertNull("新一轮提交一开始就该清掉上一轮的错误", started.importError)
        assertTrue("提交进行中应保持忙碌", started.isImporting)

        val done = awaitState("第二次提交成功") { !it.isImporting && it.importReport != null }
        assertEquals("同一门课应被更新，而不是新增", 1, done.importReport!!.updated)
        assertNull("提交成功后不能残留错误", done.importError)
        assertNull("提交成功后预览应收起", done.importPreview)
    }

    @Test
    fun `第二次仍失败：显示本次错误`() {
        seedCourse()
        previewAndWait()
        val duplicate = seedCourse("专业必修")

        viewModel.commitImport()
        val first = awaitState("第一次提交失败") { !it.isImporting && it.importError != null }
        val firstError = first.importError!!

        runBlocking { container.courseRepository.updateCourseCategory(duplicate, "通识公共") }

        viewModel.commitImport()
        val second = awaitState("第二次提交失败") {
            !it.isImporting && it.importError != null && it.importError != firstError
        }
        assertTrue("应显示本次的类别冲突", second.importError!!.contains("通识公共"))
        assertFalse("不能把上一轮的错误原样留着", second.importError == firstError)
        assertNotNull("再次失败后预览仍应保留", second.importPreview)
    }
}
