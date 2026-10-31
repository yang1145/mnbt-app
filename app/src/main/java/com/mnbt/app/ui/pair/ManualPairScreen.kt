package com.mnbt.app.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.delay

/**
 * 手动配对页（PRD §7.2「手动添加页」）：站点地址 + 配对码两个输入框，
 * 「下一步」与扫码走同一条探测流（PairViewModel.probe），后续分支与扫码页一致。
 */
@Composable
fun ManualPairScreen(navController: NavController) {
    val palette = LocalAppPalette.current
    val vm: PairViewModel = mnbtViewModel { c, _ -> PairViewModel(c) }
    val state by vm.state.collectAsState()

    var baseText by rememberSaveable { mutableStateOf("") }
    var codeText by rememberSaveable { mutableStateOf("") }
    val busy = state is PairState.Probing || state is PairState.Pairing

    LaunchedEffect(state) {
        when (val s = state) {
            is PairState.PlaintextWarn, is PairState.Untrusted -> {
                val qr = s.trustHandoffQr ?: return@LaunchedEffect
                vm.reset()
                navController.navigate(Destinations.pairTrustRoute(qr.baseUrl, qr.code))
            }
            is PairState.Bound -> {
                delay(1_200)
                if (navController.currentBackStackEntry?.destination?.route == Destinations.PAIR_MANUAL) {
                    navController.finishPairingAndOpenHost(s.hostId)
                }
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
    ) {
        PairTopBar(stringResource(R.string.pair_title_manual)) { navController.popBackStack() }

        Text(
            text = stringResource(R.string.pair_manual_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LabeledInput(
            label = stringResource(R.string.pair_manual_site),
            value = baseText,
            onValueChange = { baseText = it },
            placeholder = stringResource(R.string.pair_manual_site_placeholder),
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        LabeledInput(
            label = stringResource(R.string.pair_manual_code),
            value = codeText,
            onValueChange = { codeText = it.uppercase() },
            placeholder = stringResource(R.string.pair_manual_code_placeholder),
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Text,
            ),
        )

        PairStateViews(state = state, onRetry = vm::retry)

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { vm.onManualSubmitted(baseText, codeText) },
            enabled = !busy && baseText.isNotBlank() && codeText.isNotBlank(),
            shape = AppCorner.Control,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(bottom = AppDimens.ScreenPadding),
        ) {
            Text(stringResource(R.string.pair_manual_next))
        }
    }
}
