package com.mnbt.app.ui.site

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * 参数表（P5 对表）：
 *  初始化 SITE_INIT section=gzip → current（on|off）
 *  提交   SITE_GZIP_SET gzip=on|off → gzip（回显新当前值）
 */
class SiteAGzipViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SiteAGzipInit>>(LoadState.Loading)
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
            val outcome = hostCall(container, hostId, MnbtActions.SITE_INIT, mapOf("section" to "gzip")) {
                decodePayload<SiteAGzipInit>(it)
            }.toLoadState()
            _state.value = outcome
            if (outcome is LoadState.Ready) _selected.value = outcome.data.current.ifBlank { "on" }
        }
    }

    fun select(value: String) {
        if (_phase.value == SiteASavePhase.Saving) return
        _selected.value = value
    }

    fun save() {
        val value = _selected.value ?: return
        viewModelScope.launch {
            _phase.value = SiteASavePhase.Saving
            val outcome = hostCall(container, hostId, MnbtActions.SITE_GZIP_SET, mapOf("gzip" to value)) {
                decodePayload<SiteAGzipResult>(it)
            }
            _phase.value = when (outcome) {
                is HostOutcome.Ready -> {
                    _selected.value = outcome.data.gzip.ifBlank { value }
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
fun SiteAGzipScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { c, _ -> SiteAGzipViewModel(c, hostId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SiteASaveEffect(phase, snackbarHostState, vm::consumePhase)
    val blocked = phase is SiteASavePhase.Blocked

    SiteAScaffold(
        title = stringResource(R.string.sitea_gzip_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        bottomBar = {
            if (state is LoadState.Ready) {
                SiteASaveBar(
                    enabled = !blocked && !selected.isNullOrBlank(),
                    saving = phase == SiteASavePhase.Saving,
                    onClick = vm::save,
                )
            }
        },
    ) {
        SiteAStateView(state = state, onRetry = vm::load) {
            SiteANotice(stringResource(R.string.sitea_gzip_desc))
            SiteASectionCard {
                SiteAFormLabel(stringResource(R.string.sitea_gzip_title))
                SiteAOptionBox(
                    label = stringResource(R.string.sitea_gzip_on),
                    selected = selected == "on",
                    onClick = { vm.select("on") },
                )
                SiteAOptionBox(
                    label = stringResource(R.string.sitea_gzip_off),
                    selected = selected == "off",
                    onClick = { vm.select("off") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
