package com.example.data.repository

import android.content.Context
import com.example.data.source.weather.AqiStandard

/**
 * 「天气块最后一行用哪套 AQI」这个偏好。
 *
 * ## 默认国标
 *
 * 这个 App 面向国内用户，大家平时比较的是国内天气 App / 国控站发布的
 * 中国国标 AQI。Open-Meteo 原生给的是美国 EPA 口径，同一个 64 一个叫
 * 「良」、另一个可能已经是「轻度污染」的边缘 —— 默认成美国口径会让用户
 * 以为数据坏了。所以默认 [AqiStandard.CN]，美国口径要自己拨过去。
 *
 * ## 它只决定显示哪一个数
 *
 * 两份数都在 weather.json 里（`aqi` 与 `usAqi`），这里不触发联网、
 * 不做换算，也不把用户的选择写进天气数据。拨开关只是换一行字。
 */
class AqiStandardPreference(
    context: Context,
    prefName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefName, Context.MODE_PRIVATE)

    var standard: AqiStandard
        get() = when (prefs.getString(KEY_STANDARD, null)) {
            AqiStandard.US.name -> AqiStandard.US
            // 没存过、存了旧值、或者存的值读不懂，都回到国标 ——
            // 这是用户真正会拿来和国内 App 对比的那一套。
            else -> AqiStandard.CN
        }
        set(value) {
            prefs.edit().putString(KEY_STANDARD, value.name).apply()
        }

    companion object {
        private const val PREFS_NAME = "miulist_aqi_prefs"
        private const val KEY_STANDARD = "aqi_standard"
    }
}
