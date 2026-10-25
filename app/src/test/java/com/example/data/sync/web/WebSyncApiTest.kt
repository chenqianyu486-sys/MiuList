package com.example.data.sync.web

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 接口层（协议 v3）：路由、签名认证、密文收发、两条落库路径。
 *
 * 这一版与 v2 最大的差别：**没有「可复用的凭据」了**。
 *
 * v2 传的是 Bearer token —— 抓到一次请求就能一直用。
 * v3 每次请求现算一个签名（时间戳 + 一次性 nonce + 请求体摘要），
 * 抓包拿到的签名既不能重放（nonce 用过就作废）也不能伪造（没有密钥算不出来），
 * 而且请求与响应**都是密文**。
 *
 * 这些用例守的就是这几件事。
 */
class WebSyncApiTest {

    private val moshi = Moshi.Builder().build()
    private val snapshotAdapter = moshi.adapter(WebSnapshotDto::class.java)
    private val applyAdapter = moshi.adapter(WebApplyRequest::class.java)

    private var now = 1_000_000L
    private var nonceSeq = 0

    private val secret = ByteArray(32) { it.toByte() }

    private lateinit var pairing: PairingStore
    private lateinit var device: PairingStore.PairedDevice
    private var appliedMode: SyncMode? = null
    private var appliedSnapshot: WebSnapshotDto? = null

    private fun setUpApi(): WebSyncApi {
        pairing = PairingStore(clock = { now }, idGenerator = { "dev-1" })
        val req = pairing.request(pairing.requestIdOf(secret), "网页端")
        device = pairing.approve(req.requestId, secret, passcode = "", deviceName = "网页端")!!
        return WebSyncApi(
            pairing = pairing,
            assets = WebAssetProvider { path ->
                if (path == "index.html") "<html>ok</html>".toByteArray(StandardCharsets.UTF_8) else null
            },
            snapshotProvider = { revision ->
                WebSnapshotDto(
                    revision = revision,
                    today = "2026-10-25",
                    latestEditAt = 111L,
                    hasCourses = true,
                    hasExams = true,
                    hasTaskTypes = true,
                    tasks = listOf(WebTaskDto(syncUid = "u1", title = "手机上的那条", updatedAt = 100L)),
                )
            },
            tasks = object : TaskWriter {
                override suspend fun create(patch: WebTaskPatchDto) =
                    TaskWriteService.Result.Ok(WebTaskDto(syncUid = "new"))

                override suspend fun update(syncUid: String, patch: WebTaskPatchDto) =
                    TaskWriteService.Result.Ok(WebTaskDto(syncUid = syncUid))

                override suspend fun delete(syncUid: String) =
                    TaskWriteService.Result.Ok(WebTaskDto(syncUid = syncUid))
            },
            qr = { text -> "<svg data=\"$text\"/>".toByteArray(StandardCharsets.UTF_8) },
            broadcaster = object : SyncEventBroadcaster {
                override fun subscribe(device: PairingStore.PairedDevice, sink: HttpReply.EventSink) = Unit
                override fun unsubscribe(sink: HttpReply.EventSink) = Unit
                override fun broadcast(revision: Long, reason: String) = Unit
            },
            revisionProvider = { 7L },
            deviceName = { "测试手机" },
            appVersion = { "1.0.4" },
            snapshotApplier = { dto, mode ->
                appliedMode = mode
                appliedSnapshot = dto
                SnapshotApplier.Report(tasks = dto.tasks.size, appliedUids = dto.tasks.map { it.syncUid })
            },
        )
    }

    /** 造一个签名正确的请求。 */
    private fun signedRequest(
        api: WebSyncApi,
        method: String = "GET",
        path: String = "/api/snapshot",
        body: ByteArray = ByteArray(0),
        encryptBody: Boolean = false,
        ts: Long = now,
        nonce: String = "n${nonceSeq++}",
        deviceId: String = device.deviceId,
        overrideSig: String? = null,
    ): HttpRequest {
        val sig = overrideSig ?: WebCrypto.signRequest(device.keys, method, path, ts, nonce, body)
        val headers = HashMap<String, String>()
        headers["X-ML-Device"] = deviceId
        headers["X-ML-Ts"] = ts.toString()
        headers["X-ML-Nonce"] = nonce
        headers["X-ML-Sig"] = sig
        val rawBody = if (encryptBody) ByteArray(0) else body
        if (encryptBody) {
            headers["X-ML-Enc"] = WebCrypto.base64Url(WebCrypto.seal(device.keys, body))
        }
        return HttpRequest(method, path, emptyMap(), headers, rawBody)
    }

    /** 状态码在 [HttpReply.Full] 上，取之前先转一下。 */
    private fun statusOf(reply: HttpReply): Int = (reply as HttpReply.Full).status

    private fun HttpReply.fullBody(): String =
        (this as HttpReply.Full).body.toString(StandardCharsets.UTF_8)

    private fun HttpReply.decrypt(): String {
        val full = this as HttpReply.Full
        val payload = WebCrypto.open(device.keys, WebCrypto.fromBase64Url(full.body.toString(StandardCharsets.UTF_8)))
        return payload.toString(StandardCharsets.UTF_8)
    }

    // ── 免鉴权 ──────────────────────────────────────────────────

    @Test
    fun `health 不需要签名 - 网页端要先确认地址上有服务`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("GET", "/api/health", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(200, statusOf(reply))
        assertTrue(reply.fullBody().contains("\"ok\":true"))
        assertTrue(reply.fullBody().contains("测试手机"))
    }

    @Test
    fun `申请配对只要摘要 - secret 不许出现`() {
        val api = setUpApi()
        val body = """{"requestId":"${pairing.requestIdOf(secret)}","deviceName":"网页端"}"""
        val reply = api.handle(
            HttpRequest("POST", "/api/pair/request", emptyMap(), emptyMap(), body.toByteArray())
        )
        assertEquals(200, statusOf(reply))
        assertFalse("请求里不该出现 secret", reply.fullBody().contains(WebCrypto.base64Url(secret)))
    }

    @Test
    fun `申请配对缺少 requestId 就 400`() {
        val api = setUpApi()
        val reply = api.handle(
            HttpRequest("POST", "/api/pair/request", emptyMap(), emptyMap(), "{}".toByteArray())
        )
        assertEquals(400, statusOf(reply))
    }

    @Test
    fun `配对状态免鉴权 - 但里面没有凭据`() {
        val api = setUpApi()
        val reply = api.handle(
            HttpRequest(
                "GET", "/api/pair/status",
                mapOf("requestId" to pairing.requestIdOf(secret)),
                emptyMap(), ByteArray(0),
            )
        )
        assertEquals(200, statusOf(reply))
        val body = reply.fullBody()
        assertTrue(body.contains("\"state\":\"approved\""))
        assertTrue(body.contains("\"deviceId\""))
        assertFalse("状态里不该有密钥相关的东西", body.contains("token"))
        assertFalse(body.contains("kEnc"))
    }

    // ── 认证 ────────────────────────────────────────────────────

    @Test
    fun `没有签名就拿不到快照 - 401`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("GET", "/api/snapshot", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(401, statusOf(reply))
    }

    @Test
    fun `签名错了也拿不到快照`() {
        val api = setUpApi()
        val reply = api.handle(
            signedRequest(api, overrideSig = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        )
        assertEquals(401, statusOf(reply))
    }

    @Test
    fun `换个设备号就拿不到快照 - 签名不能挪到别的设备上用`() {
        val api = setUpApi()
        val reply = api.handle(signedRequest(api, deviceId = "别人"))
        assertEquals(401, statusOf(reply))
    }

    @Test
    fun `签名对了就能拿到快照 - 而且响应是密文`() {
        val api = setUpApi()
        val reply = api.handle(signedRequest(api))
        assertEquals(200, statusOf(reply))
        assertEquals("1", (reply as HttpReply.Full).headers["X-ML-Enc"])
        // 响应原文里不该直接出现任务标题 —— 它在密文里。
        assertFalse(reply.fullBody().contains("手机上的那条"))
        assertTrue(reply.decrypt().contains("手机上的那条"))
    }

    @Test
    fun `同一个 nonce 不能用两次`() {
        val api = setUpApi()
        assertEquals(200, statusOf(api.handle(signedRequest(api, nonce = "same"))))
        assertEquals(401, statusOf(api.handle(signedRequest(api, nonce = "same"))))
    }

    // ── 两条落库路径 ────────────────────────────────────────────

    @Test
    fun `apply 走 merge 时只带增量的那几条`() {
        val api = setUpApi()
        val incoming = WebSnapshotDto(
            hasCourses = false,
            tasks = listOf(WebTaskDto(syncUid = "w1", title = "网页端改的", updatedAt = 200L)),
        )
        val body = applyAdapter.toJson(WebApplyRequest(mode = "merge", snapshot = incoming))
        val reply = api.handle(
            signedRequest(api, method = "POST", path = "/api/snapshot/apply", body = body.toByteArray(), encryptBody = true)
        )
        assertEquals(200, statusOf(reply))
        assertEquals(SyncMode.MERGE, appliedMode)
        assertEquals(1, appliedSnapshot?.tasks?.size)
        assertEquals("w1", appliedSnapshot?.tasks?.first()?.syncUid)
    }

    @Test
    fun `apply 走 replace 时带的是整份`() {
        val api = setUpApi()
        val incoming = WebSnapshotDto(
            hasCourses = true,
            tasks = listOf(WebTaskDto(syncUid = "w1"), WebTaskDto(syncUid = "w2")),
        )
        val body = applyAdapter.toJson(WebApplyRequest(mode = "replace", snapshot = incoming))
        val reply = api.handle(
            signedRequest(api, method = "POST", path = "/api/snapshot/apply", body = body.toByteArray(), encryptBody = true)
        )
        assertEquals(200, statusOf(reply))
        assertEquals(SyncMode.REPLACE, appliedMode)
        assertEquals(2, appliedSnapshot?.tasks?.size)
    }

    @Test
    fun `apply 里的护栏标记要原样透传 - 空课程块不能把手机课表清空`() {
        val api = setUpApi()
        val body = applyAdapter.toJson(
            WebApplyRequest(
                mode = "replace",
                snapshot = WebSnapshotDto(hasCourses = false, hasExams = false, hasTaskTypes = false),
            )
        )
        api.handle(
            signedRequest(api, method = "POST", path = "/api/snapshot/apply", body = body.toByteArray(), encryptBody = true)
        )
        assertEquals(false, appliedSnapshot?.hasCourses)
        assertEquals(false, appliedSnapshot?.hasExams)
    }

    @Test
    fun `apply 没有签名就是 401 - 不能让局域网里的任何设备改手机的数据`() {
        val api = setUpApi()
        val body = applyAdapter.toJson(WebApplyRequest(mode = "replace", snapshot = WebSnapshotDto()))
        val reply = api.handle(
            HttpRequest("POST", "/api/snapshot/apply", emptyMap(), emptyMap(), body.toByteArray())
        )
        assertEquals(401, statusOf(reply))
        assertEquals(null, appliedMode)
    }

    @Test
    fun `密文被改过就解不开 - 改一个字节也不行`() {
        val api = setUpApi()
        val body = "{\"mode\":\"replace\",\"snapshot\":{\"tasks\":[]}}".toByteArray()
        val sealed = WebCrypto.seal(device.keys, body)
        sealed[15] = (sealed[15].toInt() xor 0x01).toByte()
        val request = signedRequest(api, method = "POST", path = "/api/snapshot/apply", body = ByteArray(0), encryptBody = true)
        val tampered = HttpRequest(
            request.method, request.path, request.query,
            request.headers + mapOf("X-ML-Enc" to WebCrypto.base64Url(sealed)),
            request.body,
        )
        assertEquals(401, statusOf(api.handle(tampered)))
    }

    // ── 杂项 ────────────────────────────────────────────────────

    @Test
    fun `静态页面直接给 - 浏览器要先拿到 index 才能显示配对页`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("GET", "/", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(200, statusOf(reply))
        assertTrue(reply.fullBody().contains("ok"))
        assertEquals("text/html; charset=utf-8", (reply as HttpReply.Full).contentType)
    }

    @Test
    fun `静态文件不许越界`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("GET", "/../../etc/passwd", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(404, statusOf(reply))
    }

    @Test
    fun `没有的接口是 404 而不是 500`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("GET", "/api/nope", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(404, statusOf(reply))
    }

    @Test
    fun `预检请求要放行 - 手动输入地址那条路会先发 OPTIONS`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("OPTIONS", "/api/snapshot", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(204, statusOf(reply))
    }

    @Test
    fun `同步模式认得出大小写 - 认不出就退回 merge`() {
        assertEquals(SyncMode.REPLACE, SyncMode.of("replace"))
        assertEquals(SyncMode.REPLACE, SyncMode.of("REPLACE"))
        assertEquals(SyncMode.MERGE, SyncMode.of("merge"))
        assertEquals(SyncMode.MERGE, SyncMode.of("不认识的"))
    }

    @Test
    fun `快照适配器能编出合法 JSON - 网页端要靠它解`() {
        val api = setUpApi()
        val reply = api.handle(signedRequest(api))
        val decoded = snapshotAdapter.fromJson(reply.decrypt())
        assertNotNull(decoded)
        assertEquals("2026-10-25", decoded?.today)
        assertEquals(111L, decoded?.latestEditAt)
    }
}
