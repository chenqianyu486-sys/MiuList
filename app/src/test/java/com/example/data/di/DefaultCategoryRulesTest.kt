package com.example.data.di

import com.example.data.model.CourseCategory
import com.example.data.sample.SampleDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置类别规则的默认值（用户 2026-10-14 直接给定的规格）。
 *
 * 纯 JUnit：这层写错不会崩，只会让「一键生成待办」安静地少生成或多数一类待办，
 * 而且现象是「今天没作业要写」，没人会去查默认值。
 *
 * 第二条用例把演示数据也钉住：规则说关、演示课却是开，用户在演示模式里看到的
 * 就不是这套规则的样子，等于给了一份假样品。
 */
class DefaultCategoryRulesTest {

    private fun switches(category: String) =
        AppContainer.DEFAULT_CATEGORY_RULES.first { it.category == category }
            .let { Triple(it.autoPreStudy, it.autoPostHomework, it.autoLabReport) }

    @Test
    fun `the built-in switches follow the specified defaults`() {
        // 三个只交课后作业的类别
        listOf("公共基础", "专业必修", "专业选修").forEach {
            assertEquals("$it 应只勾课后作业", Triple(false, true, false), switches(it))
        }
        // 实验课：实验报告 + 预习，不开作业
        assertEquals(
            Triple(true, false, true),
            switches(CourseCategory.LAB)
        )
        // 通识课：三个都不勾
        assertEquals(Triple(false, false, false), switches("通识公共"))
    }

    @Test
    fun `every category chip has a built-in rule row`() {
        // 类别 chip 与规则表是两份清单，漏一条的表现是那个类别点进去三个开关全空。
        val missing = (com.example.data.model.COURSE_CATEGORIES -
            AppContainer.DEFAULT_CATEGORY_RULES.map { it.category }).toSet()
        assertEquals("这些类别没有内置默认规则：$missing", emptySet<String>(), missing)
    }

    @Test
    fun `demo courses carry the same switches their category defaults prescribe`() {
        SampleDataProvider.sampleCourses.forEach { course ->
            val (pre, post, lab) = switches(course.category)
            assertEquals("${course.name} 的预习开关与类别规则不一致", pre, course.autoPreStudy)
            assertEquals("${course.name} 的作业开关与类别规则不一致", post, course.autoPostHomework)
            assertEquals("${course.name} 的实验报告开关与类别规则不一致", lab, course.autoLabReport)
        }
        // 顺带守住「通识课不勾」这条最容易被当成漏写而改回去的规格。
        val english = SampleDataProvider.sampleCourses.first { it.category == "通识公共" }
        assertFalse(english.autoPreStudy)
        assertFalse(english.autoPostHomework)
        assertFalse(english.autoLabReport)
        assertTrue(english.name.isNotBlank())
    }
}