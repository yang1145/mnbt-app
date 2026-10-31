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

/**
 * 通知中心状态机（契约 §10.2，notice.list / notice.read）。
 * 点击未读项 → notice.read(id) → 本地把该项 read 置 1 并扣减 unread，不整页重拉；
 * 「全部已读」→ notice.read 不带 id（服务端语义即整站标记），本地整页 read 置 1、unread 归零。
 * 通知项的副标题取 content（契约里的 type/level 本页不渲染，故 DTO 不声明）。
 */
class NoticeViewModel(
    private val application: Application,
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _listState = MutableStateFlow<LoadState<OpsNoticeListData>>(LoadState.Loading)
    val listState: StateFlow<LoadState<OpsNoticeListData>> = _listState.asStateFlow()

    private val _readPhase = MutableStateFlow<OpsWritePhase>(OpsWritePhase.Idle)
    val readPhase: StateFlow<OpsWritePhase> = _readPhase.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (_listState.value !is LoadState.Ready) _listState.value = LoadState.Loading
            _listState.value = hostCall(
                container, hostId, MnbtActions.NOTICE_LIST
            ) { decodePayload<OpsNoticeListData>(it) }.toLoadState()
        }
    }

    fun markRead(item: OpsNoticeItem) {
        if (item.read != 0 || _readPhase.value is OpsWritePhase.Saving) return
        viewModelScope.launch {
            _readPhase.value = OpsWritePhase.Saving
            val outcome = hostCall(
                container, hostId, MnbtActions.NOTICE_READ,
                mapOf("id" to item.id.toString())
            ) { decodePayload<OpsNoticeReadData>(it) }
            when (outcome) {
                is HostOutcome.Ready -> {
                    markLocal(item.id)
                    _readPhase.value = OpsWritePhase.Done(
                        application.getString(R.string.ops_notice_marked),
                    )
                }

                HostOutcome.TokenRejected -> {
                    _readPhase.value = OpsWritePhase.Idle
                    _listState.value = LoadState.TokenRejected
                }

                HostOutcome.CredentialMissing -> {
                    _readPhase.value = OpsWritePhase.Idle
                    _listState.value = LoadState.CredentialMissing
                }

                is HostOutcome.HostBlocked -> {
                    _readPhase.value = OpsWritePhase.Idle
                    _listState.value = LoadState.HostBlocked(outcome.message)
                }

                is HostOutcome.Failed ->
                    _readPhase.value = OpsWritePhase.Error(outcome.message, outcome.retryAfterSeconds)
            }
        }
    }

    /**
     * 全部标记已读（契约 §10.2）：`notice.read` 不传 id 就是「全部」，
     * 服务端不返回受影响的条数，所以成功后按「本地这页全部置已读 + unread 归零」镜像。
     */
    fun markAllRead() {
        if (_readPhase.value is OpsWritePhase.Saving) return
        viewModelScope.launch {
            _readPhase.value = OpsWritePhase.Saving
            val outcome = hostCall(
                container, hostId, MnbtActions.NOTICE_READ,
            ) { decodePayload<OpsNoticeReadData>(it) }
            when (outcome) {
                is HostOutcome.Ready -> {
                    markAllLocal()
                    _readPhase.value = OpsWritePhase.Done(
                        application.getString(R.string.ops_notice_marked_all),
                    )
                }

                HostOutcome.TokenRejected -> {
                    _readPhase.value = OpsWritePhase.Idle
                    _listState.value = LoadState.TokenRejected
                }

                HostOutcome.CredentialMissing -> {
                    _readPhase.value = OpsWritePhase.Idle
                    _listState.value = LoadState.CredentialMissing
                }

                is HostOutcome.HostBlocked -> {
                    _readPhase.value = OpsWritePhase.Idle
                    _listState.value = LoadState.HostBlocked(outcome.message)
                }

                is HostOutcome.Failed ->
                    _readPhase.value = OpsWritePhase.Error(outcome.message, outcome.retryAfterSeconds)
            }
        }
    }

    fun consumeReadPhase() {
        val phase = _readPhase.value
        if (phase is OpsWritePhase.Done || phase is OpsWritePhase.Error) {
            _readPhase.value = OpsWritePhase.Idle
        }
    }

    /** 服务端回读 read=1 成功后的本地镜像更新；列表若已被替换成非 Ready 态则忽略。 */
    private fun markLocal(id: Int) {
        val current = _listState.value as? LoadState.Ready ?: return
        val data = current.data
        _listState.value = LoadState.Ready(
            data.copy(
                list = data.list.map { if (it.id == id) it.copy(read = 1) else it },
                unread = (data.unread - 1).coerceAtLeast(0),
            ),
        )
    }

    private fun markAllLocal() {
        val current = _listState.value as? LoadState.Ready ?: return
        val data = current.data
        _listState.value = LoadState.Ready(
            data.copy(list = data.list.map { it.copy(read = 1) }, unread = 0),
        )
    }
}
