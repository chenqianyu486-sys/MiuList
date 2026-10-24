package com.example.ui.viewmodel

/**
 * 引导要带用户去看的功能。
 *
 * 放在 viewmodel 这一层是因为它是**行为契约**：ViewModel 认得这些目标并负责
 * 打开对应的功能，界面只负责把用户的选择翻译成其中一个。
 *
 * 文案（标题、说明、按钮）不在这里 —— 那些是界面的事，见
 * `com.example.ui.components.GuideSteps`。
 */
enum class GuideTarget {
    /** 浙大同步：登录浙大教务，把课表拉进来。 */
    ZJU_SYNC,

    /** 课程类别：每门课归到哪一个类别。 */
    COURSE_CATEGORY,

    /** 联动规则配置：按类别批量设三个开关。 */
    LINKAGE_RULES,

    /**
     * 一键生成本周待办：按类别规则把本周的作业、实验报告与预习排进清单。
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
    HERO_FLIP_CARD
}
