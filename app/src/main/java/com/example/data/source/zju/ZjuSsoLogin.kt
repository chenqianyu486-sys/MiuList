package com.example.data.source.zju

import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 浙大统一身份认证的**密码登录**：学号密码 → 全局 SSO Cookie（`iPlanetDirectoryPro`，下称 TGC）。
 *
 * ## 为什么要独立成一个类
 *
 * 浙大有多个子站（学在浙大 courses、教务网 zdbk、研究生院 yjsy……），
 * 它们共用**同一张**统一身份认证会话。各子站要做的只是拿 TGC 去做一次
 * "CAS service 握手"换取自己的业务会话 —— 密码**只需要输一次**。
 *
 * 早先每个客户端各自写了一套密码登录，于是「保存一次凭据、开两个功能、
 * 却登录两次」。上游 Celechron 为此专门修过一个 bug：
 * 「不同网络客户端共享 CAS 会话导致登录失败」（@n0sig）——
 * 两套独立登录互相顶掉会话，症状是随机失败、无法复现。
 * 密码登录因此收拢到这里，各子站只负责后半段握手。
 *
 * ## 提交哪些字段
 *
 * 提交 `username / password / execution / _eventId / rememberMe`，
 * 与上游 Celechron 的 `ZjuAm._getSsoCookie` 完全一致（该实现服务 6K+ 用户）。
 *
 * 登录页上其实还有 `authcode`（验证码）与 `ys`（认证方式，5=密码）两个 input，
 * 这里**故意不提交**：浙大只在判定需要挑战时才要求它们，
 * 带上空值与不带并非同一件事，而空值这条路没有实证。
 *
 * @see LoginPageParser 登录页解析（execution 的切法）
 * @see ZjuCoursesClient 拿到 TGC 之后怎么用它换学在浙大的业务会话
 */
open class ZjuSsoLogin(
    private val client: OkHttpClient
) {

    /**
     * 登录并返回可用于访问任意 `*.zju.edu.cn` 子站的 TGC。
     *
     * 返回的 Cookie 已把 Domain 固定成 `zju.edu.cn` —— CAS 下发时通常不带 Domain，
     * 那样它就只对 `zjuam.zju.edu.cn` 生效，传不到学在浙大去。
     * 抛出的都是带中文文案的 [ZjuException]。
     */
    open suspend fun login(studentId: String, password: String): Cookie {
        val pageBody = getCasLoginPage()
        val execution = LoginPageParser().execution(pageBody)
            ?: throw ZjuException("浙大统一身份认证页面结构变动，请稍后重试", ZjuFailureKind.SERVER)

        val pubKeyJson = client.newCall(
            Request.Builder().url(PUBLIC_KEY_URL.toHttpUrl()).get()
                .header("User-Agent", USER_AGENT)
                .build()
        ).execute().use { it.body?.string().orEmpty() }

        val modulus = extractJsonString(pubKeyJson, "modulus")
        val exponent = extractJsonString(pubKeyJson, "exponent")
        if (modulus == null || exponent == null) {
            throw ZjuException("无法获取浙大登录公钥，请稍后再试", ZjuFailureKind.SERVER)
        }

        val response = client.newCall(
            Request.Builder()
                .url(LOGIN_URL.toHttpUrl())
                .header("User-Agent", USER_AGENT)
                .post(
                    FormBody.Builder()
                        .add("username", studentId)
                        .add("password", ZjuRsa.encryptPassword(password, modulus, exponent))
                        .add("execution", execution)
                        .add("_eventId", "submit")
                        .add("rememberMe", "true")
                        .build()
                )
                .build()
        ).execute()

        val status = response.code
        val body = response.use { it.body?.string().orEmpty() }

        // 取最后一个未过期值：同一个响应里可能先下发删除指令再下发新值，
        // 挑中删除指令就会拿着空 TGC 去跑后面所有请求。
        val now = System.currentTimeMillis()
        val tgc = Cookie.parseAll(response.request.url, response.headers)
            .lastOrNull { it.name == TGC_NAME && it.value.isNotBlank() && it.expiresAt >= now }

        if (tgc != null) return tgc.asGlobalSsoCookie()

        throw ZjuException(casFailureMessage(status, body), casFailureKind(status, body))
    }

    /** 取登录页。execution 与它下发的 JSESSIONID 属于同一次表单会话，必须先取这一页。 */
    private fun getCasLoginPage(): String {
        val response = client.newCall(
            Request.Builder().url(LOGIN_URL.toHttpUrl()).get()
                .header("User-Agent", USER_AGENT)
                .build()
        ).execute()
        val body = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            // 光报状态码是没用的：实测这一跳回过 302，而「302」既可能是页面结构变了，
            // 也可能是「你已经登录了、所以这里没有登录页可给」——
            // 后者根本不是故障。把跳转目标带出来，两者一眼就能分开。
            val where = response.header("Location")?.takeIf { it.isNotBlank() }
            throw ZjuException(
                if (where != null) "浙大登录页被重定向到 $where，请稍后再试"
                else "浙大登录页打不开（HTTP ${response.code}），请稍后再试",
                ZjuFailureKind.SERVER
            )
        }
        return body
    }

    private fun Cookie.asGlobalSsoCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(SSO_DOMAIN)
        .path("/")
        .apply {
            if (secure) secure()
            if (httpOnly) httpOnly()
        }
        .build()

    /**
     * 登录失败的文案。
     *
     * 浙大密码错时回的是 **HTTP 200 + 一段 HTML**，只看状态码什么都判断不出来；
     * 但反过来「非 200 就是密码错」也不成立（网关故障、execution 被解析坏同样走这条路），
     * 一律报「密码错误」只会把用户推去反复改密码、直到撞上锁号。
     * 所以**只有正文明确说是凭据问题才这么说**。
     */
    private fun casFailureMessage(status: Int, body: String): String = when {
        body.contains("账号被锁定") || body.contains("账号已被暂时锁定") ->
            "密码错误次数过多，账号已被暂时锁定，请等解锁后再试"

        body.contains("用户名或密码错误") || body.contains("异常登录") ->
            "学号或密码错误"

        // 验证码是浙大侧的反制，不该让用户以为是密码错了。
        body.contains("验证码") || body.contains("动态口令") ->
            "浙大统一身份认证要求验证码，请先在浏览器登录一次再回 App 重试"

        status == 403 ->
            "密码错误次数过多，账号已被暂时锁定，请等解锁后再试"

        status >= 500 ->
            "浙大登录服务出错（HTTP $status），请稍后再试"

        else -> LoginPageParser().errorDivReason(body)
            ?: "浙大登录未通过，请确认学号与密码（HTTP $status）"
    }

    private fun casFailureKind(status: Int, body: String): ZjuFailureKind = when {
        body.contains("账号被锁定") || body.contains("账号已被暂时锁定") || status == 403 ->
            ZjuFailureKind.LOCKED

        status >= 500 -> ZjuFailureKind.SERVER

        body.contains("验证码") || body.contains("动态口令") -> ZjuFailureKind.CREDENTIAL

        // 其余（含 200 + 登录页原样吐回）都归到凭据：用户能自己解决的就是改凭据。
        else -> ZjuFailureKind.CREDENTIAL
    }

    private fun extractJsonString(json: String, key: String): String? {
        val marker = "\"$key\""
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + marker.length)
        if (colon < 0) return null
        val open = json.indexOf('"', colon)
        if (open < 0) return null
        val close = json.indexOf('"', open + 1)
        if (close < 0) return null
        return json.substring(open + 1, close).takeIf { it.isNotBlank() }
    }

    internal companion object {
        const val LOGIN_URL = "https://zjuam.zju.edu.cn/cas/login"
        const val PUBLIC_KEY_URL = "https://zjuam.zju.edu.cn/cas/v2/getPubKey"
        const val TGC_NAME = "iPlanetDirectoryPro"

        /**
         * TGC 的作用域。
         *
         * CAS 下发时常常不带 Domain，那样它在浏览器/客户端里只对 `zjuam.zju.edu.cn`
         * 有效 —— 而真正要用它的 `courses.zju.edu.cn`、`zdbk.zju.edu.cn` 都收不到，
         * 于是表现为「登录明明成功，后面每一步都被弹回登录页」。
         */
        const val SSO_DOMAIN = "zju.edu.cn"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36 Edg/115.0.1901.188"
    }
}
