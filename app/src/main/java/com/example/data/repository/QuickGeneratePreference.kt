package com.example.data.repository

import android.content.Context

/**
 * 「一键生成待办」这个开关。
 *
 * ## 它管的是什么
 *
 * 顶栏那颗 ✨（按课程规则一键把本周的作业、实验报告与预习排进清单）
 * 以及它背后的那一整套设置：课程联动规则、待办生成规则。
 *
 * **默认关闭**：有些人更想要一个轻量的清单 —— 手动加待办，不碰任何规则。
 * 开着就得先配类别、配规则、配节奏与截止，那三样东西对只想记一件事的人
 * 是纯负担。所以「一键生成」是**要主动打开**的功能，不是默认摊在面前的东西。
 *
 * ## 为什么单独一个仓储
 *
 * 它是**用户的一个偏好**，不是一条数据：没有表、没有行、没有 JOIN。
 * 放 `ScheduleSettingsRepository`（那里是课程 × 类型的生成规则，落在库里）
 * 会把「要不要有这个功能」和「这个功能怎么排」混成两件事。
 *
 * ## 「做没做过决定」是第三种状态
 *
 * [hasDecision] 区分「用户明确关掉了」与「从来没决定过」——
 * 后者要靠**老用户用没用过**来定默认值（见 `MainViewModel` 里的启动判定）：
 * 升级上来的老用户如果一直在用一键生成，就不该因为这次改动突然失去那个入口；
 * 而全新安装的人默认得到轻量体验。
 */
class QuickGeneratePreference(
    context: Context,
    prefName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefName, Context.MODE_PRIVATE)

    /**
     * 用户**做没做过决定**（在引导里开过，或在「更多」里拨过）。
     *
     * 键不存在 = 还没决定过 = 这台设备可能是刚装的，也可能是升级上来的老用户。
     * 这两种要分开处理，见 [isEnabled] 的说明。
     */
    val hasDecision: Boolean
        get() = prefs.contains(KEY_ENABLED)

    /**
     * 「一键生成待办」开没开。
     *
     * 读不到键时**默认关闭**（[DEFAULT_ENABLED]）：轻量体验是新装的默认状态，
     * 老用户的例外由启动时的一次性判定写回这个键。
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        /**
         * 默认**关闭**。
         *
         * 见类头的说明：想要轻量清单的人不该先被推去配一遍规则。
         */
        const val DEFAULT_ENABLED = false

        private const val PREFS_NAME = "miulist_quick_generate_prefs"
        private const val KEY_ENABLED = "quick_generate_enabled"
    }
}
