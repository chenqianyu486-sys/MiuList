package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                onOpenWallpaperDialog = { openedWallpaper = true }
            )
        }

        // 这一项**只留标题**：原来卡下面还挂着「默认壁纸 · Cloudy Knoxville」
        // 这样的副标题，现在整个设置页的副标题一律不要了。
        composeTestRule.onNodeWithText("自定义壁纸").assertIsDisplayed()
        composeTestRule.onNodeWithText("默认壁纸 · Cloudy Knoxville").assertDoesNotExist()

        // 点击「更换」按钮
        composeTestRule.onNodeWithTag("open_wallpaper_button").performClick()
        assertTrue(openedWallpaper)
    }

    @Test
    fun settings_screen_items_keep_titles_only() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                onOpenWallpaperDialog = {}
            )
        }

        composeTestRule.onNodeWithText("数据导入 / 导出").assertIsDisplayed()
        composeTestRule.onNodeWithText("待办分类管理").assertIsDisplayed()
        composeTestRule.onNodeWithText("自定义壁纸").assertIsDisplayed()

        // 四条副标题都不该再出现
        composeTestRule.onNodeWithText("课表、待办、分类与规则一起带走").assertDoesNotExist()
        composeTestRule.onNodeWithText("4 个自带分类 · 暂无自定义分类").assertDoesNotExist()
        composeTestRule.onNodeWithText("预设壁纸 · Soft Grass").assertDoesNotExist()
        composeTestRule.onNodeWithText("四步走一遍：浙大同步、课程类别、联动规则与壁纸").assertDoesNotExist()
    }

    @Test
    fun settings_screen_acknowledges_pxlab() {
        val pxlab = AcknowledgementsList.find { it.project.contains("PXLab") }
        org.junit.Assert.assertNotNull(pxlab)
        org.junit.Assert.assertEquals("https://pxlab.cn/index.html", pxlab?.url)
    }
}
