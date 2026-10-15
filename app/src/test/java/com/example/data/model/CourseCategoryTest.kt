package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入时的类别判定。
 *
 * 这条规则唯一的性质是**猜错方向的代价不对称**：把普通课误判成实验课，
 * 用户在设置里点一下就改回来；把实验课留在「公共基础」里，它就不会生成实验报告，
 * 而用户很可能想不起自己要去逐门改 —— 所以关键词宁可多认。
 */
class CourseCategoryTest {

    @Test
    fun `a plain course lands on the default category`() {
        assertEquals(DEFAULT_CATEGORY, CourseCategory.classifyOnImport("高等数学"))
    }

    @Test
    fun `默认类别是公共基础`() {
        // 钉住值本身：这个常量同时是校验器的兜底与新建课程的默认值，
        // 改成「专业必修」等于替用户断言所有课都是专业课。
        assertEquals("公共基础", DEFAULT_CATEGORY)
    }

    @Test
    fun `实验 goes to the lab category`() {
        assertEquals("实验实践", CourseCategory.classifyOnImport("大学物理实验"))
        assertTrue(CourseCategory.isLabCourse("计算机组成实验"))
    }

    @Test
    fun `实训 实践 实习 are lab courses too`() {
        // 与「实验」并列的三个词：浙大课表里它们出现在同一个语义位置
        // （单独排课、动手做、单独交报告）。漏掉它们等于实验课混在基础课里不被生成。
        listOf("程序设计实训", "工程实践", "生产实习", "认识实习", "会计实务实习").forEach {
            assertTrue("$it 应该算实验课", CourseCategory.isLabCourse(it))
            assertEquals("实验实践", CourseCategory.classifyOnImport(it))
        }
    }

    @Test
    fun `the keyword matches inside a longer name`() {
        // 子串匹配是刻意的：中文没有词边界，「实验(一)」「实训(上)」都得认。
        assertTrue(CourseCategory.isLabCourse("有机化学实验(一)"))
        assertTrue(CourseCategory.isLabCourse("程序设计实训(上)"))
        assertTrue(CourseCategory.isLabCourse("大学物理实验"))
    }

    @Test
    fun `a missing name is never a lab course`() {
        // 空名在解析层早被丢弃；这里给出 false 只是为了让这个纯函数没有意外态。
        assertFalse(CourseCategory.isLabCourse(null))
        assertFalse(CourseCategory.isLabCourse(""))
        assertEquals(DEFAULT_CATEGORY, CourseCategory.classifyOnImport(null))
    }

    @Test
    fun `实验室管理这类通识课会被认成实验课`() {
        // 已知代价，写成断言而不是删掉：这是有意的取舍（宁可多认一门），
        // 哪天用户反馈误判了，改这里一行关键词表即可。
        assertTrue(CourseCategory.isLabCourse("实验室管理"))
    }
}
