package com.example.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.calendar.AppZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地提醒的闹钟调度器。
 *
 * ## 三个渠道、三只闹钟
 *
 * 每日汇总 / 上课考试 / 待办截止各注册**一只**下一次的闹钟。响过之后
 * Receiver 重新读库、再排下一次，因此任何时刻系统里最多三只 PendingIntent。
 *
 * 这比「一次排一周」重要得多：改课、停课、补课、勾掉待办、改校历之后，
 * 不需要去追着一批旧 PendingIntent 逐个注销 —— 下一次重排自然覆盖掉旧的那只。
 *
 * ## 精确闹钟与降级
 *
 * Android 12+ 的 `SCHEDULE_EXACT_ALARM` 是特殊权限，用户可能没给。
 * 给了就用 `setExactAndAllowWhileIdle`（Doze 下也能准时）；
 * 没给就退到 `setAndAllowWhileIdle`，系统可能把提醒挪后几分钟 ——
 * 设置弹层会把这件事如实告诉用户，而不是假装准时。
 */
class NotificationScheduler(
    context: Context,
    private val settingsRepository: NotificationSettingsRepository,
    private val snapshotLoader: ReminderSnapshotLoader,
    private val notifier: ReminderNotifier,
    private val deliveryStore: ReminderDeliveryStore,
    /**
     * 每日汇总里的一行天气。
     *
     * 优先读本机缓存；容器那边会在没有当天缓存时做一次**很短的**补充拉取。
     * 这是整套提醒里唯一可能出网的环节，而且只发固定坐标、不携带任何用户数据；
     * 超时或失败就少写一行，绝不影响提醒本身按时出现。
     */
    private val weatherLine: suspend () -> String? = { null }
) {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager? =
        appContext.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    /**
     * 按当前数据与偏好，把三只闹钟重新排一遍。
     *
     * `Mutex` 是必要的：数据流、设置变更、App 回到前台、Receiver 响完
     * 都可能同时触发重排。两次重排交错执行时，后一次算出来的时间可能被
     * 前一次覆盖，表现成「改了设置，但闹钟还是旧时间」。
     */
    suspend fun reschedule() {
        try {
            mutex.withLock {
                notifier.ensureChannels()
                val settings = settingsRepository.read()
                if (!settings.enabled || !notifier.canPost()) {
                    cancelAll()
                    return@withLock
                }

                val snapshot = snapshotLoader.load()
                val now = System.currentTimeMillis()
                // 库里已经不存在的待办，把它的提醒记录一起清掉。
                deliveryStore.prune(
                    snapshot.tasks.mapTo(HashSet()) { ReminderPlanner.todoDeliveryKey(it) }
                )
                val delivered = deliveryStore.deliveredKeys()

                val dailyAt = if (settings.dailyActive) {
                    ReminderPlanner.nextDailyTrigger(now, settings.dailyMinuteOfDay)
                } else {
                    null
                }
                val classAt = if (settings.classActive) {
                    ReminderPlanner.nextClassTrigger(snapshot, now, settings.classLeadMinutes)
                } else {
                    null
                }
                val todoAt = if (settings.todoActive) {
                    ReminderPlanner.nextTodoTrigger(
                        tasks = snapshot.tasks,
                        nowMillis = now,
                        leadMinutes = settings.todoLeadMinutes,
                        deliveredKeys = delivered
                    )
                } else {
                    null
                }

                schedule(ACTION_DAILY, dailyAt)
                schedule(ACTION_CLASS, classAt)
                schedule(ACTION_TODO, todoAt)
            }
        } catch (e: Exception) {
            // 排期失败不该把 App 拖崩：提醒是锦上添花，课表和待办本身仍然可用。
            // 记一条日志，下一次数据变化 / 回到前台会再试。
            Log.w(TAG, "重排本地提醒失败", e)
        }
    }

    /**
     * 闹钟响了之后的处理。
     *
     * 内容一律**重新算**，不把文案预先塞进 PendingIntent：
     * 用户在排完闹钟之后改了教室、勾掉了待办、加了新考试，响的时候
     * 要按当下的数据说话。算出来没有东西可提醒就静默跳过，但**仍然重排下一次**。
     */
    suspend fun handleAlarm(action: String) {
        try {
            notifier.ensureChannels()
            val settings = settingsRepository.read()
            if (settings.enabled && notifier.canPost()) {
                val snapshot = snapshotLoader.load()
                val now = System.currentTimeMillis()
                when (action) {
                    ACTION_DAILY -> if (settings.dailyActive) {
                        val briefing = ReminderPlanner.dailyBriefing(
                            snapshot = snapshot,
                            epochDay = AppZone.epochDayOf(now),
                            weatherLine = withContext(Dispatchers.IO) {
                                runCatching { weatherLine() }.getOrNull()
                            }
                        )
                        if (briefing.hasAnything) {
                            notifier.postDaily(briefing)
                        }
                    }

                    ACTION_CLASS -> if (settings.classActive) {
                        val events = ReminderPlanner.classEventsInFireWindow(
                            snapshot = snapshot,
                            nowMillis = now,
                            leadMinutes = settings.classLeadMinutes
                        )
                        if (events.isNotEmpty()) {
                            notifier.postClasses(events, now)
                        }
                    }

                    ACTION_TODO -> if (settings.todoActive) {
                        val delivered = deliveryStore.deliveredKeys()
                        val events = ReminderPlanner.todoEventsInFireWindow(
                            tasks = snapshot.tasks,
                            nowMillis = now,
                            leadMinutes = settings.todoLeadMinutes,
                            deliveredKeys = delivered
                        )
                        if (events.isNotEmpty() && notifier.postTodos(events, now)) {
                            // 只有真的发出去才记「已提醒」。没发出去（权限刚被撤销）
                            // 不记，用户重新打开权限后还能收到。
                            deliveryStore.markDelivered(
                                events.map { ReminderPlanner.todoDeliveryKey(it.task) }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理提醒闹钟失败：$action", e)
        } finally {
            // 不管这一次有没有发出通知，都要把下一次排上 ——
            // 否则一条坏数据会让整条提醒链从此停摆。
            reschedule()
        }
    }

    private fun schedule(action: String, atMillis: Long?) {
        val manager = alarmManager ?: return
        val pendingIntent = alarmPendingIntent(action)
        manager.cancel(pendingIntent)
        if (atMillis == null) return

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        try {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            // 权限在两次检查之间被收回（用户刚从系统设置里关掉）：
            // 退到不精确闹钟，至少不会让整个重排失败。
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            }
        }
    }

    private fun cancelAll() {
        val manager = alarmManager ?: return
        for (action in listOf(ACTION_DAILY, ACTION_CLASS, ACTION_TODO)) {
            manager.cancel(alarmPendingIntent(action))
        }
    }

    private fun alarmPendingIntent(action: String): PendingIntent {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            this.action = action
        }
        val requestCode = when (action) {
            ACTION_DAILY -> REQUEST_DAILY
            ACTION_CLASS -> REQUEST_CLASS
            else -> REQUEST_TODO
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_DAILY = "com.example.notification.action.DAILY"
        const val ACTION_CLASS = "com.example.notification.action.CLASS"
        const val ACTION_TODO = "com.example.notification.action.TODO"

        const val REQUEST_DAILY = 4301
        const val REQUEST_CLASS = 4302
        const val REQUEST_TODO = 4303

        private const val TAG = "MiuListReminder"
    }
}
