package com.mnbt.app.ui.hostmgmt

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.pair.PairTopBar
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatHostTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * 我的 → 主机管理（PRD §7.2）：全部主机条目 + 重命名 / 解绑 / 重新配对入口。
 *
 * 行内常显两个高频图标操作（重命名、解绑，§8.3「只常显 2 个」）；
 * needsRepair 行的「重新配对」是整行点击 + 行尾文字入口，不占第三个图标位。
 */
@Composable
fun HostManageScreen(navController: NavController) {
    val palette = LocalAppPalette.current
    val vm: HostMgmtViewModel = mnbtViewModel { c, _ -> HostMgmtViewModel(c) }
    val hosts by vm.hosts.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var renaming by remember { mutableStateOf<HostEntity?>(null) }
    var unbinding by remember { mutableStateOf<HostEntity?>(null) }

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
            PairTopBar(stringResource(R.string.hostmgmt_title)) { navController.popBackStack() }

            if (hosts.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.ic_server,
                    title = stringResource(R.string.hosts_empty_title),
                    description = stringResource(R.string.hosts_empty_desc),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostManageRow(
                            host = host,
                            onRename = { renaming = host },
                            onUnbind = { unbinding = host },
                            onRepair = { navController.navigate(Destinations.PAIR_SCAN) },
                        )
                        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                    }
                }
            }
        }
    }

    renaming?.let { host ->
        var input by remember(host) { mutableStateOf(host.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.hostmgmt_rename_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                com.mnbt.app.ui.pair.LabeledInput(
                    label = stringResource(R.string.hostmgmt_rename_title),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.hostmgmt_rename_hint),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(host.id, input)
                        renaming = null
                    },
                    enabled = input.isNotBlank(),
                ) { Text(stringResource(R.string.hostmgmt_rename_confirm), color = palette.primary) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.hostmgmt_cancel), color = palette.textSecondary)
                }
            },
            containerColor = palette.surface,
            tonalElevation = 0.dp,
        )
    }

    unbinding?.let { host ->
        AlertDialog(
            onDismissRequest = { unbinding = null },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = palette.error,
                )
            },
            title = { Text(stringResource(R.string.hostmgmt_unbind_title, host.label), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.hostmgmt_unbind_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.unbind(host.id)
                        unbinding = null
                    },
                ) { Text(stringResource(R.string.hostmgmt_unbind_confirm), color = palette.error) }
            },
            dismissButton = {
                TextButton(onClick = { unbinding = null }) {
                    Text(stringResource(R.string.hostmgmt_cancel), color = palette.textSecondary)
                }
            },
            containerColor = palette.surface,
            tonalElevation = 0.dp,
        )
    }
}

@Composable
private fun HostManageRow(
    host: HostEntity,
    onRename: () -> Unit,
    onUnbind: () -> Unit,
    onRepair: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (host.needsRepair) Modifier.clickable(onClick = onRepair) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = host.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
                if (host.needsRepair) {
                    // 需重新配对：灰红标记，只用文字色不铺色块（§8.3）
                    Text(
                        text = stringResource(R.string.hostmgmt_needs_repair),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (host.cleartext) {
                    // 未加密条目常驻灰色标记（§9.3）
                    Text(
                        text = stringResource(R.string.hosts_cleartext_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPlaceholder,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // 横向超长的地址给细横向滚动，不静默截断（§8.3）
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
                text = stringResource(R.string.hostmgmt_created, formatHostTime(host.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPlaceholder,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (host.needsRepair) {
            TextButton(onClick = onRepair) {
                Text(
                    text = stringResource(R.string.hostmgmt_repair),
                    color = palette.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_pencil),
                contentDescription = stringResource(R.string.hostmgmt_rename_desc),
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
        }
        IconButton(onClick = onUnbind, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.hostmgmt_unbind_desc),
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
        }
    }
}
