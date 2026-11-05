package com.example

import android.app.Application
import com.example.data.di.AppContainer

/**
 * 持有全应用唯一的 [AppContainer]。
 *
 * 改造前依赖在 `MainActivity` 的 `by viewModels {}` 里现场拼装，
 * 换界面/写测试都要把同一段装配代码抄一遍。放进 Application 后，
 * 生命周期跟着进程走，测试也能从 `InstrumentationRegistry` 或
 * `ApplicationProvider` 拿到同一份配置。
 */
class MiuListApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 渠道要在第一条通知之前建好。放在 Application 而不是 Receiver 里兜底：
        // 用户可能在设置里先关掉某一类，渠道不存在时系统设置里连开关都看不到。
        container.reminderNotifier.ensureChannels()
    }
}