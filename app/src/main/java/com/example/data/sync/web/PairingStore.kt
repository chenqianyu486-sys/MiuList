package com.example.data.sync.web

import kotlin.math.abs

/**
 * 配对与设备凭据（协议 v3）。
 *
 * ## 与 v2 最大的差别：**配对码不再上网络**
 *
 * v2 的配对码是网页端生成、服务端保存、网页端用**查询参数**轮询状态 ——
 * 于是局域网里任何人只要抓到那次轮询请求，就拿到了配对码，
 * 紧接着就能自己去领 token。**抓包 = 拿到钥匙。**
 *
 * v3 改成：
 *
 * 1. 网页端本地生成 32 字节随机 secret（`crypto.getRandomValues`），
 *    只把它的**摘要**（`requestId`）发给服务端；
 * 2. 二维码里带的是 **secret 本身** —— 它只走「二维码 → 手机相机」这条视觉通道，
 *    **从来不经过网络**；
 * 3. 手机扫到 secret 后自己算一遍摘要，跟服务端那条对上才批准。
 *
 * 于是抓包能看到的只有 `requestId`（sha256 摘要，不可逆）与之后的一切密文。
 *
 * ## 之后的每次请求：挑战-应答
 *
 * 不再传「可复用的凭据」，而是每次请求现算一个签名：
 *
 * ```
 * sig = HMAC(kAuth, "v3\nMETHOD\n/path\nts\nnonce\nbase64url(sha256(body))")
 * ```
 *
 * - **抓包拿到的 sig 不能重放**：nonce 每个请求随机，且服务端记着「用过的 nonce」；
 * - **抓包拿到的 sig 不能伪造**：不知道 kAuth 就算不出新的 sig；
 * - **请求体进了签名**：否则密文可以被换成另一段密文。
 *
 * 时间戳窗口（±[TS_WINDOW_MS]）再挡掉「隔天重放」。
 */
class PairingStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { WebCrypto.base64Url(WebCrypto.randomBytes(9)) },
) {

    enum class State { PENDING, APPROVED, DENIED, EXPIRED }

    /**
     * 一次待批准的配对请求。
     *
     * ## 里面能装两种密钥材料
     *
     * - **老协议（v3）**：`legacySecret` —— secret 本身从二维码过来。
     *   服务端只见到它的摘要（`requestId`），抓包的人拿不到 secret。
     * - **新协议（v3.1）**：`peerPublicKey` + `sharedSecret` ——
     *   电脑的 DH 公钥换来的共享密钥。**过网的只有公钥**。
     *
     * 两者的差别只在「密钥怎么到手机上」，后面的密钥派生、
     * 签名、加密全都一样。
     */
    data class PendingRequest(
        val requestId: String,
        val requestedAt: Long,
        val expiresAt: Long,
        var state: State = State.PENDING,
        var deviceId: String? = null,
        var deviceName: String = "",
        /** 电脑的 DH 公钥。null = 还没拿到（老协议，或深链还没到）。 */
        var peerPublicKey: ByteArray? = null,
        /** 手机自己的一次性私钥。**只在内存里。** */
        var ephemeralPrivate: ByteArray? = null,
        /** 手机的一次性公钥 —— 要回给电脑，它靠它算出同一把共享密钥。 */
        var ephemeralPublic: ByteArray? = null,
        /** 协商出来的共享密钥（32 字节）。 */
        var sharedSecret: ByteArray? = null,
        /** 老协议：secret 本身（二维码带来的）。 */
        var legacySecret: ByteArray? = null,
    ) {
        /**
         * **配对码**：给用户肉眼核对的两端一致的 6 位码。
         *
         * - 新协议：公钥的哈希（两端一开始就有同一个公钥，立刻能算）；
         * - 老协议：`requestId` 的前 6 位（老网页端显示的就是这个）。
         */
        val pairCode: String
            get() = peerPublicKey?.let { WebDh.pairCode(it) }
                ?: requestId.take(6).uppercase()

        /** 这条请求现在**配不配得成** —— 没有密钥材料就还不成。 */
        val hasKeyMaterial: Boolean
            get() = sharedSecret != null || legacySecret != null
    }

    /**
     * 一台已配对的设备。
     *
     * [keys] 是从 secret 派生的三把子密钥 —— **它等价于 secret 本身**，
     * 泄露了它就等于泄露了密钥，所以存盘时要加密（见 `WebSyncService`）。
     */
    data class PairedDevice(
        val deviceId: String,
        val keys: WebCrypto.Keys,
        val deviceName: String,
        val pairedAt: Long,
        var lastSeenAt: Long,
    )

    /** 轮询配对状态时返回给网页端的东西。**不含任何凭据。** */
    data class Status(
        val state: State,
        val deviceId: String? = null,
        val deviceName: String? = null,
        /**
         * 手机的一次性公钥 —— 电脑靠它算出**同一把**共享密钥。
         *
         * **它是公钥，不是秘密**：抓包的人看到它也算不出共享密钥
         * （DH 的单向性）。没有它，电脑就没有 secret可用。
         */
        val publicKey: ByteArray? = null,
        /** 配对码。电脑拿它跟自己算出来的一比 —— 对不上就是有人在中间换公钥。 */
        val pairCode: String? = null,
    )

    private val pending = LinkedHashMap<String, PendingRequest>()
    private val devices = LinkedHashMap<String, PairedDevice>()

    /** deviceId → (nonce → 用掉的时间)。用来挡重放。 */
    private val usedNonces = HashMap<String, HashMap<String, Long>>()

    // ── 申请与批准 ─────────────────────────────────────────────

    /**
     * 申请配对。
     *
     * @param requestId `requestIdOf(secret)`（老协议），或一个随机 id（新协议）。
     * @param peerPublicKey 电脑的 DH 公钥。**给了它，手机就能自己算出共享密钥** ——
     *   这条「只填地址」的路就是这样走通的：过网的只有公钥。
     */
    fun request(
        requestId: String,
        deviceName: String = "",
        peerPublicKey: ByteArray? = null,
    ): PendingRequest {
        purgeExpired()
        val now = clock()
        val existing = pending[requestId]
        if (existing != null && existing.state == State.PENDING) {
            // **补公钥**：两条通道（HTTP 请求 / 二维码深链）谁先到都行 ——
            // 先到的那条只带 requestId，后到的那条把公钥补上。
            if (peerPublicKey != null) attachPeerKey(existing, peerPublicKey)
            return existing
        }
        val req = PendingRequest(
            requestId = requestId,
            requestedAt = now,
            expiresAt = now + CODE_TTL_MS,
            deviceName = deviceName,
        )
        if (peerPublicKey != null) attachPeerKey(req, peerPublicKey)
        pending[requestId] = req
        return req
    }

    /**
     * 把电脑的 DH 公钥接到一条待批准的请求上，并**当场协商出共享密钥**。
     *
     * 为什么「当场」：共享密钥一旦定下来，配对码也就定下来了 ——
     * 界面要立刻把它显示出来让用户核对。
     */
    private fun attachPeerKey(req: PendingRequest, peerPublicKey: ByteArray) {
        // 已经有密钥材料就不覆盖：同一请求重复到达时，保留第一次协商的结果
        // （换了公钥就换了一把密钥，两端会对不上）。
        if (req.peerPublicKey != null) return
        if (peerPublicKey.size != WebDh.KEY_BYTES) return
        val (priv, pub) = WebDh.generateKeypair()
        req.peerPublicKey = peerPublicKey
        req.ephemeralPrivate = priv
        req.ephemeralPublic = pub
        req.sharedSecret = WebDh.sharedSecret(priv, peerPublicKey)
    }

    /**
     * 批准 —— **由用户在手机上点「允许」后调用**。
     *
     * @param secret 老协议（二维码带来的那个 secret）。新协议传 null ——
     *   共享密钥在 [request] 收到公钥时就算好了。
     * @param secret 老协议里会**重算一遍摘要**跟 [requestId] 对上：
     *   二维码被人改过（比如把 requestId 换成另一台设备的）就会在这里被挡住。
     */
    fun approve(
        requestId: String,
        secret: ByteArray? = null,
        passcode: String = "",
        deviceName: String = "",
    ): PairedDevice? {
        purgeExpired()
        val req = pending[requestId] ?: return null
        if (req.state != State.PENDING) return null
        if (clock() > req.expiresAt) {
            req.state = State.EXPIRED
            return null
        }
        // 新协议：共享密钥已经协商好了。
        // 老协议：secret 由二维码带来，这时才校验摘要。
        val effective = req.sharedSecret ?: secret ?: req.legacySecret ?: return null
        if (req.sharedSecret == null && requestIdOf(effective) != requestId) return null

        val deviceId = idGenerator()
        val now = clock()
        val device = PairedDevice(
            deviceId = deviceId,
            keys = WebCrypto.deriveKeys(effective, passcode),
            deviceName = deviceName.ifBlank { req.deviceName.ifBlank { "网页端" } },
            pairedAt = now,
            lastSeenAt = now,
        )
        devices[deviceId] = device
        req.state = State.APPROVED
        req.deviceId = deviceId
        req.deviceName = device.deviceName
        return device
    }

    fun deny(requestId: String): Boolean {
        val req = pending[requestId] ?: return false
        if (req.state != State.PENDING) return false
        req.state = State.DENIED
        return true
    }

    /**
     * 查配对状态。
     *
     * **未加密、免鉴权**，但里面**没有凭据** —— 只有状态与 deviceId。
     * 抓到这条请求的人知道「有个设备在等」，但没有 secret，
     * 拿不到 token、也算不出签名。
     */
    fun status(requestId: String): Status {
        purgeExpired()
        val req = pending[requestId] ?: return Status(State.EXPIRED)
        return Status(
            state = req.state,
            deviceId = req.deviceId,
            deviceName = req.deviceName.takeIf { it.isNotBlank() },
            publicKey = req.ephemeralPublic,
            pairCode = req.pairCode.takeIf { req.hasKeyMaterial },
        )
    }

    // ── 设备 ───────────────────────────────────────────────────

    fun device(deviceId: String): PairedDevice? = devices[deviceId]

    fun pairedDevices(): List<PairedDevice> = devices.values.toList()

    fun revoke(deviceId: String): Boolean {
        usedNonces.remove(deviceId)
        return devices.remove(deviceId) != null
    }

    /** 进程重启后恢复（密钥本身要由调用方从加密存储里读出来）。 */
    fun restore(list: List<PairedDevice>) {
        for (d in list) devices[d.deviceId] = d
    }

    // ── 请求认证 ───────────────────────────────────────────────

    /**
     * 校验一次请求的签名。
     *
     * 三道关：
     * 1. 设备还在、且没空闲过期；
     * 2. 时间戳在窗口内；
     * 3. nonce 没用过（防重放）。
     *
     * 过了这三道才比签名 —— **顺序不能换**：
     * 先比签名的话，重放攻击要先把时间戳改成新的，而那需要密钥。
     */
    fun verify(
        deviceId: String,
        method: String,
        path: String,
        ts: Long,
        nonce: String,
        signature: String,
        body: ByteArray,
    ): Boolean {
        val device = devices[deviceId] ?: return false
        val now = clock()
        if (now - device.lastSeenAt > IDLE_TIMEOUT_MS) {
            devices.remove(deviceId)
            return false
        }
        if (abs(now - ts) > TS_WINDOW_MS) return false
        if (nonce.isBlank()) return false

        val used = usedNonces.getOrPut(deviceId) { HashMap() }
        used.entries.removeAll { now - it.value > TS_WINDOW_MS }
        if (used.containsKey(nonce)) return false

        val expected = WebCrypto.signRequest(device.keys, method, path, ts, nonce, body)
        if (!constantTimeEquals(expected, signature)) return false

        used[nonce] = now
        device.lastSeenAt = now
        return true
    }

    // ── 工具 ───────────────────────────────────────────────────

    /**
     * secret → requestId。
     *
     * 取摘要的**前 16 字节**（128 位）转 base64url：
     * 足够长（猜不出来），又短到能塞进二维码与 URL。
     */
    fun requestIdOf(secret: ByteArray): String =
        WebCrypto.base64Url(WebCrypto.sha256(secret).copyOfRange(0, 16))

    private fun purgeExpired() {
        val now = clock()
        pending.entries.removeAll { (id, req) ->
            if (req.state == State.PENDING && now > req.expiresAt) {
                req.state = State.EXPIRED
                true
            } else {
                false
            }
        }
    }

    /** 定长比较，避免「比较耗时不同」泄露信息。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        /** 配对码有效期。5 分钟，与 OpenCode 的 `opencode pair` 一致。 */
        const val CODE_TTL_MS = 5 * 60 * 1000L

        /** 签名时间戳窗口。±90 秒：够容忍两台机器的时钟差，又短到隔天重放无效。 */
        const val TS_WINDOW_MS = 90 * 1000L

        /** 设备空闲多久算失效。半小时没来请求就当它走了。 */
        const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
