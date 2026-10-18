package com.example.data.source.zju

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 教务网登录链路的**离线**测试。
 *
 * 这次修掉的坑是「GET 登录页带 `service`、POST 密码不带 `service`」——
 * CAS 对此固定回 **HTTP 400** 且不解释原因，界面上只剩「原因未说明」。
 * 这类错配靠读代码能看出来，但**必须有一组夹具钉住正确链路的形状**，
 * 否则下次改回去没人会发现。
 *
 * 夹具照的是 celechron `Zdbk._doLogin` 的三段式：
 * 密码登录换 TGC → 带 TGC 向 service 换票 → 用票换 `JSESSIONID` + `route`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZdbkCasHandshakeTest {

    @Test
    fun 三段握手走通后拿到了成对的教务网会话() = runBlocking {
        val jar = InMemoryCookieJar()
        val cas = ZjuCasClient(fakeClient(jar, successChain()))

        cas.login("3250000000", "whatever")

        // 成对签发是硬要求：少一个后续课表接口的会话校验就过不去，
        // 症状是「登录成功但课表空/404」，看起来像接口坏了。
        assertNotNull("应当拿到 JSESSIONID", jar.findCookie("JSESSIONID"))
        assertNotNull("应当拿到 route", jar.findCookie("route"))
        assertNotNull("TGC 应当留在罐子里", jar.findCookie(ZjuSsoLogin.TGC_NAME))
    }

    @Test
    fun CAS没认这次登录时停在换票这一步并报会话失效() = runBlocking {
        val jar = InMemoryCookieJar()
        // 第 4 跳回的是 200 + 登录页，而不是带 ticket 的 302：
        // 这正是 TGC 失效时的形态。若继续往下走，只会拿到一张空课表。
        val cas = ZjuCasClient(fakeClient(jar, successChain(TicketStep.LOGIN_PAGE)))

        try {
            cas.login("3250000000", "whatever")
            fail("没拿到票据就不该算登录成功")
        } catch (e: ZjuException) {
            assertEquals("实际文案：${e.message}", ZjuFailureKind.SESSION, e.kind)
            assertTrue("文案应说明认证没认出登录，实际：${e.message}",
                e.message!!.contains("没有认出这次登录"))
        }
    }

    @Test
    fun 教务网没下发成对会话时报出缺哪一个() = runBlocking {
        val jar = InMemoryCookieJar()
        val cas = ZjuCasClient(fakeClient(jar, successChain(TicketStep.NO_ROUTE)))

        try {
            cas.login("3250000000", "whatever")
            fail("缺 route 不该算登录成功")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.SESSION, e.kind)
            assertTrue("文案要点名缺的是 route，实际：${e.message}", e.message!!.contains("route"))
        }
    }

    /**
     * 罐里已经有 TGC 时**不许再走一遍密码登录**。
     *
     * 这条是被一次真实故障逼出来的：界面上出现过「浙大登录页打不开（HTTP 302）」，
     * 而同一台模拟器、同一发请求，干净探针量到的却是 **HTTP 200**。
     * 差别不在请求，而在**罐里的状态** —— 已认证的 CAS 拿 TGC 打一个不带 `service`
     * 的 `/cas/login` 时不给登录页、只回 302。于是「上一次留下的 TGC」
     * 把这一次的真实失败原因盖掉了。
     *
     * 罐由 [ZjuImportService] 长期持有，所以「上一次」可能就是同一次运行里的前一下。
     */
    @Test
    fun 罐里已有TGC时不再重复走密码登录() = runBlocking {
        val jar = InMemoryCookieJar()
        jar.addCookie(
            Cookie.Builder().name(ZjuSsoLogin.TGC_NAME).value("TGC-OLD")
                .domain(ZjuSsoLogin.SSO_DOMAIN).path("/").build()
        )

        // 只有两跳：换票 → 建会话。多出来的一跳就说明又去取登录页了。
        val cas = ZjuCasClient(
            fakeClient(
                jar,
                listOf(
                    FakeHop(
                        302,
                        location = "https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html?ticket=ST-1234"
                    ),
                    FakeHop(
                        200,
                        body = "<html>教务网首页</html>",
                        setCookies = listOf("JSESSIONID=JS1; Path=/jwglxt", "route=/jwglxt; Path=/")
                    )
                )
            )
        )

        cas.login("3250000000", "whatever")

        assertNotNull("应当拿到 JSESSIONID", jar.findCookie("JSESSIONID"))
        assertEquals(
            "既有的 TGC 不该被重新登录换掉",
            "TGC-OLD", jar.findCookie(ZjuSsoLogin.TGC_NAME)?.value
        )
    }

    /**
     * 票据换到了但会话没建起来时，要丢掉旧 TGC 重登一次再试。
     *
     * 不重登的后果不是「这次失败」，而是**永久失败**：陈旧 TGC 留在罐里，
     * 用户点几次「重试一次」都会拿着同一个去换票、被同样地拒 ——
     * 于是界面提示「重新登录」也解决不了，因为重试走的还是那条旧路。
     */
    @Test
    fun 会话没建起来时丢掉旧TGC重登一次() = runBlocking {
        val jar = InMemoryCookieJar()
        jar.addCookie(
            Cookie.Builder().name(ZjuSsoLogin.TGC_NAME).value("TGC-STALE")
                .domain(ZjuSsoLogin.SSO_DOMAIN).path("/").build()
        )
        val modulus = "8daca18834c6dfe60b5707dea93e6ff38828720792a1c18f64635d7d822bcf75"
        val pubKey = JSONObject().put("modulus", modulus).put("exponent", "10001").toString()
        val ticket = FakeHop(
            302,
            location = "https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html?ticket=ST-1234"
        )

        val cas = ZjuCasClient(fakeClient(jar, attempt(TicketStep.NO_ROUTE) + relogin() + attempt(TicketStep.OK)))

        cas.login("3250000000", "whatever")

        assertEquals("旧 TGC 应当已被换掉", "TGC-VALUE", jar.findCookie(ZjuSsoLogin.TGC_NAME)?.value)
        assertNotNull("重登后应当拿到 route", jar.findCookie("route"))
    }

    /**
     * 统一认证与教务网签发的**同名** `JSESSIONID` 不能互相顶掉。
     *
     * 罐原来只按名字存，于是教务网那个直接把统一认证那个顶掉；
     * 而回发时又按域名过滤，被顶掉的正好是教务网要用的那一个。
     * 症状是「明明换到票了，却报没有下发会话」——很难往回找。
     */
    @Test
    fun 两个主机签发的同名JSESSIONID各自保留() {
        val jar = InMemoryCookieJar()
        val casCookie = Cookie.Builder().name("JSESSIONID").value("cas-one")
            .domain("zjuam.zju.edu.cn").path("/cas").build()
        val zdbkCookie = Cookie.Builder().name("JSESSIONID").value("zdbk-one")
            .domain("zdbk.zju.edu.cn").path("/jwglxt").build()
        jar.addCookie(casCookie)
        jar.addCookie(zdbkCookie)

        assertEquals(
            "统一认证那个应当发回统一认证",
            listOf(casCookie),
            jar.loadForRequest("https://zjuam.zju.edu.cn/cas/login".toHttpUrl())
        )
        assertEquals(
            "教务网那个应当发回教务网",
            listOf(zdbkCookie),
            jar.loadForRequest("https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html".toHttpUrl())
        )
    }

    /**
     * 罐里**只有**统一认证那个 `JSESSIONID` 时，必须判定教务网会话没建成。
     *
     * 这条是被真服务器实测逼出来的（2026-10-18）：统一认证和教务网**都会**下发
     * 一个叫 `JSESSIONID` 的 Cookie（无票打回调、没会话打课表接口都能看到这一对
     * `Set-Cookie: JSESSIONID; route`）。罐按 `名字|域名|path` 存之后两者并存，
     * 于是不限定主机的查找会捡到先存进去的那个 ——
     * 教务网该有的那个恰恰缺着，而检查照样通过，
     * 症状是「会话建立成功了，课表接口却一直 302」。
     */
    @Test
    fun 只有统一认证的同名会话时不算教务网会话建成() = runBlocking {
        val jar = InMemoryCookieJar()
        // 统一认证那个：域名对不上教务网。
        jar.addCookie(
            Cookie.Builder().name("JSESSIONID").value("cas-one")
                .domain("zjuam.zju.edu.cn").path("/cas").build()
        )
        // 教务网只发了 route，JSESSIONID 没成对。
        jar.addCookie(
            Cookie.Builder().name("route").value("zdbk-route")
                .domain("zdbk.zju.edu.cn").path("/").build()
        )

        // 两次尝试都缺 zdbk 的 JSESSIONID：被拒之后生产代码会丢掉旧 TGC 重登一次再试，
        // 夹具必须是定长的，少了后面那几跳就会停在「链路比预期更长」上。
        val cas = ZjuCasClient(
            fakeClient(jar, attempt(TicketStep.NO_SESSION) + relogin() + attempt(TicketStep.NO_SESSION))
        )
        jar.addCookie(
            Cookie.Builder().name(ZjuSsoLogin.TGC_NAME).value("TGC")
                .domain(ZjuSsoLogin.SSO_DOMAIN).path("/").build()
        )

        try {
            cas.login("3250000000", "whatever")
            fail("只有统一认证的 JSESSIONID 时不该判成教务网会话建成")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.SESSION, e.kind)
            assertTrue("文案要点名缺的是 JSESSIONID，实际：${e.message}",
                e.message!!.contains("JSESSIONID"))
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private enum class TicketStep {
        OK, LOGIN_PAGE,

        /** 只下发 route，缺 JSESSIONID —— 与「只有统一认证那个同名 Cookie」的场景配对使用。 */
        NO_SESSION,

        NO_ROUTE
    }

    private data class FakeHop(
        val code: Int,
        val body: String = "",
        val location: String? = null,
        val setCookies: List<String> = emptyList()
    )

    /**
     * 一次「换票 + 建会话」的完整链路夹具。
     *
     * [ticketStep] 不是 OK 时会**自动接上一段重登后的失败**：
     * 生产代码在换票/建会话被拒时会丢掉旧 TGC、重新输一次密码再试一次，
     * 而第二次仍然以同样的方式失败 —— 所以夹具必须把那一段也铺出来，
     * 否则测试会停在「链路比预期更长」上，报的却不是它本来要断言的东西。
     * OK 的情形不会触发重试，因此没有尾巴。
     */
    private fun successChain(ticketStep: TicketStep = TicketStep.OK): List<FakeHop> {
        val steps = mutableListOf<FakeHop>()
        // 首次密码登录 → 换票 → 建会话
        steps += relogin()
        steps += attempt(ticketStep)
        // 失败时才发生重登，所以才有这一段；OK 的情形根本不会触发。
        if (ticketStep != TicketStep.OK) {
            steps += relogin()
            steps += attempt(ticketStep)
        }
        return steps
    }

    /** 重登那三跳：登录页 → 公钥 → 提交密码（都不带 service，天然配对）。 */
    private fun relogin(): List<FakeHop> = listOf(
        FakeHop(200, body = CAS_LOGIN_PAGE),
        FakeHop(200, body = pubKeyJson(), setCookies = listOf("JSESSIONID=CAS1; Path=/cas")),
        FakeHop(302, setCookies = listOf("iPlanetDirectoryPro=TGC-VALUE; Path=/; Domain=zju.edu.cn"))
    )

    /**
     * ② 换票 + ③ 建会话。
     *
     * **③ 的形状照着实测写**（2026-10-18）：回调那一跳先 302 去教务网首页、
     * 且**不下发任何 Cookie**，会话是在首页那一跳才成对签发的。
     * 照这个形状铺夹具，才挡得住「把回调的 302 当失败」那种回归。
     */
    private fun attempt(ticketStep: TicketStep): List<FakeHop> {
        if (ticketStep == TicketStep.LOGIN_PAGE) {
            // CAS 不认 TGC：把登录页又吐了一遍（HTTP 200），没有 ticket 回调。
            //
            // ⚠️ 这种情形下**不能**再铺后面的任何一跳：流程在换票这步就抛了，
            // 根本走不到。多铺的那一跳会被下一次重登当成登录页吃掉，
            // 正文变成「教务网首页」，于是报出「页面结构变动」——
            // 一个和真实故障毫无关系的假象，还很难查。
            return listOf(
                FakeHop(200, body = CAS_LOGIN_PAGE, setCookies = listOf("JSESSIONID=CAS1; Path=/cas"))
            )
        }

        val paired = when (ticketStep) {
            TicketStep.OK -> listOf(
                "JSESSIONID=JS1; Path=/jwglxt",
                "route=/jwglxt; Path=/"
            )
            TicketStep.NO_SESSION -> listOf("route=/jwglxt; Path=/")
            TicketStep.NO_ROUTE -> listOf("JSESSIONID=JS1; Path=/jwglxt")
            TicketStep.LOGIN_PAGE -> emptyList()
        }

        return listOf(
            // ② 换票
            FakeHop(
                302,
                location = "https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html?ticket=ST-1234",
                setCookies = listOf("JSESSIONID=CAS1; Path=/cas")
            ),
            // ③ 回调：票被接受，跳去教务网首页，这一跳**不下发任何 Cookie**
            FakeHop(
                302,
                location = "https://zdbk.zju.edu.cn/jwglxt/xtgl/index_initMenu.html?jsdm=06"
            ),
            // 首页这一跳才成对签发
            FakeHop(200, body = "<html>教务网首页</html>", setCookies = paired)
        )
    }

    private fun pubKeyJson(): String {
        val modulus = "8daca18834c6dfe60b5707dea93e6ff38828720792a1c18f64635d7d822bcf75"
        return JSONObject().put("modulus", modulus).put("exponent", "10001").toString()
    }

    private fun fakeClient(jar: InMemoryCookieJar, steps: List<FakeHop>): OkHttpClient {
        var index = 0
        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .cookieJar(jar)
            .followRedirects(false)
            .addInterceptor { interceptor ->
                val request: Request = interceptor.request()
                val step = steps.getOrNull(index++) ?: run {
                    fail("链路比预期更长：多出来的一次请求 ${request.url}")
                    error("unreachable")
                }
                // 逐跳打出来：夹具是**定长**的，多铺/少铺一跳都会让后面每一跳
                // 拿到错误的正文，症状是「报了一个和真实故障无关的错误」，
                // 光看断言根本看不出是夹具错位。这行日志就是用来一眼定位错位的。
                println("[fixture] #${index - 1} ${request.method} ${request.url} -> ${step.code} ${step.location ?: step.body.take(40)}")
                val builder = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(step.code)
                    .message(if (step.code < 300) "OK" else "Found")
                    .body(step.body.toResponseBody("text/html; charset=utf-8".toMediaTypeOrNull()))
                step.location?.let { builder.header("Location", it) }
                step.setCookies.forEach { builder.addHeader("Set-Cookie", it) }
                builder.build()
            }
            .build()
    }

    private companion object {
        val CAS_LOGIN_PAGE = """
            <!DOCTYPE html lang="zh-CN"><html><body>
            <form id="fm1" action="/cas/login" method="post">
            <input id="username" name="username" type="text"/>
            <input id="password" name="password" type="password"/>
            <input type="hidden" name="execution" value="abc"/>
            <input type="hidden" name="_eventId" value="submit"/>
            <input type="hidden" name="ys" value="5"/>
            </form></body></html>
        """.trimIndent()
    }
}
