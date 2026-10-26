package com.example.data.sync.web

import android.content.Context
import com.example.data.di.AppContainer
import com.example.data.security.SecureAccountStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    val pairing = PairingStore()

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

    /** 正在等用户确认的配对请求（二维码里带来的 secret 与它的摘要）。 */
    @Volatile
    var pendingPairing: PendingPairing? = null
        private set

    /** 一次待批准的配对。 */
    data class PendingPairing(
        val requestId: String,
        /** base64url 的 secret。**只在这一瞬间存在于内存里。** */
        val secretBase64: String,
        val deviceName: String,
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
            qr = { text -> QrCodes.svg(text) },
            broadcaster = this,
            revisionProvider = { revision.get() },
            deviceName = { deviceName() },
            appVersion = { appVersion() },
            snapshotApplier = { dto, mode ->
                // 记下网页端那份快照的最新编辑时间：手动选方向时要给用户看。
                remoteLatestEditAt = dto.latestEditAt
                applier.apply(dto, mode)
            },
        )
        val http = LanHttpServer(port = 0, handler = api::handle)
        return try {
            http.start()
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
     * 深链带进来的配对请求。
     *
     * 深链格式：`miulist://pair?v=3&s=<base64url(secret)>&r=<requestId>`。
     *
     * **secret 只在这一刻存在于内存里** —— 它由网页端本地生成，
     * 从头到尾没有经过网络（见 [PairingStore] 的说明）。
     */
    fun offerPairing(requestId: String, secretBase64: String, deviceName: String = "网页端"): Boolean {
        if (requestId.isBlank() || secretBase64.isBlank()) return false
        pendingPairing = PendingPairing(requestId, secretBase64, deviceName)
        return true
    }

    /**
     * 用户在手机上点了「允许」。
     *
     * 批准时会用 secret 派生密钥（**同步密码会混进去**），
     * 并把这台设备记下来。
     */
    fun approvePending(): Boolean {
        val pending = pendingPairing ?: return false
        val secret = runCatching { WebCrypto.fromBase64Url(pending.secretBase64) }.getOrNull()
        if (secret == null) {
            pendingPairing = null
            return false
        }
        // 摘要对不上 = 二维码被人改过。宁可失败也不批。
        if (pairing.requestIdOf(secret) != pending.requestId) {
            pendingPairing = null
            return false
        }
        val device = pairing.approve(
            requestId = pending.requestId,
            secret = secret,
            passcode = container.secureAccountStorage.getSyncPasscode().orEmpty(),
            deviceName = pending.deviceName,
        )
        pendingPairing = null
        if (device != null) persistDevice(device)
        return device != null
    }

    fun denyPending(): Boolean {
        val pending = pendingPairing ?: return false
        pairing.deny(pending.requestId)
        pendingPairing = null
        return true
    }

    fun pairedDevices(): List<PairingStore.PairedDevice> = pairing.pairedDevices()

    fun revoke(deviceId: String): Boolean {
        val ok = pairing.revoke(deviceId)
        if (ok) container.secureAccountStorage.removeSyncDeviceKeys(deviceId)
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
     * 那三把子密钥等价于配对 secret 本身 —— 明文存进 SharedPreferences
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
                lastSeenAt = System.currentTimeMillis(),
            )
        }
        pairing.restore(restored)
    }

    companion object {
        private const val KEY_REMOTE_LATEST_EDIT = "remote_latest_edit_at"
        private const val KEY_DEVICE_NAME_PREFIX = "device_name_"
        private const val KEY_DEVICE_PAIRED_PREFIX = "device_paired_"
    }
}
