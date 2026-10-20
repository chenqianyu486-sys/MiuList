package com.example.ui.viewmodel

/**
 * 引导要带用户去看的四个功能。
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

    /** 壁纸：预设渐变或自己的照片。 */
    WALLPAPER
}
