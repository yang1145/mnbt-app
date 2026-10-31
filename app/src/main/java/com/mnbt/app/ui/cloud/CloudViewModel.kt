package com.mnbt.app.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.domain.model.CloudServerSummary
import com.mnbt.app.domain.model.CloudSite
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一个绑定站点与其下的服务器列表，列表按绑定顺序分组展示。 */
data class CloudSiteGroupUi(
    val site: CloudSite,
    val servers: List<CloudServerSummary>,
)

/**
 * 列表页状态。概览条三个计数口径：powerStatus on=运行中、off=已关机、
 * 其余（operating/fault/未知）一律归「其他」。
 */
data class CloudUiState(
    val groups: List<CloudSiteGroupUi> = emptyList(),
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val total: Int = 0,
    val running: Int = 0,
    val poweredOff: Int = 0,
    val other: Int = 0,
)

class CloudViewModel(private val container: AppContainer) : ViewModel() {

    private val syncing = MutableStateFlow(false)

    /** 解绑失败的一次性提示：服务端 message 可直接展示。 */
    private val _unbindMessage = MutableStateFlow<String?>(null)
    val unbindMessage: StateFlow<String?> = _unbindMessage.asStateFlow()

    val state: StateFlow<CloudUiState> =
        combine(
            container.cloudSiteRepository.sites(),
            container.cloudServerRepository.servers(),
            syncing,
        ) { sites, servers, isSyncing ->
            val groups = sites.map { site ->
                CloudSiteGroupUi(site = site, servers = servers.filter { it.siteId == site.id })
            }
            val all = groups.flatMap { it.servers }
            CloudUiState(
                groups = groups,
                loaded = true,
                syncing = isSyncing,
                total = all.size,
                running = all.count { it.powerStatus == "on" },
                poweredOff = all.count { it.powerStatus == "off" },
                other = all.count { it.powerStatus != "on" && it.powerStatus != "off" },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudUiState())

    /**
     * 下拉刷新（对齐 HostsScreen）：进入即刷一次，之后手动触发。
     * 站点侧 refreshSite 与服务器侧 refresh 并发跑；列表由 Flow diff 更新，
     * 单站点失败不阻塞其他站点（结果体现在各站数据是否变化上）。
     */
    fun refresh() {
        viewModelScope.launch {
            if (syncing.value) return@launch
            val sites = container.cloudSiteRepository.sites().first()
            if (sites.isEmpty()) return@launch
            syncing.value = true
            coroutineScope {
                launch { container.cloudServerRepository.refresh() }
                sites.forEach { site ->
                    launch { container.cloudSiteRepository.refreshSite(site.id) }
                }
            }
            syncing.value = false
        }
    }

    /** 解绑站点：成功后 sites()/servers() Flow 会自动吐出新列表，无需手动刷。 */
    fun unbind(siteId: Long) {
        viewModelScope.launch {
            val result = container.cloudSiteRepository.unbindSite(siteId)
            if (result.isFailure) {
                _unbindMessage.value = result.exceptionOrNull()?.message ?: "解绑失败，请稍后重试"
            }
        }
    }

    /** 解绑提示已展示，复位一次性消息。 */
    fun consumeUnbindMessage() {
        _unbindMessage.value = null
    }
}
