package com.example.data.source.zju

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 学在浙大（`courses.zju.edu.cn`）待办客户端。
 *
 * ## 链路现状（2026-10-18 于模拟器实测，校网内）
 *
 * 学在浙大**已经换到 Keycloak 身份源**，未认证访问的实际跳转链是：
 *
 * ```
 * courses.zju.edu.cn/user/index
 *   →302→ identity.zju.edu.cn/auth/realms/zju/protocol/cas/login?…   ← Keycloak 协议登录
 *   →303→ identity.zju.edu.cn/auth/realms/zju/broker/cas-client/login?client_id=TronClass
 *   →303→ zjuam.zju.edu.cn/cas/login?service=…&state=…                ← 浙大 CAS
 *   →200→ 统一身份认证登录页
 * ```
 *
 * 三跳都在 `identity.zju.edu.cn` 上，靠 `session_code` / `AUTH_SESSION_ID` / `KC_RESTART`
 * 维持broker 会话，最后才回落到浙大 CAS。**这条链必须逐跳跟进**，
 * 少跟一跳或把中间跳当成终点，结果都是「看似登录成功，下一步被弹回登录页」。
 *
 * ## 两条曾经把问题藏起来的坑
 *
 * **1. 匿名会话被当成认证会话。**
 * `courses.zju.edu.cn` 对**未登录访客**也会下发一个 `session` Cookie（实测第 0 跳就有）。
 * 早先的成功判定是「`session` 存在即算登录成功」，而那个 Cookie 在第一跳就到手了，
 * 于是判定**永远成立**——SSO 根本没传过去也会被判成成功，随后待办接口回 401，
 * 用户看到「会话失效，请重试刷新」，被引向「重新登录 / 改密码」这个错误方向。
 * 现在只认「**离开过 courses 又回来之后**」才拿到的 `session`，那才是认证后的那一个。
 *
 * **2. 停在 courses 就返回成功。**
 * 原实现里 `if (currentUrl.host == "courses.zju.edu.cn") return` 是无条件的：
 * 哪怕这次响应根本没带会话，也照样宣称登录成功。已删除。
 *
 * ## 架构：密码只登一次
 *
 * 密码登录由 [ZjuSsoLogin] 统一负责，拿到 TGC 后本类只做「CAS service 握手」
 * 换学在浙大自己的业务 `session`。与上游 Celechron 一致。
 *
 * 会话类失败允许**自动重登一次**（不重新输密码，只重建业务会话），
 * 对应上游 `Courses.getTodo` 里的那一层重试。
 *
 * @see ZjuSsoLogin 密码登录 → TGC
 * @see CoursesAuthGuard 认证失效的判定（纯函数，可离线单测）
 */
open class ZjuCoursesClient(
    private val baseClient: OkHttpClient = defaultOkHttpClient()
) {
    /**
     * 拉取当前学生的学在浙大未交作业列表。
     * 抛出的异常均为带有中文提示的 [ZjuException]。
     */
    open suspend fun fetchTodos(
        studentId: String,
        password: String
    ): List<CoursesTodoItem> = withContext(Dispatchers.IO) {
        if (studentId.isBlank() || password.isBlank()) {
            throw ZjuException("学号与密码不能为空", ZjuFailureKind.CREDENTIAL)
        }

        try {
            val jar = CoursesInMemoryCookieJar()
            val client = baseClient.newBuilder()
                .cookieJar(jar)
                // 全部跳转手动跟：既要区分「哪一跳」也要区分「这是中间跳还是终态」，
                // 交给 OkHttp 自动跟就全看不清了。
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            // 密码只登这一次，下面两次业务会话重建都复用这个 TGC。
            jar.addCookie(ZjuSsoLogin(client).login(studentId, password))

            try {
                establishCoursesSession(client, jar)
                fetchTodoListFromApi(client)
            } catch (first: ZjuException) {
                // 只有「会话没了」才值得原样重试一次；网络/服务端错误重试没有意义。
                if (first.kind != ZjuFailureKind.SESSION) throw first
                jar.clearCoursesBusinessSession()
                establishCoursesSession(client, jar)
                fetchTodoListFromApi(client)
            }
        } catch (e: ZjuException) {
            throw e
        } catch (e: Exception) {
            throw ZjuException("连接学在浙大失败：${e.message ?: "网络超时"}", ZjuFailureKind.NETWORK)
        }
    }

    /**
     * 拿着 TGC 跟完整条 SSO 链，直到拿到学在浙大自己的业务会话。
     *
     * 抛异常时会把**每一跳**的地址、状态码、Cookie 名字带进 message。
     * 这不是调试日志而是用户可见文案：这个环节失败时界面只能显示一句话，
     * 而「卡在第几跳」是唯一能把「浙大改了链路」「Cookie 没传过去」
     * 「TGC 本身就没生效」三者分开的信息。
     */
    internal fun establishCoursesSession(
        client: OkHttpClient,
        jar: CoursesInMemoryCookieJar
    ) {
        val trace = ArrayList<String>()
        var current: HttpUrl = COURSES_ENTRY_URL.toHttpUrl()

        // 见类注释「坑 1」：匿名 session 与认证 session 必须分开记账。
        var leftCourses = false
        var authenticatedSession: Cookie? = null
        val infraHosts = LinkedHashSet<String>()

        for (hop in 0 until MAX_REDIRECT_HOPS) {
            val host = current.host
            if (host != COURSES_HOST) leftCourses = true
            if (host == IDENTITY_HOST || host == CAS_HOST) infraHosts += host

            val response = client.newCall(
                Request.Builder()
                    .url(current)
                    .get()
                    .header("User-Agent", ZjuSsoLogin.USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
            ).execute()

            val status = response.code
            val location = response.header("Location")
            val body = response.use { it.body?.string().orEmpty() }
            val responseCookies = Cookie.parseAll(current, response.headers)

            for (cookie in responseCookies) {
                jar.addCookie(cookie)
                // 只有「已经离开过 courses、又被引回来」之后拿到的 session 才是认证后的。
                // 第一跳那个匿名 session 正好落在 leftCourses == false 的那一侧。
                if (host == COURSES_HOST && cookie.name == COURSES_SESSION_COOKIE && leftCourses) {
                    authenticatedSession = cookie
                }
            }

            trace += "第${hop + 1}跳 $host HTTP $status" +
                "，下发 Cookie：${responseCookies.joinToString("/") { it.name }.ifEmpty { "无" }}" +
                (location?.let { "，跳往 ${it.take(80)}" } ?: "")

            // ---- 30x：中间跳，必须继续跟 ----
            if (status in 300..399) {
                if (location.isNullOrBlank()) {
                    throw ZjuException(
                        "学在浙大跳转缺少目标地址（$host HTTP $status）\n${trace.joinToString("\n")}",
                        ZjuFailureKind.SERVER
                    )
                }
                current = current.resolve(location)
                    ?: throw ZjuException(
                        "学在浙大的跳转地址无效\n${trace.joinToString("\n")}",
                        ZjuFailureKind.SERVER
                    )
                continue
            }

            // ---- 2xx：可能是 meta-refresh 中间跳，也可能是终态 ----
            if (status in 200..299) {
                val metaRefresh = extractMetaRefresh(body, current)
                if (metaRefresh != null) {
                    trace += "  （HTTP 200 meta-refresh 中间跳）"
                    current = metaRefresh
                    continue
                }

                if (host == COURSES_HOST) {
                    // 落回学在浙大了，但仍可能是「又被弹回来的登录页」——正文会说话。
                    if (CoursesAuthGuard.bodyIsLoginPage(body)) {
                        throw ZjuException(
                            "学在浙大把会话退回了登录页，请重新登录\n${trace.joinToString("\n")}",
                            ZjuFailureKind.SESSION
                        )
                    }
                    if (authenticatedSession == null) {
                        val via = if (infraHosts.isEmpty()) {
                            "跳转链没有经过任何统一身份认证节点"
                        } else {
                            "跳转链经过了 ${infraHosts.joinToString("、")}，但没有拿回学在浙大的业务会话"
                        }
                        throw ZjuException(
                            "学在浙大未下发业务会话（$via），请重新登录\n${trace.joinToString("\n")}",
                            ZjuFailureKind.SESSION
                        )
                    }
                    return
                }

                // 停在身份认证节点 = 这条链没走完。
                throw ZjuException(
                    "学在浙大登录停在统一身份认证页（$host），请重新登录\n${trace.joinToString("\n")}",
                    ZjuFailureKind.SESSION
                )
            }

            // ---- 4xx/5xx ----
            if (status == 401 || status == 403) {
                throw ZjuException(
                    "学在浙大拒绝了当前会话（HTTP $status），请重新登录\n${trace.joinToString("\n")}",
                    ZjuFailureKind.SESSION
                )
            }
            throw ZjuException(
                "访问学在浙大失败（HTTP $status）\n${trace.joinToString("\n")}",
                ZjuFailureKind.SERVER
            )
        }

        throw ZjuException(
            "学在浙大跳转跳数过多，仍未建立业务会话\n${trace.joinToString("\n")}",
            ZjuFailureKind.SERVER
        )
    }

    private fun fetchTodoListFromApi(client: OkHttpClient): List<CoursesTodoItem> {
        val request = Request.Builder()
            .url(COURSES_TODOS_URL.toHttpUrl())
            .get()
            .header("User-Agent", ZjuSsoLogin.USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        val response = client.newCall(request).execute()
        val text = response.use { it.body?.string().orEmpty() }
        val status = response.code
        val location = response.header("Location")

        // 判定顺序：**先认会话没了，再认数据不对**。
        // 反过来的话，HTTP 200 的登录页会被当成「JSON 解析失败」，
        // 用户看到的是格式错误，而真实原因是登录态没了。
        if (status == 401 || status == 403 || status == 901 ||
            CoursesAuthGuard.locationIndicatesAuthFailure(location)
        ) {
            throw ZjuException("学在浙大会话已失效，请重新登录", ZjuFailureKind.SESSION)
        }
        if (!response.isSuccessful) {
            throw ZjuException("获取学在浙大待办失败（HTTP $status）", ZjuFailureKind.SERVER)
        }
        if (CoursesAuthGuard.bodyIsLoginPage(text)) {
            throw ZjuException("学在浙大会话已失效，请重新登录", ZjuFailureKind.SESSION)
        }

        return parseTodoList(text)
    }

    /**
     * 把待办接口的 JSON 解析成列表。
     *
     * 抽出来是为了能**离线**单测：这段是整条链上唯一按上游字段口径做取舍的地方
     * （哪些条目该留、按什么排序），而它的输入是一段固定结构的 JSON，
     * 完全没有理由非要联网才验证得了。
     *
     * @throws ZjuException 会话失效（[ZjuFailureKind.SESSION]）或数据不可用（[ZjuFailureKind.DATA_INVALID]）
     */
    internal fun parseTodoList(text: String): List<CoursesTodoItem> {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ZjuException(
                "学在浙大返回的数据无法解析，请稍后再试",
                ZjuFailureKind.DATA_INVALID
            )

        if (CoursesAuthGuard.jsonIndicatesAuthFailure(json)) {
            throw ZjuException("学在浙大会话已失效，请重新登录", ZjuFailureKind.SESSION)
        }

        val rawList = json.optJSONArray("todo_list") ?: return emptyList()
        val items = ArrayList<CoursesTodoItem>(rawList.length())
        var skippedByIdentity = 0

        for (i in 0 until rawList.length()) {
            val item = rawList.optJSONObject(i) ?: continue

            // `is_student` 表示「这条待办确实是发给学生本人的」。
            // 助教身份下抓到的作业同样落在这个接口里（上游为此修过一次：
            // 「学在浙大作业抓取不应显示助教身份的作业」）。
            //
            // 字段**缺失**时按「保留」处理，与显式 false 区分开：
            // 一旦上游改了字段名，严格过滤会把整屏作业清空成「已交清」——
            // 那比多显示几条更糟，因为它让用户以为真的没事。
            if (item.has("is_student") && !item.optBoolean("is_student", true)) {
                skippedByIdentity++
                continue
            }

            val id = item.optString("id", "").ifBlank { i.toString() }
            val title = item.optString("title", "未命名作业")
            val courseName = item.optString("course_name", "未知课程")
            val endTime = if (item.has("end_time")) item.optString("end_time") else null

            items.add(CoursesTodoItem.fromRawApi(id, title, courseName, endTime))
        }

        if (skippedByIdentity > 0) {
            // 不静默丢弃：数量说明「接口里确实混了助教视角的作业」，是上游口径而非用户口味。
            android.util.Log.i(
                LOG_TAG,
                "学在浙大返回 ${rawList.length()} 条，$skippedByIdentity 条 is_student=false 已按助教视角过滤"
            )
        }

        // 严格按 DDL 升序排序：最靠近今天的最靠左；无截止时间的排在最后
        return items.sortedWith { a, b ->
            when {
                a.dueTimestamp == null && b.dueTimestamp == null -> 0
                a.dueTimestamp == null -> 1
                b.dueTimestamp == null -> -1
                else -> a.dueTimestamp.compareTo(b.dueTimestamp)
            }
        }
    }

    private fun extractMetaRefresh(body: String, current: HttpUrl): HttpUrl? {
        for (m in META_TAG_REGEX.findAll(body)) {
            val tag = m.value
            if (!REFRESH_ATTR_REGEX.containsMatchIn(tag)) continue
            val urlMatch = URL_ATTR_REGEX.find(tag) ?: continue
            val rawUrl = urlMatch.groupValues.getOrNull(1)?.replace("&amp;", "&") ?: continue
            if (rawUrl.isBlank()) continue
            val resolved = current.resolve(rawUrl) ?: continue
            if (resolved.isHttps && resolved.host.endsWith(".zju.edu.cn")) {
                return resolved
            }
        }
        return null
    }

    companion object {
        const val COURSES_HOST = "courses.zju.edu.cn"

        /** Keycloak 身份源。浙大统一认证迁移后，学在浙大先经它再回落到浙大 CAS。 */
        const val IDENTITY_HOST = "identity.zju.edu.cn"
        const val CAS_HOST = "zjuam.zju.edu.cn"
        const val COURSES_SESSION_COOKIE = "session"

        const val COURSES_ENTRY_URL = "https://courses.zju.edu.cn/user/index"
        const val COURSES_TODOS_URL = "https://courses.zju.edu.cn/api/todos"

        /**
         * 15 跳够用，但要留余量：实测未认证链路就已经要 4 跳，
         * 认证成功时还要多出「CAS 回跳 → Keycloak 换票 → 回到 courses」这一段。
         */
        const val MAX_REDIRECT_HOPS = 20

        internal const val LOG_TAG = "ZjuCoursesClient"

        private val META_TAG_REGEX = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val REFRESH_ATTR_REGEX = Regex("""http-equiv\s*=\s*["']?refresh""", RegexOption.IGNORE_CASE)
        private val URL_ATTR_REGEX = Regex("""url\s*=\s*["']?([^"' >;]+)""", RegexOption.IGNORE_CASE)

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 「这个响应是不是在说登录态没了」的判定。
 *
 * 全部是纯函数、**刻意不碰网络**，因为它是整条链上唯一能把
 * 「会话失效」和「数据格式变了」分开的地方：
 * 认错了前者，用户会去反复改密码直到撞上锁号；
 * 认错了后者，一个好好的接口会被报成登录问题。
 *
 * 口径对齐上游 Celechron 的 `bodyIndicatesAuthenticationFailure` /
 * `locationIndicatesAuthenticationFailure` / `jsonIndicatesAuthenticationFailure`。
 */
internal object CoursesAuthGuard {

    /**
     * Location 指向认证节点。
     *
     * `identity.zju.edu.cn` 与 `auth/realms/zju` 是 Keycloak 迁移后的形态，
     * 只认老的 `cas/login` 会漏掉「被 Keycloak 弹回」这一种。
     */
    fun locationIndicatesAuthFailure(location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        val lower = location.lowercase()
        return lower.contains("identity.zju.edu.cn") ||
            lower.contains("cas/login") ||
            lower.contains("auth/realms/zju")
    }

    /** 正文是不是一张登录页。 */
    fun bodyIsLoginPage(body: String): Boolean {
        val lower = body.lowercase()
        if (lower.length > 20_000 && !lower.contains("<form")) return false
        return lower.contains("cas/login") ||
            lower.contains("login_ssologin") ||
            lower.contains("统一身份认证") ||
            lower.contains("请先登录") ||
            lower.contains("登录已失效") ||
            lower.contains("unauthorized") ||
            lower.contains("authserver") ||
            (lower.contains("name=\"username\"") && lower.contains("name=\"password\""))
    }

    /**
     * JSON 正文自报的认证失败。
     *
     * 上游用 `kickout: 1` 表达「会话被顶掉」，它返回的 HTTP 仍是 200，
     * 只看状态码会当成正常数据，于是列表莫名其妙变空。
     */
    fun jsonIndicatesAuthFailure(json: JSONObject?): Boolean {
        if (json == null) return false
        if (json.optInt("kickout", 0) == 1) return true
        if (asInt(json, "code") == 401 || asInt(json, "code") == 403 || asInt(json, "code") == 901) return true
        if (asInt(json, "status") == 401 || asInt(json, "status") == 403 || asInt(json, "status") == 901) return true

        val message = listOf("message", "msg", "error")
            .mapNotNull { json.optString(it, "").ifBlank { null } }
            .joinToString(" ")
            .lowercase()
        val authWording = message.contains("登录") ||
            message.contains("认证") ||
            message.contains("过期") ||
            message.contains("unauthorized") ||
            message.contains("kickout") ||
            message.contains("token")
        if (!authWording) return false
        val success = json.optBoolean("success", true)
        val code = asInt(json, "code") ?: asInt(json, "status")
        return !success || (code != null && code != 200)
    }

    /** JSON 里的数字可能是字符串（上游 `asInt` 就这么干），两种都要认。 */
    private fun asInt(json: JSONObject, key: String): Int? = when (val v = json.opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }
}

/**
 * 针对多域重定向的内存 CookieJar，支持 RFC 域名匹配与路径匹配。
 */
internal class CoursesInMemoryCookieJar : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            addCookie(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookiesFor(url)

    @Synchronized
    fun addCookie(cookie: Cookie) {
        val key = makeKey(cookie)
        if (cookie.expiresAt < System.currentTimeMillis()) {
            // 服务端下发删除指令：必须真的删掉。留着的话下一次请求还会带上旧值。
            cookies.remove(key)
        } else {
            cookies[key] = cookie
        }
    }

    @Synchronized
    fun cookiesFor(url: HttpUrl): List<Cookie> {
        val host = url.host.lowercase()
        val path = url.encodedPath
        val now = System.currentTimeMillis()

        return cookies.values.filter { cookie ->
            if (cookie.expiresAt < now) return@filter false
            // Secure Cookie 不能走明文回发。漏掉这条不会让认证失败，
            // 但会让排障时看到「明明发了 Cookie 却像是没发」。
            if (cookie.secure && !url.isHttps) return@filter false

            val domain = cookie.domain.lowercase().removePrefix(".")
            if (host != domain && !host.endsWith(".$domain")) return@filter false

            val cookiePath = if (cookie.path.isEmpty()) "/" else cookie.path
            path.startsWith(cookiePath)
        }
    }

    /**
     * 只清学在浙大的业务会话，**保留 TGC**。
     *
     * 自动重登靠的就是这个 TGC：重建业务会话不需要重新输密码，
     * 而把 TGC 一起清掉就退化成再登一次，白白多一次密码提交。
     */
    @Synchronized
    fun clearCoursesBusinessSession() {
        val doomed = cookies.filterKeys { key ->
            val domain = key.substringAfter('|').substringBefore('|')
            domain.trimStart('.').endsWith(COURSES_HOST)
        }.keys
        cookies.keys.removeAll(doomed)
    }

    /** 只按名字取当前仍然有效的 Cookie，取不到返回 null。 */
    @Synchronized
    fun findCookie(name: String): Cookie? {
        val now = System.currentTimeMillis()
        return cookies.values.lastOrNull { it.name == name && it.expiresAt >= now }
    }

    private fun makeKey(cookie: Cookie): String {
        val domain = cookie.domain.lowercase().removePrefix(".")
        val path = if (cookie.path.isEmpty()) "/" else cookie.path
        return "${cookie.name}|$domain|$path"
    }

    private companion object {
        const val COURSES_HOST = ZjuCoursesClient.COURSES_HOST
    }
}
