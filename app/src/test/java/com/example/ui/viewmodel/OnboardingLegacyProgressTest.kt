package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 老用户的引导进度换算：引导的清单改过好几次，
 * 而进度是**存在设备上**的 —— 认不得就等于让他从头再看一遍。
 *
 * 换算有两条路：
 *
 * - 老版本存**序号**（那时引导固定六步）→ 按 [GuideTarget.legacyOrderBeforeToggle] 换；
 * - 新一点的版本存**目标名** → 按名字认，认不得的看
 *   [GuideTarget.retiredTargetNames]。
 *
 * 这里钉的是「已经从引导里去掉的那一步」怎么落地：
 * 「总览页主卡可翻面」那一步（HERO_FLIP_CARD）已经从引导里拿掉了，
 * 老设备上还可能留着它 —— 序号是 5，名字是 "HERO_FLIP_CARD"。
 * 两条路都要落到**新的最后一步**（壁纸），而不是第 1 步。
 */
class OnboardingLegacyProgressTest {

    @Test
    fun `老序号落在老清单最后一步的人落到壁纸`() {
        // 老清单的最后一步是「翻面」（序号 5），那一步已经没了。
        // 落到壁纸 = 新的最后一步。
        assertEquals(
            GuideTarget.WALLPAPER,
            GuideTarget.legacyOrderBeforeToggle.getOrNull(5)
        )
    }

    @Test
    fun `老清单的其余序号不受影响`() {
        assertEquals(
            GuideTarget.ZJU_SYNC,
            GuideTarget.legacyOrderBeforeToggle.getOrNull(0)
        )
        assertEquals(
            GuideTarget.COURSE_CATEGORY,
            GuideTarget.legacyOrderBeforeToggle.getOrNull(1)
        )
        assertEquals(
            GuideTarget.LINKAGE_RULES,
            GuideTarget.legacyOrderBeforeToggle.getOrNull(2)
        )
        assertEquals(
            GuideTarget.WEEK_TASK_GENERATION,
            GuideTarget.legacyOrderBeforeToggle.getOrNull(3)
        )
        assertEquals(
            GuideTarget.WALLPAPER,
            GuideTarget.legacyOrderBeforeToggle.getOrNull(4)
        )
    }

    @Test
    fun `去掉的那一步的旧名字要认得出来`() {
        // 存的是名字：老设备上还可能写着 "HERO_FLIP_CARD"。
        // 认不得这个名字就等于把他推回第 1 步 —— 白看一遍。
        assertEquals(
            GuideTarget.WALLPAPER,
            GuideTarget.retiredTargetNames["HERO_FLIP_CARD"]
        )
    }

    @Test
    fun `现在的目标名不会落进已去掉的那张表`() {
        // 现在清单里的目标都还认得，别被「已去掉」那张表误伤。
        GuideTarget.entries.forEach { target ->
            assertEquals(
                "${target.name} 现在还在清单里，不该被当成已去掉的目标",
                null,
                GuideTarget.retiredTargetNames[target.name]
            )
        }
    }
}
