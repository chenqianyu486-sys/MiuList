package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 天气文字 → 图标类别的归类。
 *
 * **这里最容易错的是判定顺序**：像「雷阵雨」「雨夹雪」「多云转小雨」这类词
 * 同时含多个关键词，先命中哪个就显示哪个图标。所以每一条用例都写成
 * 「含有干扰关键词的复合词」，而不是干净的单词。
 *
 * 用例里的词取自真实上游会出现的中文词（心知的「小雨」「多云」、
 * Open-Meteo 的「雷阵雨」），而判定顺序是这一层自己的事，
 * 与哪个数据源无关 —— 换上游不该改这些用例。
 */
class WeatherGlyphTest {

    @Test
    fun `干净的单关键词`() {
        assertEquals(WeatherGlyph.SUNNY, glyphFor("晴"))
        assertEquals(WeatherGlyph.CLOUDY, glyphFor("多云"))
        assertEquals(WeatherGlyph.OVERCAST, glyphFor("阴"))
        assertEquals(WeatherGlyph.RAIN, glyphFor("小雨"))
        assertEquals(WeatherGlyph.SNOW, glyphFor("小雪"))
        assertEquals(WeatherGlyph.THUNDERSTORM, glyphFor("雷阵雨"))
        assertEquals(WeatherGlyph.HAZE, glyphFor("雾"))
        assertEquals(WeatherGlyph.HAZE, glyphFor("霾"))
    }

    /** 「雷阵雨」含「雨」也含「雷」——必须选雷，不能退化成一把伞。 */
    @Test
    fun `雷优先于雨`() {
        assertEquals(WeatherGlyph.THUNDERSTORM, glyphFor("雷阵雨"))
        assertEquals(WeatherGlyph.THUNDERSTORM, glyphFor("雷阵雨伴冰雹"))
    }

    /** 「雨夹雪」含「雨」也含「雪」——雪优先，否则下雨天和下雪天长得一样。 */
    @Test
    fun `雪优先于雨`() {
        assertEquals(WeatherGlyph.SNOW, glyphFor("雨夹雪"))
        assertEquals(WeatherGlyph.SNOW, glyphFor("冻雨"))
    }

    /** 「多云转小雨」含「云」也含「雨」——降水优先，反过来会显示一把云。 */
    @Test
    fun `雨优先于云`() {
        assertEquals(WeatherGlyph.RAIN, glyphFor("多云转小雨"))
        assertEquals(WeatherGlyph.RAIN, glyphFor("阴转中雨"))
    }

    @Test
    fun `认不出来的退回中性图标而不是抛`() {
        assertEquals(WeatherGlyph.NEUTRAL, glyphFor(""))
        assertEquals(WeatherGlyph.NEUTRAL, glyphFor("火星"))
    }
}
