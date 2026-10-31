package com.mnbt.app.ui.site

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 参数表（P5 写 bridge 对表用）：
 *  初始化 SITE_INIT section=php → current / options
 *  提交   SITE_PHP_SET php_version=<版本> → phpversion（回显新当前值）
 */
class SiteAPhpViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SiteAPhpInit>>(LoadState.Loading)
    val state = _state.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected = _selected.asStateFlow()

    private val _phase = MutableStateFlow<SiteASavePhase>(SiteASavePhase.Idle)
    val phase = _phase.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val outcome = hostCall(container, hostId, MnbtActions.SITE_INIT, mapOf("section" to "php")) {
                decodePayload<SiteAPhpInit>(it)
            }.toLoadState()
            _state.value = outcome
            if (outcome is LoadState.Ready) _selected.value = outcome.data.current
        }
    }

    fun select(value: String) {
        if (_phase.value == SiteASavePhase.Saving) return
        _selected.value = value
    }

    fun save() {
        val value = _selected.value ?: return
        if (value.isBlank()) return
        viewModelScope.launch {
            _phase.value = SiteASavePhase.Saving
            val outcome = hostCall(container, hostId, MnbtActions.SITE_PHP_SET, mapOf("php_version" to value)) {
                decodePayload<SiteAPhpResult>(it)
            }
            _phase.value = when (outcome) {
                is HostOutcome.Ready -> {
                    _selected.value = outcome.data.phpversion.ifBlank { value }
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
fun SiteAPhpScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { c, _ -> SiteAPhpViewModel(c, hostId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SiteASaveEffect(phase, snackbarHostState, vm::consumePhase)

    SiteAScaffold(
        title = stringResource(R.string.sitea_php_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        bottomBar = {
            if (state is LoadState.Ready) {
                SiteASaveBar(
                    enabled = phase !is SiteASavePhase.Blocked && !selected.isNullOrBlank(),
                    saving = phase == SiteASavePhase.Saving,
                    onClick = vm::save,
                )
            }
        },
    ) {
        SiteAStateView(state = state, onRetry = vm::load) { init ->
            SiteANotice(stringResource(R.string.sitea_php_desc))
            SiteASectionCard {
                SiteAFormLabel(stringResource(R.string.sitea_php_title))
                if (init.options.isEmpty()) {
                    SiteASupportText(stringResource(R.string.sitea_php_options_empty))
                } else {
                    SiteAOptionFlow(
                        options = init.options,
                        selected = selected,
                        onSelect = vm::select,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
