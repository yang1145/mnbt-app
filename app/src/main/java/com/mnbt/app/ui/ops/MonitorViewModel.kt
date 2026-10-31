package com.mnbt.app.ui.ops

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.R
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * 网站监控页状态机（PRD §5.6）。
 * 列表 = monitor.list；写操作 save/delete/toggle 失败分类与组 A 一致：
 * 凭证/封禁类直接替换整页状态（禁继续写），Failed 走 Snackbar（1008 附 retryAfter）。
 * 日志 = monitor.log，弹层内独立状态，不进整页通道。
 */
class MonitorViewModel(
    private val application: Application,
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _listState = MutableStateFlow<LoadState<OpsMonitorListData>>(LoadState.Loading)
    val listState: StateFlow<LoadState<OpsMonitorListData>> = _listState.asStateFlow()

    private val _writePhase = MutableStateFlow<OpsWritePhase>(OpsWritePhase.Idle)
    val writePhase: StateFlow<OpsWritePhase> = _writePhase.asStateFlow()

    /** null = 日志弹层未打开 */
    private val _logState = MutableStateFlow<Pair<String, LoadState<OpsMonitorLogData>>?>(null)
    val logState: StateFlow<Pair<String, LoadState<OpsMonitorLogData>>?> = _logState.asStateFlow()

    /** 写操作期间禁用重复提交 */
    val busy: Boolean get() = _writePhase.value is OpsWritePhase.Saving

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (_listState.value !is LoadState.Ready) _listState.value = LoadState.Loading
            _listState.value = hostCall(
                container, hostId, MnbtActions.MONITOR_LIST
            ) { decodePayload<OpsMonitorListData>(it) }.toLoadState()
        }
    }

    /**
     * monitor.save：`id` 传 0 表示新增（契约 §10.1，插件会补齐 method/timeout/判定规则等
     * 隐藏字段，编辑时沿用旧行，不会被重置）。
     * `type=resource` 时 `target` 是资源类别词（web/sql/traffic），不是 URL；
     * 启停另有 monitor.toggle，用 `status=on/off`。
     */
    fun save(id: Int?, name: String, type: String, target: String, cycle: Int) {
        write(
            MnbtActions.MONITOR_SAVE,
            linkedMapOf(
                "id" to (id ?: 0).toString(),
                "name" to name,
                "type" to type,
                "target" to target,
                "cycle" to cycle.toString(),
            ),
            { decodePayload<OpsMonitorSaveData>(it) },
        ) {
            application.getString(R.string.ops_monitor_saved)
        }
    }

    fun delete(id: Int) {
        write(
            MnbtActions.MONITOR_DELETE,
            mapOf("id" to id.toString()),
            { decodePayload<OpsMonitorDeleteData>(it) },
        ) {
            application.getString(R.string.ops_monitor_deleted)
        }
    }

    fun toggle(task: OpsMonitorTask) {
        val next = if (task.status == "on") "off" else "on"
        write(
            MnbtActions.MONITOR_TOGGLE,
            mapOf("id" to task.id.toString(), "status" to next),
            { decodePayload<OpsMonitorToggleData>(it) },
        ) {
            if (next == "on") {
                application.getString(R.string.ops_monitor_enabled)
            } else {
                application.getString(R.string.ops_monitor_disabled)
            }
        }
    }

    fun loadLog(task: OpsMonitorTask) {
        viewModelScope.launch {
            _logState.value = task.name to LoadState.Loading
            val outcome = hostCall(
                container, hostId, MnbtActions.MONITOR_LOG,
                mapOf("id" to task.id.toString())
            ) { decodePayload<OpsMonitorLogData>(it) }
            _logState.value = task.name to outcome.toLoadState()
        }
    }

    fun closeLog() {
        _logState.value = null
    }

    fun consumeWritePhase() {
        val phase = _writePhase.value
        if (phase is OpsWritePhase.Done || phase is OpsWritePhase.Error) {
            _writePhase.value = OpsWritePhase.Idle
        }
    }

    private fun <T> write(
        action: String,
        params: Map<String, String>,
        decode: (JsonElement) -> T,
        successMessage: (T) -> String,
    ) {
        viewModelScope.launch {
            if (_writePhase.value is OpsWritePhase.Saving) return@launch
            _writePhase.value = OpsWritePhase.Saving
            val outcome = hostCall(container, hostId, action, params, decode = decode)
            when (outcome) {
                is HostOutcome.Ready -> {
                    _writePhase.value = OpsWritePhase.Done(successMessage(outcome.data))
                    load()
                }

                else -> applyWriteOutcome(outcome)
            }
        }
    }

    /** 凭证失效/到期封禁：整页换成对应引导态；Failed：Snackbar 级（含 1008 的 retryAfter）。 */
    private fun applyWriteOutcome(outcome: HostOutcome<*>) {
        when (outcome) {
            HostOutcome.TokenRejected -> {
                _writePhase.value = OpsWritePhase.Idle
                _listState.value = LoadState.TokenRejected
            }

            HostOutcome.CredentialMissing -> {
                _writePhase.value = OpsWritePhase.Idle
                _listState.value = LoadState.CredentialMissing
            }

            is HostOutcome.HostBlocked -> {
                _writePhase.value = OpsWritePhase.Idle
                _listState.value = LoadState.HostBlocked(outcome.message)
            }

            is HostOutcome.Failed ->
                _writePhase.value = OpsWritePhase.Error(outcome.message, outcome.retryAfterSeconds)

            is HostOutcome.Ready -> Unit
        }
    }
}
