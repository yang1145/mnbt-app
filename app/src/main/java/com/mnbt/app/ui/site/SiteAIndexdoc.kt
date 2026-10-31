package com.mnbt.app.ui.site

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 参数表（P5 对表）：
 *  初始化 SITE_INIT section=mrwd → current（逗号分隔默认文档）
 *  提交   SITE_INDEXDOC_SET index=<逗号串> → indexdoc（回显新当前值）
 */
class SiteAIndexdocViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SiteAIndexdocInit>>(LoadState.Loading)
    val state = _state.asStateFlow()

    private val _value = MutableStateFlow("")
    val value = _value.asStateFlow()

    private val _phase = MutableStateFlow<SiteASavePhase>(SiteASavePhase.Idle)
    val phase = _phase.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val outcome = hostCall(container, hostId, MnbtActions.SITE_INIT, mapOf("section" to "mrwd")) {
                decodePayload<SiteAIndexdocInit>(it)
            }.toLoadState()
            _state.value = outcome
            if (outcome is LoadState.Ready) _value.value = outcome.data.current
        }
    }

    fun onValueChange(value: String) {
        _value.value = value
    }

    fun save() {
        val value = _value.value
        if (value.isBlank()) return
        viewModelScope.launch {
            _phase.value = SiteASavePhase.Saving
            val outcome = hostCall(container, hostId, MnbtActions.SITE_INDEXDOC_SET, mapOf("index" to value)) {
                decodePayload<SiteAIndexdocResult>(it)
            }
            _phase.value = when (outcome) {
                is HostOutcome.Ready -> {
                    _value.value = outcome.data.indexdoc.ifBlank { value }
                    SiteASavePhase.Done
                }
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
fun SiteAIndexdocScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { c, _ -> SiteAIndexdocViewModel(c, hostId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val value by vm.value.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SiteASaveEffect(phase, snackbarHostState, vm::consumePhase)

    val blocked = phase is SiteASavePhase.Blocked
    SiteAScaffold(
        title = stringResource(R.string.sitea_indexdoc_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        bottomBar = {
            if (state is LoadState.Ready) {
                SiteASaveBar(
                    enabled = !blocked && value.isNotBlank(),
                    saving = phase == SiteASavePhase.Saving,
                    onClick = vm::save,
                )
            }
        },
    ) {
        SiteAStateView(state = state, onRetry = vm::load) {
            SiteANotice(stringResource(R.string.sitea_indexdoc_desc))
            SiteASectionCard {
                SiteAOutlinedField(
                    value = value,
                    onValueChange = vm::onValueChange,
                    label = stringResource(R.string.sitea_indexdoc_label),
                    supportText = stringResource(R.string.sitea_indexdoc_desc),
                    singleLine = false,
                    minLines = 2,
                    enabled = !blocked,
                )
            }
        }
    }
}
