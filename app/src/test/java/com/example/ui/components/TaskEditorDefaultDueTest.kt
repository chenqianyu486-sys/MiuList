package com.example.ui.components

import com.example.data.calendar.AppZone
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 手动新建待办时的**默认截止**（2026-10-28 用户定：3 天 → 6 天）。
 *
 * 抽成可喂「现在」的纯函数是为了让这条规则能被直接钉住 ——
 * 弹层里显示的是日期，不看代码没法知道默认是几天。
 */
class TaskEditorDefaultDueTest {

    @Test
    fun `默认截止是 6 天后的 23 59`() {
        // 2026-10-28 15:40（北京时间）
        val now = AppZone.calendar().apply {
            set(2026, Calendar.OCTOBER, 28, 15, 40, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val due = AppZone.calendar(defaultDueTimestamp(now))

        assertEquals("多出来的是 6 天", 2026, due.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, due.get(Calendar.MONTH))
        assertEquals(3, due.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, due.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, due.get(Calendar.MINUTE))
        assertEquals(0, due.get(Calendar.SECOND))
    }

    @Test
    fun `默认截止落在北京时间而不是设备时区`() {
        // 喂一个 UTC 时刻：2026-10-28 07:00Z = 北京 15:00。
        // 若哪天真把 AppZone 换成设备时区，结果会差一整天。
        val now = 1793170800000L // 2026-10-28 07:00:00Z

        val due = AppZone.calendar(defaultDueTimestamp(now))

        assertEquals(Calendar.NOVEMBER, due.get(Calendar.MONTH))
        assertEquals(3, due.get(Calendar.DAY_OF_MONTH))
    }
}
