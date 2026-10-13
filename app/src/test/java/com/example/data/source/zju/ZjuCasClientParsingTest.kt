package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录页 hidden input 的解析。
 *
 * 这里的样本是 2026-10-13 从 `https://zjuam.zju.edu.cn/cas/login` 实抓的片段
 * （execution 的值做了截断，但**属性的排版是真的**）。
 *
 * 为什么值得钉死：早期版本照抄上游 zju-ical 的字节索引写法
 * （找 `execution"` 到 `" />`），在真实页面上切出的是
 * ` value="986dedd1-…" />` —— 带前导空格、带 `value="` 壳、带结尾。
 * 提交后 CAS 回 HTTP 500，而 500 和「密码错」在日志里长得几乎一样，
 * 于是排查方向整个跑偏。这个用例就是防止它再退化回去。
 */
class ZjuCasClientParsingTest {

    /** 实抓的 hidden input 排版，属性之间是换行 + 缩进。 */
    private val realPage = """
        <input type="hidden" name="execution" value="986dedd1-6ff6-4d30-ae9f-4a170db42f03_ZXlKaGJHY2lPaUpJVXpVeE1pSjkuT0RZMmIy" />
        <input type="hidden" name="_eventId" value="submit" />
        <input type="hidden" id = "ys" name="ys" value="5" />
    """.trimIndent()

    @Test
    fun `execution 不含 value 大小写与收尾符号`() {
        val parser = LoginPageParser()

        val execution = parser.execution(realPage)

        assertEquals(
            "986dedd1-6ff6-4d30-ae9f-4a170db42f03_ZXlKaGJHY2lPaUpJVXpVeE1pSjkuT0RZMmIy",
            execution
        )
        // 旧实现切出来会带这些毛刺，逐个钉住。
        assertTrue("不该带前导空格", !execution!!.startsWith(" "))
        assertTrue("不该带 value= 前缀", !execution.startsWith("value="))
        assertTrue("不该带结尾 />", !execution.endsWith("/>"))
        assertTrue("不该含引号", !execution.contains('"'))
    }

    @Test
    fun `ys 认证方式标记取得到`() {
        assertEquals("5", LoginPageParser().input(realPage, "ys"))
    }

    @Test
    fun `属性顺序颠倒也认得出`() {
        // value 在 name 之前、结尾是 '>' 而不是 '/>'
        val page = """<input value="abc123" name="execution" type="hidden">"""
        assertEquals("abc123", LoginPageParser().execution(page))
    }

    @Test
    fun `页面里没有时返回 null`() {
        assertEquals(null, LoginPageParser().execution("<html><body>无表单</body></html>"))
        assertEquals(null, LoginPageParser().input(realPage, "不存在的字段"))
    }

    @Test
    fun `空值不算命中`() {
        val page = """<input type="hidden" name="execution" value="" />"""
        assertEquals(null, LoginPageParser().execution(page))
    }
}

/**
 * 课表接口失败时的文案分支。
 *
 * 为什么这条值得钉死：504 这个分支最早是为了区分
 * 「认证正常但浙大服务挂了」和「学号密码填错」——
 * 前者该让用户等，后者该让用户去改，而两者在响应里一度长得几乎一样。
 *
 * 一旦把 504 漏进 `else`，用户看到的就是「教务网没返回课表（HTTP 504）」——
 * 他会理解成自己学号填错了，然后反复改密码，直到撞上锁号。
 */
class ZjuScheduleFailureReasonTest {

    /**
     * 纯文案分支，不需要网络；客户端走默认构造即可。
     * （`scheduleFailureReason` 是成员函数，不是顶层函数。）
     */
    private val client = ZjuCasClient()

    @Test
    fun `504 说是对方服务不可用而不是让用户检查学号`() {
        val reason = client.scheduleFailureReason(504)
        assertTrue("504 应指向浙大服务故障，实际=$reason", reason.contains("浙大教务网服务暂时不可用"))
        assertTrue("504 不该暗示凭据/学号有问题，实际=$reason", !reason.contains("学号"))
        assertTrue("504 不该暗示密码，实际=$reason", !reason.contains("密码"))
        assertTrue("504 应给出可执行的下一步（稍后再试），实际=$reason", reason.contains("稍后再试"))
    }

    @Test
    fun `401 403 指向会话失效 要重新登录`() {
        listOf(401, 403).forEach { code ->
            val reason = client.scheduleFailureReason(code)
            assertTrue("HTTP $code 应要求重新获取课表，实际=$reason", reason.contains("会话已失效"))
        }
    }

    @Test
    fun `其余 5xx 归为服务端错误 不误报凭据问题`() {
        listOf(500, 502, 503).forEach { code ->
            val reason = client.scheduleFailureReason(code)
            assertTrue("HTTP $code 文案=$reason", reason.contains("浙大教务网出错"))
            assertTrue(
                "HTTP $code 不该让用户去改密码，实际=$reason",
                !reason.contains("学号或密码错误")
            )
        }
    }

    @Test
    fun `4xx 兜底文案带上状态码`() {
        val reason = client.scheduleFailureReason(431)
        assertTrue("兜底文案应含状态码便于排查，实际=$reason", reason.contains("431"))
    }
}

/**
 * 传输层异常的翻译。
 *
 * 2026-10-13 在模拟器上实测：宿主代理不通时，界面上出现的**唯一**东西是
 * OkHttp 的原始英文 `unexpected end of stream on https://zjuam.zju.edu.cn/...`。
 * 用户看不懂，更会误以为是学号填错而反复改密码直到撞上锁号。
 */
class ZjuNetworkFailureReasonTest {

    private val client = ZjuCasClient()

    @Test
    fun `IOException 翻译成「检查网络」而不是英文原文`() {
        val e = java.io.IOException("unexpected end of stream on https://zjuam.zju.edu.cn/cas/login?service=x")
        val reason = client.networkFailureReason(e)
        assertTrue("应提示检查网络，实际=$reason", reason.contains("网络"))
        assertFalse("不该出现英文原文", reason.contains("unexpected end of stream"))
        assertFalse("不该把 URL 抛给用户", reason.contains("http"))
        assertFalse("不该暗示学号/密码有问题", reason.contains("学号") || reason.contains("密码"))
    }

    @Test
    fun `超时的因果链里任意一层是 Timeout 都算超时`() {
        // OkHttp 常把真实原因包了好几层，只看最外层会漏判。
        val e = java.net.SocketTimeoutException("timeout")
        val wrapped = java.io.IOException("failed", e)
        val reason = client.networkFailureReason(wrapped)
        assertTrue("应识别为超时，实际=$reason", reason.contains("超时"))
    }

    @Test
    fun `非 IO 异常走兜底 且不编造原因`() {
        val reason = client.networkFailureReason(IllegalStateException("boom"))
        assertTrue("应给出可读兜底，实际=$reason", reason.contains("稍后再试"))
        assertFalse("不该泄漏异常原文", reason.contains("boom"))
    }
}