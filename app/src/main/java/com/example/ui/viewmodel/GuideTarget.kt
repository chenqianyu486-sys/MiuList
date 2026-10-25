package com.example.ui.viewmodel

/**
 * 引导要带用户去看的功能。
 *
 * 放在 viewmodel 这一层是因为它是**行为契约**：ViewModel 认得这些目标并负责
 * 打开对应的功能，界面只负责把用户的选择翻译成其中一个。
 *
 * 文案（标题、说明、按钮）不在这里 —— 那些是界面的事，见
 * `com.example.ui.components.GuideSteps`。
 *
 * ## 枚举顺序就是**完整清单**的顺序
 *
 * [GuideTarget.entries] 的顺序被三处依赖：引导的步进、老版本进度换算
 * （[legacyOrderBeforeToggle]）、以及「这一步看不见时落到哪一步」的回退规则。
 * 所以**加新目标要挑位置**，不是随便追加到末尾。
 *
 * [QUICK_GENERATE_TOGGLE] 排在 [COURSE_CATEGORY] 前面是刻意的：
 * 「要不要一键生成」这个决定必须问在**配置规则之前** ——
 * 不想用这个功能的人，先被推去配一遍类别与规则，那就正好是「折腾设置」，
 * 也就是这个开关要避免的那件事。
 */
enum class GuideTarget {
    /** 浙大同步：登录浙大教务，把课表拉进来。 */
    ZJU_SYNC,

    /**
     * **「一键生成待办」这个开关本身**。
     *
     * 它是一步，但**不是**「去看看某个功能」那一步：没有弹层可开，
     * 按下去是**打开这个功能**，然后引导接着往下走（课程分类 → 联动规则 →
     * 生成本周待办）。
     *
     * **只在功能关闭时出现**。开着的时候它没有可讲的内容（入口在顶栏，
     * 引导的第 4 步已经讲过怎么用），硬塞一步等于让用户看一个已经开着的东西。
     */
    QUICK_GENERATE_TOGGLE,

    /** 课程类别：每门课归到哪一个类别。 */
    COURSE_CATEGORY,

    /** 联动规则配置：按类别批量设三个开关。 */
    LINKAGE_RULES,

    /**
     * 生成本周待办：按类别规则把本周的作业、实验报告与预习排进清单。
     *
     * 落点是**预览弹层**（`QuickGenerateDialog`）：先看会排出些什么，再决定要不要收下。
     * 这一步是异步造的（要读校历、课程与规则），所以开它的那条路和别的目标不一样，
     * 见 `MainViewModel.openOnboardingTarget`。
     */
    WEEK_TASK_GENERATION,

    /** 壁纸：预设渐变或自己的照片。 */
    WALLPAPER,

    /**
     * 总览页那张主卡的**背面**：学在浙大待交的作业。
     *
     * 这一项**没有弹层**：它是总览页最上面那张卡翻过去那一面，
     * 「去看看」= 把人送到总览页并替他把卡翻过去（见 `MainViewModel.openOnboardingTarget`）。
     * 它排在引导的最后一步，因为翻过去看就是这一趟的终点。
     */
    HERO_FLIP_CARD;

    companion object {
        /**
         * 加开关之前那六步的顺序。
         *
         * 老版本把引导的进度存成**序号**，而序号只在「固定六步」那套清单里
         * 才有意义。现在清单会随开关变成四步或六步，序号就对不上了，
         * 所以老用户的进度要按这张表换算成**目标名**（见
         * `OnboardingRepository.currentTarget`）。
         */
        val legacyOrderBeforeToggle: List<GuideTarget> = listOf(
            ZJU_SYNC,
            COURSE_CATEGORY,
            LINKAGE_RULES,
            WEEK_TASK_GENERATION,
            WALLPAPER,
            HERO_FLIP_CARD
        )
    }
}
