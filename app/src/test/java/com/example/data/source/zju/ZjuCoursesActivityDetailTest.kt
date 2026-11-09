package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 作业详情接口的 JSON 怎么变成 [CoursesTodoDetail]。
 *
 * 这是整条链上唯一按上游字段口径做取舍的地方（说明在哪、附件在哪、
 * 时间怎么算），所以离线钉住它。
 *
 * 走 Robolectric：`org.json` 是 Android 的实现，单元测试里默认是空壳，
 * 没有 Robolectric 的话每个 `optInt` 都会抛「not mocked」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZjuCoursesActivityDetailTest {

    private val client = ZjuCoursesClient()

    @Test
    fun `说明在data里也能取到`() {
        val body = """
            {"activity":{
              "id":42,
              "title":"第3章习题",
              "end_time":"2099-10-25T23:59:59+08:00",
              "data":{"description":"<p>做完第 1-6 题</p>"}
            }}
        """.trimIndent()

        val detail = client.parseActivityDetail(body, "42")
        assertEquals("第3章习题", detail.title)
        assertEquals("做完第 1-6 题", detail.description)
    }

    @Test
    fun `说明直接挂在活动上也能取到`() {
        val body = """{"activity":{"id":7,"title":"作业","description":"<b>交PDF</b>"}}"""
        val detail = client.parseActivityDetail(body, "7")
        assertEquals("交PDF", detail.description)
    }

    @Test
    fun `附件只取文件名`() {
        val body = """
            {"activity":{
              "id":1,
              "title":"作业",
              "uploads":[
                {"id":11,"name":"习题.pdf","size":102400},
                {"id":12,"name":"封面.png"}
              ]
            }}
        """.trimIndent()

        val detail = client.parseActivityDetail(body, "1")
        assertEquals(listOf("习题.pdf", "封面.png"), detail.attachments.map { it.name })
    }

    @Test
    fun `附件挂在data下也能取到`() {
        val body = """{"activity":{"id":1,"title":"作业","data":{"uploads":[{"id":9,"name":"报告.docx"}]}}}"""
        val detail = client.parseActivityDetail(body, "1")
        assertEquals(listOf("报告.docx"), detail.attachments.map { it.name })
    }

    @Test
    fun `没有附件时是空列表不是null`() {
        val detail = client.parseActivityDetail("""{"activity":{"id":1,"title":"作业"}}""", "1")
        assertFalse(detail.hasAttachments)
        assertTrue(detail.attachments.isEmpty())
    }

    @Test
    fun `完成条件被取出来`() {
        val body = """{"activity":{"id":1,"title":"作业","completion_criterion":"提交一次即算完成"}}"""
        val detail = client.parseActivityDetail(body, "1")
        assertEquals("提交一次即算完成", detail.completionCriterion)
    }

    @Test
    fun `截止时间被解析成时间戳`() {
        val body = """{"activity":{"id":1,"title":"作业","end_time":"2099-10-25T23:59:59+08:00"}}"""
        val detail = client.parseActivityDetail(body, "1")
        // 2099-10-25T15:59:59Z 的毫秒时间戳（东八区 23:59:59）。
        assertEquals(4096627199000L, detail.dueTimestamp)
    }

    @Test
    fun `没有截止时间时是null`() {
        val detail = client.parseActivityDetail("""{"activity":{"id":1,"title":"作业"}}""", "1")
        assertEquals(null, detail.dueTimestamp)
    }

    @Test
    fun `会话被顶掉时抛SESSION而不是当作空详情`() {
        try {
            client.parseActivityDetail("""{"kickout":1,"activity":{"id":1}}""", "1")
            fail("应当抛出会话失效")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.SESSION, e.kind)
        }
    }

    @Test
    fun `正文不是JSON时报数据不可用`() {
        try {
            client.parseActivityDetail("<html>502</html>", "1")
            fail("应当抛出数据不可用")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.DATA_INVALID, e.kind)
        }
    }
}
