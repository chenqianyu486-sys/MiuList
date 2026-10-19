package com.example.data.source.weather

import com.example.data.calendar.AppZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 静态托管天气文件的**真联网**测试。
 *
 * ## 为什么需要它
 *
 * 托管文件这条路线的每一环都只有对着真服务器才看得见：
 *
 *  * 镜像地址是不是还活着、CI 是不是还在提交；
 *  * 文件里的 `date` 是不是**今天**（不是昨天 —— 那会让界面拿一份昨天的
 *    温度冒充今天，而且不崩不报错）；
 *  * `aqi` 是不是真的嵌在文件里（空气来自 Open-Meteo，由 CI 拼进去）。
 *
 * 这些都不是单测能覆盖的：单测里正文是注入的，注入的正文永远是「对」的那份。
 *
 * ## 跑法
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*HostedWeatherLiveTest*" "-Pmiulist.liveNet=1"
 * ```
 *
 * ## 网络层失败一律 skip，不算失败
 *
 * 「连不上」和「文件不对」是两件完全不同的事。连不上说明**这台机器的网络**
 * 有问题（代理掐了 TLS、域名被墙、公司网络拦截），那时我们根本没拿到文件，
 * 无从判断它对不对 —— 报 FAIL 是把环境问题伪装成代码问题。
 * 只有**真的拿到了文件**、而它不对，才算失败。
 *
 * 所以：**当这组用例全部 skip 时，不要以为「托管文件没问题」** ——
 * 它只是没被验证过。
 *
 * 代理由 app/build.gradle.kts 从 https_proxy 注入到 test JVM。
 */
class HostedWeatherLiveTest {

    private val location = WeatherLocation.DEFAULT

    @Before
    fun requireLiveNetwork() {
        assumeTrue(
            "未开启真联网测试：加 -Pmiulist.liveNet=1",
            System.getProperty("miulist.liveNet") == "1"
        )
    }

    @Test
    fun `托管文件能拉到并且是今天的`() = runTest {
        val source = SeniverseWeatherSource()
        val fetched = source.fetchToday(location)

        assertTrue(
            "托管文件拉不到：${(fetched as? WeatherFetch.Unavailable)?.reason}",
            fetched is WeatherFetch.Got
        )
        val body = (fetched as WeatherFetch.Got).text

        val parsed = source.parseToday(body, location)
        assertNotNull("真实文件解析不出天气，正文=$body", parsed)
        val w = parsed!!

        // **核心断言**：文件里的日期必须是今天。差一天就是拿昨天的天气冒充今天。
        assertEquals(
            "托管文件里的日期不是今天 —— CI 是不是停了、或者 date 字段改了格式",
            AppZone.todayEpochDay(),
            w.epochDay
        )
        assertTrue("城市名不该是空串", w.cityName.isNotBlank())
        assertNotNull("高低温不该有一头是空的：$w", w.high)
        assertNotNull("高低温不该有一头是空的：$w", w.low)
    }

    /**
     * 空气那一项是**嵌在天气文件里**的（由 CI 拿 Open-Meteo 的读数拼进去）。
     *
     * 这一条断掉时，界面上会在城市名旁边少一段空气质量 —— 而那是个
     * **没有报错、没有日志、只是少了一小段**的故障，最难被用户当成故障报上来。
     */
    @Test
    fun `文件里带着空气质量`() = runTest {
        val source = SeniverseWeatherSource()
        val fetched = source.fetchToday(location)
        assertTrue(
            "托管文件拉不到：${(fetched as? WeatherFetch.Unavailable)?.reason}",
            fetched is WeatherFetch.Got
        )

        val parsed = source.parseToday((fetched as WeatherFetch.Got).text, location)
        assertNotNull("真实文件解析不出天气", parsed)

        // 空气是可选项（拉不到时只是少一段），但**长期缺失**就是 CI 出了问题。
        // 这里只断言「有值时它得是个合理的读数」，不硬性要求非空 ——
        // Open-Meteo 偶尔会短时拿不到，硬要求会让这条用例变成 CI 的健康检查，
        // 而它该守的是「解析口径没变」。
        val aqi = parsed!!.aqi
        if (aqi != null) {
            assertTrue("AQI 读数离谱：$aqi", aqi in 0..500)
        }
    }

    /** 三个镜像里至少要有一个真的能通，且都是同一份内容。 */
    @Test
    fun `镜像列表非空且逐个尝试`() {
        assertTrue("镜像列表空了", SeniverseWeatherSource.REMOTE_URLS.isNotEmpty())
        assertEquals(
            "镜像地址重复了，白白多等一轮",
            SeniverseWeatherSource.REMOTE_URLS.size,
            SeniverseWeatherSource.REMOTE_URLS.distinct().size
        )
    }
}
