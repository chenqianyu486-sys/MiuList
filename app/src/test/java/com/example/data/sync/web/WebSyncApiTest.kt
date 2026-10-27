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

    /** 假的「手机代跑学在浙大」。真实现要网络与 Keystore，接口层不该依赖那些。 */
    private val zjuProxy = FakeZjuProxy()

    private fun setUpApi(
        snapshotApplier: suspend (WebSnapshotDto, SyncMode) -> SnapshotApplier.Report = { dto, mode ->
            appliedMode = mode
            appliedSnapshot = dto
            SnapshotApplier.Report(tasks = dto.tasks.size, appliedUids = dto.tasks.map { it.syncUid })
        },
    ): WebSyncApi {
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
            snapshotApplier = snapshotApplier,
            zjuProxy = zjuProxy,
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
        // 密文走 HTTP body（`X-ML-Enc: 1` 只是标记）；签名始终覆盖明文。
        val rawBody = if (encryptBody) WebCrypto.seal(device.keys, body) else body
        if (encryptBody) headers["X-ML-Enc"] = "1"
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

    private fun pairRequest(
        api: WebSyncApi,
        requestId: String,
        host: String,
        port: Int,
    ): Int = statusOf(
        api.handle(
            HttpRequest(
                method = "POST",
                path = "/api/pair/request",
                query = emptyMap(),
                headers = emptyMap(),
                body = """{"requestId":"$requestId"}""".toByteArray(),
                remoteAddress = "$host:$port",
                remoteHost = host,
                remotePort = port,
            )
        )
    )

    @Test
    fun `同一 IP 换临时端口仍受同一份限流配额约束`() {
        val api = setUpApi()
        repeat(PairingStore.MAX_REQUESTS_PER_SOURCE) { i ->
            assertEquals(
                "第 ${i + 1} 次应当放行",
                200,
                pairRequest(api, "same-ip-$i", "192.168.1.5", 40000 + i),
            )
        }
        assertEquals(
            "端口变了不该换来新配额",
            429,
            pairRequest(api, "same-ip-over", "192.168.1.5", 50000),
        )
    }

    @Test
    fun `不同 IP 不互相影响 - 窗口过后恢复`() {
        val api = setUpApi()
        repeat(PairingStore.MAX_REQUESTS_PER_SOURCE) { i ->
            assertEquals(200, pairRequest(api, "ip-a-${i.toString().padStart(3, '0')}", "192.168.1.5", 40000 + i))
        }
        assertEquals(429, pairRequest(api, "ip-a-over", "192.168.1.5", 50000))
        assertEquals("另一个 IP 有它自己的配额", 200, pairRequest(api, "ip-b-000", "192.168.1.6", 40000))

        now += PairingStore.RATE_WINDOW_MS + 1
        assertEquals("窗口过了应恢复", 200, pairRequest(api, "ip-a-again", "192.168.1.5", 50001))
    }

    @Test
    fun `并发申请不能绕过限流`() {
        val api = setUpApi()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val tasks = (0 until 32).map { i ->
                pool.submit<Int> {
                    start.await()
                    pairRequest(api, "concurrent-$i", "192.168.1.9", 45000 + i)
                }
            }
            start.countDown()
            val codes = tasks.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(
                "并发下只有配额内的请求能成功",
                PairingStore.MAX_REQUESTS_PER_SOURCE,
                codes.count { it == 200 },
            )
            assertEquals(
                "其余必须是 429，不能一起越过限制",
                32 - PairingStore.MAX_REQUESTS_PER_SOURCE,
                codes.count { it == 429 },
            )
        } finally {
            pool.shutdownNow()
        }
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
    fun `数量护栏异常与真实落库异常返回不同提示`() {
        val guarded = setUpApi(
            snapshotApplier = { _, _ -> throw SnapshotGuardException("远端待办太少") },
        )
        val guardedBody = applyAdapter.toJson(
            WebApplyRequest(mode = "replace", snapshot = WebSnapshotDto(tasks = emptyList())),
        )
        val guardedReply = guarded.handle(
            signedRequest(
                guarded,
                method = "POST",
                path = "/api/snapshot/apply",
                body = guardedBody.toByteArray(),
                encryptBody = true,
            )
        )
        val guardedText = guardedReply.decrypt()
        assertTrue(guardedText, guardedText.contains("已阻止可能清空数据的替换"))
        assertFalse("护栏拒绝不是落库失败", guardedText.contains("落库失败"))

        val failed = setUpApi(
            snapshotApplier = { _, _ -> throw IllegalStateException("database is locked") },
        )
        val failedBody = applyAdapter.toJson(
            WebApplyRequest(mode = "replace", snapshot = WebSnapshotDto(tasks = emptyList())),
        )
        val failedReply = failed.handle(
            signedRequest(
                failed,
                method = "POST",
                path = "/api/snapshot/apply",
                body = failedBody.toByteArray(),
                encryptBody = true,
            )
        )
        val failedText = failedReply.decrypt()
        assertTrue(failedText, failedText.contains("落库失败"))
        assertFalse("真实落库异常不能冒充护栏提示", failedText.contains("已阻止可能清空数据的替换"))
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
        val request = signedRequest(api, method = "POST", path = "/api/snapshot/apply", body = body, encryptBody = true)
        val tamperedBody = request.body.copyOf()
        tamperedBody[15] = (tamperedBody[15].toInt() xor 0x01).toByte()
        val tampered = HttpRequest(request.method, request.path, request.query, request.headers, tamperedBody)
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

    // ── 网页端请手机去学在浙大拉课表 ──────────────────────────

    @Test
    fun `学期列表不需要账号 - 但还是要签名`() {
        val api = setUpApi()
        zjuProxy.termItems = listOf(
            ZjuProxy.TermItem(year = "2026-2027", term = 0, label = "2026-2027 秋冬"),
            ZjuProxy.TermItem(year = "2025-2026", term = 4, label = "2025-2026 春夏"),
        )
        val reply = api.handle(signedRequest(api, path = "/api/zju/terms"))
        assertEquals(200, statusOf(reply))
        val body = reply.decrypt()
        assertTrue(body.contains("2026-2027"))
        assertTrue(body.contains("2025-2026"))
        // 秋 = 0、春 = 4：网页端把它直接当查询参数用，值错了就是查错学期。
        assertTrue(body.contains("\"term\":0"))
        assertTrue(body.contains("\"term\":4"))
    }

    @Test
    fun `学期列表没签名就拿不到`() {
        val api = setUpApi()
        val reply = api.handle(HttpRequest("GET", "/api/zju/terms", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(401, statusOf(reply))
    }

    @Test
    fun `拉课表的请求体必须加密 - 明文密码不许过`() {
        val api = setUpApi()
        val body = """{"studentId":"3220000000","password":"secret"}"""
        val reply = api.handle(
            signedRequest(api, method = "POST", path = "/api/zju/sync", body = body.toByteArray())
        )
        assertEquals(401, statusOf(reply))
    }

    @Test
    fun `学号密码原样转交给手机 - 网页端不自己碰浙大`() {
        val api = setUpApi()
        zjuProxy.outcome = ZjuProxy.Outcome.Ok(
            courseCount = 5,
            slotCount = 12,
            examCount = 2,
            termLabel = "2026-2027 秋冬",
            examError = null,
            savedCredentials = true,
        )
        val body = """{"studentId":"3220000000","password":"secret","year":"2026-2027","term":0,"remember":true}"""
        val reply = api.handle(
            signedRequest(api, method = "POST", path = "/api/zju/sync", body = body.toByteArray(), encryptBody = true)
        )
        assertEquals(200, statusOf(reply))
        val call = zjuProxy.lastSync
        assertNotNull("手机那边根本没被叫起来", call)
        assertEquals("3220000000", call!!.studentId)
        assertEquals("secret", call.password)
        assertEquals("2026-2027", call.year)
        assertEquals(0, call.term)
        // **远端带来的凭据一律不写回手机的 Keystore**（协议 v3.2）。
        //
        // 原来这里是 `assertTrue(call.remember)`：页网端说「记住它」就记住。
        // 那等于一台已配对的设备能把它带来的学号密码
        // **存进这台手机的凭据存储里**，把用户自己绑定的那个账号顶掉。
        assertFalse("远端带来的凭据不许写回 Keystore", call.remember)
        val plain = reply.decrypt()
        assertTrue(plain.contains("\"ok\":true"))
        assertTrue(plain.contains("\"courseCount\":5"))
        assertTrue(plain.contains("\"examCount\":2"))
    }

    @Test
    fun `不填密码就是用手机已存的凭据 - 网页端不记密码`() {
        val api = setUpApi()
        zjuProxy.outcome = ZjuProxy.Outcome.Ok(
            courseCount = 1, slotCount = 2, examCount = 0,
            termLabel = "2026-2027 秋冬", examError = null, savedCredentials = true,
        )
        val reply = api.handle(
            signedRequest(api, method = "POST", path = "/api/zju/sync", body = "{}".toByteArray(), encryptBody = true)
        )
        assertEquals(200, statusOf(reply))
        val call = zjuProxy.lastSync
        assertNotNull(call)
        assertEquals(null, call!!.studentId)
        assertEquals(null, call.password)
    }

    @Test
    fun `拉失败要说清原因 并说本机数据没被动过`() {
        val api = setUpApi()
        zjuProxy.outcome = ZjuProxy.Outcome.Failed(
            kind = "AUTH",
            reason = "学号或密码不对",
            localDataIntact = true,
        )
        val body = """{"studentId":"3220000000","password":"wrong"}"""
        val reply = api.handle(
            signedRequest(api, method = "POST", path = "/api/zju/sync", body = body.toByteArray(), encryptBody = true)
        )
        assertEquals(200, statusOf(reply))
        val plain = reply.decrypt()
        assertTrue(plain.contains("\"ok\":false"))
        assertTrue(plain.contains("\"kind\":\"AUTH\""))
        assertTrue(plain.contains("\"localDataIntact\":true"))
    }

    /**
     * 假的「手机代跑学在浙大」。
     *
     * 真实现（[ZjuProxyService]）要网络、要 Keystore、要真的浙大账号 ——
     * 那些都不是接口层要守的东西。接口层要守的是：
     * **密码有没有被原样转交出去**、**结果有没有被如实转述**。
     */
    private class FakeZjuProxy : ZjuProxy {
        var termItems: List<ZjuProxy.TermItem> = emptyList()
        var outcome: ZjuProxy.Outcome = ZjuProxy.Outcome.Failed("TEST", "没设结果", true)
        var lastSync: SyncCall? = null

        data class SyncCall(
            val studentId: String?,
            val password: String?,
            val year: String?,
            val term: Int?,
            val remember: Boolean,
        )

        override suspend fun terms(): List<ZjuProxy.TermItem> = termItems

        override suspend fun sync(
            studentId: String?,
            password: String?,
            year: String?,
            term: Int?,
            remember: Boolean,
        ): ZjuProxy.Outcome {
            lastSync = SyncCall(studentId, password, year, term, remember)
            return outcome
        }
    }
}
