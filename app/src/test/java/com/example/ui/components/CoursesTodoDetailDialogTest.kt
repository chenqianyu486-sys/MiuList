package com.example.ui.components

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.CourseEntity
import com.example.data.source.zju.Attachment
import com.example.data.source.zju.CoursesTodoDetail
import com.example.data.source.zju.CoursesTodoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 学在浙大待办详情弹窗。
 *
 * 钉的是两件事：
 *
 * 1. **课程名只出现一次**。早先标题下面有一行、下面还有一块「关联课程」，
 *    两处说的是同一件事。现在关联改由**颜色**表达，名字只剩标题下面那一个。
 * 2. **加进待办的入口还在**，且加过之后按钮变成状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CoursesTodoDetailDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mathCourse = CourseEntity(
        id = 7L,
        name = "高等数学",
        category = "公共基础",
        teacher = "张老师",
        classroom = "教三 201",
        colorHex = "#93C5FD"
    )

    private fun item(courseName: String = "高等数学") = CoursesTodoItem(
        id = "1",
        title = "第3章习题",
        courseName = courseName,
        dueTimestamp = 1793116740000L,
        dueFormatted = "10月25日 23:59",
        daysRemainingText = "2天后",
        isUrgent = false
    )

    private fun detail(
        description: String = "做完第 1-6 题，拍照上传。",
        completionCriterion: String = "",
        attachments: List<Attachment> = listOf(Attachment("习题.pdf"))
    ) = CoursesTodoDetail(
        activityId = "1",
        title = "第3章习题",
        description = description,
        completionCriterion = completionCriterion,
        attachments = attachments,
        dueTimestamp = 1793116740000L
    )

    private fun showDialog(
        matchedCourse: CourseEntity? = mathCourse,
        detail: CoursesTodoDetail? = null,
        isAdded: Boolean = false,
        onAddToMiuList: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            CoursesTodoDetailDialog(
                item = item(),
                detail = detail,
                isLoading = false,
                error = null,
                isAdded = isAdded,
                matchedCourse = matchedCourse,
                onAddToMiuList = onAddToMiuList,
                onDismiss = {}
            )
        }
    }

    @Test
    fun `课程名只出现一次`() {
        showDialog(matchedCourse = mathCourse)

        // 标题下面那一个：学在浙大写的课程名。
        composeTestRule.onAllNodesWithText("高等数学").assertCountEquals(1)
    }

    @Test
    fun `没匹配到本地课程时课程名也只出现一次`() {
        showDialog(matchedCourse = null)

        composeTestRule.onAllNodesWithText("高等数学").assertCountEquals(1)
    }

    @Test
    fun `不再特意展示关联到了哪门课`() {
        showDialog(matchedCourse = mathCourse)

        // 关联改由课程色表达，不写字。
        composeTestRule.onNodeWithText("关联课程").assertDoesNotExist()
        composeTestRule.onNodeWithText("已关联").assertDoesNotExist()
    }

    @Test
    fun `标题是作业名`() {
        showDialog()

        composeTestRule.onNodeWithText("第3章习题").assertIsDisplayed()
        composeTestRule.onNodeWithText("10月25日 23:59").assertExists()
    }

    @Test
    fun `作业说明和完成条件都在`() {
        showDialog(
            detail = detail(
                description = "做完第 1-6 题。",
                completionCriterion = "拍照上传到课程网站"
            )
        )

        composeTestRule.onNodeWithText("作业说明").assertExists()
        composeTestRule.onNodeWithText("做完第 1-6 题。").assertExists()
        composeTestRule.onNodeWithText("完成条件").assertExists()
        composeTestRule.onNodeWithText("拍照上传到课程网站").assertExists()
    }

    @Test
    fun `附件清单在`() {
        showDialog(
            detail = detail(attachments = listOf(Attachment("习题.pdf"), Attachment("封面.docx")))
        )

        composeTestRule.onNodeWithText("附件（2）").assertExists()
        composeTestRule.onNodeWithText("习题.pdf").assertExists()
        composeTestRule.onNodeWithText("封面.docx").assertExists()
    }

    @Test
    fun `说明按换行拆段而不是挤成一整块`() {
        // 上游 HTML 的每个块级元素转出来就是一个换行，
        // 所以单个换行也必须算「另起一段」，否则整片说明读起来全是连着的。
        showDialog(detail = detail(description = "第一行。\n第二行。"))

        composeTestRule.onNodeWithText("第一行。").assertExists()
        composeTestRule.onNodeWithText("第二行。").assertExists()
        composeTestRule.onNodeWithText("第一行。\n第二行。").assertDoesNotExist()
    }

    @Test
    fun `添加到待办的按钮能点`() {
        var added = false
        showDialog(onAddToMiuList = { added = true })

        composeTestRule.onNodeWithTag("add_courses_todo_button").performClick()

        assertTrue(added)
    }

    @Test
    fun `截止日期行不跟着正文滚`() {
        // 正文给足，撑出滚动区。
        val longText = (1..40).joinToString("\n") { "第 $it 行说明文字。" }
        showDialog(detail = detail(description = longText))

        // 截止行的祖先链里**不能**有滚动区 —— 它待在固定区（标题那一块），
        // 正文滚多滚少它都在。
        val due = composeTestRule
            .onNodeWithText("10月25日 23:59", useUnmergedTree = true)
            .fetchSemanticsNode()
        val body = composeTestRule
            .onNodeWithTag("courses_todo_detail_body", useUnmergedTree = true)
            .fetchSemanticsNode()

        assertFalse(
            "截止日期行不该在滚动区里（正文一长它就沉出屏幕了）",
            ancestorsOf(due).any { it.id == body.id }
        )
        // 顺带确认它确实在这个弹层里（不是断言写空了）
        assertTrue(ancestorsOf(due).isNotEmpty())
    }

    /** 从某个节点往上数它的全部祖先。 */
    private fun ancestorsOf(node: SemanticsNode): List<SemanticsNode> {
        val chain = mutableListOf<SemanticsNode>()
        var current = node.parent
        while (current != null) {
            chain.add(current)
            current = current.parent
        }
        return chain
    }

    @Test
    fun `已经加过了就只剩状态`() {
        showDialog(isAdded = true)

        composeTestRule.onNodeWithText("已添加到待办").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_courses_todo_button").assertDoesNotExist()
    }
}
