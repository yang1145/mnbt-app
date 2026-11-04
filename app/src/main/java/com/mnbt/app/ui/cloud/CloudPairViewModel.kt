package com.mnbt.app.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.http.HostUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 云站点绑定状态机：idle → pairing → bound | failed。
 * 扫码页的二维码校验是纯本地逻辑，不经过这里（见 CloudPairScanScreen.parseCloudQr）；
 * 只有绑定页的 pairSite 走本 VM。
 */
sealed interface CloudPairState {
    data object Idle : CloudPairState
    data object Pairing : CloudPairState
    data object Bound : CloudPairState

    /** [message] 为可直接展示的中文（本地校验或仓库层 Result 的异常 message）。 */
    data class Failed(val message: String) : CloudPairState
}

class CloudPairViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<CloudPairState>(CloudPairState.Idle)
    val state: StateFlow<CloudPairState> = _state.asStateFlow()

    /** 绑定 POST 是否在途：挡住快速双击把同一个一次性绑定码连发两次。 */
    private var pairing = false

    /**
     * 绑定页「确认绑定」。地址本地先规范化兜底（无协议默认按 https，与 MNBT 手动配对
     * 同一规则），码与设备名原样交给仓库层；设备名空串时由仓库侧处理。
     * [systemType] 为二维码 type 字段（服务商系统标识）；手输绑定为空串。
     */
    fun pair(rawBase: String, rawCode: String, deviceName: String, systemType: String = "") {
        if (pairing) return
        val base = HostUrl.normalize(rawBase)
        if (base == null) {
            _state.value = CloudPairState.Failed("站点地址无效，示例：https://cloud.example.com 或 http://1.2.3.4/panel")
            return
        }
        val code = rawCode.trim()
        if (code.isEmpty()) {
            _state.value = CloudPairState.Failed("请在站点后台生成绑定码后填入")
            return
        }
        pairing = true
        viewModelScope.launch {
            _state.value = CloudPairState.Pairing
            val result = container.cloudSiteRepository.pairSite(base, code, deviceName.trim(), systemType)
            pairing = false
            _state.value = result.fold(
                onSuccess = { CloudPairState.Bound },
                onFailure = { e -> CloudPairState.Failed(e.message ?: "绑定失败，请稍后重试") },
            )
        }
    }

    /** 失败提示已展示（重试/返回）后复位，允许再次发起。 */
    fun reset() {
        if (_state.value !is CloudPairState.Pairing) _state.value = CloudPairState.Idle
    }
}
