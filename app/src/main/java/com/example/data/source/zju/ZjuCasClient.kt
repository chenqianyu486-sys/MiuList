package com.example.data.source.zju

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * ## 登录是三段，不是一次密码提交（2026-10-18 修正）
 *
 * 早先这里是「GET 登录页（**带** `service`）→ POST 密码（**不带** `service`）」。
 * `execution` 与 `service` 绑定，POST 少了这个必填参数，CAS **必回 HTTP 400**，
 * 且 400 的正文不解释原因 —— 界面上只剩一句「浙大登录返回了 HTTP 400，原因未说明」。
 *
 * 现在分三段，每段都有明确判据（与上游 Celechron 的 `Zdbk._doLogin` 同构）：
 *
 * 1. [ZjuSsoLogin] 用学号密码换全局 TGC —— **与学在浙大共用同一次密码登录**；
 * 2. 带 TGC 请求 zdbk 的 service，CAS 回一张带 `ticket` 的回调地址；
 * 3. 访问回调地址，zdbk **成对**签发 `JSESSIONID` + `route`。
 *
 * ⚠️ 密码提交那一跳必须**关掉自动跳转**：TGC 是下在 302 上的，
 * 跟着跳完只剩最后一跳的响应头，而那张头里没有 TGC。
 *
 * 错误文案借鉴上游的措辞：浙大在密码错时返回的是 **HTTP 200 + 一段 HTML**，
 * 所以**光看状态码什么都判断不出来**，必须去读正文。
 * 但也不能反过来说「非 200 就是密码错」——网关超时、回跳异常也走同一条路，
 * 一律报「学号或密码错误」会让用户去反复改密码、撞上锁号。
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

    /**
     * 教务网登录：**密码登一次 → TGC → 向教务网换票 → 建立 zdbk 会话**。
     *
     * ## 为什么不再直接 POST 密码
     *
     * 原来这里是「GET 登录页（**带** `service=教务网`）→ POST `/cas/login`（**不带** `service`）」。
     * 这个错配是**必挂**的：`execution` 是与 `service` 绑定的一次性令牌，
     * POST 少了这个必填参数，CAS 直接回 **HTTP 400**；而 400 的正文通常不解释原因，
     * 于是界面上只剩一句「浙大登录返回了 HTTP 400，原因未说明」——
     * 用户既不知道是网络、是浙大、还是自己的密码，只能反复重试。
     *
     * 现在与学在浙大共用 [ZjuSsoLogin]：GET/POST 都是不带 `service` 的 `/cas/login`，
     * 天然配对，先拿到全局 TGC；再拿 TGC 去教务网的 service 换票。
     * 三段各有明确的失败判据，不再有「静默成功」那回事。
     *
     * 与上游 Celechron 的 `Zdbk._doLogin` 同构。
     *
     * ## TGC 是复用还是重输（2026-10-18 修正）
     *
     * 罐里已经有 TGC 就**不再走密码登录**；换票/建会话被拒时才丢掉它重登一次。
     * 这两条都是被一次真实故障逼出来的：
     *
     * - **不重取**：已认证的 CAS 拿 TGC 打一个不带 `service` 的 `/cas/login` 时不给登录页、
     *   只回 302。而原代码把 302 一律当成「登录页打不开」——
     *   于是**上一次留下的 TGC 把这一次的真实失败原因盖掉**，界面永远显示
     *   「浙大登录页打不开（HTTP 302）」，真正出问题的那一跳反倒没留下任何线索。
     * - **不重登**：陈旧 TGC 不丢掉的话，「重试一次」会拿着同一个去换票、被同样地拒，
     *   无限循环，而界面却一直提示「请重新登录」，重试走的还是那条旧路。
     *
     * 罐由 [ZjuImportService] 长期持有，所以「上一次」就是同一次运行里的前一下。
     */
    private suspend fun loginImpl(studentId: String, password: String) {
        // 换票这两跳都要自己读 Location，所以这一段**不能**自动跟跳转。
        //
        // ⚠️ 密码登录也必须关掉自动跳转：TGC 是下在 302 上的，跟着跳完
        // 只剩最后一跳的响应头，而那张头里没有 TGC —— 这正是「登录成功却什么都拿不到」。
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val jar = client.cookieJar as? InMemoryCookieJar
            ?: throw ZjuException(
                "课表客户端的 Cookie 罐类型不对，无法建立教务网会话",
                ZjuFailureKind.UNKNOWN
            )

        // ── 第一步：拿到全局 TGC ──
        //
        // ⚠️ 罐里已经有 TGC 就**不要再走密码登录**（2026-10-18 修正）。
        // 已认证的 CAS 拿到 TGC 打一个不带 `service` 的 `/cas/login` 时不会给登录页，
        // 而是回 **302**；而原代码把 302 一律当成「登录页打不开」——
        // 于是**上一次尝试留下的 TGC 会把这一次的真实失败原因盖掉**，
        // 用户看到的永远是「浙大登录页打不开（HTTP 302）」，
        // 真正出问题的那一跳（换票 / 建会话）反而没有留下任何线索。
        // 罐由 [ZjuImportService] 长期持有，所以「上一次」就在几个小时前的同一次运行里。
        if (jar.findCookie(ZjuSsoLogin.TGC_NAME) == null) {
            jar.addCookie(ZjuSsoLogin(noRedirect).login(studentId, password))
        }

        // ── 第二步 + 第三步：换票，再用票换教务网自己的会话 ──
        establishZdbkSession(noRedirect, jar, studentId, password)
    }

    /**
     * 换票 + 建会话；被拒就丢掉旧 TGC、重新输一次密码，再试一次。
     *
     * ## 为什么这两步要一起兜着重试
     *
     * 换票拿回来的 **302 + ticket** 和建会话时的 **200** 都不能单独说明会话是好的，
     * 而且「服务端已经不认这个 TGC」在两步上表现不同：
     * - 换票那步：CAS 不认 TGC，就把登录页又吐一遍（**HTTP 200**，没有 ticket）；
     * - 建会话那步：票是换到了，但教务网没下发成对的 `JSESSIONID` + `route`。
     *
     * 只在第三步重试的话，第一种情况会漏掉 —— 而它恰恰是**卡死循环**的成因：
     * 陈旧 TGC 留在罐里，用户点几次「重试一次」都拿着同一个去换票、每次都被同样地拒，
     * 界面上却一直提示「请重新登录」，而重试走的还是那条旧路。
     */
    private suspend fun establishZdbkSession(
        noRedirect: OkHttpClient,
        jar: InMemoryCookieJar,
        studentId: String,
        password: String
    ) {
        try {
            establishZdbkSession(noRedirect, jar, requestZdbkTicket(noRedirect))
        } catch (first: ZjuException) {
            jar.removeCookie(ZjuSsoLogin.TGC_NAME)
            jar.addCookie(ZjuSsoLogin(noRedirect).login(studentId, password))
            try {
                establishZdbkSession(noRedirect, jar, requestZdbkTicket(noRedirect))
            } catch (second: ZjuException) {
                // 重登过还是不行，报第二次的：第一次那条已经被换掉的动作覆盖了。
                throw second
            }
        }
    }

    /**
     * 带 TGC 请求教务网的 service，CAS 应当 302 回一张带 `ticket` 的回调地址。
     *
     * 拿到 200 而不是 302，意味着 CAS 没认这次登录 —— 它把登录页又吐了一遍。
     * 这种情况下**继续往下走只会拿到一张空课表**，必须在这里停。
     */
    private fun requestZdbkTicket(noRedirect: OkHttpClient): HttpUrl {
        val response = noRedirect.newCall(
            Request.Builder().url(ZDBK_CAS_LOGIN_URL.toHttpUrl()).get()
                .header("User-Agent", ZjuSsoLogin.USER_AGENT)
                .build()
        ).execute()
        val status = response.code
        val location = response.header("Location")
        val body = response.use { it.body?.string().orEmpty() }

        if (status !in 300..399) {
            val gotLoginPage = CoursesAuthGuard.bodyIsLoginPage(body)
            throw ZjuException(
                if (gotLoginPage) "统一身份认证没有认出这次登录，请重新登录"
                else "换取教务网登录票据失败（HTTP $status）",
                if (gotLoginPage) ZjuFailureKind.SESSION else ZjuFailureKind.SERVER
            )
        }
        if (location.isNullOrBlank()) {
            throw ZjuException("换取教务网登录票据失败：跳转缺少目标地址", ZjuFailureKind.SERVER)
        }

        val callback = ZDBK_CAS_LOGIN_URL.toHttpUrl().resolve(location)
            ?: throw ZjuException("教务网回调地址无效", ZjuFailureKind.SERVER)

        // ticket 必须落在教务网上。放任任意地址跳转等于把会话令牌发给它指的地方。
        if (callback.host != ZDBK_HOST || callback.queryParameter("ticket").isNullOrBlank()) {
            throw ZjuException(
                "教务网返回的票据回调不对（${callback.host}），请稍后再试",
                ZjuFailureKind.SERVER
            )
        }
        return callback
    }

    /**
     * 用票据访问教务网，zdbk 会**成对**签发 `JSESSIONID` 与 `route`。
     *
     * ## 为什么不能只打一跳（2026-10-18 实测修正）
     *
     * 活体实测拿到的真实形态是：
     *
     * ```
     * GET /jwglxt/xtgl/login_ssologin.html?ticket=ST-…
     *   → 302  Location=/jwglxt/xtgl/index_initMenu.html?jsdm=06&_t=…
     *   → 本跳 **不**下发任何 Cookie
     * ```
     *
     * `index_initMenu.html` 是**教务网首页**（`jsdm=06` 是本科生院系代码）——
     * 票被接受了，这只是流程里的下一步，会话在**下一跳**才下发。
     * 原实现把回调这一跳的 302 当成失败，于是「票明明换到了，会话也建得起来」
     * 被报成「教务网登录失败（HTTP 302）」。
     *
     * 所以这里走一个**有界的同主机跳转游走**：成对的 `JSESSIONID` + `route`
     * 到手就算成功，不看状态码。反过来，CAS 拒票时会跳去 `zjuam/cas/filtererr.jsp`
     * 或登录页——那是**跨主机**，游走会立刻停下并报错，两种情形因此不会混淆。
     */
    private fun establishZdbkSession(
        noRedirect: OkHttpClient,
        jar: InMemoryCookieJar,
        ticketUrl: HttpUrl
    ) {
        var current = ticketUrl
        var hop = 0
        while (hop < MAX_SESSION_HOPS) {
            hop++
            val response = noRedirect.newCall(
                Request.Builder().url(current).get()
                    .header("User-Agent", ZjuSsoLogin.USER_AGENT)
                    .build()
            ).execute()
            val status = response.code
            val location = response.header("Location")
            val body = response.use { it.body?.string().orEmpty() }

            // 显式入库，不依赖 OkHttp 的自动 Cookie 存档。
            //
            // 自动存档确实也会存，但那是**链式副作用**：一旦响应不是经由正常的
            // BridgeInterceptor 产生（测试替身、被中间件短路），cookie 就凭空消失，
            // 而症状是「明明拿到了会话却说没有」——极难往回找。
            // 显式存一次，行为就只取决于这段代码本身。
            for (cookie in Cookie.parseAll(current, response.headers)) {
                jar.addCookie(cookie)
            }

            if (status == 401 || status == 403) {
                throw ZjuException("教务网拒绝了这次登录（HTTP $status），请重新登录", ZjuFailureKind.SESSION)
            }
            // 会话被顶掉时 zdbk 回的是 200 + 一张登录页，状态码完全正常，只能靠正文认。
            if (CoursesAuthGuard.bodyIsLoginPage(body) || body.contains("login_ssologin")) {
                throw ZjuException("教务网会话没建立，请重新登录", ZjuFailureKind.SESSION)
            }

            // 成对签发是硬要求：只拿到其中一个，后续课表接口的会话校验就过不去，
            // 而症状是「登录成功但课表 404/空」——看起来像接口坏了，其实差一个 cookie。
            //
            // ⚠️ 按**教务网这个域名**找，不要只按名字：统一认证也签发一个同名 JSESSIONID，
            // 不限定主机就会捡到那个，导致真正该有的 zdbk 会话缺着也判成通过。
            val missing = listOf(ZDBK_SESSION_COOKIE, ZDBK_ROUTE_COOKIE)
                .filter { jar.findCookie(it, ZDBK_HOST) == null }
            if (missing.isEmpty() && status in 200..299) return

            if (status !in 300..399) break
            val next = location?.takeIf { it.isNotBlank() }?.let { current.resolve(it) }
            if (next == null) break
            // 跨主机 = 教务网不要这条会话了（多半是 CAS 拒票或要求重新登录）。
            // 这里绝不跟：跟过去就是把会话令牌发给别的地方。
            if (next.host != ZDBK_HOST) {
                throw ZjuException(
                    "教务网把会话建立转到了 ${next.host}（${next.encodedPath}），请重新登录",
                    ZjuFailureKind.SESSION
                )
            }
            current = next
        }

        val missing = listOf(ZDBK_SESSION_COOKIE, ZDBK_ROUTE_COOKIE)
            .filter { jar.findCookie(it, ZDBK_HOST) == null }
        val at = current.encodedPath
        if (missing.isNotEmpty()) {
            throw ZjuException(
                "教务网没有下发会话（缺 ${missing.joinToString("、")}），停在 $at，请重新登录",
                ZjuFailureKind.SESSION
            )
        }
        throw ZjuException("教务网会话没有稳定下来（走了 $hop 跳仍停在 $at），请重新登录", ZjuFailureKind.SESSION)
    }

    // 旧实现（GET 登录页带 service、POST 不带 → CAS 回 HTTP 400）已整段移除；
    // 新流程见 loginImpl / requestZdbkTicket / establishZdbkSession。

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
            // 不自动跟跳转：会话被顶掉时 zdbk 会 302 到登录页，
            // 跟着跳完只会拿到一张 200 的登录页 HTML，被当成「没返回课表」——
            // 而真实原因是会话没了。停在 302 上才看得出它到底想跳去哪。
            val noRedirect = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val response = noRedirect.newCall(
                Request.Builder()
                    .url(SCHEDULE_API_URL.toHttpUrl())
                    .post(body)
                    .build()
            ).execute()

            val text = response.use { it.body?.string().orEmpty() }
            if (response.code in 300..399 ||
                CoursesAuthGuard.locationIndicatesAuthFailure(response.header("Location"))
            ) {
                throw ZjuException("教务网会话已失效，请重新获取课表", ZjuFailureKind.SESSION)
            }
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
     * 取考试安排。返回的是教务网的原始 JSON，交由 [ZjuExamMapper] 解。
     *
     * 与课表**用同一个会话**（同一个 CAS 登录、同一对 `JSESSIONID` / `route`），
     * 所以调用它之前不需要重新登录 —— [ZjuImportService] 会在同一次登录里
     * 先取课表再取考试。
     *
     * 失败的处理与课表**刻意不一样**：
     * 考试拉不到**不应该让整次同步失败**（考试只是课表之外的补充），
     * 所以这里抛出的异常由调用方按「尽力而为」处理。
     */
    open suspend fun fetchExams(): String = withContext(Dispatchers.IO) {
        try {
            fetchExamsImpl()
        } catch (e: ZjuException) {
            throw e
        } catch (e: Exception) {
            throw networkFailure(e)
        }
    }

    private suspend fun fetchExamsImpl(): String {
        // 与课表同一套：不自动跟跳转。会话被顶掉时 zdbk 会 302 到登录页，
        // 跟着跳完只会拿到一张 200 的登录页 HTML，被当成「没返回考试」。
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val response = noRedirect.newCall(
            Request.Builder()
                .url(EXAM_API_URL.toHttpUrl())
                .post(FormBody.Builder().build())
                .header("Referer", ZDBK_HOME_URL)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()
        ).execute()

        val text = response.use { it.body?.string().orEmpty() }
        if (response.code in 300..399 ||
            CoursesAuthGuard.locationIndicatesAuthFailure(response.header("Location"))
        ) {
            throw ZjuException("教务网会话已失效，请重新登录", ZjuFailureKind.SESSION)
        }
        if (!response.isSuccessful) {
            throw ZjuException("获取考试安排失败（HTTP ${response.code}）", ZjuFailureKind.SERVER)
        }
        val trimmed = text.trimStart()
        if (text.isBlank() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            if (text.contains("登录") || text.contains("login")) {
                throw ZjuException("教务网会话已失效，请重新登录", ZjuFailureKind.SESSION)
            }
            throw ZjuException("教务网没返回考试安排", ZjuFailureKind.EMPTY)
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

    companion object {
        /**
         * CAS 的 service 指向 zdbk。**不是 appservice** —— 那是已退役的老路径，
         * 见类注释。老 service 下即使登录成功，课表接口也只会 504。
         *
         * 这个值**整条链必须原样带在每一次请求上**：`service` 是 CAS 认票的凭据，
         * 少了它会直接回 HTTP 400 且不解释原因（这正是本次修掉的坑）。
         */
        private const val ZDBK_CAS_LOGIN_URL =
            "https://zjuam.zju.edu.cn/cas/login?service=" +
                "https%3A%2F%2Fzdbk.zju.edu.cn%2Fjwglxt%2Fxtgl%2Flogin_ssologin.html"

        private const val ZDBK_HOST = "zdbk.zju.edu.cn"

        /**
         * 建立教务网会话时最多跟几跳。
         *
         * 实测真实形态是「回调 → 302 → 教务网首页（这里才下发会话）」，两跳够用；
         * 留一点余量是因为不同院系入口页数可能不同，但**同主机**才有资格跟，
         * 跨主机一律立即报错。
         */
        private const val MAX_SESSION_HOPS = 6

        /** zdbk 成对签发的两个会话 Cookie，缺一不可。 */
        private const val ZDBK_SESSION_COOKIE = "JSESSIONID"
        private const val ZDBK_ROUTE_COOKIE = "route"

        /** 课表（本科）。学号由 CAS 会话带，请求体里**只有**学年与学期两个参数。 */
        private const val SCHEDULE_API_URL =
            "https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"

        /**
         * 考试（本科，个人考试查询）。
         *
         * `doType=query` 与 `queryModel.showCount` 都在查询串里，
         * 请求体是空的 —— 与课表接口不同，**不传学年与学期**：
         * 这个接口一次返回该生名下的全部考试（各学期都在里面），
         * 学期是每条记录自己的字段。
         *
         * `showCount` 给到 5000 是照抄教务网页面的表格设置，
         * 实测一学期的考试也就十几条。
         */
        private const val EXAM_API_URL =
            "https://zdbk.zju.edu.cn/jwglxt/xskscx/kscx_cxXsgrksIndex.html" +
                "?doType=query&queryModel.showCount=5000"

        /** 教务网首页。教务网的 Ajax 接口都要带这个 Referer 才认。 */
        private const val ZDBK_HOME_URL =
            "https://zdbk.zju.edu.cn/jwglxt/xtgl/index_initMenu.html"

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

        private val ERROR_DIV_REGEX =
            Regex("""class="error text-left"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    }

    fun input(page: String, name: String): String? =
        INPUT_TAG_REGEX.findAll(page)
            .map { attributesOf(it.value) }
            .firstOrNull { it.containsKey("name") && it["name"] == name }
            ?.get("value")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun errorDivReason(page: String): String? {
        val match = ERROR_DIV_REGEX.find(page) ?: return null
        val raw = match.groupValues.getOrNull(1) ?: return null
        val text = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.isNotBlank() }
    }
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

    /**
     * 按 **名字 + 域名 + path** 存，不只是名字。
     *
     * 只按名字存是原来就有的写法，当时看不出问题 —— 直到统一认证和教务网
     * **两边都签发一个叫 `JSESSIONID` 的 Cookie**，后者直接顶掉前者。
     * 罐里于是只剩一个，而 [loadForRequest] 又按域名过滤：教务网要开会话时，
     * 罐里存着的恰好是统一认证那个（域名对不上，被丢掉），
     * 表现是「明明换到票了，却报没有下发会话」。
     */
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { store[keyOf(it)] = it }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
        synchronized(store) {
            store.values.filter { matchesHost(it, url.host) }
        }

    /**
     * 塞进一个已经构造好的 Cookie。
     *
     * 存在的理由：[ZjuSsoLogin] 是**构造**出 TGC 再交回来的，并不会经过一次响应，
     * 所以 OkHttp 的自动存档钩子看不到它。而 TGC 不入罐，后续所有请求都发不出去。
     */
    @Synchronized
    fun addCookie(cookie: Cookie) {
        store[keyOf(cookie)] = cookie
    }

    /**
     * 按名字取当前有效的 Cookie，取不到返回 null。
     *
     * ⚠️ **有同名 Cookie 时必须传 [host]**（2026-10-18）。
     * 统一认证（zjuam）和教务网（zdbk）**都会**签发一个叫 `JSESSIONID` 的 Cookie，
     * 罐里两个并存。不限定主机就会撞上「先存进去的那个」——
     * 教务网该有的那个恰好缺着，而检查照样通过，
     * 表现为「会话建立成功了，课表接口却一直 302」。
     */
    @Synchronized
    fun findCookie(name: String, host: String? = null): Cookie? =
        store.values.firstOrNull {
            it.name == name &&
                it.expiresAt >= System.currentTimeMillis() &&
                (host == null || matchesHost(it, host))
        }

    /**
     * 丢掉一个 Cookie（按名字，全域名）。
     *
     * 存在的理由：罐里的 TGC 是**上一次**登录留下的，服务端可能已经不认它。
     * 这种陈旧会话不丢掉的话，下一次同步会一直拿着它去换票、一直失败 ——
     * 表现为「点几次重试都没用」，而真正该做的只是重新输一次密码。
     */
    @Synchronized
    fun removeCookie(name: String) {
        store.keys.filter { it.startsWith("$name|") }.forEach { store.remove(it) }
    }

    private fun keyOf(cookie: Cookie) = "${cookie.name}|${cookie.domain}|${cookie.path}"

    /** RFC 6265 的域名匹配：`host == domain || host.endsWith("." + domain)`。 */
    private fun matchesHost(cookie: Cookie, host: String): Boolean {
        val domain = cookie.domain.removePrefix(".").lowercase()
        val h = host.lowercase()
        return h == domain || h.endsWith(".$domain")
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