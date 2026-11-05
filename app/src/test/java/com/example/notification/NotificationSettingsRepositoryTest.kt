package com.example.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 提醒偏好的持久化与脏数据收束。
 *
 * 三条：
 * - 默认全开，时间与提前量就是需求里那组默认值；
 * - 改过的值要跨实例活着（ViewModel 与弹层各拿一个实例）；
 * - 越界的值读出来要被收进合法范围 —— 一个坏掉的偏好不该让整条提醒链失灵。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSettingsRepositoryTest {

    private fun repo(name: String) = NotificationSettingsRepository(
        ApplicationProvider.getApplicationContext<Context>(),
        prefName = name
    )

    @Test
    fun `默认值就是需求里的那组`() {
        val settings = repo("test_notification_defaults").read()

        assertTrue(settings.enabled)
        assertTrue(settings.dailyEnabled)
        assertEquals(7 * 60 + 30, settings.dailyMinuteOfDay)
        assertTrue(settings.classEnabled)
        assertEquals(15, settings.classLeadMinutes)
        assertTrue(settings.todoEnabled)
        assertEquals(6 * 60, settings.todoLeadMinutes)
    }

    @Test
    fun `改过的值换个实例也读得到`() {
        val writer = repo("test_notification_shared")
        writer.save(
            NotificationSettings(
                enabled = true,
                dailyEnabled = false,
                dailyMinuteOfDay = 8 * 60,
                classEnabled = true,
                classLeadMinutes = 30,
                todoEnabled = false,
                todoLeadMinutes = 120
            )
        )

        val reader = repo("test_notification_shared").read()
        assertFalse(reader.dailyEnabled)
        assertEquals(8 * 60, reader.dailyMinuteOfDay)
        assertEquals(30, reader.classLeadMinutes)
        assertFalse(reader.todoEnabled)
        assertEquals(120, reader.todoLeadMinutes)
    }

    @Test
    fun `越界的偏好读出来会被收进合法范围`() {
        val writer = repo("test_notification_clamp")
        writer.save(
            NotificationSettings(
                dailyMinuteOfDay = 99_999,
                classLeadMinutes = 0,
                todoLeadMinutes = 0
            )
        )

        val settings = repo("test_notification_clamp").read()
        assertEquals(NotificationSettings.DAILY_MINUTE_MAX, settings.dailyMinuteOfDay)
        assertEquals(1, settings.classLeadMinutes)
        assertEquals(1, settings.todoLeadMinutes)
    }

    @Test
    fun `通知权限只主动问一次`() {
        val first = repo("test_notification_asked")
        assertFalse(first.hasRequestedPostNotifications)
        first.hasRequestedPostNotifications = true

        assertTrue(repo("test_notification_asked").hasRequestedPostNotifications)
    }

    @Test
    fun `投递记录按待办与截止时刻去重且能清理`() {
        val store = ReminderDeliveryStore(
            ApplicationProvider.getApplicationContext<Context>(),
            prefName = "test_reminder_delivery"
        )
        store.markDelivered(listOf("1:100", "2:200"))
        assertEquals(setOf("1:100", "2:200"), store.deliveredKeys())

        store.prune(setOf("2:200", "3:300"))
        assertEquals(setOf("2:200"), store.deliveredKeys())
    }
}
