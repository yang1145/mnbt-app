package com.mnbt.app.ui.site

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.compose.material3.MaterialTheme
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
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 参数表（P5 对表）：
 *  读取 SITE_HOTLINK_GET （无入参） → open(Int 0/1) / domains / exts / lasts(Int 秒)
 *  提交 SITE_HOTLINK_SET open=1|0 & domains & exts & lasts=<秒> → 同名字段（回显）
 */
class SiteAHotlinkViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SiteAHotlinkData>>(LoadState.Loading)
    val state = _state.asStateFlow()

    private val _open = MutableStateFlow(false)
    val open = _open.asStateFlow()

    private val _domains = MutableStateFlow("")
    val domains = _domains.asStateFlow()

    private val _exts = MutableStateFlow("")
    val exts = _exts.asStateFlow()

    private val _lasts = MutableStateFlow("30")
    val lasts = _lasts.asStateFlow()

    private val _phase = MutableStateFlow<SiteASavePhase>(SiteASavePhase.Idle)
    val phase = _phase.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val outcome = hostCall(container, hostId, MnbtActions.SITE_HOTLINK_GET) {
                decodePayload<SiteAHotlinkData>(it)
            }.toLoadState()
            _state.value = outcome
            if (outcome is LoadState.Ready) apply(outcome.data)
        }
    }

    private fun apply(data: SiteAHotlinkData) {
        _open.value = data.open != 0
        _domains.value = data.domains
        _exts.value = data.exts
        _lasts.value = data.lasts.toString()
    }

    fun setOpen(value: Boolean) {
        if (_phase.value == SiteASavePhase.Saving) return
        _open.value = value
    }

    fun setDomains(value: String) {
        _domains.value = value
    }

    fun setExts(value: String) {
        _exts.value = value
    }

    fun setLasts(value: String) {
        _lasts.value = value.filter { it.isDigit() }.take(9)
    }

    fun save() {
        val lastsValue = _lasts.value.ifBlank { "0" }
        viewModelScope.launch {
            _phase.value = SiteASavePhase.Saving
            val params = mapOf(
                "open" to if (_open.value) "1" else "0",
                "domains" to _domains.value,
                "exts" to _exts.value,
                "lasts" to lastsValue,
            )
            val outcome = hostCall(container, hostId, MnbtActions.SITE_HOTLINK_SET, params) {
                decodePayload<SiteAHotlinkData>(it)
            }
            _phase.value = when (outcome) {
                is HostOutcome.Ready -> {
                    apply(outcome.data)
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
fun SiteAHotlinkScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { c, _ -> SiteAHotlinkViewModel(c, hostId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val domains by vm.domains.collectAsStateWithLifecycle()
    val exts by vm.exts.collectAsStateWithLifecycle()
    val lasts by vm.lasts.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = LocalAppPalette.current

    SiteASaveEffect(phase, snackbarHostState, vm::consumePhase)
    val blocked = phase is SiteASavePhase.Blocked

    SiteAScaffold(
        title = stringResource(R.string.sitea_hotlink_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        bottomBar = {
            if (state is LoadState.Ready) {
                SiteASaveBar(
                    enabled = !blocked,
                    saving = phase == SiteASavePhase.Saving,
                    onClick = vm::save,
                )
            }
        },
    ) {
        SiteAStateView(state = state, onRetry = vm::load) {
            SiteANotice(stringResource(R.string.sitea_hotlink_desc))
            SiteASectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sitea_hotlink_switch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = open,
                        onCheckedChange = vm::setOpen,
                        enabled = !blocked,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = palette.surface,
                            checkedTrackColor = palette.primary,
                            uncheckedThumbColor = palette.surface,
                            uncheckedTrackColor = palette.background,
                            uncheckedBorderColor = palette.outline,
                        ),
                    )
                }
            }
            SiteASectionCard {
                SiteAOutlinedField(
                    value = domains,
                    onValueChange = vm::setDomains,
                    label = stringResource(R.string.sitea_hotlink_domains_label),
                    supportText = stringResource(R.string.sitea_hotlink_domains_support),
                    singleLine = false,
                    minLines = 2,
                    enabled = !blocked && open,
                )
                SiteAOutlinedField(
                    value = exts,
                    onValueChange = vm::setExts,
                    label = stringResource(R.string.sitea_hotlink_exts_label),
                    supportText = stringResource(R.string.sitea_hotlink_exts_support),
                    singleLine = false,
                    minLines = 2,
                    enabled = !blocked && open,
                    modifier = Modifier.padding(top = 12.dp),
                )
                SiteAOutlinedField(
                    value = lasts,
                    onValueChange = vm::setLasts,
                    label = stringResource(R.string.sitea_hotlink_lasts_label),
                    keyboardType = KeyboardType.Number,
                    enabled = !blocked && open,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
