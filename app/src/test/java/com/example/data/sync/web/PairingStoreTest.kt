package com.example.data.sync.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对状态机。
 *
 * 配对只有一条链路：**电脑发起、手机确认**。请求必须带电脑的 DH 公钥，
 * 手机收到后当场协商共享密钥、给出配对码；用户核对无误再点「允许」。
 *
 * 所以这里最要紧的用例是：**没有公钥就配不成，公钥不对就拒收** ——
 * 密钥只在两端各自算出，链路上始终只有公钥。
 */
class PairingStoreTest {

    private var now = 1_000_000L
    private var idSeq = 0

    private fun store(onExpired: (String) -> Unit = {}) = PairingStore(
        clock = { now },
        idGenerator = { "dev-${idSeq++}" },
        onDeviceExpired = onExpired,
    )

    /** 走完整的 DH 链路造一台已配对设备。 */
    private fun newDevice(store: PairingStore): PairingStore.PairedDevice {
        val (_, pub) = WebDh.generateKeypair()
        val req = store.request("req-${idSeq++}", "网页端", pub)
        return store.approve(req.requestId)!!
    }

    /** 生成一对「电脑」密钥并申请一条配对。 */
    private fun newRequest(store: PairingStore, requestId: String): PairingStore.PendingRequest {
        val (_, pub) = WebDh.generateKeypair()
        return store.request(requestId, "网页端", pub)
    }

    // ── 申请 ────────────────────────────────────────────────────

    @Test
    fun `申请配对本身不授予任何权限`() {
        val store = store()
        val req = newRequest(store, "req-0001")

        assertEquals(PairingStore.State.PENDING, req.state)
        assertTrue("申请配对不该产生任何设备", store.pairedDevices().isEmpty())
    }

    @Test
    fun `同一个 requestId 重复申请会复用同一条`() {
        val store = store()
        val first = newRequest(store, "req-0002")
        // 第二次故意换一把公钥：同一请求不该被换掉密钥，否则两端算出的码对不上。
        val (_, otherPub) = WebDh.generateKeypair()
        val again = store.request("req-0002", "网页端", otherPub)

        assertEquals(first.requestId, again.requestId)
        assertEquals("重复申请不能改掉第一次协商的配对码", first.pairCode, again.pairCode)
    }

    @Test
    fun `批准之后再用同一个 requestId 申请 - 不能重新协商出新的密钥`() {
        val store = store()
        val first = newRequest(store, "req-approved-01")
        store.approve(first.requestId)!!

        // 重放：同一 requestId 又来一次（免鉴权接口，第三方脚本做得到）。
        val (_, otherPub) = WebDh.generateKeypair()
        val again = store.request("req-approved-01", "网页端", otherPub)

        assertEquals(
            "已批准的请求不能被重新协商 —— 新密钥会让手机再弹一张对不上的卡",
            PairingStore.State.APPROVED,
            again.state,
        )
        assertEquals("配对码必须还是上一次那把密钥算出来的", first.pairCode, again.pairCode)
        assertEquals(
            "共享密钥也不能换 —— 换了两端就对不上",
            first.sharedSecret.toList(),
            again.sharedSecret.toList(),
        )
    }

    @Test
    fun `处理过的 requestId 过了待确认表的期限也还认得`() {
        val store = store()
        val first = newRequest(store, "req-approved-02")
        store.approve(first.requestId)!!

        // 待确认表只留 CODE_TTL_MS：过了就清掉。
        now += PairingStore.CODE_TTL_MS + 1

        assertEquals(PairingStore.State.APPROVED, store.knownState("req-approved-02"))
        // 全新的一条仍然是 null —— 不能把谁都当成「处理过」。
        assertNull(store.knownState("req-never-seen"))
    }

    @Test
    fun `拒绝过的 requestId 也不再重新协商`() {
        val store = store()
        val first = newRequest(store, "req-denied-01")
        assertTrue(store.deny(first.requestId))

        val (_, otherPub) = WebDh.generateKeypair()
        val again = store.request("req-denied-01", "网页端", otherPub)

        assertEquals(PairingStore.State.DENIED, again.state)
        assertEquals("配对码不能变", first.pairCode, again.pairCode)
    }

    // ── 批准 ────────────────────────────────────────────────────

    @Test
    fun `批准之后产生一台设备 - 且密钥由协商出来的共享密钥派生`() {
        val store = store()
        val (priv, pub) = WebDh.generateKeypair()
        val req = store.request("req-0003", "网页端", pub)

        val device = store.approve(req.requestId, passcode = "", deviceName = "网页端")

        assertNotNull(device)
        assertEquals("网页端", device!!.deviceName)
        assertEquals(1, store.pairedDevices().size)
        assertNotNull(store.device(device.deviceId))
        // 派生出来的密钥必须等于「拿两端算出的同一把共享密钥再派生」的结果。
        assertArrayEqualsBytes(
            WebCrypto.deriveKeys(WebDh.sharedSecret(priv, req.ephemeralPublic)).kEnc,
            device.keys.kEnc,
        )
    }

    @Test
    fun `没申请过就批准不了`() {
        val store = store()
        assertNull(store.approve("req-missing"))
    }

    @Test
    fun `过了有效期就不能批准了`() {
        val store = store()
        val req = newRequest(store, "req-0004")

        now += PairingStore.CODE_TTL_MS + 1

        assertNull(store.approve(req.requestId))
        assertEquals(PairingStore.State.EXPIRED, store.status(req.requestId).state)
    }

    @Test
    fun `拒绝之后不能批准`() {
        val store = store()
        val req = newRequest(store, "req-0005")
        assertTrue(store.deny(req.requestId))
        assertNull(store.approve(req.requestId))
    }

    @Test
    fun `带同步密码时密钥会不同 - 密码错了就连不上`() {
        // **两次批准要用两个不同的请求** —— 同一个 requestId 只能批准一次
        // （批准后它就作废了，正是「一次性配对码」的意思）。
        val withPw = store().let { s ->
            s.approve(s.request("req-pw", "网页端", WebDh.generateKeypair().second).requestId, passcode = "1234")
        }
        val withoutPw = store().let { s ->
            s.approve(s.request("req-nopw", "网页端", WebDh.generateKeypair().second).requestId, passcode = "")
        }

        assertNotNull(withPw)
        assertNotNull(withoutPw)
        assertFalse(
            "带密码与不带密码必须派生出不同的密钥",
            withPw!!.keys.kEnc.contentEquals(withoutPw!!.keys.kEnc),
        )
    }

    // ── DH 协商 ─────────────────────────────────────────────────

    @Test
    fun `带公钥的申请会当场协商出共享密钥 - 并给出配对码`() {
        val store = store()
        val (priv, pub) = WebDh.generateKeypair()

        val req = store.request("req-dh-0001", "网页端", pub)

        assertEquals(
            "配对码必须是**双方公钥**的哈希 —— 电脑那边算出来的是同一个",
            WebDh.pairCode(pub, req.ephemeralPublic),
            req.pairCode,
        )
        // 共享密钥 = sha256(公钥协商出来的值)
        assertArrayEqualsBytes(
            WebDh.sharedSecret(req.ephemeralPrivate, pub),
            req.sharedSecret,
        )
        assertTrue(req.ephemeralPublic.size == WebDh.KEY_BYTES)
        assertTrue(priv.size == WebDh.PRIV_BYTES)
    }

    @Test
    fun `批准时用协商出来的密钥 - 与电脑端算出来的是同一把`() {
        val store = store()
        val (priv, pub) = WebDh.generateKeypair()
        val req = store.request("req-dh-0002", "网页端", pub)

        val device = store.approve(req.requestId, passcode = "", deviceName = "网页端")

        assertNotNull(device)
        // 电脑那边会用自己算出来的共享密钥派生 —— 两边必须一致。
        assertArrayEqualsBytes(
            WebCrypto.deriveKeys(WebDh.sharedSecret(priv, req.ephemeralPublic)).kEnc,
            device!!.keys.kEnc,
        )
    }

    @Test
    fun `状态里会回手机自己的公钥与配对码 - 电脑靠它算出同一把密钥`() {
        val store = store()
        val (_, pub) = WebDh.generateKeypair()
        store.request("req-dh-0003", "网页端", pub)

        val status = store.status("req-dh-0003")

        assertEquals(PairingStore.State.PENDING, status.state)
        assertNotNull("手机要把自己的公钥回给电脑", status.publicKey)
        assertEquals(WebDh.pairCode(pub, status.publicKey!!), status.pairCode)
    }

    @Test
    fun `公钥长度不对时请求直接失败 - 不能拿乱码当公钥`() {
        val store = store()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.request("req-dh-0005", "网页端", ByteArray(10) { 1 })
        }
        assertNull(store.status("req-dh-0005").pairCode)
    }

    @Test
    fun `退化公钥一律拒收 - 否则共享密钥会变成公开常量`() {
        val store = store()
        // 256 字节、值 1：共享值会退化成 1，共享密钥谁都能自己算出来。
        val degenerate = ByteArray(WebDh.KEY_BYTES).also { it[it.lastIndex] = 1 }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.request("req-dh-0006", "网页端", degenerate)
        }
        assertNull(store.approve("req-dh-0006"))
    }

    @Test
    fun `同一个来源申请太频繁会被限流 - 免鉴权接口不能被刷`() {
        val store = store()
        repeat(PairingStore.MAX_REQUESTS_PER_SOURCE) {
            assertTrue(store.tryAcquireRequestSlot("192.168.1.5"))
        }
        assertFalse("超过配额之后要限流", store.tryAcquireRequestSlot("192.168.1.5"))
        assertTrue("别的来源不受影响", store.tryAcquireRequestSlot("192.168.1.6"))
    }

    @Test
    fun `限流窗口过后恢复 - 窗口外的记录会被清掉`() {
        val store = store()
        repeat(PairingStore.MAX_REQUESTS_PER_SOURCE) {
            assertTrue(store.tryAcquireRequestSlot("192.168.1.5"))
        }
        assertFalse(store.tryAcquireRequestSlot("192.168.1.5"))
        assertEquals(1, store.rateLimitSourceCount())

        now += PairingStore.RATE_WINDOW_MS + 1

        assertTrue("窗口过了就该恢复", store.tryAcquireRequestSlot("192.168.1.5"))
        // 清理发生在新一次的检查里：旧记录被丢掉，只剩刚记下的这一条。
        assertEquals(1, store.rateLimitSourceCount())
    }

    @Test
    fun `窗口内没有记录的来源会从限流表里移除`() {
        val store = store()
        store.tryAcquireRequestSlot("192.168.1.5")
        store.tryAcquireRequestSlot("192.168.1.6")
        assertEquals(2, store.rateLimitSourceCount())

        now += PairingStore.RATE_WINDOW_MS + 1
        // 一次检查会先清掉所有过期来源；本次记录让表里只剩当前这个。
        store.tryAcquireRequestSlot("192.168.1.7")
        assertEquals(1, store.rateLimitSourceCount())
    }

    @Test
    fun `并发申请也不能绕过限流 - 检查与记录必须是原子的`() {
        val store = store()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val tasks = (0 until 32).map {
                pool.submit<Boolean> {
                    start.await()
                    store.tryAcquireRequestSlot("192.168.1.5")
                }
            }
            start.countDown()
            val accepted = tasks.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(
                "并发下最多只能放行配额内的请求",
                PairingStore.MAX_REQUESTS_PER_SOURCE,
                accepted,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `待确认的请求有上限 - 超了就拒绝新的`() {
        val store = store()
        repeat(PairingStore.MAX_PENDING_REQUESTS) {
            store.request("req-cap-$it", "网页端", WebDh.generateKeypair().second)
        }
        assertFalse("到上限之后不能再接新的", store.canAcceptMore())
    }

    @Test
    fun `设备空闲到期会连持久化那份一起清掉`() {
        var expired: String? = null
        val store = store { expired = it }
        val device = newDevice(store)
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        now += PairingStore.IDLE_TIMEOUT_MS + 1
        assertFalse(store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
        assertEquals("到期必须回调，把持久化的密钥也删掉", device.deviceId, expired)
    }

    @Test
    fun `活跃时间更新后会持久化 - 且按约 60 秒节流`() {
        val persisted = HashMap<String, Long>()
        val store = PairingStore(
            clock = { now },
            idGenerator = { "dev-seen" },
            onDeviceSeen = { persisted[it.deviceId] = it.lastSeenAt },
        )
        val device = newDevice(store)
        fun verifyNow(nonce: String): Boolean {
            val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", now, nonce, ByteArray(0))
            return store.verify(device.deviceId, "GET", "/api/snapshot", now, nonce, sig, ByteArray(0))
        }

        assertTrue(verifyNow("seen-1"))
        assertEquals("第一次活跃必须落盘", now, persisted[device.deviceId])

        now += 30_000
        assertTrue(verifyNow("seen-2"))
        assertEquals("窗口内的第二次请求不该再写盘", now - 30_000, persisted[device.deviceId])

        now += 31_000
        assertTrue(verifyNow("seen-3"))
        assertEquals("超过 60 秒后要写最新值", now, persisted[device.deviceId])
    }

    @Test
    fun `重启后使用持久化的活跃时间 - 仍活跃的设备不会被误删`() {
        val persisted = HashMap<String, Long>()
        val store = PairingStore(
            clock = { now },
            idGenerator = { "dev-restart" },
            onDeviceSeen = { persisted[it.deviceId] = it.lastSeenAt },
        )
        val device = newDevice(store)
        val pairedAt = device.pairedAt

        // 配对后 20 分钟仍在用：lastSeenAt 推进到此刻，并落盘。
        now += 20 * 60 * 1000L
        val seenSig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", now, "seen", ByteArray(0))
        assertTrue(store.verify(device.deviceId, "GET", "/api/snapshot", now, "seen", seenSig, ByteArray(0)))
        assertEquals(now, persisted[device.deviceId])

        // 又过了 20 分钟进程重启。按 lastSeenAt 只空闲 20 分钟，仍应有效；
        // 若错误地用 pairedAt，就会以为已空闲 40 分钟而把它删掉。
        now += 20 * 60 * 1000L
        assertTrue("测试前提：按配对时间算已经超过 30 分钟", now - pairedAt > PairingStore.IDLE_TIMEOUT_MS)
        val reborn = store()
        reborn.restore(listOf(device.copy(lastSeenAt = persisted[device.deviceId]!!)))
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", now, "after-restart", ByteArray(0))
        assertTrue(
            "持久化的是活跃时间，不是配对时间 —— 重启后不能误删正在用的设备",
            reborn.verify(device.deviceId, "GET", "/api/snapshot", now, "after-restart", sig, ByteArray(0)),
        )
    }

    @Test
    fun `真正超过 30 分钟空闲仍正常过期 - 重启也一样`() {
        var expired: String? = null
        val persisted = HashMap<String, Long>()
        val store = PairingStore(
            clock = { now },
            idGenerator = { "dev-idle" },
            onDeviceExpired = { expired = it },
            onDeviceSeen = { persisted[it.deviceId] = it.lastSeenAt },
        )
        val device = newDevice(store)
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", now, "n1", ByteArray(0))
        assertTrue(store.verify(device.deviceId, "GET", "/api/snapshot", now, "n1", sig, ByteArray(0)))

        now += PairingStore.IDLE_TIMEOUT_MS + 1
        val reborn = PairingStore(
            clock = { now },
            onDeviceExpired = { expired = it },
        )
        reborn.restore(listOf(device.copy(lastSeenAt = persisted[device.deviceId]!!)))
        val laterSig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", now, "n2", ByteArray(0))
        assertFalse(reborn.verify(device.deviceId, "GET", "/api/snapshot", now, "n2", laterSig, ByteArray(0)))
        assertEquals("真正空闲超时仍要清掉持久化密钥", device.deviceId, expired)
    }

    // ── 请求认证 ────────────────────────────────────────────────

    @Test
    fun `签名正确就通过`() {
        val store = store()
        val device = newDevice(store)
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertTrue(store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `签名不对就拒绝`() {
        val store = store()
        val device = newDevice(store)
        val ts = now
        assertFalse(
            store.verify(device.deviceId, "GET", "/api/snapshot", ts, "n1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", ByteArray(0))
        )
    }

    @Test
    fun `换个方法或路径就通不过 - 签名要覆盖它们`() {
        val store = store()
        val device = newDevice(store)
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertFalse(store.verify(device.deviceId, "POST", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
        assertFalse(store.verify(device.deviceId, "GET", "/api/snapshot/apply", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `请求体进了签名 - 换了内容就通不过`() {
        val store = store()
        val device = newDevice(store)
        val ts = now
        val nonce = "n1"
        val body = "{\"a\":1}".toByteArray()
        val sig = WebCrypto.signRequest(device.keys, "POST", "/api/snapshot/apply", ts, nonce, body)

        assertTrue(store.verify(device.deviceId, "POST", "/api/snapshot/apply", ts, nonce, sig, body))
        assertFalse(
            store.verify(device.deviceId, "POST", "/api/snapshot/apply", ts, nonce, sig, "{\"a\":2}".toByteArray())
        )
    }

    @Test
    fun `同一个 nonce 不能用两次 - 防重放`() {
        val store = store()
        val device = newDevice(store)
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertTrue(store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
        assertFalse("同一个 nonce 再来一次必须被拒", store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `时间戳超窗就拒绝 - 隔天的重放无效`() {
        val store = store()
        val device = newDevice(store)
        val ts = now - PairingStore.TS_WINDOW_MS - 1000
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertFalse(store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `不存在的设备一律拒绝`() {
        val store = store()
        assertFalse(store.verify("谁", "GET", "/api/snapshot", now, "n1", "x", ByteArray(0)))
    }

    // ── 撤销与空闲 ──────────────────────────────────────────────

    @Test
    fun `撤销之后这台设备立刻失效`() {
        val store = store()
        val device = newDevice(store)
        assertTrue(store.revoke(device.deviceId))
        assertNull(store.device(device.deviceId))
        assertFalse(
            store.verify(
                device.deviceId, "GET", "/api/snapshot", now, "n1",
                WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", now, "n1", ByteArray(0)),
                ByteArray(0),
            )
        )
    }

    @Test
    fun `空闲太久就当它走了`() {
        val store = store()
        val device = newDevice(store)
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        now += PairingStore.IDLE_TIMEOUT_MS + 1000

        assertFalse(
            store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0))
        )
    }

    @Test
    fun `重启后恢复设备 - 浏览器不该被踢下线`() {
        val store = store()
        val device = newDevice(store)

        val reborn = store()
        assertNull(reborn.device(device.deviceId))
        reborn.restore(listOf(device))
        assertNotNull(reborn.device(device.deviceId))
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "字节不一致",
            expected.toList(),
            actual.toList(),
        )
    }
}
