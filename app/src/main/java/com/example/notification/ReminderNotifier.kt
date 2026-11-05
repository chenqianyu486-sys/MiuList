package com.example.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.calendar.AppZone
import com.example.data.timetable.formatMinute

/**
 * 通知渠道。三个渠道各自独立，用户在系统设置里可以只关掉其中一类。
 *
 * ## 每日汇总为什么是「静音的中优先级」
 *
 * 它是一条**晨间信息**，不是「现在就去做」的指令：不该响铃、不该震动，
 * 也不该弹 heads-up 把人从早餐桌上拽起来。但也不能低到被折叠进「其他通知」——
 * 一天的第一眼就看它有没有东西，所以保持 DEFAULT 的重要性，只把声音与震动关掉。
 */
object NotificationChannels {
    const val DAILY = "miulist_daily_summary"
    const val CLASS = "miulist_class_reminder"
    const val TODO = "miulist_todo_reminder"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(DAILY) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DAILY,
                    "每日日程汇总",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "每天早上的课程、截止待办与考试概览"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        if (manager.getNotificationChannel(CLASS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CLASS,
                    "上课与考试提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "课前与考前提醒，响铃并震动"
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        if (manager.getNotificationChannel(TODO) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    TODO,
                    "待办截止提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "待办临近截止时提醒，响铃并震动"
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }
}

/**
 * 通知的**渲染层**：把排期算出来的事实变成三张通知。
 *
 * 所有文案都在这里拼，Planner 只管时间与数据。两者分开之后，
 * 时间边界可以在 JUnit 里测，而文案/渠道只在真机上才看得出效果。
 *
 * 固定 tag + 固定 id：同一个渠道同一时刻只留一张通知。
 * 每天多条待办截止时用 InboxStyle 合成一张，而不是一次刷出五条。
 */
class ReminderNotifier(private val context: Context) {

    fun ensureChannels() = NotificationChannels.ensure(context)

    /**
     * 能不能发通知。
     *
     * Android 13+ 需要运行时权限；更早的版本用户在系统里整体关掉通知也会走到这里。
     * 不能发的时候 `NotificationScheduler` 会**注销全部闹钟** —— 排着一堆注定
     * 发不出来的 Alarm 没有意义，还会在用户重新打开权限时一次性补发。
     */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun postDaily(briefing: DailyBriefing): Boolean {
        if (!canPost() || !briefing.hasAnything) return false
        val lines = buildList {
            if (briefing.sessionCount > 0) {
                val firstTime = briefing.firstStartMinute?.let { formatMinute(it) }
                val where = briefing.firstLocation.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                val first = if (firstTime != null) {
                    "第一节 $firstTime$where"
                } else {
                    briefing.firstCourseName
                }
                add("${briefing.sessionCount} 节课 · $first".trim())
            }
            if (briefing.dueTaskCount > 0) add("${briefing.dueTaskCount} 项待办今天截止")
            if (briefing.examCount > 0) {
                val time = briefing.firstExamMinute?.let { formatMinute(it) }
                val exam = listOfNotNull(
                    briefing.firstExamName.takeIf { it.isNotBlank() },
                    time
                ).joinToString(" ")
                add("${briefing.examCount} 场考试 · $exam".trim())
            }
            briefing.weatherLine?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (lines.isEmpty()) return false

        val text = lines.joinToString("\n")
        val notification = baseBuilder(NotificationChannels.DAILY)
            .setContentTitle("今日日程")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                openApp(
                    destination = NotificationNavigation.DEST_DASHBOARD,
                    requestCode = REQUEST_DAILY
                )
            )
            // 汇总不响铃：渠道已经是静音的，这里再把优先级降一档，
            // 免得某些 ROM 按 app 内优先级补一声提示音。
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return notify(TAG_DAILY, ID_DAILY, notification)
    }

    fun postClasses(events: List<ClassReminderEvent>, nowMillis: Long): Boolean {
        if (!canPost() || events.isEmpty()) return false
        val single = events.singleOrNull()
        val builder = baseBuilder(NotificationChannels.CLASS)
            .setContentIntent(
                openApp(
                    destination = NotificationNavigation.DEST_TODAY_COURSES,
                    requestCode = REQUEST_CLASS
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (single != null) {
            val title = if (single.isExam) {
                "${single.eventTitle} ${single.kindLabel}考试".trim()
            } else {
                "${single.eventTitle} 即将上课"
            }
            builder
                .setContentTitle(title)
                .setContentText(classDetail(single, nowMillis))
                .setStyle(NotificationCompat.BigTextStyle().bigText(classDetail(single, nowMillis)))
        } else {
            val style = NotificationCompat.InboxStyle()
            events.forEach { event ->
                val prefix = if (event.isExam) "${event.eventTitle} ${event.kindLabel}考试" else event.eventTitle
                style.addLine("${formatMinute(event.startMinuteOfDay)} · $prefix")
            }
            val title = "接下来有 ${events.size} 项安排"
            builder
                .setContentTitle(title)
                .setContentText("${formatMinute(events.first().startMinuteOfDay)} · ${events.first().eventTitle}")
                .setStyle(style)
        }
        return notify(TAG_CLASS, ID_CLASS, builder.build())
    }

    fun postTodos(events: List<TodoReminderEvent>, nowMillis: Long): Boolean {
        if (!canPost() || events.isEmpty()) return false
        val single = events.singleOrNull()
        val builder = baseBuilder(NotificationChannels.TODO)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (single != null) {
            val due = dueLabel(single.task.dueDateTimestamp, nowMillis)
            val remaining = remainingLabel(single.task.dueDateTimestamp, nowMillis)
            val taskTitle = single.task.title.trim().ifBlank { "待办" }
            builder
                .setContentTitle("待办即将截止")
                .setContentText("「$taskTitle」$due 截止 · $remaining")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("「$taskTitle」\n$due 截止 · $remaining")
                )
                .setContentIntent(
                    openApp(
                        destination = NotificationNavigation.DEST_CHECKLIST,
                        requestCode = REQUEST_TODO + (single.task.id % 500L).toInt(),
                        taskId = single.task.id
                    )
                )
        } else {
            val style = NotificationCompat.InboxStyle()
            events.take(6).forEach { event ->
                val taskTitle = event.task.title.trim().ifBlank { "待办" }
                style.addLine("$taskTitle · ${dueLabel(event.task.dueDateTimestamp, nowMillis)}")
            }
            if (events.size > 6) style.setSummaryText("另有 ${events.size - 6} 项")
            builder
                .setContentTitle("${events.size} 项待办即将截止")
                .setContentText(
                    "${events.first().task.title.trim().ifBlank { "待办" }} · " +
                        dueLabel(events.first().task.dueDateTimestamp, nowMillis)
                )
                .setStyle(style)
                .setContentIntent(
                    openApp(
                        destination = NotificationNavigation.DEST_CHECKLIST,
                        requestCode = REQUEST_TODO
                    )
                )
        }
        return notify(TAG_TODO, ID_TODO, builder.build())
    }

    private fun classDetail(event: ClassReminderEvent, nowMillis: Long): String {
        val parts = buildList {
            add(formatMinute(event.startMinuteOfDay))
            event.location.takeIf { it.isNotBlank() }?.let { add("于 $it") }
            if (event.isExam) {
                event.seat.takeIf { it.isNotBlank() }?.let { add("座位 $it") }
            }
            add(remainingUntilStart(event.startAtMillis, nowMillis))
        }
        return parts.joinToString(" · ")
    }

    private fun remainingUntilStart(startAtMillis: Long, nowMillis: Long): String {
        val minutes = ((startAtMillis - nowMillis + 59_999L) / 60_000L).coerceAtLeast(0L)
        return if (minutes <= 0L) "即将开始" else "${minutes} 分钟后"
    }

    private fun dueLabel(dueAtMillis: Long, nowMillis: Long): String {
        val time = AppZone.format("HH:mm", dueAtMillis)
        return when (AppZone.epochDayOf(dueAtMillis) - AppZone.epochDayOf(nowMillis)) {
            0L -> "今天 $time"
            1L -> "明天 $time"
            -1L -> "昨天 $time"
            else -> AppZone.format("M月d日 HH:mm", dueAtMillis)
        }
    }

    private fun remainingLabel(dueAtMillis: Long, nowMillis: Long): String {
        val diff = dueAtMillis - nowMillis
        if (diff <= 0L) return "已到期"
        val minutes = diff / 60_000L
        return when {
            minutes < 60L -> "还剩 ${minutes.coerceAtLeast(1L)} 分钟"
            minutes < 24 * 60L -> "还剩 ${minutes / 60L} 小时"
            else -> "还剩 ${minutes / (24 * 60L)} 天"
        }
    }

    private fun baseBuilder(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIFICATION_ACCENT)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun openApp(
        destination: String,
        requestCode: Int,
        taskId: Long? = null
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = NotificationNavigation.ACTION_OPEN
            putExtra(NotificationNavigation.EXTRA_DESTINATION, destination)
            if (taskId != null) {
                putExtra(NotificationNavigation.EXTRA_TASK_ID, taskId)
            }
            // CLEAR_TOP + SINGLE_TOP：App 已在后台时回到现有实例，走 onNewIntent，
            // 而不是再叠一个 MainActivity（叠出来的那个拿不到当前 ViewModel 的导航状态时
            // 会表现成「点了通知，回到的是旧页面」）。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 发送通知。**调用前必须过 [canPost]**：那里已经检查 POST_NOTIFICATIONS
     * 与系统通知总开关；lint 看不到这层间接关系，所以这里显式抑制。
     */
    @SuppressLint("MissingPermission")
    private fun notify(tag: String, id: Int, notification: Notification): Boolean {
        if (!canPost()) return false
        return runCatching {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG_DAILY = "miulist_daily"
        private const val TAG_CLASS = "miulist_class"
        private const val TAG_TODO = "miulist_todo"

        private const val ID_DAILY = 4101
        private const val ID_CLASS = 4102
        private const val ID_TODO = 4103

        private const val REQUEST_DAILY = 4201
        private const val REQUEST_CLASS = 4202
        private const val REQUEST_TODO = 4203

        /** 主题绿。通知上的小色块只在部分 ROM 上可见，但不该是系统默认蓝。 */
        private val NOTIFICATION_ACCENT = 0xFF3F7D5A.toInt()
    }
}
