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

    /** 一次待批准的配对请求。**只存摘要，不存 secret。** */
    data class PendingRequest(
        val requestId: String,
        val requestedAt: Long,
        val expiresAt: Long,
        var state: State = State.PENDING,
        var deviceId: String? = null,
        var deviceName: String = "",
    )

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
    )

    private val pending = LinkedHashMap<String, PendingRequest>()
    private val devices = LinkedHashMap<String, PairedDevice>()

    /** deviceId → (nonce → 用掉的时间)。用来挡重放。 */
    private val usedNonces = HashMap<String, HashMap<String, Long>>()

    // ── 申请与批准 ─────────────────────────────────────────────

    /**
     * 申请配对。
     *
     * @param requestId `requestIdOf(secret)`，服务端**只能看到这个摘要**。
     */
    fun request(requestId: String, deviceName: String = ""): PendingRequest {
        purgeExpired()
        val now = clock()
        val existing = pending[requestId]
        if (existing != null && existing.state == State.PENDING) return existing
        val req = PendingRequest(
            requestId = requestId,
            requestedAt = now,
            expiresAt = now + CODE_TTL_MS,
            deviceName = deviceName,
        )
        pending[requestId] = req
        return req
    }

    /**
     * 批准 —— **由用户在手机上点「允许」后调用**。
     *
     * @param secret 二维码里带来的那个 secret。
     *   这里会**重算一遍摘要**跟 [requestId] 对上：二维码被人改过
     *   （比如把 code 换成另一台设备的 requestId）就会在这里被挡住。
     */
    fun approve(
        requestId: String,
        secret: ByteArray,
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
        if (requestIdOf(secret) != requestId) return null

        val deviceId = idGenerator()
        val now = clock()
        val device = PairedDevice(
            deviceId = deviceId,
            keys = WebCrypto.deriveKeys(secret, passcode),
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
