package com.example.data.source.zju

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoursesTodoItemTest {

    @Test
    fun fromRawApi_withValidIsoTime_parsesCorrectly() {
        val rawTime = "2026-10-25T23:59:59+08:00"
        val item = CoursesTodoItem.fromRawApi(
            id = "hw_101",
            title = "操作系统实验二",
            courseName = "操作系统",
            rawEndTime = rawTime
        )

        assertEquals("hw_101", item.id)
        assertEquals("操作系统实验二", item.title)
        assertEquals("操作系统", item.courseName)
        assertNotNull(item.dueTimestamp)
        assertTrue(item.dueTimestamp!! > 0)
    }

    @Test
    fun fromRawApi_withNullOrBlankTime_handlesGracefully() {
        val item = CoursesTodoItem.fromRawApi(
            id = "hw_102",
            title = "",
            courseName = "",
            rawEndTime = null
        )

        assertEquals("hw_102", item.id)
        assertEquals("未命名作业", item.title)
        assertEquals("通用课程", item.courseName)
        assertNull(item.dueTimestamp)
        assertEquals("无截止", item.dueFormatted)
        assertEquals("无截止", item.daysRemainingText)
        assertFalse(item.isUrgent)
    }

    @Test
    fun fromRawApi_pastDeadline_isMarkedUrgentAndOverdue() {
        val pastInstant = Instant.now().minus(2, ChronoUnit.HOURS)
        val item = CoursesTodoItem.fromRawApi(
            id = "hw_past",
            title = "已过期的离散数学作业",
            courseName = "离散数学",
            rawEndTime = pastInstant.toString()
        )

        assertTrue(item.isUrgent)
        assertEquals("已逾期", item.daysRemainingText)
    }

    @Test
    fun jsonSerialization_roundtrip_preservesAllFields() {
        val original = CoursesTodoItem(
            id = "task_999",
            title = "计算机网络大作业展示",
            courseName = "计算机网络",
            dueTimestamp = 1792994400000L,
            dueFormatted = "10月25日 23:59",
            daysRemainingText = "3天后",
            isUrgent = false
        )

        val json = original.toJson()
        val restored = CoursesTodoItem.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.courseName, restored.courseName)
        assertEquals(original.dueTimestamp, restored.dueTimestamp)
        assertEquals(original.dueFormatted, restored.dueFormatted)
        assertEquals(original.daysRemainingText, restored.daysRemainingText)
        assertEquals(original.isUrgent, restored.isUrgent)
    }

    @Test
    fun listJsonSerialization_roundtrip_preservesList() {
        val items = listOf(
            CoursesTodoItem(
                id = "item1",
                title = "作业一",
                courseName = "课程A",
                dueTimestamp = 1792994400000L,
                dueFormatted = "10月25日 23:59",
                daysRemainingText = "今天截止",
                isUrgent = true
            ),
            CoursesTodoItem(
                id = "item2",
                title = "作业二",
                courseName = "课程B",
                dueTimestamp = null,
                dueFormatted = "无截止",
                daysRemainingText = "无截止",
                isUrgent = false
            )
        )

        val jsonString = CoursesTodoItem.listToJsonString(items)
        val parsedList = CoursesTodoItem.listFromJsonString(jsonString)

        assertEquals(2, parsedList.size)
        assertEquals("item1", parsedList[0].id)
        assertEquals("作业一", parsedList[0].title)
        assertTrue(parsedList[0].isUrgent)

        assertEquals("item2", parsedList[1].id)
        assertNull(parsedList[1].dueTimestamp)
        assertFalse(parsedList[1].isUrgent)
    }

    @Test
    fun coursesTodoCache_saveAndLoad_worksCorrectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = CoursesTodoCache(context)

        val sampleList = listOf(
            CoursesTodoItem(
                id = "cache_test_1",
                title = "软件工程报告",
                courseName = "软件工程",
                dueTimestamp = 1792994400000L,
                dueFormatted = "10月26日 12:00",
                daysRemainingText = "明天截止",
                isUrgent = true
            )
        )

        val now = System.currentTimeMillis()
        cache.save(sampleList, now)

        val (cachedList, cachedTime) = cache.load()

        assertEquals(1, cachedList.size)
        assertEquals("cache_test_1", cachedList[0].id)
        assertEquals("软件工程报告", cachedList[0].title)
        assertEquals(now, cachedTime)
    }
}
