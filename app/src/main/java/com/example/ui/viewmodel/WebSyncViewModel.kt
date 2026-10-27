package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.di.AppContainer
import com.example.data.sync.web.PairingStore
import com.example.data.sync.web.SyncDirection
import com.example.data.sync.web.WebSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「网页同步」那一屏的状态。
 *
 * 刻意**不并进 [MainViewModel]**：主状态机已经很大，
 * 而这一屏的生命周期与它无关（服务开着的时候用户在别的页面也该能用）。
 */
data class WebSyncUiState(
    val running: Boolean = false,
    val port: Int = -1,
    val addresses: List<String> = emptyList(),
    val pairedDevices: List<PairingStore.PairedDevice> = emptyList(),
    /**
     * **正在等确认的配对请求**（可能不止一条）。
     *
     * 服务层给的是**列表**语义：后来的请求不再顶掉先来的 ——
     * 真电脑的和陌生设备会**同时出现**，用户才有机会发现「怎么来了两个」。
     *
     * 每条都带**服务端观察到的来源地址**（`sourceAddress`）：
     * 那是客户端伪造不了的，比自报的设备名可靠。
     */
    val pendingPairings: List<WebSyncService.PendingPairing> = emptyList(),
    /** 本机最新的一次编辑发生在什么时候。 */
    val localLatestEditAt: Long = 0L,
    /** 网页端最近一次同步过来的快照的最新编辑时间。 */
    val remoteLatestEditAt: Long = 0L,
    /** 设了同步密码没有。**不示显明文。** */
    val hasPasscode: Boolean = false,
    /** 刚刚发生过的事，给界面一句提示。 */
    val message: String? = null,
)

class WebSyncViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val service: WebSyncService get() = container.webSyncService

    private val _uiState = MutableStateFlow(WebSyncUiState())
    val uiState: StateFlow<WebSyncUiState> = _uiState.asStateFlow()

    init {
        // 「有一台设备请求连接」可能**在弹层开着的时候**才出现 ——
        // 深链是在 HTTP 处理线程里到的，弹层开着不代表界面会重读一次状态。
        // 所以这里订阅服务上的那个流，而不是只在 refresh() 时读一次。
        //
        // 订阅的是**列表**：后来的请求不再顶掉先来的。
        viewModelScope.launch {
            service.pendingPairings.collect { pending ->
                _uiState.value = _uiState.value.copy(pendingPairings = pending)
            }
        }
    }

    /** 待确认配对的那一块状态（可能有多条）。 */
    private fun pendingState(): List<WebSyncService.PendingPairing> =
        service.pendingPairings.value

    /** 重新读一遍服务状态。打开弹层时调一次。 */
    fun refresh() {
        val pending = pendingState()
        _uiState.value = WebSyncUiState(
            running = service.isRunning,
            port = service.port,
            addresses = service.lanAddresses(),
            pairedDevices = service.pairedDevices(),
            pendingPairings = pending,
            localLatestEditAt = _uiState.value.localLatestEditAt,
            remoteLatestEditAt = service.remoteLatestEditAt,
            hasPasscode = service.syncPasscode().isNotEmpty(),
            message = _uiState.value.message,
        )
        refreshLocalLatestEdit()
    }

    /** 本机最新编辑时间要从库里查，是挂起调用。 */
    private fun refreshLocalLatestEdit() {
        viewModelScope.launch {
            val latest = service.localLatestEditAt()
            _uiState.value = _uiState.value.copy(localLatestEditAt = latest)
        }
    }

    /** 开 / 关局域网服务。 */
    fun setServiceEnabled(enabled: Boolean) {
        val ok = if (enabled) {
            service.start()
        } else {
            service.stop()
            true
        }
        val pending = pendingState()
        _uiState.value = _uiState.value.copy(
            running = service.isRunning,
            port = service.port,
            addresses = if (enabled && ok) service.lanAddresses() else emptyList(),
            pairedDevices = service.pairedDevices(),
            pendingPairings = pending,
            message = if (enabled && !ok) "启动失败：端口可能被占用" else null,
        )
        refreshLocalLatestEdit()
    }

    /**
     * 请网页端按指定方向同步一次。
     *
     * **手机也能选方向。** 浏览器不能监听端口，所以手机推不动数据 ——
     * 但手机可以**请求**网页端去做，方向由手机选。
     */
    fun requestSync(direction: SyncDirection) {
        if (!service.isRunning) {
            _uiState.value = _uiState.value.copy(message = "网页同步还没打开")
            return
        }
        service.requestSync(direction)
        val text = when (direction) {
            SyncDirection.PULL -> "已让网页端以手机为准"
            SyncDirection.PUSH -> "已让网页端以网页端为准"
            SyncDirection.MERGE -> "已让网页端合并两边"
        }
        _uiState.value = _uiState.value.copy(message = text)
    }

    /**
     * 配对请求来了（二维码深链，或「只填地址」那条路的 HTTP 请求）。
     *
     * **只记下来，不批准。** 批准必须由用户在界面上点一下 ——
     * 局域网里任何设备都能构造出这个深链，自动批准等于把门开着。
     *
     * @param requestId 这次配对的 id。
     * @param secretBase64 老协议：secret 本身（base64url）。**它只在这一刻存在于内存里。**
     * @param publicKeyBase64 新协议：电脑的 DH 公钥（base64url）。
     * @param sourceAddress 服务端观察到的来源地址。**扫码深链那条路没有** —— 留空即可。
     */
    fun offerPairing(
        requestId: String,
        secretBase64: String = "",
        publicKeyBase64: String = "",
        sourceAddress: String = "",
    ) {
        service.offerPairing(
            requestId = requestId,
            secretBase64 = secretBase64.ifBlank { null },
            publicKeyBase64 = publicKeyBase64.ifBlank { null },
            sourceAddress = sourceAddress,
        )
        refresh()
    }

    /**
     * 批准**某一条**待确认请求。
     *
     * @param requestId 要批准的那条的 id —— 不是「当前唯一那条」。
     */
    fun approve(requestId: String) {
        // **先把这条记下来**：批准完它就从列表里没了，
        // 失败时要告诉用户「是哪一张卡没批成」。
        val target = pendingState().firstOrNull { it.requestId == requestId }
        val ok = service.approvePending(requestId)
        refresh()
        _uiState.value = _uiState.value.copy(
            message = if (ok) "已允许，网页端会自己连上" else "这个配对请求已经失效了${describe(target)}",
        )
    }

    /** 拒绝**某一条**待确认请求。 */
    fun deny(requestId: String) {
        service.denyPending(requestId)
        refresh()
        _uiState.value = _uiState.value.copy(message = "已拒绝")
    }

    /**
     * 失败提示里的「是哪一条」：优先给**来源地址**（伪造不了），
     * 没有就给设备名（自报的，至少还能认个脸）。
     */
    private fun describe(target: WebSyncService.PendingPairing?): String {
        if (target == null) return ""
        val address = target.sourceAddress.trim()
        if (address.isNotEmpty()) return "（来源 $address）"
        val name = target.deviceName.trim()
        if (name.isNotEmpty()) return "（$name）"
        return ""
    }

    fun revoke(deviceId: String) {
        service.revoke(deviceId)
        refresh()
        _uiState.value = _uiState.value.copy(message = "已移除这台设备")
    }

    /**
     * 设 / 改同步密码。空串 = 取消密码。
     *
     * **只收 4 位数字**（用户定的，好敲好念）。
     * 代价要说清楚：10⁴ 种 —— 拿到配对密钥的人可以离线毫秒级爆破它，
     * 它挡的是「旁边有人瞄到屏幕」，不是密码学意义上的第二因子。
     */
    fun setPasscode(passcode: String) {
        val digits = passcode.filter { it.isDigit() }
        if (digits.length != passcode.length || digits.length > 4) {
            _uiState.value = _uiState.value.copy(message = "同步密码只能是 4 位数字")
            return
        }
        service.setSyncPasscode(digits)
        refresh()
        _uiState.value = _uiState.value.copy(
            message = if (digits.isEmpty()) "已取消同步密码" else "同步密码已保存",
        )
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

class WebSyncViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebSyncViewModel::class.java)) {
            return WebSyncViewModel(container) as T
        }
        throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
    }
}
