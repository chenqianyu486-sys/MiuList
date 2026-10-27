package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键生成待办」关着时引导走轻量三步、开着时走完整五步。
 *
 * 这条规则撑着一个用户会不会被推去「折腾设置」：
 * 那三步（课程分类 / 课程联动规则 / 生成本周待办）**只服务于一键生成**，
 * 不想要这个功能的人不该先被拉着配一遍规则。
 *
 * 所以它写成纯函数：开关是唯一输入，走哪几步是唯一输出。
 * 界面（面板上的「第 N 步 / 共 M 步」与进度点）和 ViewModel（下一步落在哪）
 * 用的是同一份计算，不会各算各的。
 */
class QuickGenerateGuideFlowTest {

    @Test
    fun `关着时引导走轻量三步`() {
        assertEquals(
            listOf(
                GuideTarget.ZJU_SYNC,
                GuideTarget.QUICK_GENERATE_TOGGLE,
                GuideTarget.WALLPAPER
            ),
            visibleGuideTargets(quickGenerateEnabled = false)
        )
    }

    @Test
    fun `开着时引导走完整五步`() {
        assertEquals(
            listOf(
                GuideTarget.ZJU_SYNC,
                GuideTarget.COURSE_CATEGORY,
                GuideTarget.LINKAGE_RULES,
                GuideTarget.WEEK_TASK_GENERATION,
                GuideTarget.WALLPAPER
            ),
            visibleGuideTargets(quickGenerateEnabled = true)
        )
    }

    @Test
    fun `关着时那三步不出现`() {
        val visible = visibleGuideTargets(quickGenerateEnabled = false)

        assertFalse("课程分类只服务于联动规则", visible.contains(GuideTarget.COURSE_CATEGORY))
        assertFalse("联动规则只服务于一键生成", visible.contains(GuideTarget.LINKAGE_RULES))
        assertFalse("生成本周待办就是那个功能本身", visible.contains(GuideTarget.WEEK_TASK_GENERATION))
    }

    @Test
    fun `开着时开关那一步不出现`() {
        // 开着的时候它没有可讲的内容（入口在顶栏，第 4 步已经讲过怎么用），
        // 摆一步「这个功能已经开着」纯属占位置。
        assertFalse(
            visibleGuideTargets(quickGenerateEnabled = true)
                .contains(GuideTarget.QUICK_GENERATE_TOGGLE)
        )
    }

    @Test
    fun `关着时从开关那一步按下会落到壁纸`() {
        // 「下一步」= 不开启、走轻量路线：那三步不出现，直接到壁纸。
        assertEquals(
            GuideTarget.WALLPAPER,
            nextVisibleGuideTarget(
                GuideTarget.QUICK_GENERATE_TOGGLE,
                quickGenerateEnabled = false
            )
        )
    }

    @Test
    fun `按下开启之后引导落在课程分类`() {
        // 用户在引导里按下「开启」：功能一开，「开关」那一步就从清单里消失了。
        // 引导要落到它后面**第一个看得见的**步骤，也就是「课程分类」——
        // 不该停在一个已经看不见的步骤上，那会像卡住了。
        assertEquals(
            GuideTarget.COURSE_CATEGORY,
            resolveVisibleGuideTarget(
                GuideTarget.QUICK_GENERATE_TOGGLE,
                quickGenerateEnabled = true
            )
        )
    }

    @Test
    fun `关掉之后停在生成那一步会落到壁纸`() {
        // 老用户停在「生成本周待办」，后来在「更多」里把功能关了：
        // 那一步不再出现，引导不能停在一个看不见的步骤上。
        assertEquals(
            GuideTarget.WALLPAPER,
            resolveVisibleGuideTarget(
                GuideTarget.WEEK_TASK_GENERATION,
                quickGenerateEnabled = false
            )
        )
    }

    @Test
    fun `看得见的步骤就落在它自己身上`() {
        listOf(true, false).forEach { enabled ->
            visibleGuideTargets(enabled).forEach { target ->
                assertEquals(
                    "看得见的一步不该被挪走（开关 $enabled）",
                    target,
                    resolveVisibleGuideTarget(target, enabled)
                )
            }
        }
    }

    @Test
    fun `两套清单都以壁纸收尾`() {
        // 引导的最后一步是「设置壁纸」：它是这一趟的终点，
        // 按完「下一步」就是「完成」。
        assertEquals(GuideTarget.WALLPAPER, visibleGuideTargets(false).last())
        assertEquals(GuideTarget.WALLPAPER, visibleGuideTargets(true).last())
    }

    @Test
    fun `最后一步没有下一步`() {
        assertNull(
            nextVisibleGuideTarget(GuideTarget.WALLPAPER, quickGenerateEnabled = false)
        )
        assertNull(
            nextVisibleGuideTarget(GuideTarget.WALLPAPER, quickGenerateEnabled = true)
        )
    }

    @Test
    fun `两套清单都至少有两步`() {
        // 「往后找一步」的回退规则依赖这一点：找不到后面的可见步骤时
        // 要落到最后一个，而不是落到一个不存在的地方。
        assertTrue(visibleGuideTargets(false).size >= 2)
        assertTrue(visibleGuideTargets(true).size >= 2)
    }

    @Test
    fun `开关那一步排在课程分类前面`() {
        // 顺序是「决定在前」：先问要不要用这个功能，再讲怎么配它。
        // 顺序反了的话，不想用的人会先被推去配一遍规则。
        val order = GuideTarget.entries.toList()
        assertTrue(
            order.indexOf(GuideTarget.QUICK_GENERATE_TOGGLE) <
                order.indexOf(GuideTarget.COURSE_CATEGORY)
        )
    }
}
