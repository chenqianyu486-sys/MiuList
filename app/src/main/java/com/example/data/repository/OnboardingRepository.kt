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
 * 「去看看」不算 —— 那是**暂停**，不是走完（见下面的 [currentTarget]）。
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
     * 引导停在**哪一步**。
     *
     * 存的是**目标的名字**而不是序号。
     *
     * 理由：引导的步骤数会随「一键生成待办」这个开关变 —— 关着时四步
     * （同步课表 → 开关 → 壁纸 → 翻面），开着时六步（与加这个开关之前一样）。
     * 同一个序号在两套清单里指的不是同一步：老版本停在「第 2 步」的用户，
     * 升级后按序号接着看会落到另一步上。名字不会认错。
     *
     * null = 还没停过（从头看）。
     */
    var currentTarget: String?
        get() = prefs.getString(KEY_STEP_TARGET, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_STEP_TARGET)
            } else {
                editor.putString(KEY_STEP_TARGET, value)
            }
            // **每一次写都记下「已经用新格式记过了」**：
            // 「没停过（从头看）」和「从没记过（老版本只存了序号）」在值上都是空，
            // 但前者要回到第 1 步、后者要按老序号换算 —— 分不开的话，
            // 走完 / 跳过之后的老用户下次打开会又跳回原来那一步。
            editor.putBoolean(KEY_STEP_TARGET_SET, true).apply()
        }

    /**
     * 进度是不是**已经用新格式记过**。
     *
     * false = 这个键从来没被写过 = 这台设备上的进度还是老版本那个序号
     * （要按 `GuideTarget.legacyOrderBeforeToggle` 换算）；
     * true = 已经记过了（哪怕记的是「从头看」）。
     */
    val hasTargetProgress: Boolean
        get() = prefs.getBoolean(KEY_STEP_TARGET_SET, false)

    /**
     * 老版本存的**序号**（那时引导固定六步、顺序不变）。
     *
     * **只读**：老用户的进度要按 `GuideTarget.legacyOrderBeforeToggle`
     * 换算成目标名（见 `MainViewModel` 里的续看逻辑），换算完就写新键，
     * 这个老键从此不再读第二遍。
     */
    val legacyCurrentStep: Int
        get() = prefs.getInt(KEY_STEP, 0)

    companion object {
        private const val PREFS_NAME = "miulist_onboarding_prefs"
        private const val KEY_SEEN = "onboarding_seen"
        private const val KEY_STEP = "onboarding_step"
        private const val KEY_STEP_TARGET = "onboarding_step_target"
        private const val KEY_STEP_TARGET_SET = "onboarding_step_target_set"
    }
}
