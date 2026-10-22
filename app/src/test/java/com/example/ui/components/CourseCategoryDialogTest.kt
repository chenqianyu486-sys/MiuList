package com.example.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.example.data.local.CourseEntity
import com.example.data.local.CourseWithSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 课程分类弹层的落库规则。
 *
 * 选完一个类别之后，弹层**留在这一门**（换下一门由用户点顶部的课程卡片）——
 * 这一点是用户明确要的：之前是「选完自动跳下一门」，那一跳是 App 替用户
 * 做的决定，他还没看清刚改的那一门就被带走了。
 *
 * 这里钉的是**剩下**的那条规则：选的就是当前类别时**不写库**。
 * 一次「什么都没发生」不该配一句「已归到 X」，也不该动数据库。
 */
class CourseCategoryDialogTest {

    @Test
    fun `选了别的类别就写库`() {
        assertTrue(shouldPersistCategory(currentCategory = "公共基础", pickedCategory = "实验实践"))
    }

    @Test
    fun `选的就是当前类别就不写库`() {
        assertFalse(shouldPersistCategory(currentCategory = "公共基础", pickedCategory = "公共基础"))
    }
}

/**
 * 「当前是哪门课」这条规则的纯逻辑部分。
 *
 * 认的是**课程 id**，不是下标：课程列表会变（改完类别会刷新、课可能增删），
 * 按下标记的话，删掉前面那门，后面每门都会**错位一号** ——
 * 看着在给 A 选类别，其实改的是 B。
 */
class CourseCategorySelectionTest {

    private val math = course(101L, "高等数学", "公共基础")
    private val physics = course(102L, "大学物理", "公共基础")
    private val linear = course(103L, "线性代数", "专业必修")
    private val courses = listOf(math, physics, linear)

    private fun course(id: Long, name: String, category: String) = CourseWithSlots(
        course = CourseEntity(
            id = id,
            name = name,
            code = "C$id",
            category = category,
            teacher = "王教授",
            classroom = "教二-301",
            colorHex = "#93C5FD"
        ),
        slots = emptyList()
    )

    @Test
    fun `还没点过就显示第一门`() {
        assertEquals("高等数学", resolveSelectedCourse(courses, null)?.course?.name)
    }

    @Test
    fun `点了哪门就显示哪门`() {
        assertEquals("大学物理", resolveSelectedCourse(courses, 102L)?.course?.name)
    }

    @Test
    fun `点的那门已经不在列表里就退回第一门`() {
        assertEquals("高等数学", resolveSelectedCourse(courses, 999L)?.course?.name)
    }

    @Test
    fun `一门课都没有就没有当前课`() {
        assertNull(resolveSelectedCourse(emptyList(), 101L))
    }
}

/**
 * 换课程这件事：**点**卡片换，**不**再横着滑换。
 *
 * 这一组钉的是用户明确提的那条：顶部那一行原来是个分页器，横着滑就换一门。
 * 现在滑只负责浏览（往回看别的课），换课只能点 —— 滑过去顺手一选就改错门，
 * 是这个弹层最容易被误操作的地方。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CourseCategoryDialogInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val math = course(101L, "高等数学", "公共基础")
    private val physics = course(102L, "大学物理", "公共基础")
    private val linear = course(103L, "线性代数", "专业必修")

    /** 最近一次落库：课程名 → 类别。 */
    private var persisted: Pair<String, String>? = null

    private fun course(id: Long, name: String, category: String) = CourseWithSlots(
        course = CourseEntity(
            id = id,
            name = name,
            code = "C$id",
            category = category,
            teacher = "王教授",
            classroom = "教二-301",
            colorHex = "#93C5FD"
        ),
        slots = emptyList()
    )

    private fun showDialog(courses: List<CourseWithSlots>) {
        persisted = null
        composeTestRule.setContent {
            CourseCategoryDialog(
                courses = courses,
                onSelectCategory = { course, category -> persisted = course.course.name to category },
                onAddCourse = {},
                onDismiss = {}
            )
        }
    }

    @Test
    fun `默认看第一门课`() {
        showDialog(listOf(math, physics, linear))

        composeTestRule.onNodeWithText("给「高等数学」选类别").assertExists()
        composeTestRule.onNodeWithTag("course_category_tab_101").assertIsSelected()
    }

    @Test
    fun `点哪张卡片就切到哪门课`() {
        showDialog(listOf(math, physics, linear))

        composeTestRule.onNodeWithTag("course_category_tab_102").performClick()

        composeTestRule.onNodeWithText("给「大学物理」选类别").assertExists()
        composeTestRule.onNodeWithTag("course_category_tab_102").assertIsSelected()
        composeTestRule.onNodeWithTag("course_category_tab_101").assertIsNotSelected()
    }

    @Test
    fun `横着滑那一行不会换当前课`() {
        showDialog(listOf(math, physics, linear))

        composeTestRule.onNodeWithTag("course_category_tab_102").performClick()
        composeTestRule.onNodeWithText("给「大学物理」选类别").assertExists()

        // 第三张卡片还没进可视区
        composeTestRule.onNodeWithTag("course_category_tab_103").assertDoesNotExist()

        // 横着滑那一行：滑只负责浏览（把后面的课露出来），不换「当前是哪门」。
        //
        // 这一行在**未合并**的语义树里找：弹层面板根节点是 mergeDescendants=true，
        // 分页行那条节点会被它吸收，合并树里就找不到它了（卡片自己有 selectable
        // 语义、不被吸收，所以卡片在合并树里是找得到的）。
        composeTestRule.onNodeWithTag("course_category_strip", useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        // 滑确实动了：第三张卡片露出来了
        composeTestRule.onNodeWithTag("course_category_tab_103").assertExists()
        // 但「当前是哪门」没跟着滑走
        composeTestRule.onNodeWithText("给「大学物理」选类别").assertExists()
        composeTestRule.onNodeWithTag("course_category_tab_102").assertIsSelected()
    }

    @Test
    fun `选类别落库到当前那门课`() {
        showDialog(listOf(math, physics))

        // 切到第二门
        composeTestRule.onNodeWithTag("course_category_tab_102").performClick()
        composeTestRule.onNodeWithText("给「大学物理」选类别").assertExists()

        // 大学物理当前是「公共基础」，点「实验实践」才该落库
        composeTestRule.onNodeWithTag("course_category_option_实验实践")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals("大学物理" to "实验实践", persisted)
    }

    @Test
    fun `选的就是当前类别就不落库`() {
        showDialog(listOf(math, physics))

        composeTestRule.onNodeWithTag("course_category_tab_102").performClick()
        // 大学物理当前已经是「公共基础」，再点一次不该写库
        composeTestRule.onNodeWithTag("course_category_option_公共基础")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertNull("选的就是当前类别时不该落库", persisted)
    }
}
