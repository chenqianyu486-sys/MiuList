package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.source.weather.AqiStandard
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AQI 标准偏好的默认值与持久化。
 *
 * 默认必须是国标：这是用户拿来和国内天气 App / 国控站对比的那一套。
 * 美国口径只能由用户主动拨过去，不能因为读不到偏好就默认成它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AqiStandardPreferenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newPrefs(): AqiStandardPreference =
        AqiStandardPreference(context, "test_aqi_prefs_${System.nanoTime()}")

    @Test
    fun `默认国标`() {
        assertEquals(AqiStandard.CN, newPrefs().standard)
    }

    @Test
    fun `写进去之后读出来还是同一套`() {
        val prefName = "test_aqi_prefs_${System.nanoTime()}"
        val first = AqiStandardPreference(context, prefName)
        first.standard = AqiStandard.US

        // 重新建一个实例，模拟下次启动；值必须还在。
        val second = AqiStandardPreference(context, prefName)
        assertEquals(AqiStandard.US, second.standard)

        second.standard = AqiStandard.CN
        assertEquals(AqiStandard.CN, AqiStandardPreference(context, prefName).standard)
    }
}
