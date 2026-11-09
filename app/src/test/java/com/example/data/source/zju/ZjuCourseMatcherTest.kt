package com.example.data.source.zju

import com.example.data.local.CourseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学在浙大的课程名 → MiuList 里的课程。
 *
 * 规则分四档（完全相等 → 归一化后相等 → 一方包含另一方 → 相似度够高），
 * 但**挑哪个**是有讲究的：课表里同时有「数字电路」和「数字电路实验」时，
 * 学在浙大上的「数字电路实验」必须对到后者。
 *
 * 还有一条压倒一切的原则：**对不上就留空**。挂错课程比不挂更糟。
 */
class ZjuCourseMatcherTest {

    private fun course(id: Long, name: String) = CourseEntity(
        id = id,
        name = name,
        category = "专业必修",
        teacher = "张老师",
        classroom = "教三 201",
        colorHex = "#93C5FD"
    )

    @Test
    fun `名字完全相等就对上`() {
        val courses = listOf(course(1L, "高等数学"), course(2L, "大学物理"))
        val hit = matchCourseByName("高等数学", courses)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun `首尾空白不算差别`() {
        val courses = listOf(course(1L, "高等数学"))
        val hit = matchCourseByName("  高等数学  ", courses)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun `一方包含另一方也算对上`() {
        val courses = listOf(course(1L, "大学物理"))
        assertEquals(1L, matchCourseByName("大学物理（下）", courses)?.id)
        assertEquals(1L, matchCourseByName("大学物", courses)?.id)
    }

    @Test
    fun `多个候选时挑名字更长的那个`() {
        // 「数字电路」排在前面，但它更短、更笼统 —— 不该抢走「数字电路实验」。
        val courses = listOf(course(1L, "数字电路"), course(2L, "数字电路实验"))
        assertEquals(2L, matchCourseByName("数字电路实验", courses)?.id)
    }

    @Test
    fun `对不上就留空`() {
        val courses = listOf(course(1L, "高等数学"), course(2L, "大学物理"))
        assertNull(matchCourseByName("马克思主义基本原理", courses))
    }

    @Test
    fun `学在浙大那边没写课程名就匹配不到`() {
        val courses = listOf(course(1L, "高等数学"))
        assertNull(matchCourseByName("", courses))
        assertNull(matchCourseByName("   ", courses))
    }

    @Test
    fun `本地课程没有名字时不参与模糊匹配`() {
        // 空名字会被任何课程名「包含」，那会让它变成万能匹配。
        val courses = listOf(course(1L, ""), course(2L, "高等数学"))
        assertEquals(2L, matchCourseByName("高等数学", courses)?.id)
    }

    @Test
    fun `课程表为空时匹配不到`() {
        assertNull(matchCourseByName("高等数学", emptyList()))
    }

    // ------------------------------------------------------------------
    // 下面这些是模糊匹配的四档规则
    // ------------------------------------------------------------------

    @Test
    fun `完全相等是最高那一档`() {
        val hit = matchCourse("高等数学", listOf(course(1L, "高等数学")))
        assertEquals(CourseMatchKind.EXACT, hit?.kind)
        assertEquals(1f, hit?.score)
    }

    @Test
    fun `括号里的补充说明剥掉之后算同一门课`() {
        // 老规则（只有完全相等 + 包含）对不上这一对：
        // 「大学物理（下）」里没有一段是「大学物理」之外的完整子串吗？有，
        // 但归一化那一档更快也更准 —— 它认的是「剥掉括号后同名」。
        val hit = matchCourse("大学物理（下）", listOf(course(1L, "大学物理")))
        assertEquals(1L, hit?.course?.id)
        assertEquals(CourseMatchKind.NORMALIZED, hit?.kind)
    }

    @Test
    fun `方括号里的学分说明也剥掉`() {
        val hit = matchCourse("马克思主义基本原理[学分2]", listOf(course(1L, "马克思主义基本原理")))
        assertEquals(1L, hit?.course?.id)
        assertEquals(CourseMatchKind.NORMALIZED, hit?.kind)
    }

    @Test
    fun `全角半角和空格都不算差别`() {
        val hit = matchCourse("Ｃ语言 程序设计", listOf(course(1L, "C语言程序设计")))
        assertEquals(1L, hit?.course?.id)
        assertEquals(CourseMatchKind.NORMALIZED, hit?.kind)
    }

    @Test
    fun `罗马数字和字母算同一门课`() {
        // 「大学英语Ⅰ」和「大学英语I」在教务系统里是同一门课的两个写法。
        val hit = matchCourse("大学英语Ⅰ", listOf(course(1L, "大学英语I")))
        assertEquals(1L, hit?.course?.id)
        assertEquals(CourseMatchKind.NORMALIZED, hit?.kind)
    }

    @Test
    fun `一方是另一方的前缀也算包含`() {
        val hit = matchCourse("高等数", listOf(course(1L, "高等数学")))
        assertEquals(1L, hit?.course?.id)
        assertEquals(CourseMatchKind.CONTAINS, hit?.kind)
    }

    @Test
    fun `互不包含时靠相似度也能对上`() {
        // 「马克思主义原理」不是「马克思主义基本原理」的子串，
        // 但它们共享「马克思主义」这一大段，相似度够线。
        val hit = matchCourse("马克思主义原理", listOf(course(1L, "马克思主义基本原理")))
        assertEquals(1L, hit?.course?.id)
        assertEquals(CourseMatchKind.FUZZY, hit?.kind)
    }

    @Test
    fun `相似度不够就不硬凑`() {
        // 「大学物理」和「大学化学」只共享「大学」两个字，相似度远低于阈值。
        // 宁可空着，也不要把作业挂到一门不相干的课上。
        assertNull(matchCourse("大学物理", listOf(course(1L, "大学化学"))))
    }

    @Test
    fun `一个字的名字不参与包含匹配`() {
        // 一个字能被任何名字包含 —— 那不叫匹配。
        assertNull(matchCourse("数据结构", listOf(course(1L, "数"))))
    }

    @Test
    fun `本地课程没有名字时不参与任何一档匹配`() {
        val hit = matchCourse("高等数学", listOf(course(1L, ""), course(2L, "高等数学")))
        assertEquals(2L, hit?.course?.id)
    }

    @Test
    fun `模糊命中时挑相似度更高的那个`() {
        // 两门课都沾边：「马克思主义基本原理」共享得更多（0.71），
        // 「马克思主义概论」只共享「马克思主义」（0.67）。
        val courses = listOf(course(1L, "马克思主义概论"), course(2L, "马克思主义基本原理"))
        val hit = matchCourse("马克思主义原理", courses)
        assertEquals(2L, hit?.course?.id)
        assertEquals(CourseMatchKind.FUZZY, hit?.kind)
    }
}
