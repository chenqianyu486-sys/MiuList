package com.example.ui.components

import com.example.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办编辑弹层的「基准」——打开弹层时的那一份，用来判断有没有改动过。
 *
 * ## 为什么这一条要钉住
 *
 * 基准决定主按钮是「保存待办」还是「关闭」。
 * 拿错了会出现两种很难解释的现象：
 *
 * - 空白新建却判成「改过了」：随手一点就造出一条标题是「待办」的空记录；
 * - **带预填的新建草稿**判成「什么都没改」：用户看到一整条填好的作业，
 *   主按钮却是「关闭」，存不下来。
 */
class TaskEditorBaselineTest {

    private val defaultDue = 1793116740000L

    private fun task(
        id: Long = 1L,
        title: String = "第3章习题",
        description: String = "做完第 1-6 题",
        taskType: String = "POST_CLASS",
        due: Long = defaultDue
    ) = TaskEntity(
        id = id,
        courseId = 3L,
        courseName = "高等数学",
        courseCategory = "公共基础",
        courseColorHex = "#93C5FD",
        title = title,
        description = description,
        taskType = taskType,
        dueDateTimestamp = due
    )

    @Test
    fun `空白新建的基准是什么都没有`() {
        val baseline = editorBaseline(null, selectedCourseIndex = 0, defaultDueMillis = defaultDue)
        assertEquals("", baseline.title)
        assertEquals("", baseline.description)
        assertEquals("", baseline.typeKey)
        assertFalse(baseline.isStarred)
        assertTrue(baseline.hasDue)
        assertEquals(defaultDue, baseline.dueMillis)
        assertEquals(0, baseline.courseIndex)
    }

    @Test
    fun `空白新建时入口指定的课程算基准不算改动`() {
        // 从课程详情「给这门课加待办」进来：课程是**用户自己挑的**，
        // 打开弹层时它就在那儿，不该被判成「改动过了」。
        val baseline = editorBaseline(null, selectedCourseIndex = 2, defaultDueMillis = defaultDue)
        assertEquals(2, baseline.courseIndex)
    }

    @Test
    fun `带预填的新建草稿的基准也是什么都没有`() {
        // id == 0 = 还没落库。预填的那份是**草稿**，不是「打开弹层时本来有的东西」。
        val draft = task(id = 0L, title = "第3章习题", description = "做完第 1-6 题")
        val baseline = editorBaseline(draft, selectedCourseIndex = 2, defaultDueMillis = defaultDue)

        assertEquals("", baseline.title)
        assertEquals("", baseline.description)
        assertEquals("", baseline.typeKey)
        assertTrue(baseline.hasDue)
        assertEquals(defaultDue, baseline.dueMillis)
        // 课程是 **App 替他找的**（按名字对上的），那是内容，不进基准。
        assertEquals(0, baseline.courseIndex)
    }

    @Test
    fun `已存在的待办以自己为基准`() {
        val existing = task(id = 1L)
        val baseline = editorBaseline(existing, selectedCourseIndex = 2, defaultDueMillis = defaultDue)

        assertEquals("第3章习题", baseline.title)
        assertEquals("做完第 1-6 题", baseline.description)
        assertEquals("POST_CLASS", baseline.typeKey)
        assertTrue(baseline.hasDue)
        assertEquals(defaultDue, baseline.dueMillis)
        assertEquals(2, baseline.courseIndex)
    }

    @Test
    fun `不设截止的待办基准里也没有截止`() {
        val existing = task(id = 1L, due = TaskEntity.NO_DUE_TIMESTAMP)
        val baseline = editorBaseline(existing, selectedCourseIndex = 1, defaultDueMillis = defaultDue)

        assertFalse(baseline.hasDue)
        // 没设截止时时间戳没有意义，落到默认截止上（关掉又打开时用的就是它）。
        assertEquals(defaultDue, baseline.dueMillis)
    }
}
