package com.example.data.di

import com.example.data.model.CourseCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内置类别规则的默认值（用户 2026-10-14 直接给定的规格）。
 *
 * 纯 JUnit：这层写错不会崩，只会让「一键生成待办」安静地少生成或多数一类待办，
 * 而且现象是「今天没作业要写」，没人会去查默认值。
 *
 * 原来还有第三条用例，把示例课程逐条钉在这套规则上（规则说关、示例课却是开，
 * 用户看到的就是一份假样品）。示例数据功能 2026-10-20 整项取消后，那条用例
 * 一起没了 —— 通识课「三个都不勾」这条规格仍由第一条用例守住。
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
}