package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CourseEntity
import com.example.data.local.CourseSlotEntity
import com.example.data.model.SlotWithWeeks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 学期周数变小时的**开课周次裁剪**。Robolectric + 真实 Room。
 *
 * 这一层要守住的是「被裁空的时段要退回每周」：
 * 学期从 20 周缩到 16 周时，只在第 20 周开的课，那一行周次会被删光；
 * 它若就此变成「这学期一次都不上」，用户看到的就是这门课凭空消失，
 * 而界面上看不出任何异常。
 *
 * 早先这条修复**从未真正执行过**：查「哪些时段的周次被删空了」那条 SQL
 * 反着查（查的是周次表里有、时段表里没有的孤儿行），正常情况下永远是空集，
 * 于是裁剪只删不补 —— 课就这么没了，还没有任何报错。
 */
@RunWith(RobolectricTestRunner::class)
// 用 SDK 34 而不是工程的 targetSdk 36：Robolectric 的 36 号沙箱要求 JDK 21，
// 本机构建环境固定是 JDK 17。这里验的是 SQL 与事务行为，与 API 36 无关。
@Config(sdk = [34])
class SlotWeekClampRobolectricTest {

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Context>())

    /**
     * 收尾清空。Robolectric 里 `AppContainer` 拿的是 `AppDatabase` 单例，
     * **测试类之间共用同一个库文件**，不留干净就会串到别的类上。
     */
    @After
    fun tearDown() {
        runBlocking {
            container().courseRepository.clearAllCourses()
        }
    }

    /**
     * 两个时段：一个只有第 20 周（学期一缩，它那行周次就被裁光），
     * 一个还留着第 1、3 周（裁剪不该动它）。
     *
     * @return 第一个（会被裁空的）与第二个（该活着的）时段 id
     */
    private suspend fun seedCourse(c: AppContainer): Pair<Long, Long> {
        val courseId = c.courseRepository.saveCourseWithSlots(
            CourseEntity(
                name = "高等数学", code = "MATH101", category = "公共基础",
                teacher = "王教授", classroom = "教二-301", colorHex = "#93C5FD"
            ),
            listOf(
                SlotWithWeeks(
                    slot = CourseSlotEntity(courseId = 0, dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
                    weeks = setOf(20)
                ),
                SlotWithWeeks(
                    slot = CourseSlotEntity(courseId = 0, dayOfWeek = 3, startPeriod = 3, endPeriod = 4),
                    weeks = setOf(1, 3)
                )
            )
        )
        val slots = c.courseRepository.getSlotsForCourseSync(courseId)
        val emptied = slots.first { it.dayOfWeek == 1 }.id
        val kept = slots.first { it.dayOfWeek == 3 }.id
        return emptied to kept
    }

    @Test
    fun `学期变短后被裁空的时段要退回每周`() = runBlocking {
        val c = container()
        c.courseRepository.clearAllCourses()
        val (emptied, kept) = seedCourse(c)

        c.courseRepository.clampSlotWeeksTo(16)

        assertEquals(
            "周次被裁空后要按每周重新铺回来",
            (1..16).toSet(),
            c.courseRepository.getWeeksBySlotIdOnce()[emptied]
        )
        assertEquals(
            "还留着周次行的时段不该被动",
            setOf(1, 3),
            c.courseRepository.getWeeksBySlotIdOnce()[kept]
        )
    }

    /**
     * 查的是「有时段、没周次」，不是反过来。
     *
     * 反过来查拿到的是**孤儿周次行**（周次表里有、时段表里没有的那些 id），
     * 正常情况下永远是空集 —— 于是「周次被删光的时段」一个都找不出来，
     * 上面那条修复也就永远不会执行。
     */
    @Test
    fun `找的是周次被删光的时段而不是孤儿行`() = runBlocking {
        val c = container()
        c.courseRepository.clearAllCourses()
        val (emptied, kept) = seedCourse(c)
        val weekDao = c.database.courseSlotWeekDao()

        // 前置条件：两个时段都有周次行
        assertEquals(setOf(20), weekDao.getForSlot(emptied).map { it.week }.toSet())
        assertEquals(setOf(1, 3), weekDao.getForSlot(kept).map { it.week }.toSet())

        // 裁剪前：周次被删光的时段一个都没有
        assertEquals(emptyList<Long>(), weekDao.findSlotsMissingFrom(listOf(emptied, kept)))

        weekDao.deleteWeeksAbove(16)

        assertEquals(
            "只有周次被删光的那个时段该被找出来",
            listOf(emptied),
            weekDao.findSlotsMissingFrom(listOf(emptied, kept))
        )
    }
}
