package com.mnbt.app.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.domain.model.CloudServerSummary
import com.mnbt.app.domain.model.CloudSite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 站点详情页状态：站点条目 + 该站点下的服务器列表（列表随 tab 刷新/本页下拉刷新更新）。 */
data class CloudSiteDetailUi(
    val site: CloudSite?,
    val servers: List<CloudServerSummary> = emptyList(),
    val syncing: Boolean = false,
)

class CloudSiteDetailViewModel(
    private val container: AppContainer,
    private val siteId: Long,
) : ViewModel() {

    private val syncing = MutableStateFlow(false)

    val state: StateFlow<CloudSiteDetailUi> =
        combine(
            container.cloudSiteRepository.sites(),
            container.cloudServerRepository.servers(),
            syncing,
        ) { sites, servers, isSyncing ->
            CloudSiteDetailUi(
                site = sites.firstOrNull { it.id == siteId },
                servers = servers.filter { it.siteId == siteId },
                syncing = isSyncing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSiteDetailUi(site = null))

    /**
     * 下拉刷新：复用整仓刷新（单站点失败不阻塞）。令牌续期由 CloudAuth 在 401 时
     * 按需完成，这里不做主动轮换（对齐 CloudViewModel.refresh 的口径）。
     */
    fun refresh() {
        viewModelScope.launch {
            if (syncing.value) return@launch
            syncing.value = true
            container.cloudServerRepository.refresh()
            syncing.value = false
        }
    }
}
