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
    /** 本机最新的一次编辑发生在什么时候。 */
    val localLatestEditAt: Long = 0L,
    /** 网页端最近一次同步过来的快照的最新编辑时间。 */
    val remoteLatestEditAt: Long = 0L,
    /** 设了同步密码没有。**不��显明文。** */
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

    /** 重新读一遍服务状态。打开弹层时调一次。 */
    fun refresh() {
        _uiState.value = WebSyncUiState(
            running = service.isRunning,
            port = service.port,
            addresses = service.lanAddresses(),
            pairedDevices = service.pairedDevices(),
            pendingPairing = service.pendingPairing != null,
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
        _uiState.value = _uiState.value.copy(
            running = service.isRunning,
            port = service.port,
            addresses = if (enabled && ok) service.lanAddresses() else emptyList(),
            pairedDevices = service.pairedDevices(),
            pendingPairing = service.pendingPairing != null,
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
     * 深链带来的配对请求。
     *
     * **只记下来，不批准。** 批准必须由用户在界面上点一下 ——
     * 局域网里任何设备都能构造出这个深链，自动批准等于把门开着。
     *
     * @param requestId secret 的摘要。
     * @param secretBase64 secret 本身（base64url）。**它只在这一刻存在于内存里。**
     */
    fun offerPairing(requestId: String, secretBase64: String, deviceName: String = "网页端") {
        service.offerPairing(requestId, secretBase64, deviceName)
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
