package com.mnbt.app.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.data.remote.api.model.CacheListData
import com.mnbt.app.data.remote.api.model.DomainListData
import com.mnbt.app.data.remote.api.model.HotlinkGetData
import com.mnbt.app.data.remote.api.model.HostSnapshot
import com.mnbt.app.data.remote.api.model.MonitorListData
import com.mnbt.app.data.remote.api.model.SiteInitData
import com.mnbt.app.data.remote.api.model.SslGetData
import com.mnbt.app.data.remote.api.model.asFlag
import com.mnbt.app.data.remote.api.model.asIntOrNull
import com.mnbt.app.data.remote.api.model.millisToDaysFromNow
import com.mnbt.app.data.remote.api.model.parsePanelDate
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.domain.sync.HostSync
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 详情页 20 行功能的「当前值」，全部来自进入时的一次并发取数；null=取不到，行渲染「—」。 */
data class FeatureValues(
    val phpVersion: String? = null,
    val rundir: String? = null,
    val indexdoc: String? = null,
    val gzipOn: Boolean? = null,
    val domainCount: Int? = null,
    val sslDaysLeft: Int? = null,
    /** ssl.get 是否应答成功（应答了但无证书与取不到要能区分） */
    val sslResponded: Boolean = false,
    val forceHttps: Boolean? = null,
    val monitorCount: Int? = null,
    val monitorAlerts: Int? = null,
    val cacheCount: Int? = null,
    val hotlinkOn: Boolean? = null,
)

data class HostDetailUi(
    val host: HostEntity? = null,
    /** 本地条目流是否已到过（区分「还没读 Room」与「条目不存在」） */
    val hostLoaded: Boolean = false,
    /** Room overview_cache 解出的快照：首帧不等网络（§10.1），刷新成功后经 Flow diff 更新 */
    val snapshot: HostSnapshot? = null,
    val values: FeatureValues = FeatureValues(),
    val query: String = "",
    val refreshing: Boolean = false,
    val tokenRejected: Boolean = false,
    val blockedMessage: String? = null,
)

/**
 * 主机详情页状态（PRD §7.3）。
 *
 * 进入时一个 coroutineScope 并发拉 10 个请求：host.overview、host.profile
 * （经 [HostSync.refresh]，顺带写回 Room 快照）、domain.list、ssl.get、monitor.list、
 * site.cache.list、site.hotlink.get、site.init(gzip/yxml/mrwd)。
 * 单项失败只让对应行显示「—」；TokenRejected / HostBlocked 升级为整页形态。
 * 所有值都汇进一个 StateFlow，UI 纯渲染。
 */
class HostDetailViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val localHost = MutableStateFlow<HostEntity?>(null)
    private val localLoaded = MutableStateFlow(false)
    private val queryFlow = MutableStateFlow("")
    private val valuesFlow = MutableStateFlow(FeatureValues())
    private val flagsFlow = MutableStateFlow(PageFlags())

    private data class PageFlags(
        val refreshing: Boolean = false,
        val started: Boolean = false,
        val tokenRejected: Boolean = false,
        val blockedMessage: String? = null,
    )

    val ui: StateFlow<HostDetailUi> = combine(
        localHost,
        queryFlow,
        valuesFlow,
        flagsFlow,
    ) { host, query, values, flags ->
        val snapshot = HostSync.decodeSnapshot(host?.overviewCache)
        HostDetailUi(
            host = host,
            hostLoaded = localLoaded.value,
            snapshot = snapshot,
            // 首帧先用 Room 快照里的 php 兜底，网络结果到达后被覆盖
            values = values.copy(phpVersion = values.phpVersion ?: snapshot?.profile?.php),
            query = query,
            refreshing = flags.refreshing,
            tokenRejected = flags.tokenRejected || host?.needsRepair == true,
            blockedMessage = flags.blockedMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostDetailUi())

    init {
        viewModelScope.launch {
            container.hostRepository.observeHost(hostId).collect { entity ->
                localLoaded.value = true
                localHost.value = entity
                if (entity != null && !flagsFlow.value.started) {
                    flagsFlow.value = flagsFlow.value.copy(started = true)
                    fetchAll(entity)
                }
            }
        }
    }

    fun setQuery(value: String) {
        queryFlow.value = value
    }

    /** 下拉刷新 / 失败重试：全量并发重取。 */
    fun refresh() {
        viewModelScope.launch {
            container.hostDao.findById(hostId)?.let { fetchAll(it) }
        }
    }

    /** ⋮ 菜单「刷新用量」：只重取 overview + profile（HostSync 会写回 overview_cache）。 */
    fun refreshUsage() {
        viewModelScope.launch {
            val host = container.hostDao.findById(hostId) ?: return@launch
            flagsFlow.update { it.copy(refreshing = true) }
            val outcome = container.hostSync.refresh(host)
            if (outcome is HostOutcome.Ready) {
                valuesFlow.update { it.copy(phpVersion = outcome.data.profile?.php ?: it.phpVersion) }
            } else {
                applyPageOutcome(outcome)
            }
            flagsFlow.update { it.copy(refreshing = false) }
        }
    }

    fun rename(label: String) {
        viewModelScope.launch { container.hostRepository.rename(hostId, label) }
    }

    /** 解绑：HostRepository.remove 先尽力吊销远端令牌再清本地；成功后回列表。 */
    fun unbind(onDone: () -> Unit) {
        viewModelScope.launch {
            container.hostRepository.remove(hostId)
            onDone()
        }
    }

    private suspend fun fetchAll(host: HostEntity) {
        flagsFlow.update { it.copy(refreshing = true, tokenRejected = false, blockedMessage = null) }
        coroutineScope {
            // 用量+档案（含 php 版本）：走 HostSync，Ready 结果由它写回 overview_cache
            launch {
                val outcome = container.hostSync.refresh(host)
                if (outcome is HostOutcome.Ready) {
                    valuesFlow.update { it.copy(phpVersion = outcome.data.profile?.php ?: it.phpVersion) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(container, hostId, MnbtActions.DOMAIN_LIST) { decodePayload<DomainListData>(it) }
                if (outcome is HostOutcome.Ready) {
                    valuesFlow.update { it.copy(domainCount = outcome.data.list?.size ?: 0) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(container, hostId, MnbtActions.SSL_GET) { decodePayload<SslGetData>(it) }
                if (outcome is HostOutcome.Ready) {
                    val data = outcome.data
                    val days = millisToDaysFromNow(parsePanelDate(data.ssl?.expire))
                    valuesFlow.update {
                        it.copy(
                            sslResponded = true,
                            sslDaysLeft = days ?: it.sslDaysLeft,
                            forceHttps = data.forceHttps.asFlag() ?: it.forceHttps,
                        )
                    }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(container, hostId, MnbtActions.MONITOR_LIST) { decodePayload<MonitorListData>(it) }
                if (outcome is HostOutcome.Ready) {
                    val list = outcome.data.list
                    valuesFlow.update {
                        it.copy(
                            monitorCount = list?.size,
                            monitorAlerts = list?.count { task -> (task.lastCode.asIntOrNull() ?: 0) >= 400 },
                        )
                    }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(container, hostId, MnbtActions.SITE_CACHE_LIST) { decodePayload<CacheListData>(it) }
                if (outcome is HostOutcome.Ready) {
                    valuesFlow.update { it.copy(cacheCount = outcome.data.list?.size ?: 0) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(container, hostId, MnbtActions.SITE_HOTLINK_GET) { decodePayload<HotlinkGetData>(it) }
                if (outcome is HostOutcome.Ready) {
                    valuesFlow.update { it.copy(hotlinkOn = outcome.data.open.asFlag()) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(
                    container, hostId, MnbtActions.SITE_INIT,
                    params = mapOf("section" to "gzip"),
                ) { decodePayload<SiteInitData>(it) }
                if (outcome is HostOutcome.Ready) {
                    val on = outcome.data.current?.let { it.equals("on", ignoreCase = true) }
                    valuesFlow.update { it.copy(gzipOn = on) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(
                    container, hostId, MnbtActions.SITE_INIT,
                    params = mapOf("section" to "yxml"),
                ) { decodePayload<SiteInitData>(it) }
                if (outcome is HostOutcome.Ready) {
                    valuesFlow.update { it.copy(rundir = outcome.data.current) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
            launch {
                val outcome = hostCall(
                    container, hostId, MnbtActions.SITE_INIT,
                    params = mapOf("section" to "mrwd"),
                ) { decodePayload<SiteInitData>(it) }
                if (outcome is HostOutcome.Ready) {
                    valuesFlow.update { it.copy(indexdoc = outcome.data.current) }
                } else {
                    applyPageOutcome(outcome)
                }
            }
        }
        flagsFlow.update { it.copy(refreshing = false) }
    }

    /** 单项 Failed 只让该行留「—」；TokenRejected / HostBlocked / CredentialMissing 是整页形态，上浮。 */
    private fun applyPageOutcome(outcome: HostOutcome<*>) {
        when (outcome) {
            is HostOutcome.TokenRejected, HostOutcome.CredentialMissing ->
                flagsFlow.update { it.copy(tokenRejected = true) }
            is HostOutcome.HostBlocked ->
                flagsFlow.update { it.copy(blockedMessage = outcome.message) }
            else -> Unit
        }
    }
}
