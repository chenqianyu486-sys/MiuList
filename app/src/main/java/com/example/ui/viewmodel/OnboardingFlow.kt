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
 *
 * ## 引导有**两套清单**
 *
 * 「一键生成待办」默认关闭，而它背后的「课程分类 / 课程联动规则 /
 * 生成本周待办」三步**只服务于它**。所以：
 *
 * - 功能**关闭**：引导走轻量四步（同步课表 → 一键生成开关 → 壁纸 → 翻面）；
 * - 功能**开启**：引导走完整六步（同步课表 → 课程分类 → 联动规则 →
 *   生成本周待办 → 壁纸 → 翻面），与加这个开关之前完全一样。
 *
 * 关着的人不该被推去配规则 —— 那正是这个开关要避免的「折腾设置」。
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
    GuideTarget.WEEK_TASK_GENERATION -> isQuickGenerateDialogOpen
    GuideTarget.WALLPAPER -> isWallpaperDialogOpen
    // 这一项**没有弹层**：它是总览页那张主卡的背面。
    // 「去看看」是直接把人送到总览页并替他把卡翻过去 —— 没有弹层开着，
    // 也就没有「做完之后接回来」这回事，所以恒为 false。
    GuideTarget.HERO_FLIP_CARD -> false
    // 开关那一步也没有弹层：它按下去是**打开这个功能**（不是去某个地方），
    // 引导不暂停、也不等谁。
    GuideTarget.QUICK_GENERATE_TOGGLE -> false
}

/**
 * 当前状态下，引导**看得见**的步骤（按顺序）。
 *
 * 关着时是轻量四步，开着时是完整六步（与加这个开关之前一样）。
 *
 * 两种清单都**至少有两步**（同步课表与翻面），所以下面那些「往后找一步」
 * 的回退永远找得到落点。
 */
internal fun visibleGuideTargets(quickGenerateEnabled: Boolean): List<GuideTarget> =
    if (quickGenerateEnabled) {
        // 开着：开关那一步**不出现**（入口在顶栏，第 4 步已经讲过怎么用），
        // 其余六步原样。
        GuideTarget.entries.filter { it != GuideTarget.QUICK_GENERATE_TOGGLE }
    } else {
        // 关着：那三步只服务于一键生成，不出现；换成「要不要开启它」那一步。
        GuideTarget.entries.filter {
            it != GuideTarget.COURSE_CATEGORY &&
                it != GuideTarget.LINKAGE_RULES &&
                it != GuideTarget.WEEK_TASK_GENERATION
        }
    }

/**
 * 把「当前停在哪一步」落到**看得见**的某一步上。
 *
 * 什么时候会落不上：开关那一步只在一键生成**关闭**时出现，而用户在引导里
 * 按下「开启」之后它就不在清单里了 —— 这时引导要接着往下走，
 * 落在**它后面第一个看得见**的步骤上，也就是「课程分类」。
 *
 * 这条规则同时处理了另一种情况：老用户停在了「生成本周待办」那一步，
 * 后来在「更多」里把功能关了（那三步不再出现）—— 引导落到「壁纸」，
 * 而不是停在一个看不见的步骤上让用户以为引导卡住了。
 */
internal fun resolveVisibleGuideTarget(
    target: GuideTarget,
    quickGenerateEnabled: Boolean
): GuideTarget {
    val visible = visibleGuideTargets(quickGenerateEnabled)
    if (target in visible) return target
    val order = GuideTarget.entries
    val index = order.indexOf(target)
    return visible.firstOrNull { order.indexOf(it) > index } ?: visible.last()
}

/**
 * 「下一步」落在哪一步。null = 已经是最后一步，这一趟到此为止。
 *
 * 算的是**看得见**的清单里的下一步，所以关着时从「开关」那一步按下
 * 会直接落到「壁纸」（那三步不出现），开着时则是「课程分类」。
 */
internal fun nextVisibleGuideTarget(
    target: GuideTarget,
    quickGenerateEnabled: Boolean
): GuideTarget? {
    val visible = visibleGuideTargets(quickGenerateEnabled)
    val current = resolveVisibleGuideTarget(target, quickGenerateEnabled)
    val index = visible.indexOf(current)
    return visible.getOrNull(index + 1)
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
