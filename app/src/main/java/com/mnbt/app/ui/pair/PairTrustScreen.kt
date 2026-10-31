package com.mnbt.app.ui.pair

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.data.remote.trust.CertificateInfo
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 证书信任 / 明文警告全屏页（PRD §9.2、§9.3）。
 *
 * base/code 从 route query 取（Destinations.pairTrustRoute 已 URL 编码，
 * Navigation 2.8 对 StringType 参数自动解码），进入后独立做一次 [CertificateProbe]：
 * - Untrusted → 主体/颁发者/有效期/SHA-256 指纹/失败原因 + §9.2 核对风险原文
 * - Plaintext → §9.3 红字强警告
 * - SystemTrusted → 不停留，直接继续配对
 *
 * 注册要求：composable(Destinations.PAIR_TRUST, arguments = listOf(
 * 　navArgument(Destinations.ARG_BASE){type=NavType.StringType},
 * 　navArgument(Destinations.ARG_CODE){type=NavType.StringType}))
 */
@Composable
fun PairTrustScreen(navController: NavController, entry: NavBackStackEntry) {
    val base = Destinations.trustQueryBase(entry)
    val code = Destinations.trustQueryCode(entry)
    val palette = LocalAppPalette.current
    val vm: PairViewModel = mnbtViewModel { c, _ -> PairViewModel(c) }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.startIfIdle(base, code) }

    LaunchedEffect(state) {
        val s = state as? PairState.Bound ?: return@LaunchedEffect
        delay(1_200)
        if (navController.currentBackStackEntry?.destination?.route?.startsWith("pair/trust") == true) {
            navController.finishPairingAndOpenHost(s.hostId)
        }
    }

    val busy = state is PairState.Probing || state is PairState.Pairing
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
    ) {
        PairTopBar(stringResource(R.string.pair_title_trust)) { navController.popBackStack() }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        ) {
            when (val s = state) {
                is PairState.Untrusted -> {
                    SiteAddressCard(s.qr.baseUrl)
                    AppCard {
                        FailureReasonRow(s.reason)
                    }
                    AppCard {
                        CertificateRows(s.info, dateFormat)
                    }
                    AppCard {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = palette.warning,
                        )
                        Text(
                            text = stringResource(R.string.pair_trust_risk),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                is PairState.PlaintextWarn -> {
                    SiteAddressCard(s.qr.baseUrl)
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(color = palette.error)
                            Text(
                                text = stringResource(R.string.pair_trust_plaintext_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.error,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        // §9.3 原句，红字强警告
                        Text(
                            text = stringResource(R.string.pair_trust_plaintext_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                is PairState.Failed -> PairStateViews(state = s, onRetry = vm::retry)
                else -> PairStateViews(state = s, onRetry = vm::retry)
            }
        }

        // 只有拿到「待确认」分支才出按钮；取消 = popBackStack，不保存任何条目（§9.2 第 5 步）
        val confirmable = state is PairState.Untrusted || state is PairState.PlaintextWarn
        if (confirmable) {
            PairConfirmButtons(
                onConfirm = vm::confirmAndPair,
                onCancel = { navController.popBackStack() },
                enabled = !busy,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun SiteAddressCard(baseUrl: String) {
    val palette = LocalAppPalette.current
    AppCard {
        Text(
            text = stringResource(R.string.pair_trust_site),
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Text(
                    text = baseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
            }
            val clipboard = LocalClipboardManager.current
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(baseUrl)) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.pair_trust_copy),
                    modifier = Modifier.size(18.dp),
                    tint = palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun FailureReasonRow(reason: String) {
    val palette = LocalAppPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = palette.warning)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = stringResource(R.string.pair_trust_reason_label),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.warning,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CertificateRows(info: CertificateInfo, dateFormat: SimpleDateFormat) {
    val palette = LocalAppPalette.current
    DetailLine(stringResource(R.string.pair_trust_subject), info.subject)
    HorizontalDividerLine()
    DetailLine(stringResource(R.string.pair_trust_issuer), info.issuer)
    HorizontalDividerLine()
    DetailLine(stringResource(R.string.pair_trust_valid_from), dateFormat.format(Date(info.notBefore)))
    HorizontalDividerLine()
    DetailLine(stringResource(R.string.pair_trust_valid_until), dateFormat.format(Date(info.notAfter)))
    if (info.selfSigned) {
        HorizontalDividerLine()
        Text(
            text = stringResource(R.string.pair_trust_selfsigned),
            style = MaterialTheme.typography.labelMedium,
            color = palette.warning,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
    HorizontalDividerLine()
    // SHA-256 指纹：等宽 + 细横向滚动 + 可复制（§9.2 第 2~3 步的核对对象）
    Text(
        text = stringResource(R.string.pair_trust_fingerprint),
        style = MaterialTheme.typography.labelMedium,
        color = palette.textSecondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = info.fingerprintDisplay,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = palette.textPrimary,
                maxLines = 1,
            )
        }
        val clipboard = LocalClipboardManager.current
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(info.fingerprintDisplay)) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = stringResource(R.string.pair_trust_copy),
                modifier = Modifier.size(18.dp),
                tint = palette.textSecondary,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HorizontalDividerLine() {
    androidx.compose.material3.HorizontalDivider(
        color = LocalAppPalette.current.outline,
        thickness = AppDimens.Hairline,
    )
}
