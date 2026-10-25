package com.example.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.example.data.model.CategoryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「课程联动规则」弹层的「应用」语义。
 *
 * 这一层是**批量设置**：类别规则对它下面的所有课程有权威，
 * 「应用」= 把当前这套规则重新盖一遍到**所有类别**的课程上。
 * 于是「这次一个字都没改」≠「没什么可做」——
 *
 * - 改了某门课的**类别**之后（只改类别、不动那三个开关），要让它听新类别的话，
 *   就得来点一次「应用」，而这时弹层里可能什么都没改；
 * - 用户在**单门课**上单独调过的开关，会被下一次「应用」推回类别值；
 * - 新导入 / 新加的课，要等下一次「应用」才拿到类别规则。
 *
 * 早期版本把按钮禁在「这次有没有改动」上、而且只提交改动过的那几个类别，
 * 于是上面第一条**直接做不成**：用户只能先在弹层里造一处无意义的改动，
 * 才能把那门课推回新类别。这组用例钉的就是这两条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CategoryRulesDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val rules = listOf(
        CategoryRule("公共基础", autoPreStudy = false, autoPostHomework = true, autoLabReport = false),
        CategoryRule("专业必修", autoPreStudy = true, autoPostHomework = true, autoLabReport = false)
    )

    /** 最近一次「应用」提交出去的规则。 */
    private var applied: List<CategoryRule>? = null

    private var dismissed = false

    private fun showDialog(rules: List<CategoryRule> = this.rules) {
        applied = null
        dismissed = false
        composeTestRule.setContent {
            CategoryRulesDialog(
                rules = rules,
                courseCountByCategory = mapOf("公共基础" to 3, "专业必修" to 5),
                onDismiss = { dismissed = true },
                onApply = { applied = it }
            )
        }
    }

    /** 「应用」在面板最底部，Robolectric 的视口可能截不到，用语义动作更稳。 */
    private fun clickApply() {
        composeTestRule.onNodeWithTag("category_rules_apply")
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    // -------------------------------------------------------------- 没改动也能应用

    @Test
    fun `没改动时应用按钮依然可点`() {
        showDialog()

        composeTestRule.onNodeWithTag("category_rules_apply").assertIsEnabled()
    }

    @Test
    fun `没改动时点应用会提交全部类别`() {
        showDialog()

        clickApply()

        assertEquals(
            "没改动也要能把规则盖到所有类别",
            listOf("公共基础", "专业必修"),
            applied?.map { it.category }
        )
    }

    // -------------------------------------------------------------- 改了也一样全量提交

    @Test
    fun `改了一格之后点应用仍然提交全部类别`() {
        showDialog()

        // 勾掉「专业必修」的课前预习
        composeTestRule.onNodeWithTag("category_rules_switch_专业必修_pre_study")
            .performSemanticsAction(SemanticsActions.OnClick)
        clickApply()

        assertEquals(
            "只改了一格也要提交全部类别（批量设置）",
            listOf("公共基础", "专业必修"),
            applied?.map { it.category }
        )
        val changed = applied?.single { it.category == "专业必修" }
        assertEquals("改过的那一格要带上改后的值", false, changed?.autoPreStudy)
        assertEquals("没动过的那一格保持原样", true, applied?.single { it.category == "公共基础" }?.autoPostHomework)
    }

    // -------------------------------------------------------------- 取消不写

    @Test
    fun `取消不会提交任何规则`() {
        showDialog()

        composeTestRule.onNodeWithTag("category_rules_cancel")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertNull("取消不该提交规则", applied)
        assertEquals("取消就是关掉弹层", true, dismissed)
    }
}
