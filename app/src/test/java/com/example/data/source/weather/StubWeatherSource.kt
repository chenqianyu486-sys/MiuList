package com.example.data.source.weather

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * 决策层用的**桩数据源**：正文由测试注入，全程不联网。
 *
 * ## 为什么不挂真实的数据源
 *
 * 决策层要验的是「拿到一份正文之后怎么办」——窗口、当天校验、三态、空气并入。
 * 「某个源的 JSON 长什么样」是**那个源自己的单测**的事。挂一个源来测决策层，
 * 代价有两个：决策层的用例被绑死在某个源的 JSON 形状上（改源就得改一整片
 * 用例），以及可能在验一条线上根本不存在的组合。
 *
 * ## 它认两件事，仅此而已
 *
 * 1. **静态托管文件那种扁平形状**（`{"date":…,"textDay":…,"high":…,"low":…}`），
 *    与 `scripts/sync_weather.py` 写出来的一致 —— 线上真会出现的形状。
 * 2. **上游报错时把原因原样带回来**（`{"error":true,"reason":"…"}`），
 *    因为决策层有一条契约是「原因不能被换成一句『获取失败』」。
 *
 * 网络行为一概不模拟：要模拟「压根没发出去」，就注入一个异常。
 *
 * ## 解析必须是真的 JSON 解析
 *
 * 用正则抠字段会**连嵌套里的字段一起抠出来**：旧格式那份正文里也有 `"date"`，
 * 正则会捞到，于是「换源前的旧响应不该被新解析器误认」那条用例会假通过。
 * Moshi 只认顶层键，嵌套里的同名字段自动不算数 —— 这正是要验的那件事。
 */
internal class StubWeatherSource(
    /** 天气正文。null = 没有这份正文。 */
    private val weatherText: String? = null,
    /** 空气正文。null = 没有这一份（空气是可选项，不是天气的前提）。 */
    private val airText: String? = null,
    /** 模拟网络层失败：fetch 抛这个异常，源把它归成 OFFLINE。 */
    private val networkError: Exception? = null
) : WeatherSource {

    override suspend fun fetchToday(location: WeatherLocation): WeatherFetch {
        networkError?.let { return WeatherFetch.Unavailable(it.describe(), WeatherFailureKind.OFFLINE) }
        val body = weatherText
            ?: return WeatherFetch.Unavailable("没有注入天气正文", WeatherFailureKind.REJECTED)
        // 上游说「出了什么问题」时，**原因要原样带回去**。决策层有一条契约是
        // 「原因不能被换成一句『获取失败』」，桩要是自己编一句，这条契约就验不到。
        val dto = runCatching { weatherAdapter.fromJson(body) }.getOrNull()
        if (dto?.error == true) {
            return WeatherFetch.Unavailable(
                dto.reason?.takeIf { it.isNotBlank() } ?: "上游报错但没给原因",
                WeatherFailureKind.REJECTED
            )
        }
        return WeatherFetch.Got(body)
    }

    override fun parseToday(text: String, location: WeatherLocation): DailyWeather? {
        val dto = runCatching { weatherAdapter.fromJson(text) }.getOrNull() ?: return null
        val epochDay = parseEpochDay(dto.date) ?: return null
        return DailyWeather(
            cityName = location.name,
            epochDay = epochDay,
            textDay = dto.textDay.orEmpty(),
            // 托管文件只给白天的词，夜间值为空 —— 与线上一致。
            textNight = "",
            high = dto.high,
            low = dto.low
        )
    }

    override suspend fun fetchAirQuality(location: WeatherLocation): WeatherFetch {
        networkError?.let { return WeatherFetch.Unavailable(it.describe(), WeatherFailureKind.OFFLINE) }
        val body = airText
            ?: return WeatherFetch.Unavailable("没有注入空气正文", WeatherFailureKind.REJECTED)
        return WeatherFetch.Got(body)
    }

    override fun parseAirQuality(text: String): Int? =
        runCatching { airAdapter.fromJson(text)?.current?.usAqi }.getOrNull()

    private data class WeatherDto(
        val error: Boolean? = null,
        val reason: String? = null,
        val date: String? = null,
        val textDay: String? = null,
        val high: Int? = null,
        val low: Int? = null
    )

    private data class AirDto(val current: AirCurrentDto? = null)

    /**
     * ⚠️ `us_aqi` 必须显式写 `@Json`。
     *
     * Moshi 默认**不**做驼峰 ↔ 下划线的转换：Kotlin 属性叫 `usAqi`，
     * 它默认只认 JSON 键 `usAqi`。而上游那个键就叫 `us_aqi`。
     * 少写这个注解的后果很安静：读数恒为 null，空气那一段永远不显示，
     * 而天气照常显示 —— 界面上看不出任何异常。
     */
    private data class AirCurrentDto(@Json(name = "us_aqi") val usAqi: Int? = null)

    private companion object {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val weatherAdapter = moshi.adapter(WeatherDto::class.java)
        val airAdapter = moshi.adapter(AirDto::class.java)
    }
}
