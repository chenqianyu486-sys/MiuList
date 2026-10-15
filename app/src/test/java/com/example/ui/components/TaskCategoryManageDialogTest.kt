package com.example.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.local.TaskEntity
import com.example.data.model.CustomTaskType
import com.example.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCategoryManageDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val customType1 = CustomTaskType(
        id = "c1",
        label = "读书笔记",
        colorHex = "#A6D6E4",
        inkHex = "#3F5F69"
    )

    private val task1 = TaskEntity(
        id = 1L,
        courseId = 0L,
        courseName = "未关联",
        courseCategory = "",
        courseColorHex = "#C9D0D8",
        title = "读资本论",
        taskType = "CUSTOM:c1",
        dueDateTimestamp = 1000L
    )

    private val builtinTask = TaskEntity(
        id = 2L,
        courseId = 0L,
        courseName = "未关联",
        courseCategory = "",
        courseColorHex = "#C9D0D8",
        title = "高数预习",
        taskType = TaskType.PRE_STUDY.name,
        dueDateTimestamp = 1000L
    )

    @Test
    fun dialog_renders_builtin_and_custom_categories_correctly() {
        var deletedType: CustomTaskType? = null
        var isDismissed = false
        var isNewTypeOpened = false

        composeTestRule.setContent {
            TaskCategoryManageDialog(
                customTypes = listOf(customType1),
                tasks = listOf(task1, builtinTask),
                onDismiss = { isDismissed = true },
                onDeleteCustomType = { deletedType = it },
                onOpenNewTypeDialog = { isNewTypeOpened = true }
            )
        }

        // 验证标题和提示
        composeTestRule.onNodeWithText("待办分类管理").assertExists()
        composeTestRule.onNodeWithText("自带分类不可删除；删除自定义分类将同时删除该分类下的所有待办。").assertExists()

        // 验证 4 个自带分类均有显示
        composeTestRule.onNodeWithText("预习").assertExists()
        composeTestRule.onNodeWithText("作业").assertExists()
        composeTestRule.onNodeWithText("实验报告").assertExists()
        composeTestRule.onNodeWithText("未分类").assertExists()

        // 验证自定义分类与待办数
        composeTestRule.onNodeWithText("读书笔记").assertExists()
        composeTestRule.onNodeWithText("1 条待办").assertExists()

        // 点击自定义分类的删除按钮（先滚动到可见位置）
        composeTestRule.onNodeWithTag("delete_category_button_c1").performScrollTo().performClick()
        assertEquals(customType1, deletedType)

        // 点击完成按钮
        composeTestRule.onNodeWithTag("close_category_manage_dialog_button").performClick()
        assertTrue(isDismissed)
    }

    @Test
    fun delete_confirm_dialog_explains_cascade_deletion() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            DeleteTaskTypeConfirmDialog(
                typeLabel = "读书笔记",
                taskCount = 3,
                onDismiss = { dismissed = true },
                onConfirm = { confirmed = true }
            )
        }

        composeTestRule.onNodeWithText("删除分类「读书笔记」？").assertIsDisplayed()
        composeTestRule.onNodeWithText("将同时删除该分类下的所有待办（含 3 条待办），操作无法恢复。").assertIsDisplayed()

        composeTestRule.onNodeWithTag("confirm_delete_task_type_button").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun settings_screen_renders_category_management_card() {
        var openedCategoryManage = false
        val state = com.example.ui.viewmodel.MainUiState(
            customTaskTypes = listOf(customType1)
        )

        composeTestRule.setContent {
            com.example.ui.screens.SettingsScreen(
                state = state,
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = { openedCategoryManage = true },
                onResetDemoData = {}
            )
        }

        // 验证设置页有待办分类管理卡片，且副标题包含自定义分类数量
        composeTestRule.onNodeWithText("待办分类管理").assertExists()
        composeTestRule.onNodeWithText("4 个自带分类 · 1 个自定义分类").assertExists()

        // 点击「管理」按钮
        composeTestRule.onNodeWithTag("open_task_category_dialog_button").performClick()
        assertTrue(openedCategoryManage)
    }
}
