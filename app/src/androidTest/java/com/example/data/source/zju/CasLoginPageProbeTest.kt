package com.example.data.source.zju

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * CAS 登录页取法的**无凭据**活体探针（2026-10-18）。
 *
 * ## 要回答的问题
 *
 * 教务网同步改走「共用 TGC」之后，界面上的第一条报错变成了
 * 「浙大登录页打不开（HTTP 302）」。而在此之前，同一个裸 GET 探针量到的是
 * **HTTP 200**。同一条请求，同一台模拟器，两次结果不同 —— 那么
 *
 * - 是浙大改了行为？
 * - 是**不带 `service`** 这个取法本身就不稳定（今天开始才 302）？
 * - 还是请求头上某个差异（User-Agent / Cookie）触发了不同分支？
 *
 * 这三件事的处理方式完全不同，不能靠猜。所以这里把变量一个一个拆开：
 * 同一个 URL，分别在「无 UA / 有 UA」「跟跳转 / 不跟跳转」下各打一次，
 * 再加上**带 service** 的一发，把跳转目标和 execution 一起打出来。
 *
 * 刻意不用学号密码：这些行为在未认证状态下就全部可观测，
 * 而且可以反复跑，不怕把账号撞到锁定。
 *
 * Cookie 只打名字不打值 —— 名字本身就是定位所需的全部信息，值是会话令牌。
 *
 * 开跑：
 *   gradlew :app:connectedDebugAndroidTest \
 *     "-Pandroid.testInstrumentationRunnerArguments.class=com.example.data.source.zju.CasLoginPageProbeTest" \
 *     -Pandroid.testInstrumentationRunnerArguments.miulist.instrumentedNet=1
 */
@RunWith(AndroidJUnit4::class)
class CasLoginPageProbeTest {

    private fun enabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("miulist.instrumentedNet") == "1"

    private fun build(follow: Boolean, withUa: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(follow)
            .followSslRedirects(follow)
            .cookieJar(ProbeCookieJar())
            .apply { if (withUa) addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", PROBE_UA)
                        .build()
                )
            } }
            .build()

    /** 打一发并把关键信息摊开。 */
    private fun shot(label: String, url: String, follow: Boolean, withUa: Boolean) {
        val response = build(follow, withUa).newCall(
            Request.Builder().url(url).get().build()
        ).execute()
        val body = response.use { it.body?.string().orEmpty() }
        val setCookies = response.headers.values("Set-Cookie")
        println("[cas] ▶▶ $label")
        println("[cas]    GET $url   (follow=$follow, ua=$withUa)")
        println("[cas]    落点 URL = ${response.request.url}")
        println("[cas]    HTTP=${response.code}  Location=${response.header("Location")}")
        println("[cas]    Set-Cookie=${cookieNamesOf(setCookies)}")
        println("[cas]    正文长度=${body.length}")
        println("[cas]    execution=${LoginPageParser().execution(body)?.take(28)}")
        println("[cas]    是登录页=${body.contains("name=\"password\"")}  正文前 160 字：${body.take(160).replace("\n", " ").trim()}")
    }

    /**
     * 主用例：把「裸 GET」在四种配置下各打一次，再打一次带 service 的。
     *
     * 只要其中任意一发是 200 且带 execution，就说明问题出在**取法**而不是浙大；
     * 反过来若全是 302，则要顺着 Location 往下看它到底想跳去哪。
     */
    @Test
    fun bareLoginPageVariants() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val bare = "https://zjuam.zju.edu.cn/cas/login"

        shot("A 裸 GET / 无 UA / 不跟跳转", bare, follow = false, withUa = false)
        shot("B 裸 GET / 有 UA / 不跟跳转 ← App 用的就是这一发", bare, follow = false, withUa = true)
        shot("C 裸 GET / 有 UA / 跟跳转", bare, follow = true, withUa = true)
        shot("D 带 service(教务网) / 有 UA / 不跟跳转", ZDBK_SERVICE_URL, follow = false, withUa = true)
    }

    /**
     * 裸 GET 若回 302，把它想跳去的地方**逐跳**摊开。
     *
     * 停在 302 上看不出「302 是要去哪」——而「它要去哪」正是唯一有用的信息：
     * 跳去另一个 host 说明浙大在把你转走（Keycloak 之类），
     * 跳回同一个 `/cas/login` 说明它只是要补参数。
     */
    @Test
    fun followBareRedirectChain() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val client = build(follow = false, withUa = true)
        var current: HttpUrl = "https://zjuam.zju.edu.cn/cas/login".toHttpUrl()

        for (hop in 0 until 10) {
            val response = client.newCall(
                Request.Builder().url(current).get().build()
            ).execute()
            val status = response.code
            val location = response.header("Location")
            val body = response.use { it.body?.string().orEmpty() }

            println("[chain] ── hop=$hop")
            println("[chain]    GET $current")
            println("[chain]    HTTP=$status")
            println("[chain]    Location=$location")
            println("[chain]    Set-Cookie=${cookieNamesOf(response.headers.values("Set-Cookie"))}")
            println("[chain]    正文长度=${body.length}  execution=${LoginPageParser().execution(body)?.take(28)}")

            if (status in 300..399 && !location.isNullOrBlank()) {
                val next = current.resolve(location)
                if (next == null) {
                    println("[chain] ⇒ Location 无法解析为绝对地址，链断")
                    return
                }
                current = next
                continue
            }
            println("[chain] ⇒ 停在 ${current.host}，HTTP=$status，长度=${body.length}")
            println("[chain]    正文前 300 字：${body.take(300).replace("\n", " ").trim()}")
            return
        }
        println("[chain] ⇒ 跳数用尽")
    }

    /**
     * 同一个裸 GET **连打两次**，中间保留 Cookie 罐。
     *
     * 用来分辨「浙大今天开始对裸 GET 回 302」和「第二次才 302」：
     * 前者是行为变更，后者是某个 Cookie 触发的分支 —— 两者改法完全不同。
     */
    @Test
    fun repeatedBareGetWithSharedJar() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val client = build(follow = false, withUa = true)
        val url = "https://zjuam.zju.edu.cn/cas/login".toHttpUrl()

        repeat(3) { round ->
            val response = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.use { it.body?.string().orEmpty() }
            println("[twice] ▶ 第 ${round + 1} 次")
            println("[twice]    HTTP=${response.code}  Location=${response.header("Location")}")
            println("[twice]    Set-Cookie=${cookieNamesOf(response.headers.values("Set-Cookie"))}")
            println("[twice]    正文长度=${body.length}  execution=${LoginPageParser().execution(body)?.take(28)}")
        }
    }

    /**
     * 用**生产代码里那个** [InMemoryCookieJar] 连打两次真实的登录页 GET。
     *
     * 前面的用例用的是探针自带的 CookieJar，它和生产的那个有个关键差别：
     * 生产罐的 `saveFromResponse` **只按 Cookie 名字**存（`store[it.name] = it`），
     * 不带域名/path。于是教务网的 `JSESSIONID` 会**覆盖掉**统一认证的同名
     * `JSESSIONID`。第一次同步失败后再点「重试一次」，罐里就是被污染过的状态 ——
     * 而那种状态在生产里才出现，用探针自己的罐永远测不到。
     *
     * 所以这里必须用生产那个罐，且必须连打两次。
     */
    @Test
    fun productionJarRepeatedLoginPageGet() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val jar = InMemoryCookieJar()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(jar)
            .build()
        val url = "https://zjuam.zju.edu.cn/cas/login".toHttpUrl()

        repeat(3) { round ->
            // 打印这一跳**实际会带出去**的 Cookie 名字 —— 域名过滤后的结果，
            // 这才是浙大那边看到的输入。
            val sent = jar.loadForRequest(url).map { it.name }.sorted()
            val response = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.use { it.body?.string().orEmpty() }
            println("[prod] ▶ 第 ${round + 1} 次   发出 Cookie=$sent")
            println("[prod]    HTTP=${response.code}  Location=${response.header("Location")}")
            println("[prod]    Set-Cookie=${cookieNamesOf(response.headers.values("Set-Cookie"))}")
            println("[prod]    正文长度=${body.length}  execution=${LoginPageParser().execution(body)?.take(28)}")

            // 模拟「先混进别的 host 的同名 Cookie」：zdbk 也会签发 JSESSIONID。
            if (round == 0) {
                jar.addCookie(
                    Cookie.Builder().name("JSESSIONID").value("zdbk-pretend-value")
                        .domain("zdbk.zju.edu.cn").path("/").build()
                )
                jar.addCookie(
                    Cookie.Builder().name("route").value("pretend-route")
                        .domain("zdbk.zju.edu.cn").path("/").build()
                )
                println("[prod]    （已注入 zdbk 的同名 JSESSIONID，模拟一次失败同步留下的残留）")
            }
        }
    }

    /**
     * 跑一遍**生产代码**的取登录页那一跳，但把提交密码的那一 POST 拦掉。
     *
     * 目的是让真正的 GET 发出去、而不把任何凭据递给浙大 ——
     * 「浙大登录页打不开（HTTP 302）」正是在这一跳抛的，而它发生在密码之前，
     * 所以不提交任何密码也能复现。POST 用 Interceptor 合成一个响应顶掉，
     * 这样流程会在 GET 之后结束，不会真的去登录（也就不会消耗失败次数）。
     */
    @Test
    fun productionLoginPageFetchWithoutSubmittingCredentials() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val jar = InMemoryCookieJar()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(jar)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "POST") {
                    // 绝不放行：真发出去就是一次登录尝试。
                    // 合成响应**必须带 body**，否则 OkHttp 会抛
                    // 「returned a response with no body」，把真正的结果盖掉。
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(599)
                        .message("probe: POST not sent")
                        .body("".toResponseBody(null))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        repeat(2) { round ->
            try {
                runBlocking { ZjuSsoLogin(client).login("probe-dummy-id", "probe-dummy-password") }
                println("[path] 第 ${round + 1} 次：居然没抛异常（不应发生）")
            } catch (e: Exception) {
                println("[path] 第 ${round + 1} 次异常：${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 教务网那三段的**目标地址**是否真的活着、路径有没有写错。
     *
     * 这一段完全不涉及凭据，但它是整个修复的前提：换票要打 CAS 的 service、
     * 建会话要打 `login_ssologin.html?ticket=…`、取课表要打 `xskbcx_cxXsKb.html`。
     * 任何一个地址写错（路径、少一层、域名），症状都是「登录成功但课表空」，
     * 或者被误读成「浙大接口挂了」——而这两种都极难往回找。
     *
     * 用一张**假票**打回调、用**空表单**打课表接口：
     * 它们都会被挡在会话校验之前，不构成任何登录尝试。
     */
    @Test
    fun zdbkEndpointsAreAlive() {
        assumeTrue("未开启真联网测试：加 miulist.instrumentedNet=1", enabled())

        val client = build(follow = false, withUa = true)

        // ② CAS 的 service（带 service 取登录页）
        shot("② CAS service（带 service 取登录页）", ZDBK_SERVICE_URL, follow = false, withUa = true)

        // ③ 回调地址：无票 / 假票各打一发
        shot("③ 回调·无 ticket", "https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html",
            follow = false, withUa = true)
        shot("③ 回调·假 ticket", "https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html?ticket=ST-PROBE-NOT-A-REAL-TICKET",
            follow = false, withUa = true)

        // ④ 课表接口：无会话时它会回什么
        val api = client.newCall(
            Request.Builder()
                .url("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html")
                .post(
                    okhttp3.FormBody.Builder()
                        .add("xnm", "2026-2027")
                        .add("xqm", "1")
                        .build()
                )
                .build()
        ).execute()
        val apiBody = api.use { it.body?.string().orEmpty() }
        println("[zdbk] ▶▶ ④ 课表接口（无会话）")
        println("[zdbk]    HTTP=${api.code}  Location=${api.header("Location")}")
        println("[zdbk]    Set-Cookie=${cookieNamesOf(api.headers.values("Set-Cookie"))}")
        println("[zdbk]    正文长度=${apiBody.length}  是登录页=${CoursesAuthGuard.bodyIsLoginPage(apiBody)}")
        println("[zdbk]    正文前 300 字：${apiBody.take(300).replace("\n", " ").trim()}")
    }

    private fun cookieNamesOf(setCookieHeaders: List<String>): String {
        if (setCookieHeaders.isEmpty()) return "<无>"
        return setCookieHeaders.joinToString("; ") { it.substringBefore('=').trim() }
    }

    /** 只保留名字，值不进日志。 */
    private class ProbeCookieJar : CookieJar {
        private val jar = LinkedHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) jar["${c.name}|${c.domain}|${c.path}"] = c
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val host = url.host.lowercase()
            return jar.values.filter {
                it.expiresAt >= now && (
                    host == it.domain.trimStart('.').lowercase() ||
                        host.endsWith("." + it.domain.trimStart('.').lowercase())
                    )
            }
        }
    }

    private companion object {
        const val PROBE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/115.0.0.0 Safari/537.36 Edg/115.0.1901.188"

        /** 与 ZjuCasClient 里那条 service 保持一致。 */
        const val ZDBK_SERVICE_URL =
            "https://zjuam.zju.edu.cn/cas/login?service=" +
                "https%3A%2F%2Fzdbk.zju.edu.cn%2Fjwglxt%2Fxtgl%2Flogin_ssologin.html"
    }
}
