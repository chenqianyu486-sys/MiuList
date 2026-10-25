package com.example.data.sync.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对状态机（协议 v3）。
 *
 * 这一版的重点变了：**secret 不再上网络**。
 *
 * v2 的配对码由服务端保存、网页端用查询参数轮询状态 ——
 * 局域网里抓到那次轮询请求的人就拿到了配对码，接着能自己去领 token。
 * v3 改成：网页端本地生成 secret，服务端只见到它的摘要 `requestId`；
 * secret 只经「二维码 → 手机相机」这条视觉通道到手机上。
 *
 * 所以这里最要紧的用例是：**摘要对不上就批准不了** ——
 * 那正是「二维码被人改过」的防线。
 */
class PairingStoreTest {

    private var now = 1_000_000L
    private var idSeq = 0

    private val secret = ByteArray(32) { it.toByte() }

    private fun store() = PairingStore(
        clock = { now },
        idGenerator = { "dev-${idSeq++}" },
    )

    private fun requestIdOf(s: ByteArray = secret) = PairingStore().requestIdOf(s)

    // ── 申请 ────────────────────────────────────────────────────

    @Test
    fun `申请配对本身不授予任何权限`() {
        val store = store()
        val req = store.request(requestIdOf())

        assertEquals(PairingStore.State.PENDING, req.state)
        assertTrue("申请配对不该产生任何设备", store.pairedDevices().isEmpty())
    }

    @Test
    fun `同一个 requestId 重复申请会复用同一条`() {
        val store = store()
        val a = store.request(requestIdOf())
        val b = store.request(requestIdOf())
        assertEquals(a.requestId, b.requestId)
    }

    // ── 批准 ────────────────────────────────────────────────────

    @Test
    fun `批准之后产生一台设备 - 且密钥由 secret 派生`() {
        val store = store()
        val req = store.request(requestIdOf(), "网页端")

        val device = store.approve(req.requestId, secret, passcode = "", deviceName = "网页端")

        assertNotNull(device)
        assertEquals("网页端", device!!.deviceName)
        assertEquals(1, store.pairedDevices().size)
        assertNotNull(store.device(device.deviceId))
        // 派生出来的密钥必须等于「拿同一个 secret 现算」的密钥。
        assertArrayEqualsBytes(
            WebCrypto.deriveKeys(secret).kEnc,
            device.keys.kEnc,
        )
    }

    @Test
    fun `摘要对不上就批准不了 - 这就是二维码被改过的防线`() {
        val store = store()
        // 请求用的是 secretA 的摘要，批准时拿的是 secretB。
        store.request(requestIdOf(ByteArray(32) { 9 }))

        val device = store.approve(requestIdOf(ByteArray(32) { 9 }), ByteArray(32) { 7 })

        assertNull("secret 与 requestId 不配对时必须拒绝", device)
        assertTrue(store.pairedDevices().isEmpty())
    }

    @Test
    fun `没申请过就批准不了`() {
        val store = store()
        assertNull(store.approve(requestIdOf(), secret))
    }

    @Test
    fun `过了有效期就不能批准了`() {
        val store = store()
        val req = store.request(requestIdOf())

        now += PairingStore.CODE_TTL_MS + 1

        assertNull(store.approve(req.requestId, secret))
        assertEquals(PairingStore.State.EXPIRED, store.status(req.requestId).state)
    }

    @Test
    fun `拒绝之后不能批准`() {
        val store = store()
        val req = store.request(requestIdOf())
        assertTrue(store.deny(req.requestId))
        assertNull(store.approve(req.requestId, secret))
    }

    @Test
    fun `带同步密码时密钥会不同 - 密码错了就连不上`() {
        // **两次批准要用两个不同的请求** —— 同一个 requestId 只能批准一次
        // （批准后它就作废了，正是「一次性配对码」的意思）。
        val withPw = store().let { s ->
            s.approve(s.request(s.requestIdOf(secret)).requestId, secret, passcode = "1234")
        }
        val withoutPw = store().let { s ->
            s.approve(s.request(s.requestIdOf(secret)).requestId, secret, passcode = "")
        }

        assertNotNull(withPw)
        assertNotNull(withoutPw)
        assertFalse(
            "带密码与不带密码必须派生出不同的密钥",
            withPw!!.keys.kEnc.contentEquals(withoutPw!!.keys.kEnc),
        )
    }

    // ── 请求认证 ────────────────────────────────────────────────

    @Test
    fun `签名正确就通过`() {
        val store = store()
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertTrue(store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `签名不对就拒绝`() {
        val store = store()
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
        val ts = now
        assertFalse(
            store.verify(device.deviceId, "GET", "/api/snapshot", ts, "n1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", ByteArray(0))
        )
    }

    @Test
    fun `换个方法或路径就通不过 - 签名要覆盖它们`() {
        val store = store()
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertFalse(store.verify(device.deviceId, "POST", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
        assertFalse(store.verify(device.deviceId, "GET", "/api/snapshot/apply", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `请求体进了签名 - 换了内容就通不过`() {
        val store = store()
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
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
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
        val ts = now
        val nonce = "n1"
        val sig = WebCrypto.signRequest(device.keys, "GET", "/api/snapshot", ts, nonce, ByteArray(0))

        assertTrue(store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
        assertFalse("同一个 nonce 再来一次必须被拒", store.verify(device.deviceId, "GET", "/api/snapshot", ts, nonce, sig, ByteArray(0)))
    }

    @Test
    fun `时间戳超窗就拒绝 - 隔天的重放无效`() {
        val store = store()
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
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
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
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
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!
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
        val device = store.approve(store.request(requestIdOf()).requestId, secret)!!

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
