package com.example.data.repository

import android.content.Context

/**
 * 上手引导的「看过」标记。
 *
 * ## 为什么需要它
 *
 * 引导会在**空态**自动弹出：本地一门课都没有时，第一次打开 App 给一份上手说明。
 * 但「空态」这个条件会一直成立 —— 用户跳过引导、还没同步课表，
 * 下一次冷启动又会弹一遍。于是每一次启动都在打断一个还没准备好的人。
 *
 * 所以记一个「已经看过」的标记：**任何一种关闭方式**（跳过 / 返回 / 完成 /
 * 去某个功能）都算看过，此后不再自动弹。
 *
 * 设置页里的入口不受这个标记影响 —— 想重看随时能看。
 */
class OnboardingRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 引导是否已经展示过（用户以任何方式关掉过）。 */
    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean(KEY_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "miulist_onboarding_prefs"
        private const val KEY_SEEN = "onboarding_seen"
    }
}
