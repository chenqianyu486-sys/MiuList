package com.example.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.data.local.CourseEntity
import com.example.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手动新建待办时，**标题不预填**（2026-10-27 用户定）。
 *
 * 名字是用户自己的话，App 替他写好等于先塞一段字给他删；
 * 而「【作业】数据结构」这种模板还替用户断言了这条待办的性质。
 *
 * 断言方式用「占位符在不在 + 模板标题在不在」，而不是读输入框的值：
 * 占位符（"待办名称"）与输入框内容都会进语义树，
 * 这两条断言在任何一种合并规则下都成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskEditorSheetTitleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mathCourse = CourseEntity(
        id = 101L,
        name = "高等数学",
        code = "MATH101",
        category = "通识必修",
        teacher = "张教授",
        classroom = "东1-101",
        colorHex = "#93C5FD"
    )

    private val linearCourse = CourseEntity(
        id = 102L,
        name = "线性代数",
        code = "MATH102",
        category = "通识必修",
        teacher = "李教授",
        classroom = "东2-201",
        colorHex = "#A6D6E4"
    )

    private fun sheet(initialTask: TaskEntity?, preselectedCourseId: Long? = mathCourse.id) {
        composeTestRule.setContent {
            TaskEditorSheet(
                initialTask = initialTask,
                courses = listOf(mathCourse, linearCourse),
                customTypes = emptyList(),
                preselectedCourseId = preselectedCourseId,
                onDismiss = {},
                onCreateTaskType = {},
                onDelete = {},
                onSave = {}
            )
        }
    }

    @Test
    fun picking_a_category_does_not_prefill_the_title_of_a_new_task() {
        sheet(initialTask = null, preselectedCourseId = mathCourse.id)

        // 刚打开：标题框是空的，只有占位符。
        composeTestRule.onNodeWithText("待办名称").assertExists()

        composeTestRule.onNodeWithTag("task_edit_type_POST_CLASS")
            .performSemanticsAction(SemanticsActions.OnClick)

        // 分类选完了，标题**仍然**是空的 —— App 没有替用户写名字。
        composeTestRule.onNodeWithText("待办名称").assertExists()
        composeTestRule.onNodeWithText("【作业】高等数学").assertDoesNotExist()
    }

    @Test
    fun changing_the_course_does_not_prefill_the_title_of_a_new_task() {
        sheet(initialTask = null, preselectedCourseId = mathCourse.id)

        // 展开课程滚筒，再点另一门课。
        composeTestRule.onNodeWithContentDescription("更换关联课程")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("线性代数")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("待办名称").assertExists()
        composeTestRule.onNodeWithText("线性代数 待办").assertDoesNotExist()
    }

    @Test
    fun editing_still_refreshes_a_title_that_is_still_a_template() {
        // 编辑的是**已有**待办：那条标题本来就是这条待办的名字，
        // 仍是模板时换分类就该跟着换（新建不预填是另一回事）。
        sheet(
            initialTask = TaskEntity(
                id = 201L,
                courseId = mathCourse.id,
                courseName = "高等数学",
                courseCategory = "通识必修",
                courseColorHex = "#93C5FD",
                title = "【作业】高等数学",
                taskType = "POST_CLASS",
                dueDateTimestamp = 1792454400000L
            ),
            preselectedCourseId = mathCourse.id
        )

        composeTestRule.onNodeWithTag("task_edit_type_PRE_STUDY")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("【预习】高等数学").assertExists()
    }
}

/**
 * 「标题要不要跟着分类 / 课程换」这条规则本身（[nextAutoTitle]）。
 *
 * 抽成纯函数是为了让**规则**能在不渲染的情况下被钉住 ——
 * 弹层那条路径（点 chip）另有 Compose 测试守着。
 */
class NextAutoTitleTest {

    @Test
    fun a_new_task_never_gets_an_auto_title() {
        // 标题是空的：不填。
        assertNull(
            nextAutoTitle(
                isNewTask = true,
                currentTitle = "",
                oldTemplate = "【作业】高等数学",
                newTemplate = "【预习】高等数学"
            )
        )
        // 标题正好是旧模板：也不填 —— 新建本来就不该有模板标题。
        assertNull(
            nextAutoTitle(
                isNewTask = true,
                currentTitle = "【作业】高等数学",
                oldTemplate = "【作业】高等数学",
                newTemplate = "【预习】高等数学"
            )
        )
    }

    @Test
    fun editing_refreshes_a_title_that_is_still_a_template() {
        assertEquals(
            "【预习】高等数学",
            nextAutoTitle(
                isNewTask = false,
                currentTitle = "【作业】高等数学",
                oldTemplate = "【作业】高等数学",
                newTemplate = "【预习】高等数学"
            )
        )
    }

    @Test
    fun editing_leaves_a_title_the_user_wrote_alone() {
        assertNull(
            nextAutoTitle(
                isNewTask = false,
                currentTitle = "习题集 P134",
                oldTemplate = "【作业】高等数学",
                newTemplate = "【预习】高等数学"
            )
        )
    }

    @Test
    fun editing_fills_an_empty_title() {
        assertEquals(
            "【预习】高等数学",
            nextAutoTitle(
                isNewTask = false,
                currentTitle = "",
                oldTemplate = "【作业】高等数学",
                newTemplate = "【预习】高等数学"
            )
        )
    }
}
