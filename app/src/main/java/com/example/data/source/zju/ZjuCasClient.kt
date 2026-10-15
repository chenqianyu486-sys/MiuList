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
 * ## 接口换过一版：appservice 已退役，现在走 zdbk
 *
 * 老实现打的是 `http://appservice.zju.edu.cn/zju-smartcampus/zdydjw/api/kbdy_cxXsZKbxx`
 * （上游 zju-ical `pkg/zjuservice/ugrsical/ugrsical.go` 的 `kAppServiceGetWeekClassInfo`）。
 * 那个接口**现在已经下线**：认证完全正常时固定在约 10 秒后回 **504**，
 * 响应体是网关的通用错误页；未认证时 60–100ms 就回 `203 登录异常`。
 * 直连、代理、换参数组合、复现上游注释里那个 "trick"、调用上游从未被接线的
 * `UgrsExtraLogin` —— 全部无效。appservice 的课程接口随浙大教务系统迁移一起废弃了。
 *
 * 现在用的是活跃维护的 `Xecades/zju-ical-py`（`zjuam/ugrs.py`）里那条**能用**的路径：
 *
 * ```
 * CAS service = https://zdbk.zju.edu.cn/jwglxt/xtgl/login_ssologin.html
 * 课表        = POST https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html
 * 参数        = xnm(学年) + xqm(学期)     ← 不是 xn/xq，而且**没有 xh**
 * ```
 *
 * 「没有 xh」是最容易踩的一处：新接口靠 CAS 会话识别学生，
 * 再传一个 `xh` 上去并不会更安全，只会让后端拿不准该信哪个。
 *
 * 登录成功后的落点是 `zdbk.zju.edu.cn/jwglxt/xtgl/index_initMenu.html?jsessionid=…`，
 * 这也是 [login] 判断成败的依据（见那里的注释）。
 *
 * 错误文案借鉴上游的措辞：浙大在密码错时返回的是 **HTTP 200 + 一段 HTML**，
 * 所以**光看状态码什么都判断不出来**，必须去读正文。
 * 但也不能反过来说「非 200 就是密码错」——网关超时、回跳异常也走同一条路，
 * 一律报「学号或密码错误」会让用户去反复改密码、撞上锁号。
 * 下面 [login] 的分支就是按这条分界写的：**只有正文明确说是凭据问题才这么说**，
 * 其余各归各类，真实原因见 [casFailureReason]。
 */
open class ZjuCasClient(
    private val client: OkHttpClient = defaultClient()
) {

    /** 登录成功。抛出的都是带中文文案的 [ZjuException]。[open] 供单测替换。 */
    open suspend fun login(studentId: String, password: String) = withContext(Dispatchers.IO) {
        try {
            loginImpl(studentId, password)
        } catch (e: ZjuException) {
            throw e
        } catch (e: Exception) {
            // **必须挡住这一层**：OkHttp 的 IOException 会带着原始英文和 URL 冒出来，
            // 形如 `unexpected end of stream on https://zjuam.zju.edu.cn/cas/login?...`，
            // 用户完全看不懂，更不知道该检查网络还是该找浙大。实测（2026-10-13，
            // 模拟器在宿主代理不通的环境下）这个原始异常是界面上唯一能看到的东西。
            throw networkFailure(e)
        }
    }

    private suspend fun loginImpl(studentId: String, password: String) {
        val loginUrl = LOGIN_PAGE_URL.toHttpUrl()

        val page = client.newCall(Request.Builder().url(loginUrl).get().build()).execute()
        val pageBody = page.use { it.body?.string().orEmpty() }
        val execution = extractExecution(pageBody)
            ?: throw ZjuException("浙大登录页结构变了，请稍后再试", ZjuFailureKind.SERVER)
        // 页面上的 <input name="ys" value="5">：认证方式标记（5 = 密码）。
        // 缺了它 CAS 仍可能放行，但带上更贴近浏览器真实提交，少一分不确定性。
        val authType = LoginPageParser().input(pageBody, "ys") ?: "5"

        val pubKeyJson = client.newCall(
            Request.Builder().url(PUBLIC_KEY_URL.toHttpUrl()).get().build()
        ).execute().use { it.body?.string().orEmpty() }

        val modulus = extractJsonString(pubKeyJson, "modulus")
        val exponent = extractJsonString(pubKeyJson, "exponent")
        if (modulus == null || exponent == null) {
            throw ZjuException("浙大登录公钥取不到，请稍后再试", ZjuFailureKind.SERVER)
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
                        .add("ys", authType)
                        .build()
                )
                .build()
        ).execute()

        val body = response.use { it.body?.string().orEmpty() }
        // 走完重定向之后的最终地址。**这是判断登录成败唯一可靠的信号**（见下）。
        val finalUrl = response.request.url.toString()

        // 浙大登录失败时返回的是 200 + 一段错误提示 HTML，HTTP 状态码一律是 200。
        when {
            body.contains("账号被锁定") || body.contains("账号已被暂时锁定") ->
                throw ZjuException("密码错误次数过多，账号被暂时锁定，请等解锁后再试", ZjuFailureKind.LOCKED)

            body.contains("用户名或密码错误") || body.contains("异常登录") ->
                throw ZjuException("学号或密码错误", ZjuFailureKind.CREDENTIAL)

            // 5xx 一律**不**报「学号或密码错误」：execution 被解析坏时 CAS 同样回 500，
            // 那是我们的请求有问题，让用户去改密码只会把人带偏。
            response.code >= 500 ->
                throw ZjuException(
                    "浙大登录服务出错（HTTP ${response.code}），请稍后再试",
                    ZjuFailureKind.SERVER
                )

            // 403 在浙大这里是**账号被暂时锁定**（连续输错密码后的反制页），
            // 不是「没权限」。归到「密码错误」会让用户以为自己密码被改了。
            response.code == 403 ->
                throw ZjuException("密码错误次数过多，账号被暂时锁定，请等解锁后再试", ZjuFailureKind.LOCKED)

            // 剩下的 4xx（400/404/429…）**不是**凭据问题。
            // 早先这里一律报「学号或密码错误（HTTP xxx）」，与本类注释里那条
            // 「只有正文明确说是凭据问题才这么说」自相矛盾：用户会去反复改密码直到撞上锁号，
            // 而状态码从一开始就没说密码有任何问题。分类留 UNKNOWN，让界面别把矛头指向凭据。
            !response.isSuccessful ->
                throw ZjuException("浙大登录返回了 HTTP ${response.code}，原因未说明", ZjuFailureKind.UNKNOWN)
        }

        /**
         * 登录成功时 CAS 会 302 跳到我们请求里的 `service`，落到 zdbk 的
         * `index_initMenu.html`（带 jsessionid）；失败时它把登录页原样再吐一遍，
         * 最终地址原地不动停在 `/cas/login`。
         *
         * **必须有这一步**：只靠「响应里没有『密码错误』这几个字」判断是不够的 ——
         * CAS 还有别的失败形态（execution 过期、页闲置、会话不匹配…），
         * 都会以「200 + 一张长得正常的登录页」回来。那时 [login] 静默返回、
         * 上层接着去调课表接口，拿到的是一个莫名其妙的空课表或 404，
         * 把排查方向整个带偏 —— 这正是之前踩过的坑。
         */
        if (finalUrl.contains("/cas/login") || !finalUrl.contains("zdbk.zju.edu.cn")) {
            throw ZjuException(casFailureReason(body), ZjuFailureKind.CREDENTIAL)
        }
    }

    /**
     * 从「又吐回来」的登录页里抠出 CAS 的真实原因。
     *
     * 页面里真正的原因在 `class="error text-left"` 那个 div 里；拿不到就退回
     * URL 上的 `exception.message` 参数（CAS 会把错误码塞在回跳地址里）。
     * 两者都拿不到时**不要编一个「密码错误」**，如实说不知道。
     */
    private fun casFailureReason(body: String): String {
        ERROR_DIV_REGEX.find(body)?.groupValues?.get(1)?.let { raw ->
            val text = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank()) return text
        }
        EXCEPTION_PARAM_REGEX.find(body)?.groupValues?.get(1)?.let { encoded ->
            val decoded = runCatching {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        return "浙大登录未通过，请确认学号与密码"
    }

    /**
     * 取课表。返回的是教务网的原始 JSON，交由 [ZjuScheduleMapper] 解。
     *
     * **注意没有 studentId 参数**：zdbk 这条接口靠 CAS 会话识别学生，
     * 传不传 `xh` 都以会话为准。老的 appservice 接口要显式传 `xh`，那是已退役的协议。
     *
     * @param termQuery 学期查询串，见 [ZjuTerms]。
     */
/**
     * 按 `xqm` 取一份课表。
     *
     * [open] 是为了单测：真实响应只有联网才拿得到，
     * `ZjuImportServiceTest` 用一份手写响应把它替掉，不必每次出网。
     */
    open suspend fun fetchSchedule(academicYear: String, termQuery: String): String =
        withContext(Dispatchers.IO) {
            try {
                fetchScheduleImpl(academicYear, termQuery)
            } catch (e: ZjuException) {
                throw e
            } catch (e: Exception) {
                throw networkFailure(e)
            }
        }

    private suspend fun fetchScheduleImpl(academicYear: String, termQuery: String): String {
            val body = FormBody.Builder()
                .add("xnm", academicYear)
                .add("xqm", termQuery)
                .build()
            val response = client.newCall(
                Request.Builder()
                    .url(SCHEDULE_API_URL.toHttpUrl())
                    .post(body)
                    .build()
            ).execute()

            val text = response.use { it.body?.string().orEmpty() }
            if (!response.isSuccessful) {
                throw ZjuException(scheduleFailureReason(response.code), scheduleFailureKind(response.code))
            }
            // 200 也可能是 zdbk 的登录页（会话被顶掉），正文不是 JSON 就算没拿到。
            if (text.isBlank() || !text.trimStart().startsWith("{")) {
                if (text.contains("登录") || text.contains("login")) {
                    throw ZjuException("教务网会话已失效，请重新获取课表", ZjuFailureKind.SESSION)
                }
                throw ZjuException(
                    "教务网没返回课表，试试在浙大钉的个人课表里确认",
                    ZjuFailureKind.EMPTY
                )
            }
            return text
    }

    /**
     * 课表接口失败时的文案。
     *
     * - **401/403** —— zdbk 会话失效，要重新登录。
     * - **504** —— 浙大网关掐的。这条分支**保留但不该再是常态**：
     *   旧 appservice 接口退役前固定约 10 秒后 504，是它下线的信号；
     *   现在走 zdbk，504 意味着 zdbk 自己也出问题了。
     *
     * 抽成 internal 是为了能离线单测：这是用户在服务端故障期间**唯一**能看到的文案，
     * 一旦有人图省事把它并进 `else` 分支，唯一的提示就退化成
     * 「教务网没返回课表（HTTP 504）」——用户会以为是学号填错了。
     */
    internal fun scheduleFailureReason(code: Int): String = when (code) {
        504 -> "浙大教务网服务暂时不可用（网关超时），课表没取到，请稍后再试"
        401, 403 -> "教务网会话已失效，请重新获取课表"
        in 500..599 -> "浙大教务网出错（HTTP $code），请稍后再试"
        else -> "教务网没返回课表（HTTP $code）"
    }

    /**
     * 课表接口失败的**分类**，与 [scheduleFailureReason] 同一张表。
     *
     * 两者必须成对：文案是给人读的，分类是给界面决定「你该去做什么」用的。
     * 只有文案没有分类的话，界面只能猜——而猜错的方向通常是「去改密码」。
     */
    internal fun scheduleFailureKind(code: Int): ZjuFailureKind = when (code) {
        504, in 500..599 -> ZjuFailureKind.SERVER
        401, 403 -> ZjuFailureKind.SESSION
        // 4xx 里除 403 外的那些不是凭据问题，别把矛头指向学号密码。
        else -> ZjuFailureKind.EMPTY
    }

    /**
     * 把传输层异常翻译成用户看得懂、且**知道下一步该做什么**的中文。
     *
     * 这一层不是锦上添花：不加它，界面上出现的就是 OkHttp 的原始英文，
     * 比如 `unexpected end of stream on https://zjuam.zju.edu.cn/cas/login?service=...`
     * （2026-10-13 在模拟器上实测到过）。带 URL 的英文既看不懂又会让人以为是
     * 「学号填错了」，于是反复改密码——直到撞上锁号。
     *
     * 分类只按「现象」不按「原因」，因为我们拿不到真实原因（TLS 被中间设备掐断、
     * 超时、DNS 失败，在客户端看来都是 IOException）：
     *
     * - `SocketTimeoutException` / 超时类 → 慢或被墙，让用户等或换网络；
     * - 其余 `IOException` → 连不上，让用户检查网络；
     * - 其它 → 兜底，但**不编造**具体原因。
     */
    internal fun networkFailureReason(e: Throwable): String = when {
        isTimeout(e) -> "连接浙大超时，请检查网络后重试"
        e is java.io.IOException -> "连不上浙大登录服务，请检查网络（或代理/VPN）后重试"
        else -> "取课表时发生异常，请稍后再试"
    }

    /** 超时一路。原因看不准，但「这是网络那一侧的事」是确定的。 */
    private fun isTimeout(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.javaClass.simpleName.contains("Timeout") }

    /**
     * 传输层异常直接包成带分类的 [ZjuException]。
     *
     * 超时与连不上都归 [ZjuFailureKind.NETWORK] —— 对用户是同一件事：
     * 「本机到浙大这条路不通」，下一步都是先看网络/代理。
     * 只有完全认不出来的异常才落到 UNKNOWN，因为那时**不能**说成是网络问题。
     */
    private fun networkFailure(e: Throwable): ZjuException {
        val kind = when {
            isTimeout(e) || e is java.io.IOException -> ZjuFailureKind.NETWORK
            else -> ZjuFailureKind.UNKNOWN
        }
        return ZjuException(networkFailureReason(e), kind)
    }

    /**
     * 从登录页里切出 CSRF 值 `execution`。见 [LoginPageParser]。
     */
    private fun extractExecution(pageBody: String): String? =
        LoginPageParser().execution(pageBody)

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
        /**
         * CAS 的 service 指向 zdbk。**不是 appservice** —— 那是已退役的老路径，
         * 见类注释。老 service 下即使登录成功，课表接口也只会 504。
         */
        private const val ZDBK_SERVICE_URL =
            "https%3A%2F%2Fzdbk.zju.edu.cn%2Fjwglxt%2Fxtgl%2Flogin_ssologin.html"
        private const val LOGIN_PAGE_URL =
            "https://zjuam.zju.edu.cn/cas/login?service=$ZDBK_SERVICE_URL"
        private const val LOGIN_URL = "https://zjuam.zju.edu.cn/cas/login"
        private const val PUBLIC_KEY_URL = "https://zjuam.zju.edu.cn/cas/v2/getPubKey"

        /** 课表（本科）。学号由 CAS 会话带，请求体里**只有**学年与学期两个参数。 */
        private const val SCHEDULE_API_URL =
            "https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"

        /**
         * 会话 Cookie 手动存：CAS 登录要带着 cookie 才能接着调 zdbk。
         *
         * 用自己的 [InMemoryCookieJar] 而不是 `CookieJar.NO_COOKIES`（那是根本不存），
         * 也不用 `JavaNetCookieJar`（它会按 RFC 处理 path）。
         * 关键点是**仍然要看域名**，理由见 InMemoryCookieJar 的注释 ——
         * zjuam 下发的 cookie 与 zdbk 下发的会话（JSESSIONID / JSESSIONTOKEN / route）
         * 分属两个主机，串发会让 zdbk 的会话校验失败。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cookieJar(InMemoryCookieJar())
            .build()

        /**
         * CAS 把失败原因渲染在这个 div 里。
         *
         * 注意 Kotlin 的枚举名是 `DOT_MATCHES_ALL`，`DOT_ALL` 那是 `java.util.regex.Pattern`
         * 的叫法 —— 抄 Java 代码时会顺手写错，而且整个属性会连带变成错误类型，
         * 报出来的却是使用处「Unresolved reference groupValues」，很难往回找。
         */
        private val ERROR_DIV_REGEX =
            Regex("""class="error text-left"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

        /** CAS 也会把错误码塞在回跳 URL 的 `exception.message` 参数里。 */
        private val EXCEPTION_PARAM_REGEX = Regex("""exception\.message=([^&"]+)""")
    }
}

/**
 * 登录页 hidden input 的解析。抽成独立对象是为了能直接单测 ——
 * 它是整条登录链上唯一一个「靠切字符串」的地方，出错的后果又是 HTTP 500
 * 这种看不出所以然的响应，必须能脱离网络验证。
 */
internal class LoginPageParser {

    /** 切出 CSRF 值 `execution`。取不到返回 null。 */
    fun execution(page: String): String? = input(page, "execution")

    /**
     * 按 `name` 取 hidden input 的 `value`，取不到（或值为空）返回 null。
     *
     * 页面里它是标准 HTML 属性：`<input type="hidden" name="execution" value="…" />`。
     * 早先这里用的是上游 zju-ical 的字节索引写法（找 `execution"` 再找 `" />`），
     * 在浙大当前的页面上会切出 ` value="986dedd1-…" />` 这种带壳带尾的字符串，
     * 提交后 CAS 一律回 **HTTP 500** —— 而 500 长得和「凭据错」几乎一样，
     * 很容易被误判成密码错误。所以这里按属性名正经解析。
     *
     * 属性顺序、`type` 有无、结尾是 `>` 还是 `/>` 页面都变过，匹配一律放宽。
     */
    private companion object {
        /**
         * name 与 value 的先后不固定（页面两种排版都出现过），所以各自独立匹配，
         * 再按 name 配对，而不是靠一条把顺序写死的正则。
         */
        private val INPUT_TAG_REGEX = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val ATTR_REGEX = Regex("""\b(name|value)\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

        fun attributesOf(tag: String): Map<String, String> =
            ATTR_REGEX.findAll(tag)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    }

    fun input(page: String, name: String): String? =
        INPUT_TAG_REGEX.findAll(page)
            .map { attributesOf(it.value) }
            .firstOrNull { it.containsKey("name") && it["name"] == name }
            ?.get("value")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}

/**
 * 内存 CookieJar，**按域名回发**。
 *
 * ## 为什么必须看域名（踩过的坑）
 *
 * 最早这里写的是「全量保存 / 全量回发」，理由是「浙大的 cookie 行为比较随性」。
 * 那个理由是猜的，代价很实：
 *
 * - CAS 在 `zjuam.zju.edu.cn` 下发 `JSESSIONID`（Path=/cas）和
 *   `_csrf`（Domain=**zju.edu.cn**，两边都该带）；
 * - 而课表接口在 `appservice.zju.edu.cn`。
 *
 * 全量回发会把属于 `zjuam.zju.edu.cn` 的 `JSESSIONID` 也塞给 appservice。
 * 实测表现是：未登录时该接口秒回 `203 登录异常`（60ms），
 * 带上这套「张冠李戴」的 Cookie 之后变成 **504 网关超时**，
 * 而且登录本身是成功的 —— 排查时极易误判成「登录没成功」或「接口挂了」。
 *
 * 所以这里按 RFC 6265 的域名匹配规则回发：
 * `host == domain || host.endsWith("." + domain)`。
 * TGC 的 Domain 是 `zju.edu.cn`，所以它照样能覆盖 appservice，会话链不会断。
 */
internal class InMemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { store[it.name] = it }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
        synchronized(store) {
            val host = url.host
            store.values.filter { cookie ->
                val domain = cookie.domain.removePrefix(".").lowercase()
                val h = host.lowercase()
                h == domain || h.endsWith(".$domain")
            }
        }
}

/**
 * 失败分类。**抛出时就带上，不要事后拿文案去猜。**
 *
 * ## 为什么必须分类，而不能靠匹配中文文案
 *
 * 早先 [com.example.data.sync.ScheduleSyncManager] 是用
 * `msg.contains("学号或密码错误") || msg.contains("锁定")` 判的。
 * 那样写的代价不是「写起来短」，而是**分类和文案绑死了**：
 * 谁把上面 [ZjuCasClient] 里那句「连接浙大超时」改成「请求超时，请稍后再试」，
 * 分支就悄悄失效，于是「稍后重试」变成「去改密码」——
 * 而连续改密码正是账号被锁的成因。
 *
 * 更糟的是它没法表达界面真正要区分的东西：
 * 「数据换了但没生效」和「换密码也没用，浙大自己挂了」对用户是完全不同的两件事，
 * 前者能靠重新输入解决，后者怎么点都没用。
 * 所以分类得是**抛出点自己声明的事实**，文案只是它的展示。
 */
enum class ZjuFailureKind {
    /** 学号或密码不对。用户能自己解决：改凭据。 */
    CREDENTIAL,

    /** 密码错太多次被锁。**再试只会延长锁定期**，必须明确劝阻。 */
    LOCKED,

    /** CAS 会话被顶掉 / 课表接口要求重新登录。重新登录即可，与密码对错无关。 */
    SESSION,

    /** 网络连不上、被代理或 VPN 掐断、超时。 */
    NETWORK,

    /** 浙大自己出问题：5xx、网关超时、页面结构变了。 */
    SERVER,

    /** 学期选错，或校历配置里没有这一期。换学期能解决。 */
    TERM,

    /** 云端确实没返回这张课表。 */
    EMPTY,

    /**
     * 云端数据拉下来了，但**没通过我们自己的校验**：字段缺失、学期自相矛盾之类。
     *
     * 这条不属于「浙大那边出了什么事」，是拉回来的东西用不了，
     * 所以单列一类 —— 界面要说的是「换学期 / 改用文件导入」，
     * 而不是「稍后重试」，重试一百次结果一样。
     */
    DATA_INVALID,

    /** 兜底。**不许编造**具体原因，只如实说「不知道」。 */
    UNKNOWN;

    /**
     * 短标题：界面上「是什么」那一行。
     *
     * 放在枚举上而不是让各处自己写，是因为曾同时存在过两份映射
     * （弹层一份、Snackbar 一份），措辞迟早分叉成「网络错误」和「连不上浙大」，
     * 用户会以为是两回事。**一个分类只能有一个名字。**
     */
    val title: String
        get() = when (this) {
            CREDENTIAL -> "学号或密码不对"
            LOCKED -> "账号被暂时锁定"
            SESSION -> "登录状态已失效"
            NETWORK -> "连不上浙大服务器"
            SERVER -> "浙大教务系统异常"
            TERM -> "这学期取不到课表"
            EMPTY -> "云端没有返回课表"
            DATA_INVALID -> "云端数据没通过校验"
            UNKNOWN -> "同步失败"
        }

    /** 是否要引导用户去重新输入学号/密码。 */
    val needsCredentials: Boolean
        get() = this == CREDENTIAL || this == LOCKED || this == SESSION

    /** 责任在浙大那边，重试和改密码都没用，只能等。 */
    val isUpstream: Boolean
        get() = this == SERVER || this == EMPTY || this == TERM
}

/** 所有对用户可见的失败原因。文案照搬上游语义，见 [ZjuCasClient] 的类注释。 */
class ZjuException(
    message: String,
    /** 抛出点声明的事实分类。没给就是 [ZjuFailureKind.UNKNOWN]，不猜。 */
    val kind: ZjuFailureKind = ZjuFailureKind.UNKNOWN
) : Exception(message)

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