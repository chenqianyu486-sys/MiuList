package com.example.data.sync.web

import kotlin.math.abs

/**
 * 配对与设备凭据。
 *
 * ## 配对只有一条链路：电脑发起、手机确认
 *
 * 1. 电脑生成一对临时 DH 密钥，`POST /api/pair/request` 把**公钥**带过来；
 * 2. 手机收到后当场生成自己的一对临时密钥，算出共享密钥，并推出确认卡；
 * 3. 用户在手机上核对 6 位配对码（两端公钥的转录哈希 `H(A‖B)`）再点「允许」；
 * 4. 电脑轮询到 approved，用自己的私钥和手机的公钥算出**同一把**共享密钥。
 *
 * **过网的只有公钥** —— 抓包的人看到公钥也算不出共享密钥（DH 的单向性），
 * 密钥自始至终没有在链路上出现过。
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
     * 电脑的公钥一到，手机就**当场**生成自己的一次性密钥对、算出共享密钥 ——
     * 配对码要立刻显示出来让用户核对，协商不能等到「允许」那一刻。
     *
     * 密钥材料只在内存里：请求过期或被清理时一起丢掉。
     */
    data class PendingRequest(
        val requestId: String,
        val requestedAt: Long,
        val expiresAt: Long,
        var state: State = State.PENDING,
        var deviceId: String? = null,
        var deviceName: String = "",
        /** 电脑的 DH 公钥。 */
        val peerPublicKey: ByteArray,
        /** 手机自己的一次性私钥。**只在内存里。** */
        val ephemeralPrivate: ByteArray,
        /** 手机的一次性公钥 —— 要回给电脑，它靠它算出同一把共享密钥。 */
        val ephemeralPublic: ByteArray,
        /** 协商出来的共享密钥（32 字节）。 */
        val sharedSecret: ByteArray,
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
         * 它是**双方公钥的转录绑定哈希** `H(A‖B)`：电脑有 A 与手机回的 B，
         * 手机有 A 与自己的 B。中间人换掉任何一个，两端就对不上。
         */
        val pairCode: String get() = WebDh.pairCode(peerPublicKey, ephemeralPublic)
    }

    /**
     * 一台已配对的设备。
     *
     * [keys] 是从**协商出来的共享密钥**派生的三把子密钥 ——
     * 它等价于共享密钥本身，所以存盘时要加密（见 `WebSyncService`）。
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
         * （DH 的单向性）。没有它，电脑就算不出共享密钥。
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

    /**
     * 已经处理完的 requestId —— 连同处理完的时刻。
     *
     * ## 为什么待确认表不够
     *
     * 待确认表里处理完的条目只留到 [CODE_TTL_MS]（见 [purgeExpiredLocked]），
     * 过了就被清掉。但「这条已经批准过了」这件事**不该跟着表一起被忘掉**：
     * 同一个 requestId 再来一次时，服务端要是忘了、重新协商一把新密钥，
     * 手机就会**再弹一张确认卡** —— 卡上的码是那把**新密钥**算出来的，
     * 电脑手里那把对不上，用户看到的只是「怎么一直对不上」。
     *
     * `/api/pair/request` 是免鉴权的，第三方脚本重放一次就能触发。
     * 所以这里另记一份账本，保留得比待确认表久（[PROCESSED_TTL_MS]）。
     */
    private val processed = LinkedHashMap<String, Processed>()

    /** 一条「已经处理完」的记录。 */
    private data class Processed(val state: State, val at: Long)

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
     * @param peerPublicKey 电脑的 DH 公钥。**公钥不是秘密**，
     *   但有了它手机就能当场算出共享密钥 —— 过网的也只有它。
     * @param sourceAddress 服务端观察到的对端地址。**客户端伪造不了**，
     *   所以它才是确认卡上能安全展示的「是谁在请求连接」。
     */
    fun request(
        requestId: String,
        deviceName: String = "",
        peerPublicKey: ByteArray,
        sourceAddress: String = "",
    ): PendingRequest = synchronized(lock) {
        purgeExpiredLocked()
        val now = clock()
        val existing = pending[requestId]
        if (existing != null) {
            // 同一个 requestId **只协商一次**。
            //
            // 重复到达时重新协商 = 换一把密钥 = 两端算出的配对码对不上；
            // 手机那边还会**再弹一张卡**，而卡上的码是旧那次协商的。
            // 所以这里**任何状态**都复用原来那一条（包括已批准的），
            // 真实状态由调用方回给客户端（见 [WebSyncApi.requestPairing]）。
            return@synchronized existing.copy()
        }
        // **退化公钥一律拒收**：公钥 = 1 会让共享密钥变成公开常量
        // （详见 [WebDh.validatePublicKey]）。不合法就让它抛出去。
        WebDh.validatePublicKey(peerPublicKey)
        val (priv, pub) = WebDh.generateKeypair()
        val req = PendingRequest(
            requestId = requestId,
            requestedAt = now,
            expiresAt = now + CODE_TTL_MS,
            deviceName = deviceName,
            peerPublicKey = peerPublicKey,
            ephemeralPrivate = priv,
            ephemeralPublic = pub,
            sharedSecret = WebDh.sharedSecret(priv, peerPublicKey),
            sourceAddress = sourceAddress,
        )
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
     * 批准 —— **由用户在手机上点「允许」后调用**。
     *
     * 共享密钥在 [request] 收到公钥时就已经协商好了，这里直接拿它派生。
     */
    fun approve(
        requestId: String,
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

        val deviceId = idGenerator()
        val now = clock()
        val device = PairedDevice(
            deviceId = deviceId,
            keys = WebCrypto.deriveKeys(req.sharedSecret, passcode),
            deviceName = deviceName.ifBlank { req.deviceName.ifBlank { "网页端" } },
            pairedAt = now,
            lastSeenAt = now,
        )
        devices[deviceId] = device
        req.state = State.APPROVED
        req.deviceId = deviceId
        req.deviceName = device.deviceName
        markProcessedLocked(req.requestId, State.APPROVED, now)
        device
    }

    fun deny(requestId: String): Boolean = synchronized(lock) {
        val req = pending[requestId] ?: return@synchronized false
        if (req.state != State.PENDING) return@synchronized false
        req.state = State.DENIED
        markProcessedLocked(requestId, State.DENIED, clock())
        true
    }

    /**
     * 这条 requestId 现在是什么状态。
     *
     * - `null`：没见过 —— 真的是一条新申请；
     * - [State.PENDING]：还在等用户确认；
     * - 其它：**已经处理完了**，不能再当成新申请重新协商。
     *
     * 已被清出待确认表的也算（见 [processed]）——
     * 「几分钟前刚批准过」这件事不能因为清表就忘了。
     */
    fun knownState(requestId: String): State? = synchronized(lock) {
        purgeExpiredLocked()
        pending[requestId]?.state ?: processed[requestId]?.state
    }

    /**
     * 查配对状态。
     *
     * **未加密、免鉴权**，但里面**没有凭据** —— 只有状态、deviceId、
     * 手机的一次性公钥与配对码。抓到这条请求的人知道「有个设备在等」，
     * 但没有共享密钥，拿不到密钥、也算不出签名。
     */
    fun status(requestId: String): Status = synchronized(lock) {
        purgeExpiredLocked()
        val req = pending[requestId] ?: return@synchronized Status(State.EXPIRED)
        Status(
            state = req.state,
            deviceId = req.deviceId,
            deviceName = req.deviceName.takeIf { it.isNotBlank() },
            publicKey = req.ephemeralPublic,
            pairCode = req.pairCode,
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

    private fun purgeExpiredLocked() {
        val now = clock()
        pending.entries.removeAll { (_, req) ->
            if (req.state == State.PENDING && now > req.expiresAt) {
                req.state = State.EXPIRED
                markProcessedLocked(req.requestId, State.EXPIRED, now)
                true
            } else {
                false
            }
        }
        // 已处理完的请求（已批准 / 已拒绝 / 已过期）**不留着**。
        //
        // 留着没有用途，却会一直占着内存；而这些记录里还有共享密钥
        // 与一次性私钥 —— 过期之后继续留着它们没有道理。
        // 「处理过」这件事另有账本记着（见 [processed]），不受这里清理影响。
        pending.entries.removeAll { (_, req) ->
            req.state != State.PENDING && now > req.requestedAt + CODE_TTL_MS
        }
        pruneProcessedLocked(now)
        pruneRateHitsLocked(now)
    }

    /** 记一笔「这条 requestId 已经处理完了」。 */
    private fun markProcessedLocked(requestId: String, state: State, at: Long) {
        processed[requestId] = Processed(state, at)
        // 账本也要有上界：免鉴权接口能被随便调用，账本不能无限长。
        while (processed.size > MAX_PROCESSED_REQUESTS) {
            val oldest = processed.entries.minByOrNull { it.value.at }?.key ?: break
            processed.remove(oldest)
        }
    }

    /** 丢掉保留期外的「已处理」记录。 */
    private fun pruneProcessedLocked(now: Long) {
        processed.entries.removeAll { (_, p) -> now > p.at + PROCESSED_TTL_MS }
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

        /**
         * 「已处理」的记录留多久。
         *
         * 比 [CODE_TTL_MS] 长：待确认表把条目清掉了，被重放也要认得出来。
         * 十分钟足够覆盖「电脑还在轮询配对结果」的那段时间。
         */
        const val PROCESSED_TTL_MS = 10 * 60 * 1000L

        /** 「已处理」账本最多留多少条。 */
        const val MAX_PROCESSED_REQUESTS = 64
    }
}
