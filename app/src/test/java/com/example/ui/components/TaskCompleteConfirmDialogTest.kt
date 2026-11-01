package com.example.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 勾掉待办前的二次确认弹层。
 *
 * 三件事要钉住：
 *
 * - **待办名摆出来**：屏幕上往往有好几条待办，一句「完成这条待办？」
 *   没有指名道姓，用户得先自己对号，对不上就点错了。
 * - **用的是卡片上那个名字**：弹层里写「高等数学A（上） 第3周预习」而卡片上
 *   只写「第3周预习」，用户仍然对不上号（见 [taskDisplayTitle]）。
 * - **不再科普后果**：弹层不写「标成已完成后它会从清单里消失」——
 *   用户点的是自己刚勾掉的那颗圆圈，确认的意义是**对号**而不是讲解。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCompleteConfirmDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val task = TaskEntity(
        id = 7L,
        courseId = 1L,
        courseName = "高等数学A（上）",
        courseCategory = "必修",
        courseColorHex = "#FDBA74",
        // 新格式：课程名在【】里。卡片上显示的是裁掉课程名的那一份。
        title = "【高等数学A（上）】第3周周三预习",
        taskType = "HOMEWORK",
        dueDateTimestamp = 100L
    )

    @Test
    fun `弹层里摆出待办名且不科普后果`() {
        composeTestRule.setContent {
            TaskCompleteConfirmDialog(task = task, onDismiss = {}, onConfirm = {})
        }

        composeTestRule.onNodeWithText("完成这条待办？").assertIsDisplayed()
        // 裁掉课程名的那一份，与卡片上显示的同一个名字。
        composeTestRule.onNodeWithText("第3周周三预习").assertIsDisplayed()
        // 后果那句已经拿掉：确认是为了对号，不是给用户讲解「完成会发生什么」。
        composeTestRule
            .onNodeWithText("标成已完成后，它会从待办清单里消失。")
            .assertDoesNotExist()
    }

    @Test
    fun `按完成走确认那一路`() {
        var confirmed = false

        composeTestRule.setContent {
            TaskCompleteConfirmDialog(
                task = task,
                onDismiss = {},
                onConfirm = { confirmed = true }
            )
        }

        composeTestRule.onNodeWithTag("task_complete_confirm_button").performClick()

        assertTrue("按「完成」要真的完成", confirmed)
    }

    @Test
    fun `按取消什么也不做`() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            TaskCompleteConfirmDialog(
                task = task,
                onDismiss = { dismissed = true },
                onConfirm = { confirmed = true }
            )
        }

        composeTestRule.onNodeWithTag("task_complete_cancel_button").performClick()

        assertTrue("按「取消」要收起弹窗", dismissed)
        assertEquals("按「取消」不能顺手完成了", false, confirmed)
    }
}
