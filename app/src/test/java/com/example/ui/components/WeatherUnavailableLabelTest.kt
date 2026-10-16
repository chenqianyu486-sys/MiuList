package com.example.ui.components

import com.example.data.source.weather.WeatherFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气不可用时那一格的**文案与图标**。
 *
 * 和 `SyncFailureAdviceTest` 同一个道理：这几个字不经过任何逻辑分支，
 * 改坏了编译期不会报错、运行期也不会崩，只在用户断网的那几秒里露出破绽。
 * 所以钉的是**约束**，不是逐字文案。
 */
class WeatherUnavailableLabelTest {

    /**
     * 最要紧的一条：**不能出现「暂无」这类词**。
     *
     * 「天气：暂无」在用户看来等于「今天没有天气」，而真实情况只是没连上。
     * 这不是措辞偏好 —— 是同一句话在说两件完全不同的事。
     */
    @Test
    fun `没有一类会说出「暂无」这种等于今天没天气的话`() {
        val labels = WeatherFailureKind.entries.map { WeatherUnavailableLabel.labelOf(it) }

        labels.forEach {
            assertTrue("「$it」会被读成今天没天气：$labels", !it.contains("暂无"))
            assertTrue("「$it」会被读成今天没天气：$labels", !it.contains("无数据"))
        }
    }

    /**
     * 只有网络那一类该把用户引向「检查网络」。
     *
     * 服务端回了正文但不能用时，让他去查网络是白跑一趟 ——
     * 那台机器上网络好好的。
     */
    @Test
    fun `只有网络问题才提示用户检查网络`() {
        assertTrue(
            WeatherUnavailableLabel.labelOf(WeatherFailureKind.OFFLINE).contains("网络")
        )
        assertTrue(
            "答复不可用不该说网络：${
                WeatherUnavailableLabel.labelOf(WeatherFailureKind.REJECTED)
            }",
            !WeatherUnavailableLabel.labelOf(WeatherFailureKind.REJECTED).contains("网络")
        )
    }

    /**
     * 一个词，且必须是短的。
     *
     * 那一格只有 92.dp 宽、字号 9sp、`maxLines = 1`。超过五六个字就会被截成
     * 「网络不…」加一个省略号 —— 而一个被截断的错误提示等于没有，
     * 用户既读不出是哪一类，也读不出该不该管。
     */
    @Test
    fun `每个标签都短到不会被截断`() {
        WeatherFailureKind.entries.forEach { kind ->
            val label = WeatherUnavailableLabel.labelOf(kind)
            assertTrue("「$label」太长了，92dp 放不下", label.length <= 5)
        }
    }

    /** 两类必须长得不一样，否则「换个网络就好了」这条建议传达不出去。 */
    @Test
    fun `两类的标签与图标都不同`() {
        assertNotEquals(
            WeatherUnavailableLabel.labelOf(WeatherFailureKind.OFFLINE),
            WeatherUnavailableLabel.labelOf(WeatherFailureKind.REJECTED)
        )
        assertNotEquals(
            WeatherUnavailableLabel.iconOf(WeatherFailureKind.OFFLINE),
            WeatherUnavailableLabel.iconOf(WeatherFailureKind.REJECTED)
        )
    }

    /**
     * 与 `SyncFailureAdvice` 用同一套图标（`WifiOff` / `CloudOff`）。
     *
     * 同一个 App 里两种失败用同一个符号，用户不用记「哪个图标是哪类」。
     */
    @Test
    fun `图标与同步失败那边同源`() {
        assertEquals(
            SyncFailureAdvice.iconOf(com.example.data.source.zju.ZjuFailureKind.NETWORK),
            WeatherUnavailableLabel.iconOf(WeatherFailureKind.OFFLINE)
        )
        assertEquals(
            SyncFailureAdvice.iconOf(com.example.data.source.zju.ZjuFailureKind.SERVER),
            WeatherUnavailableLabel.iconOf(WeatherFailureKind.REJECTED)
        )
    }
}
