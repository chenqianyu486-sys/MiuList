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
    /** 有一台设备在等确认。 */
    val pendingPairing: Boolean = false,
    /**
     * **配对码**：两端一致的 6 位码。
     *
     * 用户要拿它跟电脑上显示的那一串比对 —— 这是「允许」之前
     * 唯一能确认「对面确实是我眼前这台电脑」的办法。
     */
    val pendingPairCode: String = "",
    /** 在等确认的是哪台设备。 */
    val pendingDeviceName: String = "",
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
        viewModelScope.launch {
            service.pendingPairing.collect { pending ->
                _uiState.value = _uiState.value.copy(
                    pendingPairing = pending != null,
                    pendingPairCode = pending?.pairCode.orEmpty(),
                    pendingDeviceName = pending?.deviceName.orEmpty(),
                )
            }
        }
    }

    /** 待确认配对的那一块状态。 */
    private fun pendingState(): Triple<Boolean, String, String> {
        val pending = service.pendingPairing.value
        return Triple(
            pending != null,
            pending?.pairCode.orEmpty(),
            pending?.deviceName.orEmpty(),
        )
    }

    /** 重新读一遍服务状态。打开弹层时调一次。 */
    fun refresh() {
        val (pending, code, deviceName) = pendingState()
        _uiState.value = WebSyncUiState(
            running = service.isRunning,
            port = service.port,
            addresses = service.lanAddresses(),
            pairedDevices = service.pairedDevices(),
            pendingPairing = pending,
            pendingPairCode = code,
            pendingDeviceName = deviceName,
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
        val (pending, code, deviceName) = pendingState()
        _uiState.value = _uiState.value.copy(
            running = service.isRunning,
            port = service.port,
            addresses = if (enabled && ok) service.lanAddresses() else emptyList(),
            pairedDevices = service.pairedDevices(),
            pendingPairing = pending,
            pendingPairCode = code,
            pendingDeviceName = deviceName,
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
            _uiState.value = _uiState.value.copy(message = "局域网服务还没开")
            return
        }
        service.requestSync(direction)
        val text = when (direction) {
            SyncDirection.PULL -> "已让网页端来拉手机上的数据"
            SyncDirection.PUSH -> "已让网页端把它那边推过来"
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
     */
    fun offerPairing(
        requestId: String,
        secretBase64: String = "",
        publicKeyBase64: String = "",
    ) {
        service.offerPairing(
            requestId = requestId,
            secretBase64 = secretBase64.ifBlank { null },
            publicKeyBase64 = publicKeyBase64.ifBlank { null },
        )
        refresh()
    }

    fun approve() {
        val ok = service.approvePending()
        refresh()
        _uiState.value = _uiState.value.copy(
            message = if (ok) "已允许，网页端会自己连上" else "这个配对请求已经失效了",
        )
    }

    fun deny() {
        service.denyPending()
        refresh()
        _uiState.value = _uiState.value.copy(message = "已拒绝")
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
