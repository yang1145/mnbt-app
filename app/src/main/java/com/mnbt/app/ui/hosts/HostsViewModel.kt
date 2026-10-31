package com.mnbt.app.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.data.remote.api.model.HostSnapshot
import com.mnbt.app.data.remote.api.model.HostProfile
import com.mnbt.app.data.remote.api.model.daysToExpire
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.sync.HostSync
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 单台主机本轮刷新所处阶段，驱动行内的细微进度与失败标记。 */
enum class RowPhase { Syncing, Fresh, Failed }

data class HostRowUi(
    val host: HostEntity,
    val snapshot: HostSnapshot?,
    val phase: RowPhase,
) {
    val profile: HostProfile? get() = snapshot?.profile
    val refreshing: Boolean get() = phase == RowPhase.Syncing
    val failed: Boolean get() = phase == RowPhase.Failed
}

/**
 * 健康总览条四个数字。口径（交付报告同步）：
 * - needAttention = needsRepair ∪ 本轮刷新失败 ∪ profile.status 明确非 running（三者去重并集）；
 * - expiringSoon  = 0 < 剩余天数 ≤ 30；
 * - expiredCount  = 剩余天数 < 0（已到期与 30 天内分开算）；今天到期（0 天）计入 expiringSoon。
 * 从未同步过（无 profile 且非本轮失败）的主机不计入「需处理」。
 */
data class HostsUiState(
    val rows: List<HostRowUi> = emptyList(),
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val total: Int = 0,
    val needAttention: Int = 0,
    val expiringSoon: Int = 0,
    val expiredCount: Int = 0,
)

class HostsViewModel(private val container: AppContainer) : ViewModel() {

    private val phases = MutableStateFlow<Map<Long, RowPhase>>(emptyMap())
    private val syncing = MutableStateFlow(false)

    val state: StateFlow<HostsUiState> =
        combine(container.hostRepository.observeHosts(), phases, syncing) { hosts, phaseMap, isSyncing ->
            val rows = hosts.map { host ->
                HostRowUi(
                    host = host,
                    snapshot = HostSync.decodeSnapshot(host.overviewCache),
                    phase = phaseMap[host.id] ?: RowPhase.Fresh,
                )
            }
            val expired = rows.count { it.profile?.daysToExpire()?.let { d -> d < 0 } == true }
            val soon = rows.count { it.profile?.daysToExpire()?.let { d -> d in 0..30 } == true }
            val attention = rows.count { row ->
                val profile = row.profile
                row.host.needsRepair ||
                    row.failed ||
                    (profile != null && !profile.running)
            }
            HostsUiState(
                rows = rows,
                loaded = true,
                syncing = isSyncing,
                total = rows.size,
                needAttention = attention,
                expiringSoon = soon,
                expiredCount = expired,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostsUiState())

    /**
     * 并发逐台刷新（HostSync.refresh 内部 overview+profile 再并发）。
     * 任何一台的结果只影响它自己的行：Room 写回由 HostSync 完成，列表经 Flow diff 更新；
     * 失败主机单独标记，不阻塞、不拖垮其他行（PRD §10.1 串行请求收敛）。
     */
    fun refresh() {
        viewModelScope.launch {
            if (syncing.value) return@launch
            val hosts = container.hostRepository.observeHosts().first()
            if (hosts.isEmpty()) return@launch
            syncing.value = true
            phases.value = hosts.associate { it.id to RowPhase.Syncing }
            coroutineScope {
                hosts.forEach { host ->
                    launch {
                        val outcome = container.hostSync.refresh(host)
                        val phase = if (outcome is HostOutcome.Ready) RowPhase.Fresh else RowPhase.Failed
                        // update{} 是 CAS 循环：多台主机并发写同一 Map 不会互相覆盖
                        phases.update { it + (host.id to phase) }
                    }
                }
            }
            syncing.value = false
        }
    }
}
