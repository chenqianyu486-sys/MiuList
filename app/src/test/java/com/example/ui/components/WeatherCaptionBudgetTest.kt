package com.example.ui.components

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import com.example.data.source.weather.DailyWeather
import com.example.data.source.weather.WeatherLocation
import com.example.data.source.weather.describeAqi
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最后一行那句「杭州 · 空气良 68」**放不放得下 [BlockWidth]**。
 *
 * ## 模型是从真机上校出来的，不是拍脑袋
 *
 * 模拟器上用 `uiautomator dump` 取 Compose 的语义边界（排版宽度，非墨迹宽度）：
 * - 9sp 的「杭州 · 空气良 67」= 180px = **68.6dp**（11 字）
 * - 12sp 的「12 门课 · 31.3 小时」= 273px = **104.0dp**（15 字）
 *
 * 拿这两条反推：CJK 约 0.93em（已含 M3 `labelSmall` 那 0.5sp 字距）、
 * 数字 0.5625em、空格 0.25em、`·` 约 0.5em，两条都在 ±2.5% 内对上。
 *
 * 这里**每一项都取上界**（CJK 1em、`·` 0.5em，字距另计），方向是宁可高估：
 * 最坏那句约 106dp，预算 108.dp。宁可测试偏严，也不要它绿着而真机截断。
 *
 * ## 为什么不能在 Robolectric 里量
 *
 * 沙箱里没装中文字形，`Paint.measureText("杭州 · 空气轻度污染 138")`
 * 只给 **15px**（12 字，每字 1.25px）—— 测试会一直绿着，真机照样截断。
 * **绿色的假测量比没有测试更坏。**
 */
class WeatherCaptionBudgetTest {

    /** M3 默认 `labelSmall` 的字距，UI 里就是它（`copy` 只改了 fontSize）。 */
    private val letterSpacingEm: Double =
        (Typography().labelSmall.letterSpacing.value / CaptionFontSize.value).toDouble()

    private fun advanceEm(c: Char): Double = when {
        // CJK 与全角标点：上界取 1 em。
        c.code >= 0x2E80 -> 1.0
        // U+00B7 间隔号：真机实测约 0.5 em（它回退到中文字体，不是 Roboto 的 0.26 em）。
        c == '·' -> 0.5
        // Roboto Regular/Medium 的数字：1151/2048。
        c.isDigit() -> 1151.0 / 2048
        c == ' ' -> 512.0 / 2048
        else -> 0.6
    }

    private fun widthDp(text: String): Double =
        text.sumOf { advanceEm(it) + letterSpacingEm } * CaptionFontSize.value

    private fun caption(city: String, aqi: Int?, quality: String? = null) = weatherCaption(
        DailyWeather(
            cityName = city,
            epochDay = 0L,
            textDay = "晴",
            textNight = "",
            high = 25,
            low = 19,
            aqi = aqi,
            aqiQuality = quality
        )
    )

    private fun assertFits(text: String) {
        val w = widthDp(text)
        // 守的是 [BlockWidth]（108.dp）：天气块**不随屏宽收窄**。
        //
        // 最坏那句按上界模型约 105.9dp，只比 108dp 少 2dp 余量，
        // 任何更窄的下限都会让指数被 Ellipsis 截掉。窄屏上省空间靠的是
        // 把左边那一列的日期退回读数字号（见 MiuType.HeroDate），不是压扁天气块。
        assertTrue(
            "「$text」按上界模型约 ${"%.1f".format(w)}dp，放不进 ${BlockWidth.value}dp 的格子，" +
                "指数会被 Ellipsis 截掉。放宽 BlockWidth 或缩短文案，**别缩字号**。",
            w <= BlockWidth.value
        )
    }

    /**
     * 最长的一句：四字等级 + 三位数。
     *
     * 两位数时等级最长是「良/优」（两字）；三位数（≥101）时最长的等级
     * 是四字的轻度/中度/重度/严重污染 —— 两者叠加才是真正的最坏情况。
     * 沙尘天和初冬都真实出现过，不是为了凑极端值编的组合。
     */
    @Test
    fun `四字等级加三位数放得下`() {
        assertFits(caption(WeatherLocation.DEFAULT.name, 138))
        assertFits(caption(WeatherLocation.DEFAULT.name, 302))
        assertFits(caption(WeatherLocation.DEFAULT.name, 500))
    }

    /** 等级用上游给的（「严重污染」）而不是本地那张表时，一样要放得下。 */
    @Test
    fun `上游给的等级也放得下`() {
        assertFits(caption(WeatherLocation.DEFAULT.name, 302, quality = "严重污染"))
    }

    /** 本地表里每一个等级都得放得下 —— 别只测了最长的那档。 */
    @Test
    fun `每一档等级都放得下`() {
        listOf(12, 64, 138, 190, 268, 302, 500).forEach { aqi ->
            assertFits(caption(WeatherLocation.DEFAULT.name, aqi, describeAqi(aqi)))
        }
    }

    /**
     * 用的是 [WeatherLocation.DEFAULT] 里的**真名**，不是手写的「杭州」。
     *
     * 以后加了城市选择器、地名可能变长（哪怕只是多了个字），
     * 这个测试会跟着用新名字重算并失败 —— 不需要谁记得回来改这里。
     */
    @Test
    fun `地名取自当前那个真实常量`() {
        assertTrue(
            "这里写死了「杭州」：地名变了就用 WeatherLocation.DEFAULT.name",
            caption(WeatherLocation.DEFAULT.name, 138).startsWith(WeatherLocation.DEFAULT.name)
        )
    }

    /**
     * 反过来钉住**下限**：这个格子不是为了塞满而存在的。
     *
     * 常见的「杭州 · 空气良 68」真机实测 68.6dp、只占预算的六成半，
     * 说明 108.dp 是照最坏情况定的。若哪天发现常见情况也快贴边，
     * 就是有第三段被加进来了。
     */
    @Test
    fun `常见读数离边界还有余量`() {
        val text = caption(WeatherLocation.DEFAULT.name, 68)
        val ratio = widthDp(text) / BlockWidth.value

        assertTrue("「$text」已占 ${"%.0f".format(ratio * 100)}% 宽，别再往这一行加东西", ratio <= 0.8)
    }

    /**
     * 模型本身不能漂。
     *
     * 若哪天 `labelSmall` 的字距被改了（0.5sp → 0），每一字都会变窄，
     * 预算就该跟着重算 —— 这个测试会提醒重新量一次真机，而不是默默变松。
     */
    @Test
    fun `模型里的字距确实来自 M3 默认值`() {
        assertTrue(
            "字距不是 0.5sp 了，重新量一次真机并更新本文件的参数",
            letterSpacingEm in 0.05..0.06
        )
        assertTrue(
            "字重变了会改变字宽，重新量一次真机",
            Typography().labelSmall.fontWeight == FontWeight.Medium
        )
    }
}
