package com.mnbt.app.ui.site

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.common.toLoadState
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 参数表（P5 对表）：
 *  初始模板列表 SITE_INIT section=wjt → current / list（只给模板名，不给内容）
 *  取规则内容   SITE_REWRITE_GET name=<模板名> → current / rewrite / list
 *               —— current、list 与 rewrite.get 重复处一律以 rewrite.get 为准（init 无内容）
 *  应用模板     SITE_REWRITE_SET rewrite=<规则正文> → rewrite（回写正文）
 *               —— 核心 setwjt 把 rewrite 当作 vhost/rewrite 目录下 xxx.conf 的**文件正文**直接落盘
 *                  （见 MNBT/user/api/site.php:100、user/fn.php:144），传模板名会往 nginx
 *                  配置里写一行裸词，整站 nginx -t 失败。
 */
class SiteARewriteViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SiteARewriteInit>>(LoadState.Loading)
    val state = _state.asStateFlow()

    private val _templates = MutableStateFlow<List<String>>(emptyList())
    val templates = _templates.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected = _selected.asStateFlow()

    private val _code = MutableStateFlow("")
    val code = _code.asStateFlow()

    private val _codeLoading = MutableStateFlow(false)
    val codeLoading = _codeLoading.asStateFlow()

    private val _codeError = MutableStateFlow<String?>(null)
    val codeError = _codeError.asStateFlow()

    private val _phase = MutableStateFlow<SiteASavePhase>(SiteASavePhase.Idle)
    val phase = _phase.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val outcome = hostCall(container, hostId, MnbtActions.SITE_INIT, mapOf("section" to "wjt")) {
                decodePayload<SiteARewriteInit>(it)
            }.toLoadState()
            _state.value = outcome
            if (outcome is LoadState.Ready) {
                _templates.value = outcome.data.list
                val current = outcome.data.current
                _selected.value = current
                if (current.isNotBlank()) fetchRules(current)
            }
        }
    }

    private fun fetchRules(name: String) {
        viewModelScope.launch {
            _codeLoading.value = true
            _codeError.value = null
            val outcome = hostCall(container, hostId, MnbtActions.SITE_REWRITE_GET, mapOf("name" to name)) {
                decodePayload<SiteARewriteDetail>(it)
            }
            when (outcome) {
                is HostOutcome.Ready -> {
                    _code.value = outcome.data.rewrite
                    // rewrite.get 为准：若带回当前名，覆盖本地选中
                    if (outcome.data.current.isNotBlank()) _selected.value = outcome.data.current
                    if (outcome.data.list.isNotEmpty()) _templates.value = outcome.data.list
                    _codeLoading.value = false
                }
                is HostOutcome.Failed -> {
                    _code.value = ""
                    _codeError.value = outcome.message
                    _codeLoading.value = false
                }
                else -> {
                    _code.value = ""
                    _codeLoading.value = false
                }
            }
        }
    }

    fun selectTemplate(name: String) {
        if (_phase.value == SiteASavePhase.Saving) return
        _selected.value = name
        fetchRules(name)
    }

    fun apply() {
        val name = _selected.value ?: return
        if (name.isBlank()) return
        val content = _code.value
        // 正文没取到就不提交：核心是整文件覆盖写，空串等于把伪静态规则清空
        if (content.isBlank()) {
            _phase.value = SiteASavePhase.Error("规则内容还没取到，请先选择一个模板")
            return
        }
        viewModelScope.launch {
            _phase.value = SiteASavePhase.Saving
            val outcome = hostCall(container, hostId, MnbtActions.SITE_REWRITE_SET, mapOf("rewrite" to content)) {
                decodePayload<SiteARewriteResult>(it)
            }
            _phase.value = when (outcome) {
                is HostOutcome.Ready -> SiteASavePhase.Done
                HostOutcome.TokenRejected, HostOutcome.CredentialMissing ->
                    SiteASavePhase.Error("令牌已失效，请重新配对")
                is HostOutcome.HostBlocked -> SiteASavePhase.Blocked(outcome.message)
                is HostOutcome.Failed -> SiteASavePhase.Error(outcome.message)
            }
        }
    }

    fun consumePhase() {
        _phase.value = SiteASavePhase.Idle
    }
}

@Composable
fun SiteARewriteScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { c, _ -> SiteARewriteViewModel(c, hostId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val code by vm.code.collectAsStateWithLifecycle()
    val codeLoading by vm.codeLoading.collectAsStateWithLifecycle()
    val codeError by vm.codeError.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = LocalAppPalette.current

    SiteASaveEffect(phase, snackbarHostState, vm::consumePhase)
    val blocked = phase is SiteASavePhase.Blocked

    SiteAScaffold(
        title = stringResource(R.string.sitea_rewrite_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        bottomBar = {
            if (state is LoadState.Ready) {
                SiteASaveBar(
                    enabled = !blocked && !selected.isNullOrBlank(),
                    saving = phase == SiteASavePhase.Saving,
                    onClick = vm::apply,
                )
            }
        },
    ) {
        SiteAStateView(state = state, onRetry = vm::load) {
            SiteANotice(stringResource(R.string.sitea_rewrite_desc))
            SiteASectionCard {
                SiteAFormLabel(stringResource(R.string.sitea_rewrite_templates_label))
                templates.forEachIndexed { index, name ->
                    if (index > 0) {
                        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                    }
                    SiteAOptionBox(
                        label = name,
                        selected = name == selected,
                        onClick = { vm.selectTemplate(name) },
                    )
                }
            }
            SiteASectionCard {
                SiteAFormLabel(stringResource(R.string.sitea_rewrite_code_label))
                when {
                    codeLoading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = palette.primary,
                        )
                    }
                    codeError != null -> SiteANotice(
                        text = codeError ?: "",
                        iconRes = R.drawable.ic_alert_triangle,
                        accent = palette.error,
                    )
                    code.isBlank() -> SiteASupportText(stringResource(R.string.sitea_rewrite_code_empty))
                    else -> SiteACodeBox(code)
                }
            }
        }
    }
}
