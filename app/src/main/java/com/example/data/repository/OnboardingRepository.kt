package com.example.data.repository

import android.content.Context

/**
 * 上手引导的进度：走过没有、停在哪一步。
 *
 * ## 为什么需要它
 *
 * 引导会在**空态**自动弹出：本地一门课都没有时，第一次打开 App 给一份上手说明。
 * 但「空态」这个条件会一直成立 —— 用户跳过引导、还没同步课表，
 * 下一次冷启动又会弹一遍。于是每一次启动都在打断一个还没准备好的人。
 *
 * 所以记一个「已经走过」的标记：**跳过**或**走完**算走过，此后不再自动弹。
 * 「去看看」不算 —— 那是**暂停**，不是走完（见下面的 [currentStep]）。
 *
 * 设置页里的入口不受这个标记影响 —— 想重看随时能看。
 */
class OnboardingRepository(
    context: Context,
    prefName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefName, Context.MODE_PRIVATE)

    /**
     * 引导已经走过一遍（走完或跳过）——此后不再自动弹。
     */
    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean(KEY_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN, value).apply()
        }

    /**
     * 引导停在哪一步。
     *
     * 引导是可以**暂停**的：用户点「去看看」去配置某个功能，那个功能做完关掉之后
     * 引导接回来接着讲。步数记在这里，于是**进程被杀也接得上** ——
     * 冷启动时引导从上次停住的地方接着讲，而不是从头再推一遍。
     *
     * 走完或跳过的引导会把步数归零（那一刻这一趟才算走完），所以
     * 「重看一遍」和「接着看」用的是同一个数：没走完就接着看，走完了就从头看。
     */
    var currentStep: Int
        get() = prefs.getInt(KEY_STEP, 0)
        set(value) {
            prefs.edit().putInt(KEY_STEP, value.coerceAtLeast(0)).apply()
        }

    companion object {
        private const val PREFS_NAME = "miulist_onboarding_prefs"
        private const val KEY_SEEN = "onboarding_seen"
        private const val KEY_STEP = "onboarding_step"
    }
}
