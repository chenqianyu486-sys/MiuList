package com.example.data.sync.web

import android.content.Context
import com.example.data.di.AppContainer
import com.example.data.security.SecureAccountStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * 局域网同步服务的**总控**。
 *
 * 它把三件东西接在一起：
 *
 * - [LanHttpServer]：HTTP + SSE 的传输层；
 * - [WebSyncApi]：接口与业务；
 * - [PairingStore]：谁能连、谁不能。
 *
 * ## 状态是怎么变的
 *
 * 服务不是常驻的：用户在「网页同步」弹层里打开它，在那里关掉它。
 * 开着的时候，App 退到后台但进程还活着，服务就还在（Android 可能随时回收它，
 * 这是系统行为，界面上会写清楚）。
 *
 * ## 「数据变了」怎么知道
 *
 * 不靠轮询，靠 Room 的 Flow：待办、课程、考试三张表任何一张变了，
 * 对应的 Flow 就会重新发射一次，[revision] 加一，并推给所有 SSE 客户端
 * （**推给每台设备时各自用自己的密钥加密**）。
 *
 * **不去重内容**是故意的 —— 只比条数的话，「改了一条待办的标题」这种
 * 最常见的改动反而不会触发任何通知（条数没变），网页端就一直显示旧内容。
 */
class WebSyncService(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : SyncEventBroadcaster {

    val pairing = PairingStore(
        // 设备空闲到期 → **连持久化的密钥一起删**。
        // 只删内存里那份的话，进程一重启它又被装回来，
        // 「半小时空闲自动失效」就成了句空话。
        onDeviceExpired = { deviceId ->
            container.secureAccountStorage.removeSyncDeviceKeys(deviceId)
            removeDevicePrefs(deviceId)
        },
        // 成功验签推进 lastSeenAt 后**及时落盘**（PairingStore 内部已做 60 秒节流）。
        // 只存活跃时间，不重复写密钥。
        onDeviceSeen = { device -> persistLastSeen(device) },
    )

    private val revision = AtomicLong(1L)
    private val sinks = CopyOnWriteArraySet<HttpReply.EventSink>()
    private val sinkDevices = java.util.concurrent.ConcurrentHashMap<HttpReply.EventSink, PairingStore.PairedDevice>()
    private var server: LanHttpServer? = null
    private var discovery: DiscoveryResponder? = null
    private var observing = false

    /** 服务是否在跑。 */
    val isRunning: Boolean get() = server != null

    /** 实际监听的端口。 */
    val port: Int get() = server?.boundPort ?: -1

    /**
     * 正在等用户确认的配对请求。
     *
     * **用 StateFlow 而不是普通属性**：待确认的这些是在 **HTTP 处理线程**里
     * 被塞进来的，而界面若只在打开弹层时读一次，
     * 用户**已经开着弹层**时就看不到「有一台设备请求连接」。
     *
     * ## 为什么是**列表**而不是单条
     *
     * 原来是单槽：后来的请求会**顶掉**先来的。
     * 那等于给了一条现成的钓鱼路径 —— 攻击者发一条请求，
     * 你电脑上那条真的请求就被换成他的，手机上只剩一张卡，
     * 用户根本看不出换过，还以为那就是自己的电脑。
     *
     * 改成列表之后：真电脑的和陌生设备会**同时出现**，
     * 用户看得见「怎么来了两个」，也就有机会拒掉那个不认识的。
     */
    private val _pendingPairings = MutableStateFlow<List<PendingPairing>>(emptyList())
    val pendingPairings: StateFlow<List<PendingPairing>> = _pendingPairings.asStateFlow()

    /** 一次待批准的配对。 */
    data class PendingPairing(
        val requestId: String,
        val deviceName: String,
        /** **配对码**：两端一致的 6 位码，用户照着核对。 */
        val pairCode: String,
        /**
         * **服务端观察到的来源地址**（`ip:port`）。
         *
         * 确认卡上给用户看的就是它 —— 客户端自报的 `deviceName` 谁都能写成
         * 「我的电脑」，而这个地址是从 TCP 连接上读出来的，伪造不了。
         */
        val sourceAddress: String,
    )

    init {
        restorePairedDevices()
    }

    // ── 生命周期 ────────────────────────────────────────────────

    /** 启动服务。返回是否成功。 */
    fun start(): Boolean {
        if (server != null) return true
        val applier = SnapshotApplier(container.database, container.courseRepository)
        val api = WebSyncApi(
            pairing = pairing,
            assets = assetProvider(),
            snapshotProvider = { rev ->
                WebSnapshotBuilder(
                    taskRepo = container.taskRepository,
                    courseRepo = container.courseRepository,
                    examRepo = container.examRepository,
                    calendarRepo = container.calendarRepository,
                    taskTypeRepo = container.taskTypeRepository,
                    deviceName = { deviceName() },
                    appVersion = { appVersion() },
                ).build(rev)
            },
            tasks = TaskWriteService(
                taskRepo = container.taskRepository,
                courseRepo = container.courseRepository,
            ),
            broadcaster = this,
            revisionProvider = { revision.get() },
            deviceName = { deviceName() },
            appVersion = { appVersion() },
            snapshotApplier = { dto, mode ->
                // 记下网页端那份快照的最新编辑时间：手动选方向时要给用户看。
                remoteLatestEditAt = dto.latestEditAt
                applier.apply(dto, mode)
            },
            // 网页端「从学在浙大拉课表」的代理：浏览器自己连不上浙大，
            // 由手机代跑那套 CAS 登录（见 ZjuProxyService 的类注释）。
            zjuProxy = ZjuProxyService(
                zjuImportService = container.zjuImportService,
                syncManager = container.syncManager,
                secureStorage = container.secureAccountStorage,
            ),
            // 有一条配对请求带着公钥进来了 → 界面该弹那张确认卡了。
            onPairingReady = { req ->
                addPending(
                    PendingPairing(
                        requestId = req.requestId,
                        deviceName = req.deviceName.ifBlank { "网页端" },
                        pairCode = req.pairCode,
                        sourceAddress = req.sourceAddress,
                    )
                )
            },
        )
        val http = openServer(api) ?: return false
        return try {
            server = http
            // 局域网发现：电脑端的网页端靠它找到「手机在哪」。
            // 起不来也不影响同步本身 —— 用户可以手填地址。
            discovery = DiscoveryResponder(
                httpPortProvider = { port },
                deviceName = { deviceName() },
                appVersion = { appVersion() },
            ).also { it.start() }
            observeDatabaseChanges()
            true
        } catch (e: Exception) {
            server = null
            false
        }
    }

    /**
     * 开一个 HTTP 服务，**端口固定**。
     *
     * ## 为什么不用随机端口
     *
     * 随机端口（`port = 0`）是防「跟电脑上别的服务撞端口」的老办法，
     * 但它直接挡掉了「电脑自己找到手机」这件事：电脑只知道手机在
     * 某个地址上，**不知道该敲哪个端口** —— 而浏览器又不能发 UDP 广播。
     *
     * 固定端口让电脑能按「上次那个网段 + 固定端口」去扫，
     * 扫到了就自己接上（用户什么都不用做）。
     *
     * 端口被占了就往后顺延 —— 宁可换个端口，也不能起不来。
     */
    private fun openServer(api: WebSyncApi): LanHttpServer? {
        for (candidate in PORT_CANDIDATES) {
            val http = LanHttpServer(
                port = candidate,
                handler = api::handle,
                // **跨源白名单**：只放行手机自己的各个局域网地址
                // （以及 LanHttpServer 里统一放行的 null / localhost）。
                // 白名单外的 Origin 会被直接 403 —— 挡的不只是「读响应」，
                // 连「把请求发出去」也挡了。
                allowedOrigins = { selfOrigins() },
            )
            try {
                http.start()
                return http
            } catch (_: Exception) {
                // 端口被占（或别的权限问题）：试下一个。
                runCatching { http.stop() }
            }
        }
        return null
    }

    /** 本服务允许跨源访问的 Origin —— 手机自己的各个局域网地址。 */
    private fun selfOrigins(): Set<String> {
        val p = port
        if (p <= 0) return emptySet()
        return lanAddresses().map { "http://$it:$p" }.toSet()
    }

    fun stop() {
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
        for (sink in sinks) {
            runCatching { sink.close() }
        }
        sinks.clear()
        sinkDevices.clear()
    }

    // ── 配对 ────────────────────────────────────────────────────

    /**
     * 把一条待确认请求并进列表。
     *
     * 同一条只留一条 —— 重复到达的请求不重复弹卡。
     */
    private fun addPending(item: PendingPairing) {
        _pendingPairings.value = _pendingPairings.value
            .filterNot { it.requestId == item.requestId }
            .let { it + item }
    }

    /**
     * 用户在手机上点了「允许」。
     *
     * 批准时会用协商出来的共享密钥派生（**同步密码会混进去**），
     * 并把这台设备记下来。
     */
    fun approvePending(requestId: String): Boolean {
        val pending = _pendingPairings.value.firstOrNull { it.requestId == requestId } ?: return false
        val device = pairing.approve(
            requestId = pending.requestId,
            passcode = container.secureAccountStorage.getSyncPasscode().orEmpty(),
            deviceName = pending.deviceName,
        )
        _pendingPairings.value = _pendingPairings.value.filterNot { it.requestId == requestId }
        if (device != null) persistDevice(device)
        return device != null
    }

    fun denyPending(requestId: String): Boolean {
        val ok = pairing.deny(requestId)
        _pendingPairings.value = _pendingPairings.value.filterNot { it.requestId == requestId }
        return ok
    }

    fun pairedDevices(): List<PairingStore.PairedDevice> = pairing.pairedDevices()

    fun revoke(deviceId: String): Boolean {
        val ok = pairing.revoke(deviceId)
        if (ok) {
            container.secureAccountStorage.removeSyncDeviceKeys(deviceId)
            removeDevicePrefs(deviceId)
        }
        return ok
    }

    /** 同步密码（可空）。为空 = 不设密码。 */
    fun syncPasscode(): String = container.secureAccountStorage.getSyncPasscode().orEmpty()

    fun setSyncPasscode(passcode: String) {
        container.secureAccountStorage.saveSyncPasscode(passcode.trim())
    }

    /** 网页端最近一次同步过来的快照的**最新编辑时间**。手动选方向时给用户看。 */
    var remoteLatestEditAt: Long
        get() = prefs().getLong(KEY_REMOTE_LATEST_EDIT, 0L)
        set(value) {
            prefs().edit().putLong(KEY_REMOTE_LATEST_EDIT, value).apply()
        }

    /** 本机最新的一次编辑发生在什么时候。 */
    suspend fun localLatestEditAt(): Long =
        container.taskRepository.getAllTasksOnce().maxOfOrNull { it.updatedAt } ?: 0L

    // ── 广播 ────────────────────────────────────────────────────

    override fun subscribe(device: PairingStore.PairedDevice, sink: HttpReply.EventSink) {
        sinks.add(sink)
        sinkDevices[sink] = device
    }

    override fun unsubscribe(sink: HttpReply.EventSink) {
        sinks.remove(sink)
        sinkDevices.remove(sink)
        runCatching { sink.close() }
    }

    override fun broadcast(revision: Long, reason: String) {
        val plain = """{"revision":$revision,"reason":"${LanHttpServer.escapeJson(reason)}"}"""
        for (sink in sinks) {
            val device = sinkDevices[sink] ?: continue
            val payload = try {
                WebCrypto.base64Url(
                    WebCrypto.seal(device.keys, plain.toByteArray(Charsets.UTF_8))
                )
            } catch (_: Exception) {
                continue
            }
            runCatching { sink.send("change", payload) }
        }
    }

    private fun bump(reason: String) {
        val rev = revision.incrementAndGet()
        broadcast(rev, reason)
    }

    /**
     * 请网页端执行一次同步。
     *
     * **手机也能选方向。** 浏览器不能监听端口，所以手机推不动数据 ——
     * 但手机可以**请求**网页端去做，方向由手机选。
     *
     * [SyncDirection] 是从**执行方（网页端）**的视角说的：
     * - [SyncDirection.PULL]：网页端去手机这边拉（手机 → 网页）；
     * - [SyncDirection.PUSH]：网页端把它自己的推给手机（网页 → 手机）；
     * - [SyncDirection.MERGE]：两边合并。
     *
     * 事件载荷同样**按设备加密** —— 它里面没有敏感内容，但保持通道一致。
     */
    fun requestSync(direction: SyncDirection) {
        val plain = """{"direction":"${direction.wire}"}"""
        for (sink in sinks) {
            val device = sinkDevices[sink] ?: continue
            val payload = try {
                WebCrypto.base64Url(WebCrypto.seal(device.keys, plain.toByteArray(Charsets.UTF_8)))
            } catch (_: Exception) {
                continue
            }
            runCatching { sink.send("sync-request", payload) }
        }
    }

    /**
     * 盯着 Room 的三张表。
     *
     * `combine` 会在**任何一个**上游发射时发射一次，所以任何一处改动都会走到 [bump]。
     * `drop(1)` 去掉首次发射 —— 那次只是「读到了当前状态」，不是「变了」。
     */
    private fun observeDatabaseChanges() {
        if (observing) return
        observing = true
        scope.launch {
            combine(
                container.taskRepository.getAllTasks(),
                container.courseRepository.getAllCoursesWithSlots(),
                container.examRepository.getExamEntries(),
            ) { _, _, _ -> Unit }
                .drop(1)
                .collect { bump("database") }
        }
    }

    // ── 网络地址 ────────────────────────────────────────────────

    /**
     * 本机在局域网里的 IPv4 地址。
     *
     * **可能有多个**（Wi-Fi + 虚拟网卡 + VPN），全部列出来让用户挑 ——
     * 猜错一个就等于「地址打不开」，而用户并不知道该用哪个。
     */
    fun lanAddresses(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            // 拿不到就返回空：界面上会显示「没找到局域网地址」，而不是假装有。
        }
        return out.toList()
    }

    /** 网页端的完整地址。 */
    fun webUrl(address: String): String = "http://$address:$port/"

    // ── 杂项 ────────────────────────────────────────────────────

    private fun deviceName(): String =
        runCatching {
            val manager = context.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            manager?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "我的手机"

    private fun appVersion(): String =
        runCatching {
            val pkg = context.packageName
            context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
        }.getOrDefault("?")

    private fun assetProvider(): WebAssetProvider = WebAssetProvider { path ->
        runCatching {
            context.assets.open("web/$path").use { it.readBytes() }
        }.getOrNull()
    }

    // ── 已配对设备的持久化 ──────────────────────────────────────

    private fun prefs() =
        context.getSharedPreferences("miulist_web_sync", Context.MODE_PRIVATE)

    /**
     * 设备密钥**加密落盘**。
     *
     * 那三把子密钥等价于配对协商出来的共享密钥本身 —— 明文存进 SharedPreferences
     * 就等于把钥匙放在桌面上。
     */
    private fun persistDevice(device: PairingStore.PairedDevice) {
        val blob = listOf(
            WebCrypto.base64Url(device.keys.master),
            WebCrypto.base64Url(device.keys.kEnc),
            WebCrypto.base64Url(device.keys.kAuth),
            WebCrypto.base64Url(device.keys.kMac),
        ).joinToString("|")
        container.secureAccountStorage.saveSyncDeviceKeys(device.deviceId, blob)
        // 设备名与时间不进加密存储（不是机密），放普通 prefs 里。
        prefs().edit()
            .putString(KEY_DEVICE_NAME_PREFIX + device.deviceId, device.deviceName)
            .putLong(KEY_DEVICE_PAIRED_PREFIX + device.deviceId, device.pairedAt)
            .putLong(KEY_DEVICE_LASTSEEN_PREFIX + device.deviceId, device.lastSeenAt)
            .apply()
    }

    /**
     * 只更新活跃时间。
     *
     * 密钥与设备名没有变化，没必要每次验签都重写加密存储；
     * 调用频率已由 [PairingStore] 按 60 秒节流。
     */
    private fun persistLastSeen(device: PairingStore.PairedDevice) {
        prefs().edit()
            .putLong(KEY_DEVICE_LASTSEEN_PREFIX + device.deviceId, device.lastSeenAt)
            .apply()
    }

    private fun removeDevicePrefs(deviceId: String) {
        prefs().edit()
            .remove(KEY_DEVICE_NAME_PREFIX + deviceId)
            .remove(KEY_DEVICE_PAIRED_PREFIX + deviceId)
            .remove(KEY_DEVICE_LASTSEEN_PREFIX + deviceId)
            .apply()
    }

    private fun restorePairedDevices() {
        val storage: SecureAccountStorage = container.secureAccountStorage
        val blobs = storage.allSyncDeviceKeys()
        if (blobs.isEmpty()) return
        val restored = blobs.mapNotNull { (deviceId, blob) ->
            val parts = blob.split("|")
            if (parts.size != 4) return@mapNotNull null
            val keys = try {
                WebCrypto.Keys(
                    master = WebCrypto.fromBase64Url(parts[0]),
                    kEnc = WebCrypto.fromBase64Url(parts[1]),
                    kAuth = WebCrypto.fromBase64Url(parts[2]),
                    kMac = WebCrypto.fromBase64Url(parts[3]),
                )
            } catch (_: Exception) {
                return@mapNotNull null
            }
            PairingStore.PairedDevice(
                deviceId = deviceId,
                keys = keys,
                deviceName = prefs().getString(KEY_DEVICE_NAME_PREFIX + deviceId, null) ?: "网页端",
                pairedAt = prefs().getLong(KEY_DEVICE_PAIRED_PREFIX + deviceId, 0L),
                // **用持久化的「上次活跃时间」，不是当前时间。**
                //
                // 原来这里写的是 `System.currentTimeMillis()` ——
                // 于是每次进程重启，每台设备都拿到一份全新的 30 分钟租约，
                // 「半小时空闲自动失效」这句话从来就没成立过：
                // 只要手机每天开一次，那台设备就永远有效。
                //
                // 改成读回上次活跃时间，跨重启也照样会到期。
                lastSeenAt = prefs().getLong(KEY_DEVICE_LASTSEEN_PREFIX + deviceId, 0L)
                    .let { if (it <= 0L) System.currentTimeMillis() else it },
            )
        }
        pairing.restore(restored)
    }

    companion object {
        private const val KEY_REMOTE_LATEST_EDIT = "remote_latest_edit_at"
        private const val KEY_DEVICE_NAME_PREFIX = "device_name_"
        private const val KEY_DEVICE_PAIRED_PREFIX = "device_paired_"
        private const val KEY_DEVICE_LASTSEEN_PREFIX = "device_lastseen_"

        /**
         * 局域网服务监听端口的候选。
         *
         * **第一个是固定的** —— 电脑端按「固定端口」扫网段来找手机。
         * 后面的几个是**兜底**：万一 38780 被别的程序占了，
         * 就往后顺延一个，而不是起不来。
         *
         * 电脑端扫的时候按同样的顺序试，所以它总能找对。
         */
        val PORT_CANDIDATES = listOf(38780, 38781, 38782, 38783, 38784)
    }
}
