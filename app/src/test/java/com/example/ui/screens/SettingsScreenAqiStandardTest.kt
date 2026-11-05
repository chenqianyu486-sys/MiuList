package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * 设置页里的「空气质量用美国 AQI」开关。
 *
 * 它决定天气块那一行显示 `aqi`（国标）还是 `usAqi`（美国）——
 * 两个数都在 weather.json 里，不是把同一个数换个标签。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenAqiStandardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `默认国标时开关是关的`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.CN
            )
        }

        composeTestRule.onNodeWithText("空气质量用美国 AQI").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_us_aqi_switch").assertIsOff()
    }

    @Test
    fun `美国模式打开时开关是开的`() {
        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.US
            )
        }

        composeTestRule.onNodeWithTag("settings_us_aqi_switch").assertIsOn()
    }

    @Test
    fun `拨开关把美国标准交出去`() {
        var changed: AqiStandard? = null

        composeTestRule.setContent {
            SettingsScreen(
                onOpenTransferDialog = {},
                onOpenCategoryManageDialog = {},
                aqiStandard = AqiStandard.CN,
                onAqiStandardChange = { changed = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_us_aqi_switch").performClick()

        assertEquals(AqiStandard.US, changed)
    }
}
