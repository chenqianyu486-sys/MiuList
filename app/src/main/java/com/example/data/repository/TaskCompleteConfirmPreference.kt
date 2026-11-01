package com.example.data.repository

import android.content.Context

/**
 * 「完成待办时要不要先确认一下」这个开关。
 *
 * ## 它管的是什么
 *
 * 点待办卡左侧那颗小圆圈把待办标成已完成时，先弹一个确认弹窗，
 * 而不是直接把那条待办勾掉。
 *
 * **默认开启**：勾掉一条待办是**不可见地丢掉了它**——它从总览的清单里消失，
 * 而「刚才那条是不是真的做完了」这个疑问只有用户自己能回答。
 * 一次多问一句，换来「手滑勾掉」不再发生。
 *
 * 那些一勾就是好几条、勾完就走的用户，可以在设置里把它关掉 ——
 * 确认弹窗对你是负担的时候，它就是负担，不该由别人替你决定。
 *
 * ## 为什么单独一个仓储
 *
 * 它是**用户的一个偏好**，不是一条数据：没有表、没有行、没有 JOIN。
 * 放 `ScheduleSettingsRepository`（那里是课程 × 类型的生成规则，落在库里）
 * 会把「要不要有这个功能」和「这个功能怎么排」混成两件事。
 *
 * ## 与「一键生成待办」的区别
 *
 * 那个开关**默认关闭**（不想用的人不该先被推去配一遍规则），
 * 这个**默认开启**（误勾的代价直接落在用户身上，而关掉它只是多按一次）。
 * 两个开关的默认值方向相反，是因为它们挡的东西不一样：
 * 一个挡的是「多出来的功能」，另一个挡的是「已经发生过的误操作」。
 */
class TaskCompleteConfirmPreference(
    context: Context,
    prefName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefName, Context.MODE_PRIVATE)

    /**
     * 勾选完成时要不要先弹确认弹窗。
     *
     * 读不到键时**默认开启**（[DEFAULT_ENABLED]）：见类头的说明 ——
     * 误勾一条待办的代价是直接丢一条，而关掉确认只是一次额外的点击。
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        /**
         * 默认**开启**。
         *
         * 与「一键生成待办」的默认关闭方向相反：那个开关挡的是**多出来的功能**
         * （不要就别碰），这个挡的是**已经发生的误操作**（要防）。
         */
        const val DEFAULT_ENABLED = true

        private const val PREFS_NAME = "miulist_task_complete_confirm_prefs"
        private const val KEY_ENABLED = "task_complete_confirm_enabled"
    }
}
