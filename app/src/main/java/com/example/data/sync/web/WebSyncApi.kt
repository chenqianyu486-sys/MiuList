package com.example.data.sync.web

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 网页端静态资源（index.html / app.js / …）。 */
fun interface WebAssetProvider {
    /** 取不到返回 null。 */
    fun read(path: String): ByteArray?
}

/** 二维码渲染器。返回 SVG 字节；渲染失败返回 null。 */
fun interface QrRenderer {
    fun svg(text: String): ByteArray?
}

/**
 * 变更广播。快照有变化时把新的 revision 推给所有 SSE 客户端。
 *
 * 单独抽出来是因为**只有服务端知道数据什么时候变了**：网页端改了东西，
 * 手机这边的 Room 会发失效通知；手机这边改了东西，也一样。
 * 网页端不需要轮询 —— 它只需要在收到 `change` 事件后重新拉一次快照。
 */
interface SyncEventBroadcaster {
    fun subscribe(device: PairingStore.PairedDevice, sink: HttpReply.EventSink)
    fun unsubscribe(sink: HttpReply.EventSink)
    fun broadcast(revision: Long, reason: String)
}

/**
 * 局域网同步的 HTTP 接口（协议 v3）。
 *
 * ## 鉴权：挑战-应答，不是可复用的凭据
 *
 * 每个请求都要带四个头：
 *
 * ```
 * X-ML-Device: <deviceId>
 * X-ML-Ts:     <epoch 毫秒>
 * X-ML-Nonce:  <一次性随机串>
 * X-ML-Sig:    <HMAC(kAuth, "v3\nMETHOD\n/path\nts\nnonce\nbase64url(sha256(body))")>
 * ```
 *
 * 抓包拿到 sig 也**用不了**：nonce 用过就作废，时间戳超窗就作废，
 * 而且没有 kAuth 就算不出新的 sig。
 *
 * SSE（`/api/events`）用**查询串**带同样四个参数 ——
 * EventSource 不能设请求头，这是唯一的例外。
 *
 * ## 负载：密文
 *
 * 请求密文放在 **HTTP body** 里（原始 sealed 字节，`X-ML-Enc: 1` 标记）；
 * 响应密文也在 body 里（base64url，`X-ML-Enc: 1` 标记）。
 * 不能把密文放进请求头：请求头在手机侧限制 16 KB，而带周课表的快照更大，
 * 放头里会把连接顶断，浏览器只会报一个莫名其妙的 CORS 失败。
 * 抓包能看到的只有密文与随机数。
 *
 * 例外：`/api/health` 与 `/api/pair/` 下的接口不加密 ——
 * 它们要么不含用户数据，要么发生在**还没有密钥**的时候。
 */
class WebSyncApi(
    private val pairing: PairingStore,
    private val assets: WebAssetProvider,
    private val snapshotProvider: suspend (Long) -> WebSnapshotDto,
    private val tasks: TaskWriter,
    private val qr: QrRenderer,
    private val broadcaster: SyncEventBroadcaster,
    private val revisionProvider: () -> Long,
    private val deviceName: () -> String,
    private val appVersion: () -> String,
    private val snapshotApplier: suspend (WebSnapshotDto, SyncMode) -> SnapshotApplier.Report,
    private val zjuProxy: ZjuProxy,
    /**
     * 有一条配对请求**拿到密钥材料**了 —— 界面该弹确认卡了。
     *
     * 为什么要有这个回调：`/api/pair/request` 是在 HTTP 处理线程里被处理的，
     * 而界面要弹的是那张「有一台设备请求连接」的卡。这条请求什么时候
     * 才算「能配了」，由 [PairingStore] 说了算（老协议要等 secret，
     * 新协议要等公钥），接口层只负责把这件事告诉界面。
     */
    private val onPairingReady: (PairingStore.PendingRequest) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val moshi: Moshi = Moshi.Builder().build()
    private val snapshotAdapter = moshi.adapter(WebSnapshotDto::class.java)
    private val patchAdapter = moshi.adapter(WebTaskPatchDto::class.java)
    private val applyAdapter = moshi.adapter(WebApplyRequest::class.java)
    private val zjuSyncAdapter = moshi.adapter(WebZjuSyncRequest::class.java)

    /**
     * 自由 JSON 对象的适配器。
     *
     * `moshi.adapter(Map::class.java)` 出来的是 `Map<*, *>`，
     * 用它 `fromJson` 出来的类型推不出来 —— 得显式说清是
     * `Map<String, Any?>`（参数字面上不能直接写 `Any?`，Moshi 要 `java.lang.Object`）。
     */
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    /** 一次已通过鉴权的请求。 */
    private class Authenticated(
        val device: PairingStore.PairedDevice,
        val body: ByteArray,
    )

    fun handle(request: HttpRequest): HttpReply {
        // 预检：网页端可能开在另一个地址上（手动输入地址那条路）。
        if (request.method == "OPTIONS") {
            return HttpReply.Full(
                status = 204,
                contentType = "text/plain; charset=utf-8",
                body = ByteArray(0),
            )
        }

        val path = request.path
        return when {
            path == "/api/health" -> health()
            path == "/api/pair/request" && request.method == "POST" -> requestPairing(request)
            path == "/api/pair/status" -> pairStatus(request)
            path == "/api/pair/qr.svg" -> pairQr(request)
            path == "/api/snapshot" -> snapshot(request)
            path == "/api/snapshot/apply" && request.method == "POST" -> applySnapshot(request)
            path == "/api/zju/terms" -> zjuTerms(request)
            path == "/api/zju/sync" && request.method == "POST" -> zjuSync(request)
            path == "/api/events" -> events(request)
            path.startsWith("/api/") -> json(404, """{"error":"没有这个接口"}""")
            else -> staticFile(path)
        }
    }

    // ── 免鉴权 ──────────────────────────────────────────────────

    private fun health(): HttpReply = json(
        200,
        """{"ok":true,"app":"MiuList","protocol":3,"pairing":4,"appVersion":"${esc(appVersion())}","deviceName":"${esc(deviceName())}","serverTime":${System.currentTimeMillis()}}""",
    )

    /**
     * 申请配对。
     *
     * 请求体里只有 `requestId`（secret 的摘要）—— **secret 本身不上网络**。
     */
    /**
     * 申请配对。
     *
     * 请求体有两种形态：
     *
     * - **老协议（v3）**：只有 `requestId`（secret 的摘要）——
     *   secret 本身从二维码过来，不上网络。
     * - **新协议（v3.1）**：多带一个 `publicKey` —— 电脑的 DH 公钥。
     *   **公钥不是秘密**（过网也泄不了密），但有了它手机就能自己
     *   协商出共享密钥 —— 「只填地址」那条路就是这样走通的。
     */
    private fun requestPairing(request: HttpRequest): HttpReply {
        val body = parseJson(request) ?: return json(400, """{"error":"请求体不合法"}""")
        val requestId = body.optString("requestId").orEmpty()
        if (requestId.length < 8) return json(400, """{"error":"缺少 requestId"}""")
        val deviceName = body.optString("deviceName").orEmpty()
        val publicKey = body.optString("publicKey")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { WebCrypto.fromBase64Url(it) }.getOrNull() }
            ?.takeIf { it.size == WebDh.KEY_BYTES }
        val req = pairing.request(requestId, deviceName, publicKey)
        // 拿到密钥材料了才弹确认卡 —— 否则用户看到的是一张「有个请求」，
        // 却连配对码都算不出来（公钥还没到）。
        if (req.hasKeyMaterial) onPairingReady(req)
        return json(
            200,
            """{"state":"${req.state.name.lowercase()}","expiresAt":${req.expiresAt}}""",
        )
    }

    /**
     * 查配对状态。
     *
     * **免鉴权、不加密**，但里面**没有任何凭据** —— 只有状态与 deviceId。
     * 抓到这条请求的人知道「有个设备在等」，但没有 secret，
     * 拿不到密钥、也算不出签名。
     */
    private fun pairStatus(request: HttpRequest): HttpReply {
        val requestId = request.query["requestId"].orEmpty()
        val status = pairing.status(requestId)
        val body = buildString {
            append("""{"state":"${status.state.name.lowercase()}"""")
            status.deviceId?.let { append(""","deviceId":"${esc(it)}"""") }
            status.deviceName?.let { append(""","deviceName":"${esc(it)}"""") }
            status.publicKey?.let {
                append(""","publicKey":"${WebCrypto.base64Url(it)}"""")
            }
            status.pairCode?.let { append(""","pairCode":"${esc(it)}"""") }
            append("}")
        }
        return json(200, body)
    }

    private fun pairQr(request: HttpRequest): HttpReply {
        val code = request.query["code"].orEmpty()
        if (code.isBlank()) return json(400, """{"error":"缺少配对码"}""")
        val svg = qr.svg(deepLinkFor(code))
            ?: return json(500, """{"error":"二维码生成失败"}""")
        return HttpReply.Full(
            status = 200,
            contentType = "image/svg+xml; charset=utf-8",
            body = svg,
            headers = mapOf("Cache-Control" to "no-store"),
        )
    }

    // ── 要签名 ──────────────────────────────────────────────────

    private fun snapshot(request: HttpRequest): HttpReply {
        val auth = authenticate(request) ?: return unauthorized()
        return try {
            val snap = runBlocking { snapshotProvider(revisionProvider()) }
            encrypted(auth.device, snapshotAdapter.toJson(snap))
        } catch (e: Exception) {
            encryptedError(auth.device, "生成快照失败：${e.message ?: ""}")
        }
    }

    /**
     * 接收远端快照。
     *
     * 两条路径共用这一个接口，靠 [WebApplyRequest.mode] 区分：
     * - `merge`：增量。自动同步走这条 —— 两端都在线且没有冲突，
     *   所以只传各自改过的那几条就够，删除不在这一步发生。
     * - `replace`：整份。手动选「以对方为准」时走这条，删除能传播全靠它。
     */
    private fun applySnapshot(request: HttpRequest): HttpReply {
        val auth = authenticate(request) ?: return unauthorized()
        val req = try {
            applyAdapter.fromJson(auth.body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } ?: return encryptedError(auth.device, "请求体不是合法的快照")
        val snapshot = req.snapshot ?: return encryptedError(auth.device, "请求体里没有快照")

        val mode = SyncMode.of(req.mode)
        return try {
            val report = runBlocking { snapshotApplier(snapshot, mode) }
            val body = buildString {
                append("""{"ok":true,"mode":"${mode.wire}"""")
                append(""","applied":{"tasks":${report.tasks},"courses":${report.courses},"exams":${report.exams},"taskTypes":${report.taskTypes},"tasksRemoved":${report.tasksRemoved}}""")
                append(""","keptUids":${jsonStringArray(report.keptUids)}""")
                append(""","appliedUids":${jsonStringArray(report.appliedUids)}""")
                append(""","revision":${revisionProvider()}}""")
            }
            encrypted(auth.device, body)
        } catch (e: Exception) {
            encryptedError(auth.device, "落库失败：${e.message ?: ""}")
        }
    }

    /**
     * 可选的学期。**不需要账号** —— 学期表是公共配置。
     *
     * 走签名鉴权但**不加密请求体**（GET 没有请求体）。
     * 响应仍然加密：网页端与手机之间的一切都走密文。
     */
    private fun zjuTerms(request: HttpRequest): HttpReply {
        val auth = authenticate(request) ?: return unauthorized()
        return try {
            val terms = runBlocking { zjuProxy.terms() }
            val body = buildString {
                append("""{"terms":[""")
                append(
                    terms.joinToString(",") { term ->
                        """{"year":"${esc(term.year)}","term":${term.term},"label":"${esc(term.label)}"}"""
                    }
                )
                append("]}")
            }
            encrypted(auth.device, body)
        } catch (e: Exception) {
            encryptedError(auth.device, "拿不到学期列表：${e.message ?: ""}")
        }
    }

    /**
     * 「去学在浙大拉一次课表」。
     *
     * **请求体必须加密**（`X-ML-Enc: 1`）：学号密码在里面。
     * `authenticate` 会先把密文解开再验签，所以这条路上密码始终是密文。
     *
     * 拉完之后手机这边的 Room 变了 —— 广播会通知网页端重拉快照，
     * 于是「用手机的课表和考试」拿到的就是刚拉回来的那份。
     */
    private fun zjuSync(request: HttpRequest): HttpReply {
        val auth = authenticate(request) ?: return unauthorized()
        val req = try {
            zjuSyncAdapter.fromJson(auth.body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } ?: return encryptedError(auth.device, "请求体不是合法的请求")

        return try {
            when (
                val outcome = runBlocking {
                    zjuProxy.sync(
                        studentId = req.studentId,
                        password = req.password,
                        year = req.year,
                        term = req.term,
                        remember = req.remember ?: true,
                    )
                }
            ) {
                is ZjuProxy.Outcome.Ok -> encrypted(
                    auth.device,
                    """{"ok":true,"courseCount":${outcome.courseCount},"slotCount":${outcome.slotCount},""" +
                        """"examCount":${outcome.examCount},"termLabel":"${esc(outcome.termLabel)}",""" +
                        """"savedCredentials":${outcome.savedCredentials},""" +
                        """"examError":${outcome.examError?.let { "\"" + esc(it) + "\"" } ?: "null"}}"""
                )
                is ZjuProxy.Outcome.Failed -> encrypted(
                    auth.device,
                    """{"ok":false,"error":"${esc(outcome.reason)}","kind":"${esc(outcome.kind)}",""" +
                        """"localDataIntact":${outcome.localDataIntact}}"""
                )
            }
        } catch (e: Exception) {
            encryptedError(auth.device, "拉取失败：${e.message ?: ""}")
        }
    }

    private fun events(request: HttpRequest): HttpReply {
        val auth = authenticate(request) ?: return unauthorized()
        val revision = revisionProvider()
        return HttpReply.EventStream { sink ->
            sink.send("hello", encryptPayload(auth.device, """{"revision":$revision,"protocol":3}"""))
            broadcaster.subscribe(auth.device, sink)
        }
    }

    // ── 鉴权 ────────────────────────────────────────────────────

    /**
     * 验签 + 解密。
     *
     * 顺序是「先解密、再验签」：
     * 解密本身带完整性检查（密文有 MAC），所以**篡改过的密文在这一步就被挡住**；
     * 验签再覆盖「请求体进了签名」这一层 —— 否则密文能被换成另一段密文。
     */
    private fun authenticate(request: HttpRequest): Authenticated? {
        val deviceId = request.header(HEADER_DEVICE) ?: request.query["device"] ?: return null
        val ts = (request.header(HEADER_TS) ?: request.query["ts"])?.toLongOrNull() ?: return null
        val nonce = request.header(HEADER_NONCE) ?: request.query["nonce"] ?: return null
        val signature = request.header(HEADER_SIG) ?: request.query["sig"] ?: return null

        val device = pairing.device(deviceId) ?: return null

        val enc = request.header(HEADER_ENC)
        // `X-ML-Enc: 1` = 密文直接放在 HTTP body 里。
        // 不能把密文放进请求头：手机侧请求头限制 16 KB，而一份带周课表的快照
        // 轻松超过这个数，放在头里会把连接直接顶断（浏览器只报 CORS 失败）。
        if (request.body.isNotEmpty() && enc != "1") return null
        val body = if (enc == "1") {
            runCatching { WebCrypto.open(device.keys, request.body) }.getOrNull() ?: return null
        } else {
            request.body
        }

        if (!pairing.verify(deviceId, request.method, request.path, ts, nonce, signature, body)) {
            return null
        }
        return Authenticated(device, body)
    }

    private fun unauthorized(): HttpReply =
        json(401, """{"error":"未配对、签名不对或已过期"}""")

    // ── 静态文件 ────────────────────────────────────────────────

    private fun staticFile(path: String): HttpReply {
        val rel = if (path == "/") "index.html" else path.removePrefix("/")
        // 只允许 web/ 下的相对路径，不允许任何形式的越界。
        if (rel.contains("..") || rel.startsWith("/")) {
            return json(404, """{"error":"没有这个文件"}""")
        }
        val bytes = assets.read(rel) ?: return json(404, """{"error":"没有这个文件"}""")
        return HttpReply.Full(
            status = 200,
            contentType = contentTypeOf(rel),
            body = bytes,
            headers = mapOf("Cache-Control" to "no-store"),
        )
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 加密后回给客户端。 */
    private fun encrypted(device: PairingStore.PairedDevice, payload: String): HttpReply {
        val sealed = WebCrypto.seal(device.keys, payload.toByteArray(Charsets.UTF_8))
        return HttpReply.Full(
            status = 200,
            contentType = "application/json; charset=utf-8",
            body = WebCrypto.base64Url(sealed).toByteArray(Charsets.UTF_8),
            headers = mapOf(HEADER_ENC to "1", "Cache-Control" to "no-store"),
        )
    }

    private fun encryptedError(device: PairingStore.PairedDevice, message: String): HttpReply =
        encrypted(device, """{"error":"${esc(message)}"}""")

    /** SSE 事件的载荷也要加密 —— 变更通知里带着待办标题。 */
    private fun encryptPayload(device: PairingStore.PairedDevice, payload: String): String =
        WebCrypto.base64Url(WebCrypto.seal(device.keys, payload.toByteArray(Charsets.UTF_8)))

    private fun parseJson(request: HttpRequest): Map<String, Any?>? = try {
        val text = request.bodyText()
        if (text.isBlank()) emptyMap() else mapAdapter.fromJson(text)
    } catch (_: Exception) {
        null
    }

    private fun Map<String, Any?>.optString(key: String): String? =
        this[key] as? String

    private fun json(status: Int, body: String): HttpReply =
        HttpReply.json(status, body)

    private fun esc(s: String): String = LanHttpServer.escapeJson(s)

    /** uid 数组 → JSON 字符串数组。 */
    private fun jsonStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "\"${esc(it)}\"" }

    private fun deepLinkFor(code: String): String = "$DEEP_LINK_SCHEME://pair?code=$code"

    companion object {
        const val DEEP_LINK_SCHEME = "miulist"

        const val HEADER_DEVICE = "X-ML-Device"
        const val HEADER_TS = "X-ML-Ts"
        const val HEADER_NONCE = "X-ML-Nonce"
        const val HEADER_SIG = "X-ML-Sig"
        const val HEADER_ENC = "X-ML-Enc"

        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "svg" to "image/svg+xml",
            "json" to "application/json; charset=utf-8",
            "webmanifest" to "application/manifest+json; charset=utf-8",
            "png" to "image/png",
        )

        fun contentTypeOf(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return MIME[ext] ?: "application/octet-stream"
        }
    }
}
