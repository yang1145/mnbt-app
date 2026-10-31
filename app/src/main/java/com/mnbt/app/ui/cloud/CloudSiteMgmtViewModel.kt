package com.mnbt.app.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.domain.model.CloudSite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 云站点管理页（我的 → 云站点管理）的本地条目 VM，结构对齐 HostMgmtViewModel：
 * 重命名、解绑全走 CloudSiteRepository，这一层没有网络直调
 * （解绑内部会尽力通知服务端吊销令牌，失败不阻断本地删除），
 * 因此状态直接是 Room Flow，不套 LoadState。
 *
 * 与 HostMgmtViewModel 的差别只在失败提示：走云功能页惯例的一次性 AlertDialog 消息
 * （对齐 CloudScreen 的 unbindMessage），不用 Snackbar。
 */
class CloudSiteMgmtViewModel(private val container: AppContainer) : ViewModel() {

    val sites: StateFlow<List<CloudSite>> = container.cloudSiteRepository.sites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 操作失败的一次性提示：服务端 message 或校验文案可直接展示。 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 重命名：非空/长度校验在仓库层，失败原因进 [errorMessage]。 */
    fun rename(siteId: Long, name: String) = viewModelScope.launch {
        container.cloudSiteRepository.renameSite(siteId, name)
            .onFailure { e -> _errorMessage.value = e.message ?: "重命名失败" }
    }

    /** 解绑：成功后 sites() Flow 会自动吐出新列表，无需手动刷。 */
    fun unbind(siteId: Long) = viewModelScope.launch {
        container.cloudSiteRepository.unbindSite(siteId)
            .onFailure { e -> _errorMessage.value = e.message ?: "解绑失败，请稍后重试" }
    }

    /** 失败提示已展示，复位一次性消息。 */
    fun consumeErrorMessage() {
        _errorMessage.value = null
    }
}
