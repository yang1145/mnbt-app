package com.mnbt.app.ui.cloud

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.delay

/**
 * 云站点绑定页（route：Destinations.CLOUD_PAIR_TRUST，base/code 走 query，手动入口空参进来）。
 *
 * 展示站点地址（可编辑）+ 绑定码（可编辑）+ 设备名（默认本机型号，对齐 MNBT 配对
 * device_name 约定，可改）。「确认绑定」调 pairSite；成功 toast 后 popBack 回云服务器
 * tab（popUpTo 整条 cloud/pair 栈），失败原因直接展示在页内可改可重试。
 */
@Composable
fun CloudPairTrustScreen(navController: NavController, entry: NavBackStackEntry) {
    val base = Destinations.trustQueryBase(entry)
    val code = Destinations.trustQueryCode(entry)
    val palette = LocalAppPalette.current
    val context = LocalContext.current
    val vm: CloudPairViewModel = mnbtViewModel { c, _ -> CloudPairViewModel(c) }
    val state by vm.state.collectAsState()

    var baseText by rememberSaveable { mutableStateOf(base) }
    var codeText by rememberSaveable { mutableStateOf(code) }
    // 设备名默认本机型号并允许修改；清空则交给仓库侧处理
    var deviceText by rememberSaveable { mutableStateOf(Build.MODEL) }

    val busy = state is CloudPairState.Pairing

    LaunchedEffect(state) {
        val s = state as? CloudPairState.Bound ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(R.string.cloud_pair_bound_toast), Toast.LENGTH_SHORT).show()
        delay(1_200)
        if (navController.currentBackStackEntry?.destination?.route?.startsWith("cloud/pair/trust") == true) {
            navController.popBackStack(Destinations.CLOUD, inclusive = false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
    ) {
        CloudPairTopBar(stringResource(R.string.cloud_pair_title_trust), navController)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        ) {
            Text(
                text = stringResource(R.string.cloud_pair_trust_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )

            CloudLabeledInput(
                label = stringResource(R.string.cloud_pair_trust_site),
                value = baseText,
                onValueChange = { baseText = it },
                placeholder = stringResource(R.string.cloud_pair_trust_site_placeholder),
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            CloudLabeledInput(
                label = stringResource(R.string.cloud_pair_trust_code),
                value = codeText,
                onValueChange = { codeText = it },
                placeholder = stringResource(R.string.cloud_pair_trust_code_placeholder),
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                ),
            )
            CloudLabeledInput(
                label = stringResource(R.string.cloud_pair_trust_device),
                value = deviceText,
                onValueChange = { deviceText = it },
                placeholder = stringResource(R.string.cloud_pair_trust_device_hint),
                enabled = !busy,
            )

            when (val s = state) {
                is CloudPairState.Pairing -> CloudPairWorkingRow(
                    label = stringResource(R.string.cloud_pair_working),
                )
                is CloudPairState.Failed -> CloudPairFailedCard(
                    message = s.message,
                    onRetry = { vm.pair(baseText, codeText, deviceText) },
                )
                else -> Unit
            }
        }

        Button(
            onClick = { vm.pair(baseText, codeText, deviceText) },
            enabled = !busy && baseText.isNotBlank() && codeText.isNotBlank(),
            shape = AppCorner.Control,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.cloud_pair_trust_submit))
        }

        TextButton(
            onClick = { navController.popBackStack() },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(bottom = AppDimens.ScreenPadding),
        ) {
            Text(
                text = stringResource(R.string.cloud_pair_trust_cancel),
                color = palette.textSecondary,
            )
        }
    }
}
