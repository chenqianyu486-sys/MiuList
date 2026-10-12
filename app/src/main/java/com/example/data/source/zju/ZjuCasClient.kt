package com.example.data.source.zju

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cookie
import java.util.concurrent.TimeUnit

/**
 * 浙大 CAS 登录 + 课表接口的 HTTP 客户端。
 *
 * 全部照抄 zju-ical `pkg/zjuam/zjuam.go` 与
 * `pkg/zjuservice/ugrsical/ugrsical.go` 的流程：
 *
 * 1. `GET https://zjuam.zju.edu.cn/cas/login?service=http%3A%2F%2Fappservice.zju.edu.cn%2F`
 *    → 从页面里切出 `execution` 这个 CSRF 值；
 * 2. `GET https://zjuam.zju.edu.cn/cas/v2/getPubKey` → RSA 公钥；
 * 3. `POST https://zjuam.zju.edu.cn/cas/login`（密码已用公钥加密）；
 * 4. `POST http://appservice.zju.edu.cn/zju-smartcampus/zdydjw/api/kbdy_cxXsZKbxx` 取课表。
 *
 * 错误文案也照抄上游的措辞：浙大在密码错时会返回一段 HTML 说「用户名或密码错误」，
 * 而 CAS 返回非 200 往往**也是**凭据问题（上游注释明确警告这时继续重试有锁号风险）。
 * 自己编一句「网络错误」会把用户引向错误的方向。
 */
class ZjuCasClient(
    private val client: OkHttpClient = defaultClient()
) {

    /** 登录成功。抛出的都是带中文文案的 [ZjuException]。 */
    suspend fun login(studentId: String, password: String) = withContext(Dispatchers.IO) {
        val loginUrl = LOGIN_PAGE_URL.toHttpUrl()

        val page = client.newCall(Request.Builder().url(loginUrl).get().build()).execute()
        val pageBody = page.use { it.body?.string().orEmpty() }
        val execution = extractExecution(pageBody)
            ?: throw ZjuException("浙大登录页结构变了，请稍后再试")

        val pubKeyJson = client.newCall(
            Request.Builder().url(PUBLIC_KEY_URL.toHttpUrl()).get().build()
        ).execute().use { it.body?.string().orEmpty() }

        val modulus = extractJsonString(pubKeyJson, "modulus")
        val exponent = extractJsonString(pubKeyJson, "exponent")
        if (modulus == null || exponent == null) {
            throw ZjuException("浙大登录公钥取不到，请稍后再试")
        }
        val encrypted = ZjuRsa.encryptPassword(password, modulus, exponent)

        val response = client.newCall(
            Request.Builder()
                .url(LOGIN_URL.toHttpUrl())
                .post(
                    FormBody.Builder()
                        .add("username", studentId)
                        .add("password", encrypted)
                        .add("authcode", "")
                        .add("execution", execution)
                        .add("_eventId", "submit")
                        .build()
                )
                .build()
        ).execute()

        val body = response.use { it.body?.string().orEmpty() }
        // 上游把这几条判在前面：浙大登录失败时返回的是 200 + 一段错误提示 HTML
        when {
            body.contains("账号被锁定") ->
                throw ZjuException("密码错误次数过多，账号被锁定，请稍后再试")

            body.contains("用户名或密码错误") || body.contains("异常登录") ->
                throw ZjuException("学号或密码错误")

            !response.isSuccessful ->
                throw ZjuException("学号或密码错误（HTTP ${response.code}）")
        }
    }

    /**
     * 取课表。返回的是教务网的原始 JSON，交由 [ZjuScheduleMapper] 解。
     *
     * @param termQuery 学期查询串，见 [ZjuTerms]。
     */
    suspend fun fetchSchedule(academicYear: String, termQuery: String, studentId: String): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("xn", academicYear)
                .add("xq", termQuery)
                .add("xh", studentId)
                .build()
            val response = client.newCall(
                Request.Builder()
                    .url(SCHEDULE_API_URL.toHttpUrl())
                    .post(body)
                    .build()
            ).execute()

            val text = response.use { it.body?.string().orEmpty() }
            if (!response.isSuccessful) {
                throw ZjuException("教务网没返回课表（HTTP ${response.code}）")
            }
            if (text.isBlank()) {
                throw ZjuException("教务网没返回课表，试试在浙大钉的个人课表里确认")
            }
            text
        }

    /**
     * 从登录页里切出 CSRF 值。
     *
     * 上游用的是字节索引 `execution"` … `" />`，这里用等价的字符串查找。
     * 切不到就抛错——继续走下去必然登录失败，报「学号或密码错误」会误导用户。
     */
    private fun extractExecution(pageBody: String): String? {
        val startMarker = "execution\""
        val endMarker = "\" />"
        val start = pageBody.indexOf(startMarker)
        if (start < 0) return null
        val from = start + startMarker.length
        val end = pageBody.indexOf(endMarker, from)
        if (end < 0) return null
        return pageBody.substring(from, end).takeIf { it.isNotBlank() }
    }

    /** 公钥接口返回的就是 `{"modulus":"<hex>","exponent":"<hex>"}`。 */
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

    companion object {
        private const val LOGIN_PAGE_URL =
            "https://zjuam.zju.edu.cn/cas/login?service=http%3A%2F%2Fappservice.zju.edu.cn%2F"
        private const val LOGIN_URL = "https://zjuam.zju.edu.cn/cas/login"
        private const val PUBLIC_KEY_URL = "https://zjuam.zju.edu.cn/cas/v2/getPubKey"
        private const val SCHEDULE_API_URL =
            "http://appservice.zju.edu.cn/zju-smartcampus/zdydjw/api/kbdy_cxXsZKbxx"

        /**
         * 会话 Cookie 手动存：CAS 登录要带着 cookie 才能接着调 appservice。
         *
         * 不用 OkHttp 自带的 `CookieJar.NO_COOKIES`，也不用 `JavaNetCookieJar` ——
         * 后者会按 RFC 处理域/path，而浙大这里的 cookie 行为比较随性，
         * 宽松地全量带上更稳，也更好排查。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cookieJar(InMemoryCookieJar())
            .build()
    }
}

/** 全量保存 / 全量回发的 CookieJar。浙大的 CAS 会话就靠这一条链。 */
private class InMemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { store[it.name] = it }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
        synchronized(store) { store.values.toList() }
}

/** 所有对用户可见的失败原因。文案照搬上游语义，见 [ZjuCasClient] 的类注释。 */
class ZjuException(message: String) : Exception(message)

/** 浙大的学期枚举与教务网查询串。与 zju-ical `pkg/zjuservice/zjuconst/extension.go` 一致。 */
object ZjuTerms {
    /** `Term` 整数 → 校历里的文案。 */
    val LABELS = listOf(
        "秋学期", "冬学期", "短学期A", "小学期", "春学期", "夏学期", "短学期B"
    )

    /**
     * `Term` 整数 → 取课表接口的 `xq` 查询串。
     *
     * 注意这里的形状不是数字，是 `1|秋` 这种带汉字的串（浙大教务网的老接口约定）。
     */
    fun queryOf(term: Int): String = when (term) {
        0 -> "1|秋"
        1 -> "1|冬"
        2 -> "1|短"
        3 -> "1|暑"
        4 -> "2|春"
        5 -> "2|夏"
        6 -> "2|短"
        else -> ""
    }

    fun labelOf(term: Int): String = LABELS.getOrElse(term) { "未知学期" }
}