package com.mnbt.app.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.domain.model.CloudFlowItem
import com.mnbt.app.domain.model.CloudPowerAction
import com.mnbt.app.domain.model.CloudServerDetail
import com.mnbt.app.ui.common.LoadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一次电源指令的下发过程：对话框据此展示进度 / 错误 / 成功后收起。 */
sealed interface CloudPowerOp {
    data object Idle : CloudPowerOp
    data object Working : CloudPowerOp

    /** 服务端拒绝（如「操作密码错误」）：留在对话框里允许改密码重试。 */
    data class Failed(val message: String) : CloudPowerOp
    data object Dispatched : CloudPowerOp
}

/** 详情页就绪数据。powerStatus/powerDesc 单独拎出来：轮询只刷新状态卡不动详情其余部分。 */
data class CloudDetailUi(
    val detail: CloudServerDetail,
    val powerStatus: String,
    val powerDesc: String,
    val operatePasswordRequired: Boolean,
    val flows: List<CloudFlowItem>?,
)

class CloudServerDetailViewModel(
    private val container: AppContainer,
    private val siteId: Long,
    private val hostId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<CloudDetailUi>>(LoadState.Loading)
    val state: StateFlow<LoadState<CloudDetailUi>> = _state.asStateFlow()

    private val refreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = refreshing.asStateFlow()

    private val _powerOp = MutableStateFlow<CloudPowerOp>(CloudPowerOp.Idle)
    val powerOp: StateFlow<CloudPowerOp> = _powerOp.asStateFlow()

    /** VNC 地址一次性事件：UI 用浏览器打开后 consume。 */
    private val _vncUrl = MutableStateFlow<String?>(null)
    val vncUrl: StateFlow<String?> = _vncUrl.asStateFlow()

    /** 不弹对话框的失败（VNC 取地址失败）走 toast 的一次性消息。 */
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    /**
     * 操作密码记住本次会话：站点要求密码且首次下发成功后，本页生命周期内
     * 再执行电源操作不再重复询问。
     */
    private var savedOperatePassword: String? = null

    init {
        loadAll()
    }

    /**
     * 首次整页加载：详情 + 站点配置（operatePasswordRequired）+ 流量。
     * 详情失败整页 Failed（message 为服务端中文文案）；配置/流量失败降级，不挡整页。
     */
    fun loadAll() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val detailResult = container.cloudServerRepository.detail(siteId, hostId)
            val detail = detailResult.getOrNull()
            if (detail == null) {
                _state.value = LoadState.Failed(detailResult.exceptionOrNull()?.message ?: "加载失败，请稍后重试")
                return@launch
            }
            val config = container.cloudSiteRepository.siteConfig(siteId).getOrNull()
            val flows = container.cloudServerRepository.flow(siteId, hostId).getOrNull()
            _state.value = LoadState.Ready(
                CloudDetailUi(
                    detail = detail,
                    powerStatus = detail.summary.powerStatus,
                    powerDesc = detail.summary.powerDesc,
                    operatePasswordRequired = config?.operatePasswordRequired ?: false,
                    flows = flows,
                ),
            )
        }
    }

    fun retry() = loadAll()

    /** 状态卡手动刷新：重拉详情/状态/流量，整页不转圈，卡片底部细条提示。 */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val status = container.cloudServerRepository.status(siteId, hostId).getOrNull()
            val detail = container.cloudServerRepository.detail(siteId, hostId).getOrNull()
            val flows = container.cloudServerRepository.flow(siteId, hostId).getOrNull()
            _state.update { current ->
                val ready = current as? LoadState.Ready<CloudDetailUi> ?: return@update current
                ready.copy(
                    data = ready.data.copy(
                        // status 比详情里的 summary 更新，优先采用
                        powerStatus = status?.first ?: ready.data.powerStatus,
                        powerDesc = status?.second ?: ready.data.powerDesc,
                        detail = detail ?: ready.data.detail,
                        flows = flows ?: ready.data.flows,
                    ),
                )
            }
            refreshing.value = false
        }
    }

    /** 本次会话是否还需要输操作密码（站点要求且尚未成功输入过）。 */
    fun needsOperatePassword(): Boolean {
        val ready = _state.value as? LoadState.Ready<CloudDetailUi> ?: return false
        return ready.data.operatePasswordRequired && savedOperatePassword == null
    }

    /**
     * 电源指令：失败留在对话框（CloudPowerOp.Failed 带服务端 message），
     * 成功立刻发 Dispatched 事件并按 2 秒间隔轮询 status 3 次刷新状态卡。
     */
    fun power(action: CloudPowerAction, operatePassword: String?) {
        if (_powerOp.value is CloudPowerOp.Working) return
        viewModelScope.launch {
            _powerOp.value = CloudPowerOp.Working
            val password = operatePassword?.takeIf { it.isNotBlank() } ?: savedOperatePassword
            val result = container.cloudServerRepository.power(siteId, hostId, action, password)
            result.fold(
                onSuccess = {
                    if (!operatePassword.isNullOrBlank()) savedOperatePassword = operatePassword
                    _powerOp.value = CloudPowerOp.Dispatched
                    pollStatus()
                },
                onFailure = { e ->
                    _powerOp.value = CloudPowerOp.Failed(e.message ?: "指令下发失败，请稍后重试")
                },
            )
        }
    }

    /** 指令下发后按 2 秒间隔轮询 status 3 次（对齐小程序），只更新状态卡。 */
    private fun pollStatus() {
        viewModelScope.launch {
            repeat(3) {
                delay(2_000)
                val pair = container.cloudServerRepository.status(siteId, hostId).getOrNull()
                    ?: return@repeat
                _state.update { current ->
                    val ready = current as? LoadState.Ready<CloudDetailUi> ?: return@update current
                    ready.copy(
                        data = ready.data.copy(powerStatus = pair.first, powerDesc = pair.second),
                    )
                }
            }
        }
    }

    fun openVnc() {
        viewModelScope.launch {
            container.cloudServerRepository.vnc(siteId, hostId).fold(
                onSuccess = { _vncUrl.value = it },
                onFailure = { e -> _toastMessage.value = e.message ?: "获取 VNC 地址失败，请稍后重试" },
            )
        }
    }

    /** 对话框关闭/成功提示已展示后复位，下一次指令重新走 Idle。 */
    fun consumePowerOp() {
        _powerOp.value = CloudPowerOp.Idle
    }

    fun consumeVnc() {
        _vncUrl.value = null
    }

    fun consumeToast() {
        _toastMessage.value = null
    }
}
