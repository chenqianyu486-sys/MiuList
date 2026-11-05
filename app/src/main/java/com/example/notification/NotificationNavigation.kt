package com.example.notification

/**
 * 通知点击后要落到哪一页。
 *
 * 常量单独放一个文件，因为发送侧（Receiver / Notifier）和接收侧（MainActivity）
 * 各在一头：字符串写错一个字母不会编译失败，只会表现成「点了通知，App 打开在总览」。
 */
object NotificationNavigation {
    const val ACTION_OPEN = "com.example.notification.action.OPEN"

    const val EXTRA_DESTINATION = "com.example.notification.extra.DESTINATION"
    const val EXTRA_TASK_ID = "com.example.notification.extra.TASK_ID"

    /** 总览页顶部。 */
    const val DEST_DASHBOARD = "dashboard"

    /** 总览页的「今日课程」那一段。 */
    const val DEST_TODAY_COURSES = "today_courses"

    /** 待办清单页，可带一条要高亮的待办 id。 */
    const val DEST_CHECKLIST = "checklist"
}
