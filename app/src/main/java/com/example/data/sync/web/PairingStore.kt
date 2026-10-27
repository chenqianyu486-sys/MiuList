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
    /**
     * 设备因**空闲超时**被踢掉时的回调。
     *
     * 之所以要有：只在内存里删掉，只是「这一轮进程里失效」——
     * 密钥还在 Keystore 加密存储里，进程一重启它又被装回来。
     * 真正的撤销必须连**持久化那份**一起删。
     */
    private val onDeviceExpired: (String) -> Unit = {},
    /**
     * 设备成功验签、`lastSeenAt` 被推进后的回调。
     *
     * 不能只在退出时保存：进程可能被系统直接回收，那条路根本没有机会执行。
     * 调用方（`WebSyncService`）负责把时间写进 SharedPreferences；
     * 这里按 [LAST_SEEN_PERSIST_INTERVAL_MS] 节流，避免每个请求都落盘。
     */
    private val onDeviceSeen: (PairedDevice) -> Unit = {},
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
        /**
         * **服务端观察到的对端地址**（`ip:port`）。
         *
         * 确认卡上要告诉用户「是谁在请求连接」—— 用它，而不是客户端自报的
         * `deviceName`：自报的名字谁都能写成「我的电脑」，
         * 而这个地址是从 TCP 连接上读出来的，**客户端伪造不了**。
         */
        val sourceAddress: String = "",
    ) {
        /**
         * **配对码**：给用户肉眼核对的两端一致的 6 位码。
         *
         * - 新协议（v3.2）：**双方公钥的转录绑定哈希** `H(A‖B)`；
         * - 老协议：`requestId` 的前 6 位（老网页端显示的就是这个）。
         *
         * 新协议下两端都能算这个码：电脑有 A 与手机回的 B，
         * 手机有 A 与自己的 B。中间人换掉任何一个，两端就对不上。
         */
        val pairCode: String
            get() {
                val a = peerPublicKey
                if (a != null) {
                    val b = ephemeralPublic ?: return ""
                    return WebDh.pairCode(a, b)
                }
                return requestId.take(6).uppercase()
            }

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

    /**
     * 保护下面几份可变状态。
     *
     * 配对请求来自 HTTP 工作线程，批准/撤销来自 UI 线程；`pending` 里的
     * [PendingRequest] 字段也会被两边读写。这里只做**最小范围**同步：
     * 所有入口在同一把锁下改/读这几份表，回调在锁外执行。
     */
    private val lock = Any()
    private val pending = LinkedHashMap<String, PendingRequest>()
    private val devices = LinkedHashMap<String, PairedDevice>()

    /** deviceId → (nonce → 用掉的时间)。用来挡重放。 */
    private val usedNonces = HashMap<String, HashMap<String, Long>>()

    /** 来源 IP → 最近的申请时刻。用来限流（见 [tryAcquireRequestSlot]）。 */
    private val requestHits = HashMap<String, ArrayDeque<Long>>()

    /** deviceId → 上次把 lastSeenAt 回调给持久化层的时间。 */
    private val lastSeenNotifiedAt = HashMap<String, Long>()
    // ── 申请与批准 ─────────────────────────────────────────────

    /**
     * 申请配对。
     *
     * @param requestId `requestIdOf(secret)`（老协议），或一个随机 id（新协议）。
     * @param peerPublicKey 电脑的 DH 公钥。**给了它，手机就能自己算出共享密钥** ——
     *   这条「只填地址」的路就是这样走通的：过网的只有公钥。
     * @param sourceAddress 服务端观察到的对端地址。**客户端伪造不了**，
     *   所以它才是确认卡上能安全展示的「是谁在请求连接」。
     */
    fun request(
        requestId: String,
        deviceName: String = "",
        peerPublicKey: ByteArray? = null,
        sourceAddress: String = "",
        legacySecret: ByteArray? = null,
    ): PendingRequest = synchronized(lock) {
        purgeExpiredLocked()
        val now = clock()
        val existing = pending[requestId]
        if (existing != null && existing.state == State.PENDING) {
            // **补公钥**：两条通道（HTTP 请求 / 二维码深链）谁先到都行 ——
            // 先到的那条只带 requestId，后到的那条把公钥补上。
            if (peerPublicKey != null) attachPeerKeyLocked(existing, peerPublicKey)
            // 老协议：secret 从二维码那条路补进来。已经有共享密钥时不覆盖。
            if (legacySecret != null && existing.sharedSecret == null && existing.legacySecret == null) {
                existing.legacySecret = legacySecret
            }
            // 返回快照而不是表里的可变对象：调用方（HTTP/UI 线程）之后读到的
            // 字段不会再被另一条线程改掉。密钥材料字段只在锁内赋值一次。
            return@synchronized existing.copy()
        }
        val req = PendingRequest(
            requestId = requestId,
            requestedAt = now,
            expiresAt = now + CODE_TTL_MS,
            deviceName = deviceName,
            sourceAddress = sourceAddress,
        )
        if (peerPublicKey != null) attachPeerKeyLocked(req, peerPublicKey)
        if (legacySecret != null && req.sharedSecret == null) req.legacySecret = legacySecret
        pending[requestId] = req
        req.copy()
    }

    /**
     * 原子地「检查并记录一次申请」。
     *
     * `/api/pair/request` 是**免鉴权**的 —— 局域网里任何设备都能调它。
     * 不限流的后果不是「多几条记录」，而是：
     *
     * - 用户被**反复弹确认卡**打断；
     * - 每条请求都要生成一对 DH 密钥，白烧 CPU；
     * - 内存里的 pending 表一直涨。
     *
     * **检查和记录必须在同一把锁里完成**：拆成
     * `if (!isRateLimited()) recordRequest()` 的话，并发请求会同时看到
     * 「还没超限」，然后一起写进去。
     *
     * @param sourceAddress 来源 **IP**（不带端口；端口每次连接都变）。
     * @return true = 本次申请已记入配额；false = 已超限，调用方应回 429。
     */
    fun tryAcquireRequestSlot(sourceAddress: String): Boolean = synchronized(lock) {
        if (sourceAddress.isBlank()) return@synchronized true
        val now = clock()
        pruneRateHitsLocked(now)
        val hits = requestHits.getOrPut(sourceAddress) { ArrayDeque() }
        if (hits.size >= MAX_REQUESTS_PER_SOURCE) return@synchronized false
        hits.addLast(now)
        true
    }

    /** 仅供测试/诊断：当前还在限流表里的来源数量。 */
    internal fun rateLimitSourceCount(): Int = synchronized(lock) {
        pruneRateHitsLocked(clock())
        requestHits.size
    }

    /**
     * 现在还能不能接一条新的待确认请求。
     *
     * 超过上限就**拒绝新的** —— 已有的那些不受影响，
     * 否则用户正要点「允许」的那条会被后来的挤掉。
     */
    fun canAcceptMore(): Boolean = synchronized(lock) {
        purgeExpiredLocked()
        pending.values.count { it.state == State.PENDING } < MAX_PENDING_REQUESTS
    }

    /**
     * 把电脑的 DH 公钥接到一条待批准的请求上，并**当场协商出共享密钥**。
     *
     * 为什么「当场」：共享密钥一旦定下来，配对码也就定下来了 ——
     * 界面要立刻把它显示出来让用户核对。
     */
    private fun attachPeerKeyLocked(req: PendingRequest, peerPublicKey: ByteArray) {
        // 已经有密钥材料就不覆盖：同一请求重复到达时，保留第一次协商的结果
        // （换了公钥就换了一把密钥，两端会对不上）。
        if (req.peerPublicKey != null) return
        if (peerPublicKey.size != WebDh.KEY_BYTES) return
        // **退化公钥一律拒收**：公钥 = 1 会让共享密钥变成公开常量
        // （详见 [WebDh.validatePublicKey]）。
        runCatching { WebDh.validatePublicKey(peerPublicKey) }.getOrElse { return }
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
    ): PairedDevice? = synchronized(lock) {
        purgeExpiredLocked()
        val req = pending[requestId] ?: return@synchronized null
        if (req.state != State.PENDING) return@synchronized null
        if (clock() > req.expiresAt) {
            req.state = State.EXPIRED
            return@synchronized null
        }
        // 新协议：共享密钥已经协商好了。
        // 老协议：secret 由二维码带来，这时才校验摘要。
        val effective = req.sharedSecret ?: secret ?: req.legacySecret ?: return@synchronized null
        if (req.sharedSecret == null && requestIdOf(effective) != requestId) return@synchronized null

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
        device
    }

    fun deny(requestId: String): Boolean = synchronized(lock) {
        val req = pending[requestId] ?: return@synchronized false
        if (req.state != State.PENDING) return@synchronized false
        req.state = State.DENIED
        true
    }

    /**
     * 查配对状态。
     *
     * **未加密、免鉴权**，但里面**没有凭据** —— 只有状态与 deviceId。
     * 抓到这条请求的人知道「有个设备在等」，但没有 secret，
     * 拿不到 token、也算不出签名。
     */
    fun status(requestId: String): Status = synchronized(lock) {
        purgeExpiredLocked()
        val req = pending[requestId] ?: return@synchronized Status(State.EXPIRED)
        Status(
            state = req.state,
            deviceId = req.deviceId,
            deviceName = req.deviceName.takeIf { it.isNotBlank() },
            publicKey = req.ephemeralPublic,
            pairCode = req.pairCode.takeIf { req.hasKeyMaterial },
        )
    }

    // ── 设备 ───────────────────────────────────────────────────

    /** 这条待确认请求还在不在（且还等着确认）。 */
    fun hasPending(requestId: String): Boolean = synchronized(lock) {
        purgeExpiredLocked()
        pending[requestId]?.state == State.PENDING
    }

    fun device(deviceId: String): PairedDevice? = synchronized(lock) { devices[deviceId] }

    fun pairedDevices(): List<PairedDevice> = synchronized(lock) { devices.values.toList() }

    fun revoke(deviceId: String): Boolean = synchronized(lock) {
        usedNonces.remove(deviceId)
        lastSeenNotifiedAt.remove(deviceId)
        devices.remove(deviceId) != null
    }

    /** 进程重启后恢复（密钥本身要由调用方从加密存储里读出来）。 */
    fun restore(list: List<PairedDevice>) = synchronized(lock) {
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
        var expiredId: String? = null
        var seen: PairedDevice? = null
        val ok = synchronized(lock) {
            val device = devices[deviceId] ?: return@synchronized false
            val now = clock()
            if (now - device.lastSeenAt > IDLE_TIMEOUT_MS) {
                devices.remove(deviceId)
                usedNonces.remove(deviceId)
                lastSeenNotifiedAt.remove(deviceId)
                // **连持久化那份一起删** —— 只删内存里的，进程一重启它又被装回来，
                // 于是「半小时空闲自动失效」这句话是假的。
                expiredId = deviceId
                return@synchronized false
            }
            if (abs(now - ts) > TS_WINDOW_MS) return@synchronized false
            if (nonce.isBlank()) return@synchronized false

            val used = usedNonces.getOrPut(deviceId) { HashMap() }
            used.entries.removeAll { now - it.value > TS_WINDOW_MS }
            if (used.containsKey(nonce)) return@synchronized false

            val expected = WebCrypto.signRequest(device.keys, method, path, ts, nonce, body)
            if (!constantTimeEquals(expected, signature)) return@synchronized false

            used[nonce] = now
            device.lastSeenAt = now
            // 约 60 秒落盘一次：既不漏掉「持续活跃」，也不让每个请求都写 SharedPreferences。
            // 60 秒远小于 30 分钟空闲超时，进程被直接回收时最多丢掉这一小段。
            val lastNotified = lastSeenNotifiedAt[deviceId]
            if (lastNotified == null || now - lastNotified >= LAST_SEEN_PERSIST_INTERVAL_MS) {
                lastSeenNotifiedAt[deviceId] = now
                seen = device.copy()
            }
            true
        }
        // 回调放在锁外：它们可能碰 SharedPreferences / Keystore，不该拖住验签路径。
        expiredId?.let(onDeviceExpired)
        seen?.let(onDeviceSeen)
        return ok
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

    private fun purgeExpiredLocked() {
        val now = clock()
        pending.entries.removeAll { (_, req) ->
            if (req.state == State.PENDING && now > req.expiresAt) {
                req.state = State.EXPIRED
                true
            } else {
                false
            }
        }
        // 已处理完的请求（已批准 / 已拒绝 / 已过期）**不留着**。
        //
        // 留着没有用途，却会一直占着内存；而这些记录里还有共享密钥
        // 与一次性私钥 —— 过期之后继续留着它们没有道理。
        pending.entries.removeAll { (_, req) ->
            req.state != State.PENDING && now > req.requestedAt + CODE_TTL_MS
        }
        pruneRateHitsLocked(now)
    }

    /** 丢掉限流窗口外的记录，顺带清掉窗口内已经没有记录的来源。 */
    private fun pruneRateHitsLocked(now: Long) {
        val iterator = requestHits.entries.iterator()
        while (iterator.hasNext()) {
            val hits = iterator.next().value
            while (hits.isNotEmpty() && now - hits.first() > RATE_WINDOW_MS) {
                hits.removeFirst()
            }
            if (hits.isEmpty()) iterator.remove()
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

        /**
         * `lastSeenAt` 的落盘节流：约 60 秒最多写一次。
         *
         * 比 [IDLE_TIMEOUT_MS] 短得多，所以进程随时被回收，持久化时间
         * 也不会陈旧到把一台仍活跃的设备误判为空闲。
         */
        const val LAST_SEEN_PERSIST_INTERVAL_MS = 60 * 1000L

        /** 同一个来源在 [RATE_WINDOW_MS] 内最多能申请几次配对。 */
        const val MAX_REQUESTS_PER_SOURCE = 5

        /** 限流窗口（1 分钟）。 */
        const val RATE_WINDOW_MS = 60 * 1000L

        /**
         * 同时最多保留几条待确认请求。
         *
         * 超过就拒绝新的 —— 一来控制内存，二来避免手机上堆满
         * 陌生设备弹出来的确认卡。
         */
        const val MAX_PENDING_REQUESTS = 8
    }
}
