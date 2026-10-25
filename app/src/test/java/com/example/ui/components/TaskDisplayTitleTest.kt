package com.example.ui.components

import com.example.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「显示名称」：把标题开头的课程名裁掉。
 *
 * 卡片上课程名已经写在 meta 行里，标题再带一遍是同一句话说两遍；
 * 而这条待办被单独拎出来（搜索、详情、导出）时，标题里还得留着课程名。
 *
 * 纯函数，不碰 Compose —— 所以不需要 Robolectric，断言的就是裁剪规则本身。
 */
class TaskDisplayTitleTest {

    private fun task(
        title: String,
        courseName: String = "高等数学",
        courseId: Long = 1L
    ) = TaskEntity(
        id = 1L,
        courseId = courseId,
        courseName = courseName,
        courseCategory = "公共基础",
        courseColorHex = "#93C5FD",
        title = title,
        taskType = "POST_CLASS",
        dueDateTimestamp = 1793116740000L
    )

    @Test
    fun `the bracketed course name of the new format is cut`() {
        assertEquals(
            "第3周（周三）预习",
            taskDisplayTitle(task("【高等数学】第3周（周三）预习"))
        )
    }

    @Test
    fun `the legacy space separated course name is still cut`() {
        // 库里还留着一键生成早先用的这些老数据，裁剪规则不能只认新格式。
        assertEquals("第3周预习", taskDisplayTitle(task("高等数学 第3周预习")))
    }

    @Test
    fun `a title the user wrote is left alone`() {
        assertEquals(
            "【作业】高数 第6章 定积分",
            taskDisplayTitle(task("【作业】高数 第6章 定积分"))
        )
    }

    @Test
    fun `an unlinked task keeps its whole title`() {
        assertEquals(
            "【预习】大学物理 第8章 电磁场",
            taskDisplayTitle(
                task("【预习】大学物理 第8章 电磁场", courseName = "未关联", courseId = 0L)
            )
        )
    }

    @Test
    fun `a title that is nothing but the course name is not emptied`() {
        // 裁完就空了 = 卡片上只剩一行空白，比不裁还糟。
        assertEquals("高等数学", taskDisplayTitle(task("高等数学")))
        assertEquals("【高等数学】", taskDisplayTitle(task("【高等数学】")))
    }

    @Test
    fun `a course name with surrounding spaces still matches`() {
        assertEquals(
            "第3周作业",
            taskDisplayTitle(task("【高等数学】第3周作业", courseName = " 高等数学 "))
        )
    }
}
