package com.example.ui.viewmodel

import com.example.data.local.TaskEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点那颗小圆圈要不要先问一句」。
 *
 * 两个边界各钉一条：
 *
 * - **勾上要问，取消不问**：取消完成是一次撤销，没有风险；
 *   给它也加一道确认，只会让「改回来」变麻烦。
 * - **开关关着就不问**：用户在设置里明确关掉了它，界面必须立刻照办。
 */
class TaskCompleteConfirmRuleTest {

    private fun task(completed: Boolean) = TaskEntity(
        id = 1L,
        courseId = 1L,
        courseName = "高等数学A（上）",
        courseCategory = "必修",
        courseColorHex = "#FDBA74",
        title = "第3周预习",
        taskType = "HOMEWORK",
        dueDateTimestamp = 100L,
        isCompleted = completed
    )

    @Test
    fun `勾上完成时要问`() {
        assertTrue(
            "勾掉一条待办是看不见的丢失，要问一句",
            shouldConfirmTaskComplete(task(completed = false), confirmEnabled = true)
        )
    }

    @Test
    fun `取消完成时不问`() {
        assertFalse(
            "取消完成是一次撤销，没有风险，不该再问一遍",
            shouldConfirmTaskComplete(task(completed = true), confirmEnabled = true)
        )
    }

    @Test
    fun `开关关着时勾上也不问`() {
        assertFalse(
            "用户在设置里关掉了它，界面就必须立刻照办",
            shouldConfirmTaskComplete(task(completed = false), confirmEnabled = false)
        )
    }

    @Test
    fun `开关关着时取消完成同样不问`() {
        assertFalse(shouldConfirmTaskComplete(task(completed = true), confirmEnabled = false))
    }
}
