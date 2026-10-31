package com.mnbt.app.ui.domainssl

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 页面渲染用的 SSL 快照。cert == null 表示当前无证书。 */
data class SslUiData(
    val cert: SslCertDto?,
    val forceHttps: Boolean,
    val pem: String,
    val key: String,
)

/**
 * SSL 证书页状态（PRD §5.5 / §7.2）。
 *
 * - ssl.apply / ssl.renew 映射到同一 gn=sqssl（type=false/true），服务端可能限流，
 *   失败带 retryAfterSeconds 时文案追加「约 N 秒后可重试」；
 * - ssl.custom 的 crt/key 是 PEM（纯 ASCII），走普通 hostCall 表单值即可，无需 hostCallBytes；
 * - ssl.force_https 入参用 qk=1|0（mock server.mjs: str('qk', str('on','1')) !== '0'），
 *   开关做即时回滚式更新：先改 UI，失败恢复并提示。
 */
class SslViewModel(
    private val container: AppContainer,
    private val hostId: Long,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SslUiData>>(LoadState.Loading)
    val state: StateFlow<LoadState<SslUiData>> = _state.asStateFlow()

    private val _opPhase = MutableStateFlow<OpPhase>(OpPhase.Idle)
    val opPhase: StateFlow<OpPhase> = _opPhase.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            _state.value = hostCall(container, hostId, MnbtActions.SSL_GET) { element ->
                val p = decodePayload<SslGetPayload>(element)
                SslUiData(
                    cert = p.ssl?.takeIf { it.status.isNotBlank() && !it.status.equals("none", true) },
                    forceHttps = p.forceHttps,
                    pem = p.pem,
                    key = p.key,
                )
            }.toFeatureLoadState(::retrySuffix)
        }
    }

    fun applyCertificate() {
        callAndRefresh(MnbtActions.SSL_APPLY, emptyMap(), R.string.ssl_apply_done)
    }

    fun renewCertificate() {
        callAndRefresh(MnbtActions.SSL_RENEW, emptyMap(), R.string.ssl_renew_done)
    }

    fun closeSsl() {
        callAndRefresh(MnbtActions.SSL_CLOSE, emptyMap(), R.string.ssl_close_done)
    }

    /** 自定义证书：pem/key 任一为空都不提交，避免服务端收到半份证书。 */
    fun submitCustom(pem: String, key: String) {
        if (pem.isBlank() || key.isBlank()) {
            _opPhase.value = OpPhase.Error(appContext.getString(R.string.ssl_custom_incomplete))
            return
        }
        viewModelScope.launch {
            _opPhase.value = OpPhase.Working
            val outcome = hostCall(
                container, hostId, MnbtActions.SSL_CUSTOM,
                mapOf("pem" to pem, "key" to key),
            ) { decodePayload<SslCustomPayload>(it) }
            when (outcome) {
                is HostOutcome.Ready -> succeed(appContext.getString(R.string.ssl_custom_done))
                else -> failOp(outcome)
            }
        }
    }

    /** 强制 HTTPS 开关：即时回滚式更新，成功后以服务端回显为准。 */
    fun setForceHttps(enabled: Boolean) {
        val snapshot = (_state.value as? LoadState.Ready)?.data ?: return
        if (snapshot.forceHttps == enabled) return
        viewModelScope.launch {
            _state.value = LoadState.Ready(snapshot.copy(forceHttps = enabled))
            val outcome = hostCall(
                container, hostId, MnbtActions.SSL_FORCE_HTTPS,
                mapOf("qk" to if (enabled) "1" else "0"),
            ) { decodePayload<SslForcePayload>(it) }
            when (outcome) {
                is HostOutcome.Ready -> {
                    _state.value = LoadState.Ready(snapshot.copy(forceHttps = outcome.data.forceHttps))
                    _opPhase.value = OpPhase.Done(
                        appContext.getString(
                            if (outcome.data.forceHttps) R.string.ssl_force_on else R.string.ssl_force_off,
                        ),
                    )
                }
                else -> {
                    // 回滚 + 分类提示
                    _state.value = LoadState.Ready(snapshot)
                    failOp(outcome)
                }
            }
        }
    }

    fun consumeOpPhase() {
        _opPhase.value = OpPhase.Idle
    }

    /** 申请/续签/关闭的公共骨架：成功后静默刷新 ssl.get，done 文案可附带服务端回显的到期日。 */
    private fun callAndRefresh(action: String, params: Map<String, String>, doneRes: Int) {
        viewModelScope.launch {
            _opPhase.value = OpPhase.Working
            val outcome = hostCall(container, hostId, action, params) { element ->
                // apply/renew 回显 expire，close 没有；统一折叠成一个可选字符串
                if (action == MnbtActions.SSL_CLOSE) {
                    decodePayload<SslClosePayload>(element)
                    ""
                } else {
                    decodePayload<SslApplyPayload>(element).expire
                }
            }
            when (outcome) {
                is HostOutcome.Ready -> {
                    val expire = outcome.data
                    val message = if (expire.isNotBlank()) {
                        appContext.getString(doneRes) +
                            appContext.getString(R.string.ssl_done_expire, expire)
                    } else {
                        appContext.getString(doneRes)
                    }
                    succeed(message)
                }
                else -> failOp(outcome)
            }
        }
    }

    private suspend fun succeed(message: String) {
        _opPhase.value = OpPhase.Done(message)
        _state.value = hostCall(container, hostId, MnbtActions.SSL_GET) { element ->
            val p = decodePayload<SslGetPayload>(element)
            SslUiData(
                cert = p.ssl?.takeIf { it.status.isNotBlank() && !it.status.equals("none", true) },
                forceHttps = p.forceHttps,
                pem = p.pem,
                key = p.key,
            )
        }.toFeatureLoadState(::retrySuffix)
    }

    private fun failOp(outcome: HostOutcome<*>) {
        val phase = classifyWriteOutcome(outcome, tokenRepairHint(), ::retrySuffix)
        _opPhase.value = phase
        if (phase is OpPhase.Blocked) _readOnly.value = true
    }

    private fun retrySuffix(seconds: Int): String =
        appContext.getString(R.string.domainssl_retry_suffix, seconds)

    private fun tokenRepairHint(): String =
        appContext.getString(R.string.domainssl_token_repair_short)
}

/**
 * 剩余天数：expire 形如 "2027-01-15 00:00:00" 或 "2027-01-15"，
 * 取前 10 位按 ISO 日期解析；解析失败返回 null（UI 只显示原始文本，不瞎猜）。
 */
fun sslDaysRemaining(expire: String): Int? {
    val date = runCatching { LocalDate.parse(expire.trim().take(10)) }.getOrNull() ?: return null
    return ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
}
