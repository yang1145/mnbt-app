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

/**
 * 域名管理页状态（PRD §5.5 / §7.2，功能对齐网页面板 domain_shop 的域名页）。
 *
 * 与网页端一致的三点口径：
 * - 面板没有「重命名」原语：改前缀 = 服务端删旧 + 按新前缀加新（domain.prefix.set）；
 * - 目录绑定看 dir：'/'（或空）是整站绑定，其余是运行目录下的子目录名，候选取
 *   `domain.subdir.list.dirs`（网页面板的下拉就是这份数据）；
 * - 一次只提交一条域名。网页端没有「同时绑定 www」，所以这里不再循环提交，
 *   避免出现「裸域成功、www 失败」的半成品状态。
 *
 * appContext 只用于取字符串资源文案（applicationContext，无泄漏风险）；
 * 服务端返回的失败原因一律原样透传，不做本地化包装。
 */
class DomainsViewModel(
    private val container: AppContainer,
    private val hostId: Long,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<DomainPageData>>(LoadState.Loading)
    val state: StateFlow<LoadState<DomainPageData>> = _state.asStateFlow()

    /** 添加弹窗打开时才拉目录选项；NotLoaded 用于区分「还没拉」与「拉了但为空」。 */
    private val _subdirs = MutableStateFlow<SubdirsState>(SubdirsState.NotLoaded)
    val subdirs: StateFlow<SubdirsState> = _subdirs.asStateFlow()

    private val _opPhase = MutableStateFlow<OpPhase>(OpPhase.Idle)
    val opPhase: StateFlow<OpPhase> = _opPhase.asStateFlow()

    /** 整页只读（写操作时令牌失效/主机封禁），UI 据此禁一切按钮。 */
    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()

    /** A 记录提示缓存：列表与提示是两个请求，任一条先回来都不该把另一条丢掉。 */
    private var resolveHint: String? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val outcome = hostCall(container, hostId, MnbtActions.DOMAIN_LIST) {
                decodePayload<DomainListPayload>(it)
            }
            _state.value = when (outcome) {
                is HostOutcome.Ready -> LoadState.Ready(outcome.data.toPage(resolveHint))
                else -> failureState(outcome)
            }
            loadResolveHint()
        }
    }

    /**
     * A 记录提示：与网页面板顶部那条同源（site.init section=url 的 btip/als）。
     * 取不到就整块不显示，绝不拿假地址占位。
     */
    private fun loadResolveHint() {
        viewModelScope.launch {
            val outcome = hostCall(container, hostId, MnbtActions.SITE_INIT, mapOf("section" to "url")) {
                decodePayload<DomainResolveHintPayload>(it)
            }
            val hint = (outcome as? HostOutcome.Ready)?.data?.let { d ->
                when {
                    d.als.isNotBlank() && !d.als.equals("false", ignoreCase = true) -> d.als
                    d.btip.isNotBlank() -> appContext.getString(R.string.domains_hint_a_record, d.btip)
                    else -> null
                }
            }
            if (hint == resolveHint) return@launch
            resolveHint = hint
            val current = _state.value
            if (current is LoadState.Ready) {
                _state.value = LoadState.Ready(current.data.copy(resolveHint = hint))
            }
        }
    }

    /** 弹窗打开时拉一次；已有成功结果不重复拉，失败可在弹窗内重试。 */
    fun loadSubdirs() {
        if (_subdirs.value is SubdirsState.Loaded || _subdirs.value is SubdirsState.Loading) return
        viewModelScope.launch {
            _subdirs.value = SubdirsState.Loading
            val outcome = hostCall(container, hostId, MnbtActions.DOMAIN_SUBDIR_LIST) {
                decodePayload<SubdirListPayload>(it).dirs
            }
            _subdirs.value = when (outcome) {
                is HostOutcome.Ready -> SubdirsState.Loaded(outcome.data)
                is HostOutcome.Failed ->
                    SubdirsState.Error(withRetryHint(outcome.message, outcome.retryAfterSeconds, ::retrySuffix))
                else -> SubdirsState.Error(pageLevelHint(outcome))
            }
        }
    }

    /**
     * 添加域名：一次只提交一条（网页端同样没有「同时绑 www」，避免半成品状态）。
     * dir 非空且不是 '/' 时随表单附带，服务端据此走目录绑定。
     */
    fun addDomain(input: String, dir: String?) {
        val base = normalizeDomain(input)
        if (base.isBlank()) {
            _opPhase.value = OpPhase.Error(appContext.getString(R.string.domainssl_add_empty))
            return
        }
        viewModelScope.launch {
            _opPhase.value = OpPhase.Working
            val params = buildMap {
                put("domain", base)
                // 真实 handler（btapi_addym）历史上用 webname 承载域名，双写保险
                put("webname", base)
                if (!dir.isNullOrBlank()) put("dir", dir)
            }
            val outcome = hostCall(container, hostId, MnbtActions.DOMAIN_ADD, params) {
                decodePayload<DomainOpPayload>(it)
            }
            when (outcome) {
                is HostOutcome.Ready -> {
                    val data = outcome.data
                    val quota = if (data.limit > 0) {
                        appContext.getString(R.string.domainssl_add_quota, data.bound, data.limit)
                    } else {
                        ""
                    }
                    finishOp(OpPhase.Done(appContext.getString(R.string.domainssl_add_done, base) + quota))
                }
                else -> failOp(outcome)
            }
        }
    }

    /**
     * 改前缀：面板没有重命名原语，服务端按网页端同款「删旧 + 加新」两步执行，
     * 所以失败文案可能是「删除失败…」也可能是「添加失败…（原域名已删除）」，一律原样透传。
     */
    fun setPrefix(item: DomainItemDto, newPrefix: String) {
        val prefix = newPrefix.trim()
        if (!PREFIX_PATTERN.matches(prefix)) {
            _opPhase.value = OpPhase.Error(appContext.getString(R.string.domains_prefix_invalid))
            return
        }
        val zone = item.domain.substringAfter('.')
        val target = if (zone.isBlank()) "$prefix.${item.domain}" else "$prefix.$zone"
        viewModelScope.launch {
            _opPhase.value = OpPhase.Working
            val outcome = hostCall(
                container,
                hostId,
                MnbtActions.DOMAIN_PREFIX_SET,
                mapOf(
                    "domain" to item.domain,
                    "prefix" to prefix,
                    "port" to item.port.toString(),
                    "dir" to item.dir,
                ),
            ) {
                decodePayload<DomainOpPayload>(it)
            }
            when (outcome) {
                is HostOutcome.Ready -> {
                    val renamed = outcome.data.domain.ifBlank { target }
                    finishOp(OpPhase.Done(appContext.getString(R.string.domains_prefix_done, item.domain, renamed)))
                }
                else -> failOp(outcome)
            }
        }
    }

    fun deleteDomain(item: DomainItemDto) {
        viewModelScope.launch {
            _opPhase.value = OpPhase.Working
            val params = buildMap {
                put("domain", item.domain)
                put("webname", item.domain)
                put("port", item.port.toString())
                // 目录绑定与普通域名的删法在服务端分流，dir 必须由列表带回来
                if (item.dir.isNotBlank()) put("dir", item.dir)
            }
            val outcome = hostCall(container, hostId, MnbtActions.DOMAIN_DEL, params) {
                decodePayload<DomainOpPayload>(it)
            }
            when (outcome) {
                is HostOutcome.Ready ->
                    finishOp(OpPhase.Done(appContext.getString(R.string.domainssl_del_done, item.domain)))
                else -> failOp(outcome)
            }
        }
    }

    fun consumeOpPhase() {
        _opPhase.value = OpPhase.Idle
    }

    private suspend fun finishOp(phase: OpPhase) {
        _opPhase.value = phase
        // 成功后静默刷新列表；失败保留旧数据并由 opPhase 提示
        val outcome = hostCall(container, hostId, MnbtActions.DOMAIN_LIST) {
            decodePayload<DomainListPayload>(it)
        }
        _state.value = when (outcome) {
            is HostOutcome.Ready -> LoadState.Ready(outcome.data.toPage(resolveHint))
            else -> failureState(outcome)
        }
    }

    /** 整页失败形态：与写操作失败的 Snackbar 分级不同，这里要的是页面本身。 */
    private fun failureState(outcome: HostOutcome<*>): LoadState<DomainPageData> = when (outcome) {
        HostOutcome.TokenRejected -> LoadState.TokenRejected
        HostOutcome.CredentialMissing -> LoadState.CredentialMissing
        is HostOutcome.HostBlocked -> LoadState.HostBlocked(outcome.message)
        is HostOutcome.Failed -> LoadState.Failed(
            withRetryHint(outcome.message, outcome.retryAfterSeconds, ::retrySuffix),
            outcome.raw,
        )
        is HostOutcome.Ready -> LoadState.Failed(appContext.getString(R.string.domains_list_unexpected))
    }

    private fun DomainListPayload.toPage(hint: String?) = DomainPageData(
        items = list,
        // total 是服务端给的全量数，缺失时按当前列表长度兜底
        total = if (total > 0) total else list.size,
        limit = limit,
        resolveHint = hint,
    )

    private fun failOp(outcome: HostOutcome<*>) {
        val phase = classifyWriteOutcome(outcome, tokenRepairHint(), ::retrySuffix)
        _opPhase.value = phase
        if (phase is OpPhase.Blocked) _readOnly.value = true
    }

    private fun retrySuffix(seconds: Int): String =
        appContext.getString(R.string.domainssl_retry_suffix, seconds)

    private fun tokenRepairHint(): String =
        appContext.getString(R.string.domainssl_token_repair_short)

    private fun pageLevelHint(outcome: HostOutcome<*>): String = when (outcome) {
        is HostOutcome.HostBlocked -> outcome.message
        HostOutcome.TokenRejected, HostOutcome.CredentialMissing -> tokenRepairHint()
        is HostOutcome.Failed -> outcome.message
        is HostOutcome.Ready -> appContext.getString(R.string.domainssl_subdir_unexpected)
    }

    private fun normalizeDomain(raw: String): String = raw.trim()
        .removePrefix("https://").removePrefix("http://")
        .trimEnd('/').substringBefore('/')

    private companion object {
        /** 前缀合法性与网页面板 p_domain_seturl 用的是同一道正则，客户端先挡一道省一次往返 */
        val PREFIX_PATTERN = Regex("^[0-9a-zA-Z]{1,24}$")
    }
}

/** 目录选项加载态：区分未加载 / 加载中 / 成功（可为空列表）/ 失败。 */
sealed interface SubdirsState {
    data object NotLoaded : SubdirsState
    data object Loading : SubdirsState
    data class Loaded(val options: List<String>) : SubdirsState
    data class Error(val message: String) : SubdirsState
}
