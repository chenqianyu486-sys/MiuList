package com.example.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MiuListApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 本地提醒唯一的广播接收器。
 *
 * ## 它同时管两类广播
 *
 * 1. **自己排的闹钟**：带着三个 ACTION_* 之一进来，先算内容、发通知，再排下一次；
 * 2. **系统事件**：开机、App 被覆盖安装、系统时间/时区被改。这些时刻原来的
 *    PendingIntent 已经不可信（重启后全丢、改时区后绝对时刻不变但墙上时间变了），
 *    必须重新读库、重新注册。这一类**不补发**错过的提醒，只排未来。
 *
 * ## 为什么是 exported=false 也能收到
 *
 * 闹钟是显式 PendingIntent（指名到这个类），系统事件是系统本身发的受保护广播，
 * 两者都不需要组件对外导出。少一个导出组件，就少一个能被别的 App 调起的入口。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? MiuListApplication ?: return
        val action = intent.action ?: return
        val scheduler = application.container.notificationScheduler
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    NotificationScheduler.ACTION_DAILY,
                    NotificationScheduler.ACTION_CLASS,
                    NotificationScheduler.ACTION_TODO -> scheduler.handleAlarm(action)

                    else -> scheduler.reschedule()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
