package com.example.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.data.model.WallpaperConfig
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperSettingDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_renders_preview_preset_items_and_completes() {
        var dismissed = false
        var selectedPresetId: String? = null
        var resetCalled = false

        val config = WallpaperConfig(
            type = WallpaperType.PRESET,
            presetId = WallpaperPresets.SOFT_GRASS.id,
            dimOpacity = 0.2f
        )

        composeTestRule.setContent {
            WallpaperSettingDialog(
                currentConfig = config,
                onDismiss = { dismissed = true },
                onSelectPreset = { selectedPresetId = it },
                onSelectCustom = {},
                onResetDefault = { resetCalled = true },
                onUpdateDimOpacity = {},
                onUpdateSoftFocus = {}
            )
        }
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()

        // 验证标题与副标题渲染
        composeTestRule.onNodeWithText("自定义壁纸").assertExists()
        composeTestRule.onNodeWithText("主界面背景与毛玻璃透光底色").assertExists()

        // 验证预设卡片渲染
        composeTestRule.onNodeWithText("Cloudy Knoxville").assertExists()
        composeTestRule.onNodeWithText("Kind Steel").assertExists()
        composeTestRule.onNodeWithText("Soft Grass").assertExists()
        composeTestRule.onNodeWithText("Rare Wind").assertExists()
        composeTestRule.onNodeWithText("Teen Notebook").assertExists()
        composeTestRule.onNodeWithText("Sweet Period").assertExists()
        composeTestRule.onNodeWithText("Nega").assertExists()
        composeTestRule.onNodeWithText("Salt Mountain").assertExists()

        // 点击预设 Rare Wind
        composeTestRule.onNodeWithTag("wallpaper_preset_rare_wind")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("rare_wind", selectedPresetId)

        // 点击恢复默认按钮
        composeTestRule.onNodeWithTag("reset_default_wallpaper_button")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(resetCalled)

        // 点击完成按钮
        composeTestRule.onNodeWithTag("wallpaper_dialog_confirm_button")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(dismissed)
    }
}
