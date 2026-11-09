package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.source.weather.AqiStandard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页里的「空气质量 AQI」两颗按钮。
 *
 * 它决定天气块那一行显示 `aqi`（国标）还是 `usAqi`（美国）——
 * 两个数都在 weather.json 里，不是把同一个数换个标签。
 *
 * **现在是两颗按钮，不是一颗开关**：两套标准各占一颗按钮，
 * 当前是哪一套由哪颗绿着直接说出来。所以断言也从「开关是开是关」
 * 换成了「哪一颗被选中」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenAqiStandardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `标题写的是空气质量 AQI`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.CN
            )
        }

        composeTestRule.onNodeWithText("空气质量 AQI").assertIsDisplayed()
        // 两套标准各是一颗按钮，名字直接写在按钮上，不用从标题里猜。
        composeTestRule.onNodeWithTag("settings_aqi_cn_label").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_aqi_us_label").assertIsDisplayed()
    }

    @Test
    fun `默认国标时选中的是中国那颗`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.CN
            )
        }

        composeTestRule.onNodeWithTag("settings_aqi_cn_label").assertIsSelected()
        composeTestRule.onNodeWithTag("settings_aqi_us_label").assertIsNotSelected()
    }

    @Test
    fun `美国模式时选中的是美国端`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.US
            )
        }

        composeTestRule.onNodeWithTag("settings_aqi_us_label").assertIsSelected()
        composeTestRule.onNodeWithTag("settings_aqi_cn_label").assertIsNotSelected()
    }

    @Test
    fun `点美国端把美国标准交出去`() {
        var changed: AqiStandard? = null

        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.CN,
                onAqiStandardChange = { changed = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_aqi_us_label").performClick()

        assertEquals(AqiStandard.US, changed)
    }

    @Test
    fun `点中国端把国标交出去`() {
        var changed: AqiStandard? = null

        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.US,
                onAqiStandardChange = { changed = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_aqi_cn_label").performClick()

        assertEquals(AqiStandard.CN, changed)
    }
}
