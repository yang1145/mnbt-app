package com.mnbt.app.ui.hostmgmt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.data.remote.trust.CertFingerprint
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.pair.PairTopBar
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 我的 → 主机管理 → 证书信任管理（PRD §9.2 尾段）：
 * 列出全部 pinned_fingerprint 非空的条目 + 指纹（可复制、细横向滚动）+ 撤销信任。
 */
@Composable
fun CertManageScreen(navController: NavController) {
    val palette = LocalAppPalette.current
    val vm: HostMgmtViewModel = mnbtViewModel { c, _ -> HostMgmtViewModel(c) }
    val pinned by vm.pinnedHosts.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var revoking by remember { mutableStateOf<HostEntity?>(null) }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = AppDimens.ScreenPadding),
        ) {
            PairTopBar(stringResource(R.string.certmgmt_title)) { navController.popBackStack() }

            if (pinned.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.ic_shield,
                    title = stringResource(R.string.certmgmt_empty_title),
                    description = stringResource(R.string.certmgmt_empty_desc),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(pinned, key = { it.id }) { host ->
                        CertRow(host = host, onRevoke = { revoking = host })
                        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                    }
                }
            }
        }
    }

    revoking?.let { host ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_info_circle),
                    contentDescription = null,
                    tint = palette.warning,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.certmgmt_revoke_title, host.label),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(stringResource(R.string.certmgmt_revoke_body), style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.revokeTrust(host.id)
                        revoking = null
                    },
                ) {
                    Text(stringResource(R.string.certmgmt_revoke_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) {
                    Text(stringResource(R.string.hostmgmt_cancel), color = palette.textSecondary)
                }
            },
            containerColor = palette.surface,
            tonalElevation = 0.dp,
        )
    }
}

@Composable
private fun CertRow(host: HostEntity, onRevoke: () -> Unit) {
    val palette = LocalAppPalette.current
    val clipboard = LocalClipboardManager.current
    val display = CertFingerprint.forDisplay(host.pinnedFingerprint.orEmpty())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = host.label,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textPrimary,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = host.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.certmgmt_pinned_at, formatHostTime(host.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            // SHA-256 指纹：等宽 + 细横向滚动，不静默截断（§8.3）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = display,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
            }
        }
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(display)) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = stringResource(R.string.certmgmt_copy_fingerprint),
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
        }
        IconButton(onClick = onRevoke, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.certmgmt_revoke_trust),
                modifier = Modifier.size(20.dp),
                tint = palette.error,
            )
        }
    }
}
