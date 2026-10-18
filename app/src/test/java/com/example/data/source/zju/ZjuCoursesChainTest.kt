package com.example.data.source.zju

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 学在浙大客户端的**离线**测试。
 *
 * 整条 SSO 链用拦截器伪造，不用 MockWebServer 也不出网：
 * 这条链的价值恰恰在「形状」——跳了几跳、每跳的 host 是什么、
 * `session` 是在第几跳拿到的。把这些写成夹具比录一份真实响应更可控，
 * 而且**可以在没有任何凭据的情况下**反复跑。
 *
 * 夹具照的是 2026-10-18 于模拟器（校网内）实测到的真实链路：
 * courses →302→ identity(Keycloak) →303→ identity broker →303→ zjuam CAS。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZjuCoursesChainTest {

    // ------------------------------------------------------------------
    // 认证失效判定
    // ------------------------------------------------------------------

    @Test
    fun location_指向Keycloak身份源_判定为认证失效() {
        // 迁移前只认 cas/login，Keycloak 这条会被漏判成「接口自己跳转了」。
        assertTrue(CoursesAuthGuard.locationIndicatesAuthFailure(
            "https://identity.zju.edu.cn/auth/realms/zju/protocol/cas/login?service=x"
        ))
        assertTrue(CoursesAuthGuard.locationIndicatesAuthFailure(
            "https://zjuam.zju.edu.cn/cas/login?service=x"
        ))
        assertTrue(!CoursesAuthGuard.locationIndicatesAuthFailure(null))
        assertTrue(!CoursesAuthGuard.locationIndicatesAuthFailure(""))
        assertTrue(!CoursesAuthGuard.locationIndicatesAuthFailure(
            "https://courses.zju.edu.cn/user/index"
        ))
    }

    @Test
    fun 正文是CAS登录页_判定为会话失效() {
        val casPage = """
            <!DOCTYPE html lang="zh-CN"><html><body>
            <form id="fm1" action="/cas/login" method="post">
            <input id="username" name="username" type="text"/>
            <input id="password" name="password" type="password"/>
            </form></body></html>
        """.trimIndent()
        assertTrue(CoursesAuthGuard.bodyIsLoginPage(casPage))
        assertTrue(CoursesAuthGuard.bodyIsLoginPage("<div>请先登录</div>"))
    }

    @Test
    fun 正文是正常待办JSON_不判定为登录页() {
        assertTrue(!CoursesAuthGuard.bodyIsLoginPage("""{"todo_list":[]}"""))
        assertTrue(!CoursesAuthGuard.bodyIsLoginPage(""))
    }

    @Test
    fun JSON里kickout为1_判定为会话失效() {
        // 这个字段是 HTTP 200 返回的：只看状态码会把「被顶号」当成正常数据，
        // 症状是作业列表莫名其妙变空而不是报错。
        assertTrue(CoursesAuthGuard.jsonIndicatesAuthFailure(JSONObject("""{"kickout":1}""")))
        assertTrue(CoursesAuthGuard.jsonIndicatesAuthFailure(JSONObject("""{"code":401}""")))
        assertTrue(CoursesAuthGuard.jsonIndicatesAuthFailure(
            JSONObject("""{"code":"401","message":"登录已过期"}""")
        ))
    }

    @Test
    fun JSON是正常数据_不判定为会话失效() {
        assertTrue(!CoursesAuthGuard.jsonIndicatesAuthFailure(JSONObject("""{"todo_list":[]}""")))
        assertTrue(!CoursesAuthGuard.jsonIndicatesAuthFailure(JSONObject("""{"code":200}""")))
        assertTrue(!CoursesAuthGuard.jsonIndicatesAuthFailure(JSONObject("""{"code":200,"message":"登录成功"}""")))
    }

    // ------------------------------------------------------------------
    // 待办解析
    // ------------------------------------------------------------------

    @Test
    fun 解析_is_student为false的助教作业被剔除() {
        val body = """
            {"todo_list":[
              {"id":"1","title":"学生作业","course_name":"高数","end_time":"2099-10-25T23:59:59+08:00","is_student":true},
              {"id":"2","title":"助教批改","course_name":"高数","end_time":"2099-10-26T23:59:59+08:00","is_student":false}
            ]}
        """.trimIndent()

        val items = ZjuCoursesClient().parseTodoList(body)
        assertEquals(1, items.size)
        assertEquals("学生作业", items.single().title)
    }

    @Test
    fun 解析_缺失is_student时保留条目而不是清空() {
        // 上游一旦改字段名，严格过滤会把整屏作业变成「已交清」——
        // 那比多显示几条危险得多，因为它让用户以为真的没事。
        val body = """
            {"todo_list":[
              {"id":"1","title":"没有该字段","course_name":"高数","end_time":"2099-10-25T23:59:59+08:00"}
            ]}
        """.trimIndent()

        val items = ZjuCoursesClient().parseTodoList(body)
        assertEquals(1, items.size)
    }

    @Test
    fun 解析_按截止时间升序_无截止时间排最后() {
        val body = """
            {"todo_list":[
              {"id":"late","title":"无截止","course_name":"c"},
              {"id":"b","title":"较晚","course_name":"c","end_time":"2099-12-31T23:59:59+08:00"},
              {"id":"a","title":"较早","course_name":"c","end_time":"2099-01-31T23:59:59+08:00"}
            ]}
        """.trimIndent()

        val items = ZjuCoursesClient().parseTodoList(body)
        assertEquals(listOf("较早", "较晚", "无截止"), items.map { it.title })
    }

    @Test
    fun 解析_会话被顶掉时抛SESSION而不是当作空列表() {
        try {
            ZjuCoursesClient().parseTodoList("""{"kickout":1,"todo_list":[]}""")
            fail("应当抛出会话失效")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.SESSION, e.kind)
        }
    }

    @Test
    fun 解析_不是JSON时抛DATA_INVALID() {
        try {
            ZjuCoursesClient().parseTodoList("<html>服务器维护</html>")
            fail("应当抛出数据不可用")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.DATA_INVALID, e.kind)
        }
    }

    // ------------------------------------------------------------------
    // 跳转链：核心回归
    // ------------------------------------------------------------------

    @Test
    fun 链路_回到courses但没下发新session时判为失败() {
        // 这条才是**头号 bug** 的精确复现。
        //
        // 未认证访问时 courses 第 0 跳就发了一个 session Cookie；
        // 旧判定是「session 存在即算成功」，于是即便整条 SSO 链走完了、
        // 却因为服务端没换发认证会话而根本没登录的情况，也会被判成功 ——
        // 随后待办接口回 401，用户看到「会话失效」被引去重新登录/改密码。
        //
        // 注意这里特意**回到 courses 并拿到 200**，所以只认「停在认证页」是不够的。
        val chain = fakeChain(
            steps = listOf(
                // 第 0 跳：匿名 session 就到手了
                FakeHop(302, setCookies = listOf("route=A", "session=ANONYMOUS", "role_token=B"),
                    location = "https://identity.zju.edu.cn/auth/realms/zju/protocol/cas/login?service=x"),
                FakeHop(303, setCookies = listOf("AUTH_SESSION_ID=C"),
                    location = "https://identity.zju.edu.cn/auth/realms/zju/broker/cas-client/login?session_code=s&client_id=TronClass"),
                FakeHop(303, setCookies = listOf("CLIENT_URL=D"),
                    location = "https://zjuam.zju.edu.cn/cas/login?service=y&state=z"),
                FakeHop(302, setCookies = listOf("JSESSIONID=E"),
                    location = "https://identity.zju.edu.cn/auth/realms/zju/broker/cas-client/endpoint?ticket=ST-1"),
                FakeHop(303, setCookies = listOf("KC_RESTART=F"),
                    location = "https://courses.zju.edu.cn/user/index?ST=ST-1"),
                // 回到 courses 且是 200，但**没有换发认证会话**
                FakeHop(200, body = "<html><body>学在浙大</body></html>")
            )
        )

        val jar = CoursesInMemoryCookieJar()
        jar.addCookie(tgcCookie())

        try {
            ZjuCoursesClient(chain).establishCoursesSession(
                chain.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build(),
                jar
            )
            fail("没有拿到认证后的会话，不该判定为成功")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.SESSION, e.kind)
            // 文案要能指出「没有拿回业务会话」，而不是含糊的「会话失效」
            assertTrue("文案应说明没拿到业务会话，实际：${e.message}", e.message!!.contains("未下发业务会话"))
            // 并且要说清链路过没经过身份认证节点 —— 那正是定位问题的关键分叉
            assertTrue("文案应指出经过的身份认证节点", e.message!!.contains("identity.zju.edu.cn"))
        }
    }

    @Test
    fun 链路_停在认证页且无会话时抛SESSION并带出每一跳() {
        val chain = fakeChain(
            steps = listOf(
                FakeHop(302, location = "https://identity.zju.edu.cn/auth/realms/zju/protocol/cas/login?service=x"),
                FakeHop(303, location = "https://zjuam.zju.edu.cn/cas/login?service=y&state=z"),
                FakeHop(200, body = CAS_LOGIN_PAGE)
            )
        )
        val jar = CoursesInMemoryCookieJar()
        jar.addCookie(tgcCookie())

        try {
            ZjuCoursesClient(chain).establishCoursesSession(
                chain.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build(),
                jar
            )
            fail("停在 CAS 登录页不该判定为成功")
        } catch (e: ZjuException) {
            assertEquals(ZjuFailureKind.SESSION, e.kind)
            assertTrue("文案应指出停在认证页，实际：${e.message}", e.message!!.contains("统一身份认证页"))
            // 每一跳都要进文案 —— 卡在第几跳是唯一能把几种失败分开的信息
            assertTrue("文案应含逐跳信息，实际：${e.message}", e.message!!.contains("第1跳"))
            assertTrue(e.message!!.contains("第3跳"))
        }
    }
    @Test
    fun 链路_认证成功后拿到的是认证会话而不是匿名那个() {
        val todosJson = """
            {"todo_list":[
              {"id":"1","title":"实验报告","course_name":"大物","end_time":"2099-10-25T23:59:59+08:00","is_student":true}
            ]}
        """.trimIndent()

        val chain = fakeChain(
            steps = listOf(
                FakeHop(302, setCookies = listOf("session=ANONYMOUS"),
                    location = "https://identity.zju.edu.cn/auth/realms/zju/protocol/cas/login?service=x"),
                FakeHop(303, setCookies = listOf("AUTH_SESSION_ID=C"),
                    location = "https://identity.zju.edu.cn/auth/realms/zju/broker/cas-client/login?session_code=s&client_id=TronClass"),
                FakeHop(303, setCookies = listOf("CLIENT_URL=D"),
                    location = "https://zjuam.zju.edu.cn/cas/login?service=y&state=z"),
                // CAS 认了 TGC → 302 回 Keycloak 换票
                FakeHop(302, setCookies = listOf("JSESSIONID=E"), location = "https://identity.zju.edu.cn/auth/realms/zju/broker/cas-client/endpoint?ticket=ST-1"),
                FakeHop(303, setCookies = listOf("KC_RESTART=F"), location = "https://courses.zju.edu.cn/user/index?ST=ST-1"),
                // 回到 courses，这次的 session 才是认证后的
                FakeHop(200, setCookies = listOf("session=AUTHENTICATED"), body = "<html><body>学在浙大</body></html>")
            )
        )

        val jar = CoursesInMemoryCookieJar()
        jar.addCookie(tgcCookie())
        val client = chain.newBuilder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        ZjuCoursesClient(client).establishCoursesSession(client, jar)

        // 关键：拿到的必须是认证后的那一个，不能是第 0 跳的匿名值
        assertNotNull("认证后应持有 session", jar.findCookie("session"))
        assertEquals("AUTHENTICATED", jar.findCookie("session")!!.value)

        // 顺手确认解析路径也能吃下同一份正文
        assertEquals("实验报告", ZjuCoursesClient().parseTodoList(todosJson).single().title)
    }

    // ------------------------------------------------------------------
    // Cookie 罐
    // ------------------------------------------------------------------

    @Test
    fun cookieJar_清业务会话时保留TGC() {
        // 自动重登靠的就是这个 TGC：连它一起清就退化成再登一次密码。
        val jar = CoursesInMemoryCookieJar()
        val now = System.currentTimeMillis()
        jar.addCookie(Cookie.Builder().name("iPlanetDirectoryPro").value("tgc").domain("zju.edu.cn").path("/").expiresAt(now + 3600_000).build())
        jar.addCookie(Cookie.Builder().name("session").value("s").domain("courses.zju.edu.cn").path("/").expiresAt(now + 3600_000).build())
        jar.addCookie(Cookie.Builder().name("route").value("r").domain("courses.zju.edu.cn").path("/").expiresAt(now + 3600_000).build())

        jar.clearCoursesBusinessSession()

        assertNotNull("TGC 必须保留", jar.findCookie("iPlanetDirectoryPro"))
        assertNull("学在浙大的业务会话应被清掉", jar.findCookie("session"))
        assertNull("同域的 route 也应被清掉", jar.findCookie("route"))
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun tgcCookie() = cookie(
        ZjuSsoLogin.TGC_NAME, "TGC_VALUE", ZjuSsoLogin.SSO_DOMAIN
    )

    private fun cookie(name: String, value: String, domain: String): Cookie =
        Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain)
            .path("/")
            .expiresAt(System.currentTimeMillis() + 3600_000)
            .build()

    private data class FakeHop(
        val code: Int,
        val body: String = "",
        val location: String? = null,
        /** 真实服务器是每个 Cookie 一个 Set-Cookie 头；合成一个头会被解析成单个 Cookie。 */
        val setCookies: List<String> = emptyList()
    )

    /**
     * 按**顺序**返回预设响应。
     *
     * 用顺序而不是按 URL 匹配，是因为这条链里同一个 URL 可能被访问多次
     * （重试、回跳），按 URL 匹配会把两次访问混成一次，测出来的东西是假的。
     */
    private fun fakeChain(steps: List<FakeHop>): OkHttpClient {
        var index = 0
        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val request: Request = chain.request()
                val step = steps.getOrNull(index++) ?: run {
                    // fail() 返回 Unit，撑不起智能转换；补一句 Nothing 让编译器认下「到此为止」。
                    fail("链路比预期更长：多出来的一次请求 ${request.url}")
                    error("unreachable")
                }
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
        /** 与实测一致的关键片段：有 form、有 username/password、有 execution。 */
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
