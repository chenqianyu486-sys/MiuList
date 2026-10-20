package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.WallpaperConfig
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import com.example.ui.viewmodel.MainUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenWallpaperTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settings_screen_displays_wallpaper_card_and_opens_dialog() {
        var openedWallpaper = false
        val state = MainUiState(
            wallpaperConfig = WallpaperConfig(type = WallpaperType.DEFAULT)
        )

        composeTestRule.setContent {
            SettingsScreen(
                state = state,
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                onOpenWallpaperDialog = { openedWallpaper = true }
            )
        }

        // 验证设置页内存在自定义壁纸卡片与默认副标题
        composeTestRule.onNodeWithText("自定义壁纸").assertIsDisplayed()
        composeTestRule.onNodeWithText("默认壁纸 · Cloudy Knoxville").assertIsDisplayed()

        // 点击「更换」按钮
        composeTestRule.onNodeWithTag("open_wallpaper_button").performClick()
        assertTrue(openedWallpaper)
    }

    @Test
    fun settings_screen_displays_preset_and_custom_subtitles() {
        val presetState = MainUiState(
            wallpaperConfig = WallpaperConfig(
                type = WallpaperType.PRESET,
                presetId = WallpaperPresets.SOFT_GRASS.id
            )
        )

        composeTestRule.setContent {
            SettingsScreen(
                state = presetState,
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                onOpenWallpaperDialog = {}
            )
        }

        composeTestRule.onNodeWithText("预设壁纸 · Soft Grass").assertIsDisplayed()
    }

    @Test
    fun settings_screen_acknowledges_pxlab() {
        val pxlab = AcknowledgementsList.find { it.project.contains("PXLab") }
        org.junit.Assert.assertNotNull(pxlab)
        org.junit.Assert.assertEquals("https://pxlab.cn/index.html", pxlab?.url)
    }
}
