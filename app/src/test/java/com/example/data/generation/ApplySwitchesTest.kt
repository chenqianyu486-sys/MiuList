package com.example.data.generation

import com.example.data.local.CourseEntity
import com.example.data.model.CategoryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课程联动规则」批量设置到课程开关的判定逻辑。
 *
 * 纯 JUnit，不碰 Room —— 这一层写错不会崩，只会让联动悄悄不生效。
 *
 * 语义是**批量设置**：类别规则对它下面的所有课程有权威，应用一次就整体覆盖三格，
 * 包括用户此前在单门课上单独调过的那些。这是有意的取舍（配套是弹层的
 * 「先暂存 → 应用 / 取消」），所以这里钉死的就是「覆盖」而不是「保留」。
 */
class ApplySwitchesTest {

    private fun course(
        category: String = "专业必修",
        pre: Boolean = true,
        post: Boolean = true,
        lab: Boolean = false
    ) = CourseEntity(
        name = "数据结构与算法",
        category = category,
        teacher = "李成远 副教授",
        classroom = "信息楼 304",
        colorHex = "#208E9E",
        autoPreStudy = pre,
        autoPostHomework = post,
        autoLabReport = lab
    )

    private fun rule(
        category: String = "专业必修",
        pre: Boolean = true,
        post: Boolean = true,
        lab: Boolean = false
    ) = CategoryRule(category, pre, post, lab)

    // -------------------------------------------------------------- 整体覆盖

    @Test
    fun `三个开关整体套到课程上`() {
        val updated = GenerationRuleResolver.applySwitches(
            course(pre = true, post = true, lab = false),
            rule(pre = false, post = false, lab = true)
        )
        assertFalse(updated.autoPreStudy)
        assertFalse(updated.autoPostHomework)
        assertTrue(updated.autoLabReport)
    }

    /**
     * 批量设置的语义核心：**连用户单独设过的那几格也一起覆盖**。
     *
     * 这门课上用户单独关掉了预习、单独打开了实验；类别规则说预习开、作业开、实验关，
     * 应用之后课程就全部听类别的。这正是「批量设置」与「默认值」的差别。
     */
    @Test
    fun `会覆盖课程上单独设过的开关`() {
        val updated = GenerationRuleResolver.applySwitches(
            course(pre = false, post = true, lab = true),
            rule(pre = true, post = false, lab = false)
        )
        assertTrue("用户在课程上单独关掉的预习要被批量覆盖", updated.autoPreStudy)
        assertFalse(updated.autoPostHomework)
        assertFalse("用户在课程上单独打开的实验要被批量覆盖", updated.autoLabReport)
    }

    /** 类别外的字段一个都不该被碰。 */
    @Test
    fun `除三个开关外其余字段原样保留`() {
        val c = course()
        val updated = GenerationRuleResolver.applySwitches(c, rule(pre = false))
        assertEquals(c.name, updated.name)
        assertEquals(c.category, updated.category)
        assertEquals(c.teacher, updated.teacher)
        assertEquals(c.classroom, updated.classroom)
        assertEquals(c.colorHex, updated.colorHex)
        assertEquals(c.dedupeKey, updated.dedupeKey)
        assertEquals(c.defaultPostDueDays, updated.defaultPostDueDays)
        assertEquals(c.id, updated.id)
    }

    // -------------------------------------------------------------- 边界

    /** 类别没有规则行时不猜：保持课程自带值。 */
    @Test
    fun `规则为 null 时原样返回`() {
        val c = course(pre = false, post = false, lab = true)
        assertSame(c, GenerationRuleResolver.applySwitches(c, null))
    }

    /**
     * 全部一致时返回**同一实例**：调用方靠这个跳过写库，
     * 否则每按一次「应用」都要 UPDATE 一次全部课程，整屏卡片跟着重建。
     */
    @Test
    fun `本来就一致时返回同一实例`() {
        val c = course(pre = true, post = true, lab = false)
        assertSame(c, GenerationRuleResolver.applySwitches(c, rule()))
    }

    /** 和规则不同就要返回新对象，否则上层挑不出该写的行。 */
    @Test
    fun `有任何一格不同就返回新实例`() {
        val c = course(pre = true, post = true, lab = false)
        val updated = GenerationRuleResolver.applySwitches(c, rule(post = false))
        assertTrue(updated !== c)
        assertFalse(updated.autoPostHomework)
    }
}
