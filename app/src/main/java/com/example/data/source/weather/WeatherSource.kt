package com.example.data.source.weather

/**
 * 天气数据源抽象。
 *
 * 负责从远程获取原始正文并解析成 [DailyWeather]。
 * 决策层 [WeatherService] 只依赖此接口，不关心具体数据由哪个上游提供，
 * 换源时决策层和界面层完全不需要改动。
 */
interface WeatherSource {
    /**
     * 取今天的逐日预报原文。
     */
    suspend fun fetchToday(location: WeatherLocation): WeatherFetch

    /**
     * 将原始响应文本解析为 [DailyWeather]。解析失败返回 null。
     */
    fun parseToday(text: String, location: WeatherLocation): DailyWeather?

    /**
     * 取当前空气质量原文。若已合并在逐日预报中，可返回 [WeatherFetch.Unavailable]。
     */
    suspend fun fetchAirQuality(location: WeatherLocation): WeatherFetch

    /**
     * 从空气质量响应文本中解析出 AQI 数值。取不到返回 null。
     */
    fun parseAirQuality(text: String): Int?
}
