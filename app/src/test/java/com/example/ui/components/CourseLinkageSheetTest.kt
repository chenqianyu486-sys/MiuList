package com.example.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.example.data.local.CourseEntity
import com.example.data.local.CourseWithSlots
import com.example.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CourseLinkageSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleCourse = CourseEntity(
        id = 101L,
        name = "高等数学",
        code = "MATH101",
        category = "通识必修",
        teacher = "张教授",
        classroom = "东1-101",
        colorHex = "#93C5FD"
    )

    private val sampleTask = TaskEntity(
        id = 201L,
        title = "完成高数第三章习题",
        dueDateString = "2026-10-20",
        dueDateTimestamp = 1792454400000L,
        courseId = 101L,
        courseName = "高等数学",
        courseColorHex = "#93C5FD",
        courseCategory = "通识必修",
        taskType = "作业",
        isCompleted = false
    )

    @Test
    fun clicking_task_row_opens_task_inspection() {
        var inspectedTask: TaskEntity? = null
        var toggledTask: TaskEntity? = null

        composeTestRule.setContent {
            CourseLinkageSheet(
                courseWithSlots = CourseWithSlots(course = sampleCourse, slots = emptyList()),
                linkedTasks = listOf(sampleTask),
                customTypes = emptyList(),
                onDismiss = {},
                onToggleTaskComplete = { toggledTask = it },
                onInspectTask = { inspectedTask = it },
                onAddNewTaskForCourse = {},
                onEditCourse = {},
                onDeleteCourse = {}
            )
        }

        // 点击待办文字/整行，应触发 onInspectTask，而不触发 onToggleTaskComplete
        composeTestRule.onNodeWithText("完成高数第三章习题").performClick()

        assertEquals("点击待办应打开待办详情弹层", sampleTask.id, inspectedTask?.id)
        assertEquals("点击待办卡片正文不应触发勾选完成", null, toggledTask)
    }

    @Test
    fun clicking_left_circular_icon_only_toggles_completion() {
        var inspectedTask: TaskEntity? = null
        var toggledTask: TaskEntity? = null

        composeTestRule.setContent {
            CourseLinkageSheet(
                courseWithSlots = CourseWithSlots(course = sampleCourse, slots = emptyList()),
                linkedTasks = listOf(sampleTask),
                customTypes = emptyList(),
                onDismiss = {},
                onToggleTaskComplete = { toggledTask = it },
                onInspectTask = { inspectedTask = it },
                onAddNewTaskForCourse = {},
                onEditCourse = {},
                onDeleteCourse = {}
            )
        }

        // 只点击左侧小圆圈图标
        composeTestRule.onNodeWithTag("course_sheet_task_check_${sampleTask.id}").performClick()

        assertEquals("点击左侧小圆圈应触发切换完成状态", sampleTask.id, toggledTask?.id)
        assertEquals("点击左侧小圆圈不应触发打开待办详情", null, inspectedTask)
    }

    @Test
    fun clicking_right_star_button_only_toggles_star() {
        var inspectedTask: TaskEntity? = null
        var toggledTask: TaskEntity? = null
        var starredTask: TaskEntity? = null

        composeTestRule.setContent {
            CourseLinkageSheet(
                courseWithSlots = CourseWithSlots(course = sampleCourse, slots = emptyList()),
                linkedTasks = listOf(sampleTask),
                customTypes = emptyList(),
                onDismiss = {},
                onToggleTaskComplete = { toggledTask = it },
                onToggleTaskStar = { starredTask = it },
                onInspectTask = { inspectedTask = it },
                onAddNewTaskForCourse = {},
                onEditCourse = {},
                onDeleteCourse = {}
            )
        }

        // 只点击右侧星标图标
        composeTestRule.onNodeWithTag("course_sheet_task_star_${sampleTask.id}").performClick()

        assertEquals("点击右侧星标按钮应触发切换标星状态", sampleTask.id, starredTask?.id)
        assertEquals("点击右侧星标按钮不应触发勾选完成", null, toggledTask)
        assertEquals("点击右侧星标按钮不应触发打开待办详情", null, inspectedTask)
    }

    @Test
    fun star_button_reflects_starred_and_unstarred_states() {
        val starredTask = sampleTask.copy(id = 202L, priority = "HIGH")
        composeTestRule.setContent {
            CourseLinkageSheet(
                courseWithSlots = CourseWithSlots(course = sampleCourse, slots = emptyList()),
                linkedTasks = listOf(sampleTask, starredTask),
                customTypes = emptyList(),
                onDismiss = {},
                onToggleTaskComplete = {},
                onToggleTaskStar = {},
                onInspectTask = {},
                onAddNewTaskForCourse = {},
                onEditCourse = {},
                onDeleteCourse = {}
            )
        }

        // 未标星的待办，星标按钮为 "标星"
        composeTestRule.onNodeWithTag("course_sheet_task_star_${sampleTask.id}")
            .assertContentDescriptionEquals("标星")

        // 已标星的待办，星标按钮为 "取消星标"
        composeTestRule.onNodeWithTag("course_sheet_task_star_${starredTask.id}")
            .assertContentDescriptionEquals("取消星标")
    }

    @Test
    fun clicking_delete_asks_for_confirmation_first() {
        var deleteRequested = false

        composeTestRule.setContent {
            CourseLinkageSheet(
                courseWithSlots = CourseWithSlots(course = sampleCourse, slots = emptyList()),
                linkedTasks = listOf(sampleTask),
                customTypes = emptyList(),
                onDismiss = {},
                onToggleTaskComplete = {},
                onInspectTask = {},
                onAddNewTaskForCourse = {},
                onEditCourse = {},
                onDeleteCourse = { deleteRequested = true }
            )
        }

        // 这一步只该「请求删除」，真正的落库要等用户在确认弹层里点「删除」。
        // 用语义动作而不是 performClick：删除按钮在弹层最底部，
        // Robolectric 的默认视口截不到它，按坐标注入的触摸会落到窗口外而静默失败。
        composeTestRule.onNodeWithText("删除课程").performSemanticsAction(SemanticsActions.OnClick)

        assertTrue("点「删除课程」应触发删除请求（进而弹出确认层）", deleteRequested)
    }
}

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class DeleteCourseConfirmDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun dialog(linkedTaskCount: Int, onState: (String) -> Unit) {
        composeTestRule.setContent {
            DeleteCourseConfirmDialog(
                courseName = "高等数学",
                slotCount = 2,
                linkedTaskCount = linkedTaskCount,
                onDismiss = { onState("dismiss") },
                onConfirm = { onState("confirm") }
            )
        }
    }

    @Test
    fun confirms_only_after_tapping_delete() {
        var action = "none"
        dialog(linkedTaskCount = 3) { action = it }

        composeTestRule.onNodeWithText("删除课程「高等数学」？").assertExists()
        // 有关联待办时必须主动说明「待办不会被删」，否则用户担心的那个问题悬着
        composeTestRule
            .onNodeWithText("3 条关联待办不会被删除，会移入「未关联课程」。")
            .assertExists()

        composeTestRule.onNodeWithTag("delete_course_confirm_button").performClick()
        assertEquals("点「删除」才该真正删除", "confirm", action)
    }

    @Test
    fun cancel_leaves_everything_untouched() {
        var action = "none"
        dialog(linkedTaskCount = 1) { action = it }

        composeTestRule.onNodeWithTag("delete_course_cancel_button").performClick()
        assertEquals("取消不该触发任何删除", "dismiss", action)
    }

    @Test
    fun says_nothing_about_tasks_when_there_are_none() {
        composeTestRule.setContent {
            DeleteCourseConfirmDialog(
                courseName = "高等数学",
                slotCount = 1,
                linkedTaskCount = 0,
                onDismiss = {},
                onConfirm = {}
            )
        }

        composeTestRule.onNodeWithText("这门课没有关联待办。").assertExists()
    }
}
