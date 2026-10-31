package com.mnbt.app.ui.pair

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtCodes
import com.mnbt.app.data.remote.api.MnbtException
import com.mnbt.app.data.remote.http.HostUrl
import com.mnbt.app.data.remote.http.PairQr
import com.mnbt.app.data.remote.trust.CertificateInfo
import com.mnbt.app.data.remote.trust.CertificateProbe
import com.mnbt.app.data.remote.trust.ProbeResult
import com.mnbt.app.domain.repo.HostAlreadyBoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 配对流程状态机（PRD §9.1/§9.2/§9.3）。
 *
 * idle → probing →（PlaintextWarn | Untrusted 待确认 | SystemTrusted 免确认直接配对）
 * → pairing → bound | failed。
 *
 * 扫码页 / 手动页 / 信任页共用同一个状态集，只是反应不同：
 * 扫码与手动页见到 PlaintextWarn/Untrusted 就跳信任页（不消费证书详情）；
 * 信任页负责渲染这两个分支并在用户确认后调 [confirmAndPair]。
 */
sealed interface PairState {
    data object Idle : PairState
    data object Probing : PairState
    data object Pairing : PairState

    /** http 站点：没有证书可核对，信任页要出 §9.3 红字强警告 */
    data class PlaintextWarn(val qr: PairQr) : PairState

    /** 自签 / 过期 / 域名不符：用户逐项核对信息并确认后才配对，确认即 pin 叶子指纹 */
    data class Untrusted(val qr: PairQr, val info: CertificateInfo, val reason: String) : PairState

    data class Bound(val hostId: Long, val label: String) : PairState

    /** [retryAfterSeconds] 非空表示限流（1008），UI 要给出「稍后重试」的等待提示 */
    data class Failed(val message: String, val retryAfterSeconds: Int? = null) : PairState
}

/** 扫码 / 手动两页把状态交给信任页的通道，pair 参数随 route 传递。 */
val PairState.trustHandoffQr: PairQr?
    get() = when (this) {
        is PairState.PlaintextWarn -> qr
        is PairState.Untrusted -> qr
        else -> null
    }

class PairViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<PairState>(PairState.Idle)
    val state: StateFlow<PairState> = _state.asStateFlow()

    /** 最近一次待处理配对信息：失败重试仍用同一份 base+code */
    private var pending: PairQr? = null

    /** 配对 POST 是否已在途：挡住快速双击/重组把同一个一次性码连发两次（第二次必然「已被使用」） */
    private var pairing = false

    /** 扫码结果入口：先 HostUrl.parsePairQr，不是配对码直接给明确报错。 */
    fun onScanned(raw: String) {
        val qr = HostUrl.parsePairQr(raw)
        if (qr == null) {
            fail("扫到的不是 MNBT 配对二维码，请扫描站点网页面板「APP 绑定」页生成的二维码")
            return
        }
        probe(qr)
    }

    /** 手动输入入口：地址用 HostUrl.normalize 兜底（无协议默认 https）。 */
    fun onManualSubmitted(rawBase: String, rawCode: String) {
        val base = HostUrl.normalize(rawBase)
        if (base == null) {
            fail("站点地址无效，示例：https://panel.example.com 或 http://1.2.3.4/mnbt")
            return
        }
        val code = rawCode.trim().uppercase()
        if (code.isEmpty()) {
            fail("请在网页面板「APP 绑定」页生成配对码后填入")
            return
        }
        probe(PairQr(base, code))
    }

    /** 信任页进入时对 base+code 做一次独立探测（决定渲染明文警告还是证书详情）。 */
    fun startIfIdle(base: String, code: String) {
        if (_state.value !is PairState.Idle) return
        if (base.toHttpUrlOrNull() == null || code.isBlank()) {
            _state.value = PairState.Failed("站点地址或配对码无效，请返回后重试")
            return
        }
        probe(PairQr(base.trimEnd('/'), code.trim().uppercase()))
    }

    /** 扫码 / 手动页把「待确认」状态交给信任页后复位，返回本页时可以再次发起。 */
    fun reset() {
        _state.value = PairState.Idle
    }

    /** 信任页「信任并继续」：明文不 pin（无证书可 pin），Untrusted 按 §9.2 第 4 步 pin 叶子指纹。 */
    fun confirmAndPair() {
        when (val s = _state.value) {
            is PairState.PlaintextWarn -> pair(s.qr, pin = null)
            is PairState.Untrusted -> pair(s.qr, pin = s.info.fingerprintSha256)
            else -> Unit
        }
    }

    /** 失败重试：重跑 TLS 探测（证书可能刚换、码可能刚重新生成同 base 复用）。 */
    fun retry() {
        pending?.let(::probe)
    }

    private fun probe(qr: PairQr) {
        pending = qr
        viewModelScope.launch {
            _state.value = PairState.Probing
            val url = qr.baseUrl.toHttpUrlOrNull()
            if (url == null) {
                _state.value = PairState.Failed("站点地址无效：${qr.baseUrl}")
                return@launch
            }
            try {
                when (val result = CertificateProbe.probe(url)) {
                    // 系统 CA 正常签发：不停信任页，直接配对（§9.2 只在证书不可信时才需要人工核对）
                    is ProbeResult.SystemTrusted -> pair(qr, pin = null)
                    is ProbeResult.Plaintext -> _state.value = PairState.PlaintextWarn(qr)
                    is ProbeResult.Untrusted -> _state.value = PairState.Untrusted(qr, result.info, result.reason)
                    is ProbeResult.Unreachable -> _state.value = PairState.Failed(result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PairState.Failed(e.message ?: "无法连接该站点")
            }
        }
    }

    private fun pair(qr: PairQr, pin: String?) {
        if (pairing) return
        pairing = true
        pending = qr
        viewModelScope.launch {
            _state.value = PairState.Pairing
            try {
                // 协议参数（§4.5）里除 code 外全部由 MnbtClient/DeviceInfo 补齐，
                // device_name 按基座约定用 Build.MODEL
                val host = container.hostRepository.pair(
                    qr,
                    pin,
                    deviceName = Build.MODEL,
                )
                _state.value = PairState.Bound(host.id, host.label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = pairFailure(e)
            } finally {
                pairing = false
            }
        }
    }

    private fun pairFailure(e: Exception): PairState.Failed = when {
        e is HostAlreadyBoundException ->
            PairState.Failed("该站点已绑定过，请在主机列表直接使用已有条目；如需换绑请先在主机管理中解绑")

        // §4.6：1002 = 配对码错误/过期/已使用。服务端已按具体原因给出不同文案
        // （没收到码 / 格式错 / 查无此码 / 已用 / 过期），照原文显示才能定位真凶，别统一成"过期"。
        e is MnbtException.Server && e.code == MnbtCodes.PAIR_CODE_INVALID ->
            PairState.Failed(e.message)

        e is MnbtException.Throttled ->
            PairState.Failed("操作过于频繁，请稍后重试", e.retryAfterSeconds)

        // Unreachable / HttpStatus / Certificate / Protocol / Server(其余 code)…文案本身已面向用户
        e is MnbtException -> PairState.Failed(e.message)

        else -> PairState.Failed(e.message ?: "配对失败，请稍后重试")
    }

    private fun fail(message: String) {
        _state.value = PairState.Failed(message)
    }
}
