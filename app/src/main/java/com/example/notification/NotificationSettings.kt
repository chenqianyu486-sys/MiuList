package com.example.notification

import android.content.Context

/**
 * 本地提醒的偏好。
 *
 * ## 为什么全部落在 SharedPreferences
 *
 * 这些值没有一条是「用户的数据」：它们只决定**提醒怎么排**，不参与导出快照，
 * 也不需要按行查询。数据库里的每张表都在讲用户自己录入或同步来的事实，
 * 把「几点提醒」混进去只会让迁移链变长。
 *
 * ## 默认值方向
 *
 * **默认全开**：下课、截止这些时间点本身就是课表与待办的一部分，
 * 提醒是把已有事实在正确时刻说出来，而不是一个额外的功能。
 * 系统通知权限与精确闹钟权限各自另有闸门（见 `NotificationScheduler` 与设置弹层），
 * 用户拒绝时不会留下一个「开关是开的、实际永远不响」的假象。
 */
data class NotificationSettings(
    /** 总开关。关掉后所有渠道的 Alarm 都会被注销。 */
    val enabled: Boolean = DEFAULT_ENABLED,
    /** 每日汇总。 */
    val dailyEnabled: Boolean = DEFAULT_DAILY_ENABLED,
    /** 每日汇总的触发时刻，自 00:00 起的分钟数。 */
    val dailyMinuteOfDay: Int = DEFAULT_DAILY_MINUTE,
    /** 上课 / 考试提醒。 */
    val classEnabled: Boolean = DEFAULT_CLASS_ENABLED,
    /** 上课提前几分钟。 */
    val classLeadMinutes: Int = DEFAULT_CLASS_LEAD_MINUTES,
    /** 待办截止提醒。 */
    val todoEnabled: Boolean = DEFAULT_TODO_ENABLED,
    /** 待办提前几分钟。 */
    val todoLeadMinutes: Int = DEFAULT_TODO_LEAD_MINUTES
) {
    val dailyActive: Boolean get() = enabled && dailyEnabled
    val classActive: Boolean get() = enabled && classEnabled
    val todoActive: Boolean get() = enabled && todoEnabled

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_DAILY_ENABLED = true
        const val DEFAULT_CLASS_ENABLED = true
        const val DEFAULT_TODO_ENABLED = true

        /** 07:30。早晨第一节课之前，又不至于把人从睡梦里叫起来。 */
        const val DEFAULT_DAILY_MINUTE = 7 * 60 + 30

        /** 课前 15 分钟。 */
        const val DEFAULT_CLASS_LEAD_MINUTES = 15

        /** 截止前 6 小时。 */
        const val DEFAULT_TODO_LEAD_MINUTES = 6 * 60

        val CLASS_LEAD_OPTIONS = listOf(5, 10, 15, 30)

        /** 待办提前量选项（分钟）：1 小时 / 2 小时 / 6 小时 / 12 小时 / 1 天。 */
        val TODO_LEAD_OPTIONS = listOf(60, 120, 360, 720, 1440)

        /** 提醒最多只排到未来这么多天，避免「没有课时」时排出无穷远的闹钟。 */
        const val DAILY_MINUTE_MIN = 0
        const val DAILY_MINUTE_MAX = 23 * 60 + 59
    }
}

/**
 * 通知偏好的持久化。
 *
 * 读出来的值一律经过一次范围收束：偏好是**外部可改的**（备份还原、root 设备、
 * 老版本写坏的键），一个越界的分钟数会让 `nextDailyTrigger` 排到明天甚至更远，
 * 而界面上看不出哪里不对。收束比抛异常好 —— 提醒不该因为一个脏偏好就整体失效。
 */
class NotificationSettingsRepository(
    context: Context,
    prefName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefName, Context.MODE_PRIVATE)

    fun read(): NotificationSettings = NotificationSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, NotificationSettings.DEFAULT_ENABLED),
        dailyEnabled = prefs.getBoolean(
            KEY_DAILY_ENABLED,
            NotificationSettings.DEFAULT_DAILY_ENABLED
        ),
        dailyMinuteOfDay = prefs.getInt(
            KEY_DAILY_MINUTE,
            NotificationSettings.DEFAULT_DAILY_MINUTE
        ).coerceIn(NotificationSettings.DAILY_MINUTE_MIN, NotificationSettings.DAILY_MINUTE_MAX),
        classEnabled = prefs.getBoolean(
            KEY_CLASS_ENABLED,
            NotificationSettings.DEFAULT_CLASS_ENABLED
        ),
        classLeadMinutes = prefs.getInt(
            KEY_CLASS_LEAD,
            NotificationSettings.DEFAULT_CLASS_LEAD_MINUTES
        ).coerceIn(1, 120),
        todoEnabled = prefs.getBoolean(
            KEY_TODO_ENABLED,
            NotificationSettings.DEFAULT_TODO_ENABLED
        ),
        todoLeadMinutes = prefs.getInt(
            KEY_TODO_LEAD,
            NotificationSettings.DEFAULT_TODO_LEAD_MINUTES
        ).coerceIn(1, 7 * 24 * 60)
    )

    fun save(settings: NotificationSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_DAILY_ENABLED, settings.dailyEnabled)
            .putInt(KEY_DAILY_MINUTE, settings.dailyMinuteOfDay)
            .putBoolean(KEY_CLASS_ENABLED, settings.classEnabled)
            .putInt(KEY_CLASS_LEAD, settings.classLeadMinutes)
            .putBoolean(KEY_TODO_ENABLED, settings.todoEnabled)
            .putInt(KEY_TODO_LEAD, settings.todoLeadMinutes)
            .apply()
    }

    /**
     * 是否已经问过 Android 13+ 的通知权限。
     *
     * 只问一次：被拒绝之后再次冷启动又弹系统框，会让用户以为 App 在纠缠。
     * 之后想开的人从设置弹层里的「系统通知权限未开启 → 去开启」走系统设置。
     */
    var hasRequestedPostNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_POST_NOTIFICATIONS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASKED_POST_NOTIFICATIONS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "miulist_notification_prefs"
        private const val KEY_ENABLED = "notifications_enabled"
        private const val KEY_DAILY_ENABLED = "daily_enabled"
        private const val KEY_DAILY_MINUTE = "daily_minute_of_day"
        private const val KEY_CLASS_ENABLED = "class_enabled"
        private const val KEY_CLASS_LEAD = "class_lead_minutes"
        private const val KEY_TODO_ENABLED = "todo_enabled"
        private const val KEY_TODO_LEAD = "todo_lead_minutes"
        private const val KEY_ASKED_POST_NOTIFICATIONS = "asked_post_notifications"
    }
}

/**
 * 已投递过的待办提醒。
 *
 * ## 它解决的是「同一件事提醒两次」
 *
 * 排期是滚动链：一次闹钟响完，Receiver 醒来重新读库、再排下一次。
 * 对于「创建时已经进入提前量窗口」的待办，下一次的触发时刻本来就在过去，
 * 如果排期只认 `due - lead`，它会**每次重排都立刻再响一遍**。
 *
 * ## 键里为什么带截止时刻
 *
 * 同一条待办改了截止时间，那就是一件**新的事实**，应该重新提醒；
 * 只按 id 记，用户把 23:59 改成明天 09:00 之后，新的提醒就永远不会来。
 * 键写成 `id:dueTimestamp`，改时间自然产生新键，旧键在下次重排时被清掉。
 */
class ReminderDeliveryStore(
    context: Context,
    prefName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefName, Context.MODE_PRIVATE)

    fun deliveredKeys(): Set<String> =
        prefs.getStringSet(KEY_DELIVERED, emptySet()).orEmpty().toSet()

    fun markDelivered(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val next = deliveredKeys() + keys
        prefs.edit().putStringSet(KEY_DELIVERED, next).apply()
    }

    /**
     * 只保留「库里还存在的待办」的键。
     *
     * 不清会永远涨：用户删掉待办、反复改截止时间，每个组合都会留下一条。
     * 这里在每次重排时顺手清一遍，键集合最多等于当前待办数。
     */
    fun prune(validKeys: Set<String>) {
        val current = deliveredKeys()
        val next = current intersect validKeys
        if (next != current) {
            prefs.edit().putStringSet(KEY_DELIVERED, next).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "miulist_reminder_delivery_prefs"
        private const val KEY_DELIVERED = "delivered_todos"
    }
}
