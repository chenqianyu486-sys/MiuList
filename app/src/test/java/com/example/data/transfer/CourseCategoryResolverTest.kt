package com.example.data.transfer

import com.example.data.model.CourseCategory
import com.example.data.model.DEFAULT_CATEGORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入时一门课的最终类别由谁决定。
 *
 * 这一层是**纯函数**，所以「缺失」与「明确给了」这两种语义可以被逐条钉死 ——
 * 而它们在早先的管线里被压成了同一个值，于是用户手动整理过的类别
 * 在一次同步后就没了。
 */
class CourseCategoryResolverTest {

    private fun resolved(category: String?, locals: List<String>, name: String? = "高等数学"): String {
        val r = CourseCategoryResolver.resolve(category, locals, name)
        assertTrue("不该是冲突：$r", r is CourseCategoryResolver.Result.Resolved)
        return (r as CourseCategoryResolver.Result.Resolved).category
    }

    @Test
    fun `a category written in the file wins over the local one`() {
        assertEquals("通识公共", resolved("通识公共", listOf("专业必修")))
    }

    @Test
    fun `an explicit 公共基础 is a decision, not a missing value`() {
        // 「公共基础」既可能是猜出来的默认值，也可能是用户认真选的结果 ——
        // 两者长得一模一样，所以判据只能是「有没有这一项」，不能是「是不是默认值」。
        assertEquals("公共基础", resolved("公共基础", listOf("专业必修")))
    }

    @Test
    fun `a missing category inherits the local one`() {
        assertEquals("专业必修", resolved(null, listOf("专业必修")))
        assertEquals("专业必修", resolved("", listOf("专业必修")))
        assertEquals("专业必修", resolved("   ", listOf("专业必修")))
    }

    @Test
    fun `a course that is not on the device falls back to the inferred default`() {
        assertEquals(DEFAULT_CATEGORY, resolved(null, emptyList()))
        assertEquals(CourseCategory.LAB, resolved(null, emptyList(), name = "大学物理实验"))
    }

    @Test
    fun `实训 实践 实习 fall back to the lab category too`() {
        listOf("程序设计实训", "工程实践", "生产实习").forEach { name ->
            assertEquals("$name 应当进实验实践", CourseCategory.LAB, resolved(null, emptyList(), name = name))
        }
    }

    @Test
    fun `a blank local category counts as missing`() {
        assertEquals(DEFAULT_CATEGORY, resolved(null, listOf("", "   ")))
    }

    @Test
    fun `several local rows with the same category are not a conflict`() {
        // 同一个自然键下有两条历史数据，但类别一致 —— 继承那个值没有歧义。
        assertEquals("专业必修", resolved(null, listOf("专业必修", "专业必修", " 专业必修 ")))
    }

    @Test
    fun `several local rows with different categories are a conflict`() {
        val r = CourseCategoryResolver.resolve(null, listOf("专业必修", "公共基础"), "数据结构")
        assertTrue("类别不一致必须是冲突：$r", r is CourseCategoryResolver.Result.Conflict)
        assertEquals(
            listOf("专业必修", "公共基础"),
            (r as CourseCategoryResolver.Result.Conflict).categories
        )
    }

    @Test
    fun `a conflict does not happen when the file brings its own category`() {
        // 文件给了类别就不需要继承本机的，本机有几条都不相干。
        assertEquals("通识公共", resolved("通识公共", listOf("专业必修", "公共基础")))
    }
}
