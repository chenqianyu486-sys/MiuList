package com.example.data.source.zju

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 学在浙大整条链路的**无凭据**活体探针。
 *
 * 存在的理由：要判断「填了学号也拉不到数据」到底是**卡在哪一跳**，
 * 就必须能单独观察每一跳 —— 而带凭据的端到端测试一旦失败，
 * 只会在最外层抛一个中文文案，看不出是 CAS 没放行、SSO 没传到、
 * session 没截到，还是接口本身变了。
 *
 * 这里刻意**不用任何学号密码**：未认证状态下就能问出绝大多数问题，
 * 而且这样跑可以反复执行、不怕把账号撞到锁定。
 *
 * 打印 Cookie 只打**名字**，不打值 —— 那几个名字本身就是定位所需的全部信息，
 * 而值是会话令牌。
 *
 * 开跑：
 *   gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.miulist.instrumentedNet=1 \
 *     -Pmiulist.instrumentedProbe=1
 */
@RunWith(AndroidJUnit4::class)
class CoursesChainProbeTest {

    private fun enabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("miulist.instrumentedNet") == "1"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(ProbeCookieJar())
            .build()
    }

    @Test
    fun casLoginPageAndPubKeyAreReachable() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val page = client.newCall(
            Request.Builder().url("https://zjuam.zju.edu.cn/cas/login").get().build()
        ).execute()
        val body = page.use { it.body?.string().orEmpty() }
        println("[probe] CAS 登录页 HTTP=${page.code} 长度=${body.length}")
        println("[probe] execution=${LoginPageParser().execution(body)}")
        println("[probe] ys=${LoginPageParser().input(body, "ys")}")
        println("[probe] 含 name=\"username\"=${body.contains("name=\"username\"")}")
        println("[probe] 正文前 300 字：${body.take(300).replace("\n", " ")}")
        assertTrue("CAS 登录页取不到内容", body.isNotBlank())
        assertTrue("登录页里切不出 execution", LoginPageParser().execution(body) != null)

        val key = client.newCall(
            Request.Builder().url("https://zjuam.zju.edu.cn/cas/v2/getPubKey").get().build()
        ).execute()
        val keyBody = key.use { it.body?.string().orEmpty() }
        println("[probe] 公钥接口 HTTP=${key.code} 长度=${keyBody.length}")
        println("[probe] 公钥正文前 160 字：${keyBody.take(160)}")
        assertTrue("公钥接口没拿到 modulus", keyBody.contains("modulus"))
    }

    /**
     * 走一遍 courses.zju.edu.cn 的未认证跳转链，把每一跳摊开。
     *
     * 这是定位「卡在哪」的核心用例：登录成功与否取决于这条链的**形状**
     * （几个 302、一次 meta-refresh、最终落在哪个 host），
     * 只要形状和客户端写死的假设不符，登录就会以各种看不出所以然的方式失败。
     */
    @Test
    fun unauthenticatedCoursesRedirectChain() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        var current: HttpUrl = "https://courses.zju.edu.cn/user/index".toHttpUrl()

        for (hop in 0 until 15) {
            val response = client.newCall(
                Request.Builder().url(current).get()
                    .header("User-Agent", PROBE_UA)
                    .build()
            ).execute()

            val status = response.code
            val location = response.header("Location")
            val contentType = response.header("Content-Type")
            val setCookies = response.headers.values("Set-Cookie")
            val text = response.use { it.body?.string().orEmpty() }

            println("[probe] ── hop=$hop")
            println("[probe]    GET $current")
            println("[probe]    HTTP=$status  Content-Type=$contentType")
            println("[probe]    Location=$location")
            println("[probe]    Set-Cookie=${cookieNamesOf(setCookies)}")
            println("[probe]    正文长度=${text.length}")
            println("[probe]    正文前 220 字：${text.take(220).replace("\n", " ").trim()}")

            if (status in 300..399 && !location.isNullOrBlank()) {
                current = current.resolve(location)!!
                continue
            }

            // 停在非 courses 主机上 = 未认证时被弹回身份认证，这就是链路的自然终点。
            if (current.host != "courses.zju.edu.cn") {
                println("[probe] ⇒ 链条终止：被弹到 ${current.host}（未认证状态的预期结果）")
                return
            }

            // 停在 courses 上但没有 session = 业务会话没建立。
            val hasSession = setCookies.any { it.startsWith("session=") && !it.contains("session=\"\"") }
            println("[probe] ⇒ 链条终止：停在 courses 且 Set-Cookie 里 session=$hasSession")
            return
        }
        println("[probe] ⇒ 跳数用尽仍未终止")
    }

    /**
     * 未认证状态下打一次待办接口，看**接口本身**长什么样。
     *
     * 这个用例和登录无关：如果接口已经改名、换路径或换了响应结构，
     * 那么再怎么修登录都不会有数据 —— 先把这件事和登录问题分开。
     */
    @Test
    fun unauthenticatedTodoEndpointShape() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        for (url in listOf(
            "https://courses.zju.edu.cn/api/todos",
            "https://courses.zju.edu.cn/api/todo_list"
        )) {
            val response = client.newCall(
                Request.Builder().url(url).get()
                    .header("User-Agent", PROBE_UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
            ).execute()
            val text = response.use { it.body?.string().orEmpty() }
            println("[probe] ▶ $url")
            println("[probe]    HTTP=${response.code}  Content-Type=${response.header("Content-Type")}")
            println("[probe]    Location=${response.header("Location")}")
            println("[probe]    长度=${text.length}")
            println("[probe]    正文前 700 字：${text.take(700).replace("\n", " ").trim()}")
        }
    }

    /**
     * 拆开两张登录页，比较**它们要求提交什么**。
     *
     * 两条路径拿到的不是同一张页：
     * - 直接 GET `/cas/login`（客户端现在用的拿 execution 方式）
     * - 经由 Keycloak 跳进来的 `/cas/login?service=…&state=…`（真实链路必经的那张）
     *
     * 两张页的字节数差得很多，而多出来的那部分很可能就是**多出来的必填项**
     * （验证码、state 回传之类）。只按第一张页的字段提交，第二条路上就会失败 ——
     * 而失败回来的往往是个 200 + 一段人话，直接被误读成「密码错了」。
     */
    @Test
    fun loginPageAnatomy() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val direct = client.newCall(
            Request.Builder().url("https://zjuam.zju.edu.cn/cas/login").get()
                .header("User-Agent", PROBE_UA).build()
        ).execute()
        val directBody = direct.use { it.body?.string().orEmpty() }

        // 走一遍 Keycloak 链，取到真实链路里那张带 service 的登录页。
        val withService = walkToCasLoginPage() ?: run {
            println("[probe] ⇒ 未能取到带 service 的登录页")
            return
        }

        for ((label, page) in listOf("直接 GET /cas/login" to directBody, "Keycloak 链路中的登录页" to withService)) {
            println("[probe] ===== $label  长度=${page.length}")
            FORM_REGEX.findAll(page).forEach {
                println("[probe]    <form ${it.value.take(200)}>")
            }
            val inputs = INPUT_TAG_REGEX.findAll(page)
                .map { it.value }
                .toList()
            println("[probe]    input 共 ${inputs.size} 个：")
            inputs.forEach { println("[probe]      ${it.take(220)}") }
            println("[probe]    含验证码字样=${Regex("captcha|验证码|verif").containsMatchIn(page)}")
            println("[probe]    含 _eventId=${page.contains("_eventId")}")
            println("[probe]    含 execution=${page.contains("execution")}")
        }
    }

    /** 沿 Keycloak 链走到 CAS 登录页，拿到真实 service 形态的正文。 */
    private fun walkToCasLoginPage(): String? {
        var current: HttpUrl = "https://courses.zju.edu.cn/user/index".toHttpUrl()
        repeat(15) {
            val response = client.newCall(
                Request.Builder().url(current).get().header("User-Agent", PROBE_UA).build()
            ).execute()
            val text = response.use { it.body?.string().orEmpty() }
            val location = response.header("Location")
            if (response.code in 300..399 && !location.isNullOrBlank()) {
                current = current.resolve(location)!!
                return@repeat
            }
            if (current.host == "zjuam.zju.edu.cn" && current.encodedPath.startsWith("/cas/login")) {
                return text
            }
            return null
        }
        return null
    }

    private fun cookieNamesOf(setCookieHeaders: List<String>): String {
        if (setCookieHeaders.isEmpty()) return "<无>"
        return setCookieHeaders.joinToString("; ") { header ->
            header.substringBefore('=').trim()
        }
    }

    /** 只保留名字，值不进日志。 */
    private class ProbeCookieJar : CookieJar {
        private val jar = LinkedHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                jar["${c.name}|${c.domain}|${c.path}"] = c
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val host = url.host.lowercase()
            return jar.values.filter {
                it.expiresAt >= now &&
                    (host == it.domain.trimStart('.').lowercase() ||
                        host.endsWith("." + it.domain.trimStart('.').lowercase()))
            }
        }
    }

    private companion object {
        const val PROBE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/115.0.0.0 Safari/537.36 Edg/115.0.1901.188"

        val FORM_REGEX = Regex("""<form\b[^>]*>""", RegexOption.IGNORE_CASE)
        val INPUT_TAG_REGEX = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
    }
}
