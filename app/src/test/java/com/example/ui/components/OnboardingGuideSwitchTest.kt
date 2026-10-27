package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.ui.viewmodel.GuideTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 引导面板在「一键生成待办」开关两态下的样子。
 *
 * ## 为什么要单独测这一层
 *
 * 引导的步骤**清单**会随开关变（关着三步、开着五步），而面板里的
 * 交叉淡入会**同时渲染两帧**：正在淡出的那一帧持有的是**旧**的目标，
 * 正在淡入的那一帧是新目标。
 *
 * 写错的话就是：淡出那一帧拿**新的**可见清单去找**旧的**目标 ——
 * 「开关」那一步在新的五步清单里已经不在了，`first {}` 直接抛
 * `NoSuchElementException`，**App 当场崩**（用户在引导里按下「开启」就会撞到）。
 *
 * 纯函数那一层（`QuickGenerateGuideFlowTest`）测不到这个：崩的是
 * 「两帧同时在场」这件事，只有真的渲染一次面板才会发生。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OnboardingGuideSwitchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `一键生成关着时引导是三步`() {
        composeTestRule.setContent {
            OnboardingGuide(
                step = GuideTarget.ZJU_SYNC,
                quickGenerateEnabled = false,
                onDismiss = {},
                onNext = {},
                onOpenTarget = {}
            )
        }

        composeTestRule.onNodeWithText("第 1 步 / 共 3 步").assertIsDisplayed()
    }

    @Test
    fun `一键生成开着时引导是五步`() {
        composeTestRule.setContent {
            OnboardingGuide(
                step = GuideTarget.ZJU_SYNC,
                quickGenerateEnabled = true,
                onDismiss = {},
                onNext = {},
                onOpenTarget = {}
            )
        }

        composeTestRule.onNodeWithText("第 1 步 / 共 5 步").assertIsDisplayed()
    }

    @Test
    fun `按下开启之后引导落到课程分类`() {
        var enabled by mutableStateOf(false)
        var step by mutableStateOf(GuideTarget.QUICK_GENERATE_TOGGLE)

        composeTestRule.setContent {
            OnboardingGuide(
                step = step,
                quickGenerateEnabled = enabled,
                onDismiss = {},
                onNext = {},
                onOpenTarget = { target ->
                    if (target == GuideTarget.QUICK_GENERATE_TOGGLE) {
                        enabled = true
                        step = GuideTarget.COURSE_CATEGORY
                    }
                }
            )
        }

        composeTestRule.onNodeWithText("第 2 步 / 共 3 步").assertIsDisplayed()

        composeTestRule.onNodeWithTag("onboarding_guide_action")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        // 功能一开，清单从三步变成五步，引导落在「课程分类」上。
        composeTestRule.onNodeWithText("设置课程分类").assertIsDisplayed()
        composeTestRule.onNodeWithText("第 2 步 / 共 5 步").assertIsDisplayed()
    }
}
