package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.AppContainer
import com.example.data.local.CustomTaskTypeEntity
import com.example.data.local.TaskEntity
import com.example.data.model.TASK_TYPE_NONE
import com.example.data.model.TaskType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 待办分类管理与删除自定义分类（连带删除分类下待办）测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCategoryDeletionRobolectricTest {

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun tearDown() {
        runBlocking {
            val c = container()
            c.database.taskDao().clearTasks()
            c.database.customTaskTypeDao().clear()
        }
    }

    private fun sampleTask(
        id: Long = 0L,
        title: String,
        taskType: String
    ) = TaskEntity(
        id = id,
        courseId = TaskEntity.UNLINKED_COURSE_ID,
        courseName = TaskEntity.UNLINKED_COURSE_NAME,
        courseCategory = "",
        courseColorHex = TaskEntity.UNLINKED_COLOR_HEX,
        title = title,
        taskType = taskType,
        dueDateTimestamp = 1000L
    )

    @Test
    fun `删除自定义分类将同时删除该分类下的所有待办且不影响其他分类`() = runBlocking {
        val c = container()
        val taskRepo = c.taskRepository
        val typeRepo = c.taskTypeRepository

        // 1. 创建两个自定义分类
        val type1Result = typeRepo.create("读书笔记", 0)
        val type2Result = typeRepo.create("小组汇报", 1)
        assertTrue(type1Result.isSuccess)
        assertTrue(type2Result.isSuccess)
        val id1 = type1Result.getOrThrow()
        val id2 = type2Result.getOrThrow()
        val key1 = "CUSTOM:$id1"
        val key2 = "CUSTOM:$id2"

        // 2. 插入属于各分类的待办
        // 属于 type1 的 2 条
        taskRepo.insertTask(sampleTask(title = "读资本论第一章", taskType = key1))
        taskRepo.insertTask(sampleTask(title = "读国富论第二章", taskType = key1))
        // 属于 type2 的 1 条
        taskRepo.insertTask(sampleTask(title = "制作小组汇报PPT", taskType = key2))
        // 属于自带分类的待办
        taskRepo.insertTask(sampleTask(title = "高数预习", taskType = TaskType.PRE_STUDY.name))
        taskRepo.insertTask(sampleTask(title = "线代作业", taskType = TaskType.POST_CLASS.name))
        taskRepo.insertTask(sampleTask(title = "大物实验报告", taskType = TaskType.LAB_REPORT.name))
        taskRepo.insertTask(sampleTask(title = "收拾书桌", taskType = TASK_TYPE_NONE))

        assertEquals(7, taskRepo.countTasks())
        assertEquals(2, taskRepo.countTasksByType(key1))
        assertEquals(1, taskRepo.countTasksByType(key2))

        // 3. 删除自定义分类 type1（读书笔记）
        val deletedCount = typeRepo.delete(id1)
        assertEquals(2, deletedCount)

        // 4. 验证：type1 的待办全部被删除，type1 本身也从 custom_task_types 中被删除
        val remainingTasks = taskRepo.getAllTasksOnce()
        assertEquals(5, remainingTasks.size)
        assertTrue("属于 type1 的待办应全部删除", remainingTasks.none { it.taskType == key1 })

        // 5. 验证：type2 和自带分类的待办完好无损
        assertTrue("type2 的待办应依然存在", remainingTasks.any { it.taskType == key2 && it.title == "制作小组汇报PPT" })
        assertTrue("PRE_STUDY 的待办应依然存在", remainingTasks.any { it.taskType == TaskType.PRE_STUDY.name })
        assertTrue("POST_CLASS 的待办应依然存在", remainingTasks.any { it.taskType == TaskType.POST_CLASS.name })
        assertTrue("LAB_REPORT 的待办应依然存在", remainingTasks.any { it.taskType == TaskType.LAB_REPORT.name })
        assertTrue("未分类的待办应依然存在", remainingTasks.any { it.taskType == TASK_TYPE_NONE })

        val customTypes = typeRepo.getAllOnce()
        assertEquals(1, customTypes.size)
        assertEquals(id2, customTypes.first().id)
    }
}
