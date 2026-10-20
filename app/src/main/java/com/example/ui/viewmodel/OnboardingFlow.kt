package com.example.ui.viewmodel

/**
 * 引导与「它送你去看的那个功能」之间的往来规则。
 *
 * ## 这条规则是什么
 *
 * 引导的「去看看」是**暂停**而不是关闭：用户点下去去看壁纸，看完关掉壁纸弹层，
 * 引导要自己接回来 —— 他还欠着几页没看，收掉就等于让他自己记着
 * 「我刚才看到第几步了」。
 *
 * ## 为什么单独放一个文件
 *
 * 这是一条纯状态判断（看被等的那个弹层开没开），没有任何副作用，
 * 所以能写成纯函数、用普通单测钉住。放到界面里测得开四个不同的弹层，
 * 那条路测不动。
 */

/**
 * 引导要去的那个功能，现在是不是开着。
 *
 * 一个目标对一个弹层，判据就落在那个弹层自己的开关上。
 */
internal fun MainUiState.isGuideTargetOpen(target: GuideTarget): Boolean = when (target) {
    GuideTarget.ZJU_SYNC -> isCloudAccountDialogOpen
    GuideTarget.COURSE_CATEGORY -> isCourseCategoryDialogOpen
    GuideTarget.LINKAGE_RULES -> isCategoryRulesDialogOpen
    GuideTarget.WALLPAPER -> isWallpaperDialogOpen
}

/**
 * 目标做完了就把引导接回来。
 *
 * 判据只看**被等的那个目标自己**的弹层：别的弹层开开关都不算数 ——
 * 用户在浙大同步弹层里顺手开了另一个弹层，关掉那个另一个弹层
 * 不该把引导拉回来（那会把他从别的界面上拽回来）。
 */
internal fun MainUiState.resumeOnboardingIfTargetDone(): MainUiState {
    val awaiting = onboardingAwaitingTarget ?: return this
    if (isGuideTargetOpen(awaiting)) return this
    return copy(
        isOnboardingGuideOpen = true,
        onboardingAwaitingTarget = null,
        onboardingResumed = true
    )
}

/**
 * 浙大同步成功之后弹层被**代码**收掉时，顺手把引导接回来。
 *
 * ## 这一步以前是缺的
 *
 * 弹层有两条收法：用户点「关闭」（走 `closeCloudAccountDialog`，那里有接回），
 * 以及**同步成功后代码自己收**（`withSyncSuccess(closeDialog = true)`）。
 * 后一条绕开了前一条，于是接回从来没在这条路上跑过 —— 用户看到的现象是
 * 「同步完了，引导没了」：他还欠着三页没看，却再也回不去。
 *
 * 判据还是「被等的那个目标自己」：浙大弹层被收掉了 → 接回来；
 * 别的目标还开着（用户正停在壁纸弹层上，碰巧后台同步成功了）→ 不接，
 * 否则会把人从别的界面上拽回来。
 */
internal fun MainUiState.closeCloudAccountDialogAfterSync(): MainUiState = copy(
    isCloudAccountDialogOpen = false,
    isTransferDialogOpen = false
).resumeOnboardingIfTargetDone()
