package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导「暂停 → 接回」这条规则。
 *
 * 规则本身很简单（一句话：被等的那个弹层关了，就把引导接回来），
 * 但它撑着一个用户能不能回到引导层 —— 「去看看」出去配完东西，
 * 回不来就等于把用户扔在半路上。所以钉住边界：
 *
 * - 目标还开着的时候不能接回（否则两层遮罩同时在场）；
 * - 只认被等的那个目标，别人的弹层关了不算；
 * - 接回之后「在等谁」要清掉，不能反复接回。
 */
class OnboardingFlowTest {

    @Test
    fun `目标还开着就不接回引导`() {
        val state = MainUiState(
            isWallpaperDialogOpen = true,
            onboardingAwaitingTarget = GuideTarget.WALLPAPER
        )

        val after = state.resumeOnboardingIfTargetDone()

        assertFalse("目标还开着就接回引导，两层遮罩会同时在场", after.isOnboardingGuideOpen)
        assertEquals(
            "还没做完就不该清掉在等谁",
            GuideTarget.WALLPAPER,
            after.onboardingAwaitingTarget
        )
    }

    @Test
    fun `目标关掉了就把引导接回来`() {
        val state = MainUiState(
            isWallpaperDialogOpen = false,
            onboardingAwaitingTarget = GuideTarget.WALLPAPER
        )

        val after = state.resumeOnboardingIfTargetDone()

        assertTrue("目标做完了引导必须自己回来", after.isOnboardingGuideOpen)
        assertNull("接回来之后就不该还在等", after.onboardingAwaitingTarget)
        assertTrue("接回来要带上提示，否则用户以为引导没动过", after.onboardingResumed)
    }

    @Test
    fun `没有在等目标时不动`() {
        val state = MainUiState(isOnboardingGuideOpen = false)

        val after = state.resumeOnboardingIfTargetDone()

        assertFalse("没人等目标就不该凭空弹出引导", after.isOnboardingGuideOpen)
        assertFalse(after.onboardingResumed)
    }

    @Test
    fun `只认被等的那个目标，别的弹层关了不算`() {
        // 等的是浙大同步（那个弹层还开着），这时候壁纸弹层关掉不算数 ——
        // 把人从别的界面上拽回来是干扰，不是接回。
        val state = MainUiState(
            isWallpaperDialogOpen = false,
            isCloudAccountDialogOpen = true,
            onboardingAwaitingTarget = GuideTarget.ZJU_SYNC
        )

        val after = state.resumeOnboardingIfTargetDone()

        assertFalse(
            "等的是浙大同步，壁纸弹层关掉不该把引导拉回来",
            after.isOnboardingGuideOpen
        )
        assertEquals(
            "还没做完就还在等原来的目标",
            GuideTarget.ZJU_SYNC,
            after.onboardingAwaitingTarget
        )
    }

    @Test
    fun `接回过一次之后规则不该再有动作`() {
        val once = MainUiState(
            isWallpaperDialogOpen = false,
            onboardingAwaitingTarget = GuideTarget.WALLPAPER
        ).resumeOnboardingIfTargetDone()

        val twice = once.resumeOnboardingIfTargetDone()

        assertTrue(once.isOnboardingGuideOpen)
        assertNull("接回之后就不该还在等", once.onboardingAwaitingTarget)
        assertEquals("接回之后规则不该再有动作", once, twice)
    }

    @Test
    fun `四个目标各自认自己那个弹层`() {
        val zju = MainUiState(isCloudAccountDialogOpen = true)
        val course = MainUiState(isCourseCategoryDialogOpen = true)
        val rules = MainUiState(isCategoryRulesDialogOpen = true)
        val wallpaper = MainUiState(isWallpaperDialogOpen = true)

        assertTrue(zju.isGuideTargetOpen(GuideTarget.ZJU_SYNC))
        assertTrue(course.isGuideTargetOpen(GuideTarget.COURSE_CATEGORY))
        assertTrue(rules.isGuideTargetOpen(GuideTarget.LINKAGE_RULES))
        assertTrue(wallpaper.isGuideTargetOpen(GuideTarget.WALLPAPER))

        // 别人的弹层开着不算自己的
        assertFalse(zju.isGuideTargetOpen(GuideTarget.WALLPAPER))
        assertFalse(wallpaper.isGuideTargetOpen(GuideTarget.ZJU_SYNC))
        assertFalse(rules.isGuideTargetOpen(GuideTarget.COURSE_CATEGORY))
        assertFalse(course.isGuideTargetOpen(GuideTarget.LINKAGE_RULES))
    }

    @Test
    fun `分类弹层开着时课程类别那一步还没做完`() {
        // 课程分类弹层是第 2 步的落点：它开着就说明这一步还在进行中，
        // 引导不该在这时候接回来。
        val state = MainUiState(
            isCourseCategoryDialogOpen = true,
            onboardingAwaitingTarget = GuideTarget.COURSE_CATEGORY
        )

        assertTrue(state.isGuideTargetOpen(GuideTarget.COURSE_CATEGORY))
        assertFalse(
            "分类弹层还开着就不该接回引导",
            state.resumeOnboardingIfTargetDone().isOnboardingGuideOpen
        )
    }

    @Test
    fun `在分类弹层里加课不算这一步做完了`() {
        // 分类弹层的空态里能直接去添加课程：那个弹层是**叠在分类弹层上面**的，
        // 分类弹层还开着，所以这一步还没做完 —— 收掉添加弹层不该把引导拽回来。
        val state = MainUiState(
            isCourseCategoryDialogOpen = true,
            isAddCourseDialogOpen = true,
            onboardingAwaitingTarget = GuideTarget.COURSE_CATEGORY
        )

        val after = state.copy(isAddCourseDialogOpen = false).resumeOnboardingIfTargetDone()

        assertFalse(
            "分类弹层还开着就不该接回引导",
            after.isOnboardingGuideOpen
        )
        assertEquals(GuideTarget.COURSE_CATEGORY, after.onboardingAwaitingTarget)
    }

    // -------------------------------------------------- 同步成功后的接回

    @Test
    fun `同步成功把弹层收掉时引导要接回来`() {
        // 这条路以前断在这里：弹层是代码收的（withSyncSuccess），
        // 绕开了用户点「关闭」那条路，于是引导再也接不回来。
        val state = MainUiState(
            isCloudAccountDialogOpen = true,
            onboardingAwaitingTarget = GuideTarget.ZJU_SYNC
        )

        val after = state.closeCloudAccountDialogAfterSync()

        assertFalse("同步成功后弹层应该已经收掉", after.isCloudAccountDialogOpen)
        assertTrue("弹层被收掉了，引导必须自己回来", after.isOnboardingGuideOpen)
        assertNull(after.onboardingAwaitingTarget)
        assertTrue(after.onboardingResumed)
    }

    @Test
    fun `同步成功时别的目标还开着就不接回引导`() {
        // 用户正停在壁纸弹层上，后台同步恰好成功了 —— 这时候把引导拽回来
        // 是干扰，不是接回。
        val state = MainUiState(
            isCloudAccountDialogOpen = true,
            isWallpaperDialogOpen = true,
            onboardingAwaitingTarget = GuideTarget.WALLPAPER
        )

        val after = state.closeCloudAccountDialogAfterSync()

        assertFalse(
            "等的不是浙大同步，就不该被这次同步拽回来",
            after.isOnboardingGuideOpen
        )
        assertEquals(GuideTarget.WALLPAPER, after.onboardingAwaitingTarget)
    }
}
