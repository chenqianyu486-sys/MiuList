package com.example.ui.components

import com.example.data.source.weather.DailyWeather
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 天气块最后一行那句「杭州 · 空气良 68」。
 *
 * 这一行没有任何逻辑分支，改坏了编译期不报错、运行期不崩，只在总览页
 * 那一行小字上露出破绽 —— 所以钉的是**约束**（两段是并排的两件事、
 * 缺一段不许留半个标点），不是逐字文案。
 */
class WeatherCaptionTest {

    private fun weather(
        city: String = "杭州",
        aqi: Int? = 68,
        quality: String? = null
    ) = DailyWeather(
        cityName = city,
        epochDay = 0L,
        textDay = "晴",
        textNight = "",
        high = 25,
        low = 19,
        aqi = aqi,
        aqiQuality = quality
    )

    /** 用户本轮定的样子：地名与空气读数之间用「·」。 */
    @Test
    fun `两段都在时中间是间隔号`() {
        assertEquals("杭州 · 空气良 68", weatherCaption(weather()))
    }

    /** 空气是另一个端点，挂掉是常事；这时只剩地名，且**不许**留下半个标点。 */
    @Test
    fun `空气拉不到时只剩城市名`() {
        assertEquals("杭州", weatherCaption(weather(aqi = null)))
    }

    /** 兜底路径：地名由我们自己声明（见 WeatherLocation.name），不该空。 */
    @Test
    fun `城市名为空时只显示空气`() {
        assertEquals("空气良 68", weatherCaption(weather(city = "  ")))
    }

    /** 两段都空时是空串，不是「 · 」，也不是「--」。 */
    @Test
    fun `两段都缺时不留标点也不补占位`() {
        assertEquals("", weatherCaption(weather(city = "", aqi = null)))
    }

    /**
     * 空格分隔的「杭州 空气良 68」读起来像一句断了的话。
     * 间隔号是在说「同一行里的两件独立事实」，所以分隔符**必须含空格**，
     * 否则「杭州·空气良 68」会挤成一坨。
     */
    @Test
    fun `分隔符两侧各留一个空格`() {
        val caption = weatherCaption(weather())

        assertEquals(" · ", caption.substring("杭州".length, caption.length - "空气良 68".length))
    }

    /**
     * 92.dp 宽、9sp、`maxLines = 1`。最坏的一档是四字等级 + 三位数
     * （「杭州 · 空气严重污染 500」）—— 它**放不放得下由 [WeatherCaptionWidthTest]
     * 用真实字形量**，这里只钉文案本身。
     */
    @Test
    fun `最长的组合写成什么样`() {
        assertEquals("杭州 · 空气严重污染 500", weatherCaption(weather(aqi = 500)))
    }
}
