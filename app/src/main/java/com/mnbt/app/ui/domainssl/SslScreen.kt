package com.mnbt.app.ui.domainssl

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 自定义证书页签/文件选择的目标字段。 */
private enum class PemTarget { Cert, Key }

/**
 * SSL 证书页（PRD §5.5 ssl.* / §7.2）。
 * 路由：Destinations.hostFeature(Destinations.F_SSL, hostId)，注册由主控统一做。
 *
 * 视觉红线：剩余天数用状态点 + 文字表达，不铺色块；证书有效期解析失败时
 * 原样展示服务端文本并说明无法解析，不猜。
 */
@Composable
fun SslScreen(navController: NavController, entry: NavBackStackEntry) {
    val context = LocalContext.current
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { container, _ ->
        SslViewModel(container, hostId, context.applicationContext)
    }

    val state by vm.state.collectAsState()
    val opPhase by vm.opPhase.collectAsState()
    val readOnly by vm.readOnly.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pickEmptyMsg = stringResource(R.string.ssl_pick_empty)

    var showCloseDialog by remember { mutableStateOf(false) }
    var pemText by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var pickTarget by remember { mutableStateOf(PemTarget.Cert) }

    LaunchedEffect(opPhase) {
        when (val phase = opPhase) {
            is OpPhase.Done -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeOpPhase()
            }
            is OpPhase.Error -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeOpPhase()
            }
            is OpPhase.Blocked -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeOpPhase()
            }
            else -> Unit
        }
    }

    // SAF 选择：PEM 是 ASCII 文本，读成字符串后仍走普通 hostCall 表单值提交
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = pickTarget
        scope.launch {
            val read = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error(pickEmptyMsg)
                }
            }
            read.onSuccess { text ->
                if (target == PemTarget.Cert) pemText = text else keyText = text
            }.onFailure { error ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.ssl_pick_failed, error.message ?: "unknown"),
                )
            }
        }
    }

    DsslScaffold(
        title = stringResource(R.string.ssl_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { vm.refresh() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.ssl_refresh),
                    tint = LocalAppPalette.current.textSecondary,
                )
            }
        },
    ) {
        DsslStateView(state = state, onRetry = { vm.refresh() }) { data ->
            val working = opPhase is OpPhase.Working
            if (readOnly) {
                DsslNotice(stringResource(R.string.domainssl_readonly_banner), warning = true)
            }

            /* ---- 当前证书 ---- */
            DsslCard {
                Text(
                    text = stringResource(R.string.ssl_cert_card),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalAppPalette.current.textPrimary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                val cert = data.cert
                if (cert == null) {
                    Text(
                        text = stringResource(R.string.ssl_no_cert),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalAppPalette.current.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.ssl_no_cert_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAppPalette.current.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                } else {
                    val days = sslDaysRemaining(cert.expire)
                    val palette = LocalAppPalette.current
                    val statusColor = when {
                        days != null && days < 0 -> palette.error
                        days != null && days <= 14 -> palette.warning
                        else -> palette.success
                    }
                    val statusLabel = when {
                        days != null && days < 0 -> stringResource(R.string.ssl_expired)
                        days != null && days <= 14 -> stringResource(R.string.ssl_status_warning)
                        else -> stringResource(R.string.ssl_status_valid)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusDot(color = statusColor)
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor,
                        )
                        if (days != null && days >= 0) {
                            Text(
                                text = stringResource(R.string.ssl_days_left, days),
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor,
                            )
                        }
                    }
                    SslKeyValue(
                        stringResource(R.string.ssl_issuer),
                        cert.issuer.ifBlank { "—" },
                    )
                    SslKeyValue(
                        stringResource(R.string.ssl_domains),
                        cert.domains.joinToString(", ").ifBlank { "—" },
                    )
                    SslKeyValue(
                        stringResource(R.string.ssl_expire_at),
                        cert.expire.ifBlank { "—" },
                    )
                    if (days == null && cert.expire.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.ssl_days_unknown, cert.expire),
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                    }
                }

                // 同行控件等高：三个操作统一 48dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsslPrimaryButton(
                        label = stringResource(R.string.ssl_apply),
                        onClick = { vm.applyCertificate() },
                        modifier = Modifier.weight(1f),
                        enabled = !readOnly,
                        working = working,
                    )
                    DsslOutlineButton(
                        label = stringResource(R.string.ssl_renew),
                        onClick = { vm.renewCertificate() },
                        modifier = Modifier.weight(1f),
                        enabled = !readOnly && data.cert != null,
                    )
                    TextButton(
                        onClick = { showCloseDialog = true },
                        enabled = !readOnly && !working && data.cert != null,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            stringResource(R.string.ssl_close),
                            color = LocalAppPalette.current.error,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.ssl_throttle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppPalette.current.textPlaceholder,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }

            /* ---- 强制 HTTPS ---- */
            DsslCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimens.RowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ssl_force_card),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalAppPalette.current.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.ssl_force_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalAppPalette.current.textSecondary,
                        )
                    }
                    Switch(
                        checked = data.forceHttps,
                        onCheckedChange = { vm.setForceHttps(it) },
                        enabled = !readOnly,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LocalAppPalette.current.primary,
                            checkedTrackColor = LocalAppPalette.current.surface,
                            checkedBorderColor = LocalAppPalette.current.primary,
                            checkedIconColor = LocalAppPalette.current.surface,
                            uncheckedThumbColor = LocalAppPalette.current.textPlaceholder,
                            uncheckedTrackColor = LocalAppPalette.current.surface,
                            uncheckedBorderColor = LocalAppPalette.current.outline,
                        ),
                    )
                }
            }

            /* ---- 自定义证书 ---- */
            DsslCard {
                Text(
                    text = stringResource(R.string.ssl_custom_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalAppPalette.current.textPrimary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.ssl_custom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppPalette.current.textSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                DsslTextField(
                    value = pemText,
                    onValueChange = { pemText = it },
                    label = stringResource(R.string.ssl_pem_label),
                    placeholder = stringResource(R.string.ssl_pem_placeholder),
                    singleLine = false,
                    minLines = 5,
                    enabled = !readOnly,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            pickTarget = PemTarget.Cert
                            pickLauncher.launch(arrayOf("application/x-x509-ca-cert", "application/pem-certificate-chain", "text/plain", "*/*"))
                        },
                        enabled = !readOnly,
                    ) {
                        Text(stringResource(R.string.ssl_pick_file), color = LocalAppPalette.current.primary)
                    }
                }
                DsslTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = stringResource(R.string.ssl_key_label),
                    placeholder = stringResource(R.string.ssl_key_placeholder),
                    singleLine = false,
                    minLines = 5,
                    enabled = !readOnly,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            pickTarget = PemTarget.Key
                            pickLauncher.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
                        },
                        enabled = !readOnly,
                    ) {
                        Text(stringResource(R.string.ssl_pick_file), color = LocalAppPalette.current.primary)
                    }
                }
                DsslPrimaryButton(
                    label = stringResource(R.string.ssl_custom_save),
                    onClick = { vm.submitCustom(pemText, keyText) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    enabled = !readOnly,
                    working = working,
                )
            }
        }
    }

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            containerColor = LocalAppPalette.current.surface,
            shape = AppCorner.Card,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = LocalAppPalette.current.error,
                )
            },
            title = { Text(stringResource(R.string.ssl_close_dialog_title)) },
            text = { Text(stringResource(R.string.ssl_close_dialog_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCloseDialog = false
                        vm.closeSsl()
                    },
                ) {
                    Text(
                        stringResource(R.string.ssl_close),
                        color = LocalAppPalette.current.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseDialog = false }) {
                    Text(stringResource(R.string.domainssl_cancel))
                }
            },
        )
    }
}

/** 标签-值行：值超宽给细横向滚动，不静默截断。 */
@Composable
private fun SslKeyValue(label: String, value: String, valueColor: Color? = null) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier
                .width(88.dp)
                .padding(end = 12.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor ?: palette.textPrimary,
                maxLines = 1,
            )
        }
    }
}
