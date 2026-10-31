package com.mnbt.app.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.mnbt.app.domain.model.CloudSite
import com.mnbt.app.domain.model.CloudSiteStatus
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的 → 云站点管理：全部云站点条目 + 重命名 / 解绑 / 重新绑定入口。
 *
 * 结构与交互对齐 ui/hostmgmt/HostManageScreen：行内常显两个图标操作（重命名、解绑）；
 * NEEDS_REPAIR 行整行可点 + 行尾「重新绑定」文字入口，跳云服务器扫码页重新扫码。
 * 操作失败走一次性 AlertDialog（云功能页惯例，见 CloudScreen），不用 Snackbar。
 */
@Composable
fun CloudSiteManageScreen(navController: NavController) {
    val palette = LocalAppPalette.current
    val vm: CloudSiteMgmtViewModel = mnbtViewModel { c, _ -> CloudSiteMgmtViewModel(c) }
    val sites by vm.sites.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()

    var renaming by remember { mutableStateOf<CloudSite?>(null) }
    var unbinding by remember { mutableStateOf<CloudSite?>(null) }

    Scaffold(
        containerColor = palette.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = AppDimens.ScreenPadding),
        ) {
            CloudPairTopBar(stringResource(R.string.cloudmgmt_title), navController)

            if (sites.isEmpty()) {
                // 空态与 HostManageScreen 同构：纯文案引导，不给跳转按钮（绑定入口在云服务器 tab）
                EmptyState(
                    iconRes = R.drawable.ic_cloud,
                    title = stringResource(R.string.cloudmgmt_empty_title),
                    description = stringResource(R.string.cloudmgmt_empty_desc),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(sites, key = { it.id }) { site ->
                        CloudSiteManageRow(
                            site = site,
                            onRename = { renaming = site },
                            onUnbind = { unbinding = site },
                            onRepair = { navController.navigate(Destinations.CLOUD_PAIR_SCAN) },
                        )
                        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                    }
                }
            }
        }
    }

    renaming?.let { site ->
        var input by remember(site) { mutableStateOf(site.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.cloudmgmt_rename_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                CloudLabeledInput(
                    label = stringResource(R.string.cloudmgmt_rename_title),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.cloudmgmt_rename_hint),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(site.id, input)
                        renaming = null
                    },
                    enabled = input.isNotBlank(),
                ) { Text(stringResource(R.string.cloudmgmt_rename_confirm), color = palette.primary) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.cloud_cancel), color = palette.textSecondary)
                }
            },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }

    unbinding?.let { site ->
        AlertDialog(
            onDismissRequest = { unbinding = null },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = palette.error,
                )
            },
            title = { Text(stringResource(R.string.cloudmgmt_unbind_title, site.name), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.cloud_unbind_desc), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.unbind(site.id)
                        unbinding = null
                    },
                ) { Text(stringResource(R.string.cloud_unbind_confirm), color = palette.error) }
            },
            dismissButton = {
                TextButton(onClick = { unbinding = null }) {
                    Text(stringResource(R.string.cloud_cancel), color = palette.textSecondary)
                }
            },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.consumeErrorMessage() },
            confirmButton = {
                TextButton(onClick = { vm.consumeErrorMessage() }) {
                    Text(stringResource(R.string.cloudmgmt_error_confirm), color = palette.primary)
                }
            },
            title = { Text(stringResource(R.string.cloudmgmt_op_failed), style = MaterialTheme.typography.titleMedium) },
            text = { Text(message, color = palette.error) },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }
}

@Composable
private fun CloudSiteManageRow(
    site: CloudSite,
    onRename: () -> Unit,
    onUnbind: () -> Unit,
    onRepair: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val needsRepair = site.status == CloudSiteStatus.NEEDS_REPAIR
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (needsRepair) Modifier.clickable(onClick = onRepair) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
                if (needsRepair) {
                    // 需重新绑定：红字标记，与云服务器 tab 分组头同一口径
                    Text(
                        text = stringResource(R.string.cloud_site_needs_repair),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.error,
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
                    text = site.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.cloudmgmt_paired_at, formatDate(site.pairedAtEpochMs)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPlaceholder,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (needsRepair) {
            TextButton(onClick = onRepair) {
                Text(
                    text = stringResource(R.string.cloudmgmt_repair),
                    color = palette.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_pencil),
                contentDescription = stringResource(R.string.cloudmgmt_rename_desc),
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
        }
        IconButton(onClick = onUnbind, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.cloudmgmt_unbind_desc),
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
        }
    }
}

/** 列表只到日期粒度（对齐「我的」页绑定日期的展示口径），不带时分。 */
private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
