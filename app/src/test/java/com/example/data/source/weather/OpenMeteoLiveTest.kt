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
 * 真联网冒烟测试：拿**今天的上游真实响应**跑一遍解析与判定。
 *
 * 默认**不跑**（[liveNet] 关着），调试天气时手动开：
 *   gradlew :app:testDebugUnitTest --tests "*OpenMeteoLiveTest*" "-Pmiulist.liveNet=1"
 *
 * **为什么这一组值得存在**：离线的用例喂的都是我自己拼的响应，
 * 而拼出来的响应永远符合我写下的假设。真正会咬人的是那些「我以为是这样」
 * 的地方 —— 最要命的就是 `timezone`。少了它，`daily.time` 按 GMT 切，
 * 东八区的晚上会拿到**明天**的日期，于是界面上显示一份不存在的天气、
 * 缓存判重全失效，而且**不崩、不报错**，只是安静地显示错的东西。
 * 这种错只有对着真接口才看得见。
 *
 * ## 网络层失败一律 skip，不算失败
 *
 * 「连不上」和「接口变了」是两件完全不同的事，必须分开：
 * 连不上说明**这台机器的网络**有问题（代理掐了 TLS、域名被墙、公司网络拦截），
 * 那时我们根本没拿到任何响应，无从判断契约有没有变 —— 报 FAIL 是把
 * 环境问题伪装成代码问题，会让人去改本来正确的代码。
 * 只有**真的拿到了服务端答复**、而答复不对，才算失败。
 *
 * 这也意味着：**当这组用例全部 skip 时，不要以为「接口没问题」——
 * 它只是没被验证过。** 真要确认接口，去看设备上天气块显示的内容。
 *
 * 代理由 app/build.gradle.kts 从 https_proxy 注入到 test JVM。
 */
class OpenMeteoLiveTest {

    private val location = WeatherLocation.DEFAULT

    @Before
    fun requireLiveNetwork() {
        assumeTrue(
            "未开启真联网测试：加 -Pmiulist.liveNet=1",
            System.getProperty("miulist.liveNet") == "1"
        )
    }

    /**
     * 拉到一份真实正文。
     *
     * **连不上就跳过整个用例**，只有真拿到了才往下走；拿到了但服务端说这是错误，
     * 那才是契约问题，直接失败。所以这个函数要么返回正文，要么测试已经结束了
     * （跳过或失败），调用方拿到的**永远**是可用正文，不需要再判可空。
     */
    private suspend fun fetchBody(at: WeatherLocation, what: String): String {
        val fetched = OpenMeteoWeatherSource().fetchToday(at)
        if (fetched is WeatherFetch.Got) {
            println("$what 上游正文: ${fetched.text}")
            return fetched.text
        }
        val reason = (fetched as WeatherFetch.Unavailable).reason
        println("$what 拉取失败: $reason")
        assumeTrue(
            "$what 这台机器连不上天气接口（环境问题，跳过而不是判失败）：$reason",
            !reason.isNetworkFailure()
        )
        // 拿到了服务端的答复但它说这是错误 —— 那才是契约问题，该失败。
        throw AssertionError("$what 上游返回了错误：$reason")
    }

    @Test
    fun 真实响应能取到今天且解析得出来() = runTest {
        val body = fetchBody(location, "正常请求")
        assertNotNull("正文不是空串", body)

        val parsed = OpenMeteoWeatherSource().parseToday(body, location)
        assertNotNull("真实响应解析不出天气，正文=$body", parsed)
        val w = parsed!!
        println("解析结果: ${w.cityName} ${w.textDay} ${w.temperatureRange()}")

        assertEquals("杭州", w.cityName)
        // **核心断言**：时区没生效的话，这里会是明天。
        assertEquals(
            "取到的不是今天 —— timezone 很可能没生效（东八区会整体错一天）",
            AppZone.todayEpochDay(),
            w.epochDay
        )
        assertTrue("高低温全空，字段名可能变了", w.high != null || w.low != null)
        assertTrue("天气词不该是『未知』，码表可能变了：${w.textDay}", w.textDay != "未知")
    }

    /**
     * 坐标越界时上游回 400 + reason，这条守着错误路径在真接口上也成立。
     *
     * 只报「HTTP 400」等于把上游已经写好的那句话扔掉。
     */
    @Test
    fun 参数写错时能读到上游给的原因() = runTest {
        val bad = WeatherLocation("测试", latitude = 300.0, longitude = 120.0)

        val fetched = OpenMeteoWeatherSource().fetchToday(bad)
        if (fetched is WeatherFetch.Got) {
            throw AssertionError("坐标 300 居然拉到了数据，上游不再校验参数了")
        }
        val reason = (fetched as WeatherFetch.Unavailable).reason
        println("上游错误: $reason")
        assumeTrue(
            "连不上天气接口，跳过而不是判失败：$reason",
            !reason.isNetworkFailure()
        )
        assertTrue("只报状态码就把上游写好的原因扔了：$reason", reason.contains("Latitude"))
    }

    /**
     * 无需任何凭据这件事，顺带让真接口确认一遍。
     *
     * 万一哪天地址被改成了需要 `apikey` 的商业端点（域名变成 `customer-` 前缀），
     * 这里会当场 401/403，而不是等到用户界面上天天显示「读不到天气」。
     */
    @Test
    fun 无需任何凭据即可访问() = runTest {
        val fetched = OpenMeteoWeatherSource().fetchToday(location)
        val reason = (fetched as? WeatherFetch.Unavailable)?.reason
        assumeTrue(
            "连不上天气接口，跳过而不是判失败：$reason",
            reason == null || !reason.isNetworkFailure()
        )

        assertTrue(
            "匿名请求被拒了：$reason（免费端点不该要 key）",
            fetched is WeatherFetch.Got
        )
    }
}

/**
 * 这条失败原因说的是**网络**，不是服务端的答复。
 *
 * 判据是措辞而不是异常类型：[Exception.describe] 已经把网络层错误
 * 翻成了固定的人话（「域名解析不了」「TLS 握手失败」「连接超时」
 * 「连不上天气服务器」），而服务端给的错误是另一批措辞。
 * 这样判的好处是：新增一种网络故障时这里不会漏判。
 */
private fun String.isNetworkFailure(): Boolean =
    contains("域名解析不了") ||
        contains("TLS 握手失败") ||
        contains("连接超时") ||
        contains("连不上天气服务器") ||
        contains("HTTP 5") ||
        contains("HTTP 429")
