package com.example.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 窗口级的防截屏能力（`FLAG_SECURE`）。
 *
 * ## 为什么需要它
 *
 * 这个 App 会让用户输入**浙大统一身份认证的学号与密码**。密码框默认是可见的，
 * 而 Android 默认允许三种捕获：
 *
 * - 系统录屏 / 第三方录屏（`MediaProjection`）；
 * - 任务管理器「最近任务」里的缩略图（recents snapshot）；
 * - 部分厂商的「截屏」快捷键。
 *
 * 这三种都**不需要任何权限申请**，也没有任何提示。用户随手截一张图发给同学，
 * 密码就跟着进了聊天记录与云端相册 —— 而这个密码同时是教务网与学在浙大的通行证。
 *
 * `FLAG_SECURE` 是系统给的唯一一个「窗口不出现在截屏/录屏里」的开关：
 * 设置之后，这个窗口既不会被截屏也不会被录屏，最近任务缩略图里也是一片黑。
 *
 * ## 为什么是**按弹层开关**，而不是整个 App 一律开
 *
 * 全局开 `FLAG_SECURE` 会顺手把「给课表截张图发朋友圈」也一起禁掉 —— 那是
 * 用户真正想要的场景，一刀切下去是拿可用性换安全。
 *
 * 而密码只出现在**两个弹层**里（总览页的一键绑定、抽屉里的账号设置），
 * 所以只在这两个弹层挂着的时候才给窗口加标志：弹层一关，标志撤掉，
 * 截课表照旧。
 *
 * ## 为什么标志挂在**窗口**上而不是某个控件上
 *
 * `FLAG_SECURE` 是窗口属性，没有「某个 View 不可见」这种粒度。
 * 本 App 的弹层走的是 [com.example.ui.components.GlassOverlayHost] ——
 * 它为了做毛玻璃，**刻意**把弹层画在 Activity 同一个窗口里（而不是另开
 * 一个 `Dialog` 窗口），所以这里拿到的是 Activity 窗口。
 *
 * 对真正独立的 `Dialog` 窗口（`DatePickerDialog` 这类），优先拿
 * `DialogWindowProvider` 给的那个窗口 —— 标志加错窗口等于没加。
 */
@Composable
fun currentHostWindow(): Window? {
    val view = LocalView.current
    // 独立 Dialog 窗口：`LocalView.current.parent` 才是承载它的那个窗口。
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    if (dialogWindow != null) return dialogWindow
    // 与 Activity 同窗口的 overlay：回落到 Activity 自己的窗口。
    return view.context.findActivity()?.window
}

/**
 * 在 [enabled] 期间禁止所在窗口被截屏 / 录屏。
 *
 * 关闭时**只撤销自己加的那个标志** —— 窗口本来就有 `FLAG_SECURE` 时
 * （理论上没有，但别假设）不能顺手把它清掉。
 */
@Composable
fun SecureWindowEffect(enabled: Boolean) {
    val window = currentHostWindow()
    DisposableEffect(window, enabled) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        var addedByUs = false
        if (enabled && window != null) {
            addedByUs = (window.attributes.flags and flag) == 0
            if (addedByUs) window.addFlags(flag)
        }
        onDispose {
            if (addedByUs) window?.clearFlags(flag)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
