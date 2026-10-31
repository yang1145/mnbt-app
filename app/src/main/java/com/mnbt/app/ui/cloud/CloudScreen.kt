package com.mnbt.app.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.domain.model.CloudServerSummary
import com.mnbt.app.domain.model.CloudSiteStatus
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.nav.Destinations.openCloudServerDetail
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 云服务器 tab 首屏：状态概览条 + 按绑定站点分组的服务器卡片列表。
 *
 * 数据来自 cloudSiteRepository.sites() 与 cloudServerRepository.servers() 两条 Flow，
 * 进入即后台刷新一次（对齐 HostsScreen），空态给扫码/手动两个入口。
 * 解绑在分组头右侧，二次确认后调 unbindSite，Flow 自动出新列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(navController: NavController, modifier: Modifier = Modifier) {
    val vm: CloudViewModel = mnbtViewModel { container, _ -> CloudViewModel(container) }
    val state by vm.state.collectAsState()
    val unbindMessage by vm.unbindMessage.collectAsState()
    val palette = LocalAppPalette.current

    var unbindTarget by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.ScreenPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(AppDimens.RowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_cloud),
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { navController.navigate(Destinations.CLOUD_PAIR_SCAN) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_scan),
                    contentDescription = stringResource(R.string.cloud_scan),
                    tint = palette.textSecondary,
                )
            }
        }

        if (state.total > 0) {
            StatusBar(state)
        }

        if (state.loaded && state.groups.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_cloud,
                title = stringResource(R.string.cloud_empty_title),
                description = stringResource(R.string.cloud_empty_desc),
                primaryLabel = stringResource(R.string.cloud_empty_scan),
                onPrimaryClick = { navController.navigate(Destinations.CLOUD_PAIR_SCAN) },
                secondaryLabel = stringResource(R.string.cloud_empty_manual),
                onSecondaryClick = { navController.navigate(Destinations.CLOUD_PAIR_TRUST) },
                modifier = Modifier.weight(1f),
            )
        } else if (state.groups.isNotEmpty()) {
            PullToRefreshBox(
                isRefreshing = state.syncing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
                    contentPadding = PaddingValues(top = AppDimens.CardGap, bottom = AppDimens.ScreenPadding),
                ) {
                    state.groups.forEach { group ->
                        item(key = "site-${group.site.id}") {
                            SiteGroupHeader(
                                siteName = group.site.name,
                                needsRepair = group.site.status == CloudSiteStatus.NEEDS_REPAIR,
                                count = group.servers.size,
                                onUnbind = { unbindTarget = group.site.id },
                            )
                        }
                        if (group.servers.isEmpty()) {
                            item(key = "site-empty-${group.site.id}") {
                                Text(
                                    text = stringResource(R.string.cloud_site_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textPlaceholder,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                                )
                            }
                        }
                        items(
                            count = group.servers.size,
                            key = { index -> "srv-${group.site.id}-${group.servers[index].hostId}" },
                        ) { index ->
                            ServerRow(
                                server = group.servers[index],
                                onClick = {
                                    navController.openCloudServerDetail(group.site.id, group.servers[index].hostId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (unbindTarget != null) {
        AlertDialog(
            onDismissRequest = { unbindTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val siteId = unbindTarget
                        unbindTarget = null
                        if (siteId != null) vm.unbind(siteId)
                    },
                ) {
                    Text(text = stringResource(R.string.cloud_unbind_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { unbindTarget = null }) {
                    Text(text = stringResource(R.string.cloud_cancel), color = palette.textSecondary)
                }
            },
            title = { Text(text = stringResource(R.string.cloud_unbind_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(text = stringResource(R.string.cloud_unbind_desc), color = palette.textSecondary) },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }

    unbindMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.consumeUnbindMessage() },
            confirmButton = {
                TextButton(onClick = { vm.consumeUnbindMessage() }) {
                    Text(text = stringResource(R.string.cloud_detail_retry), color = palette.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.consumeUnbindMessage() }) {
                    Text(text = stringResource(R.string.cloud_cancel), color = palette.textSecondary)
                }
            },
            title = { Text(text = stringResource(R.string.cloud_unbind_failed), style = MaterialTheme.typography.titleMedium) },
            text = { Text(text = message, color = palette.error) },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }
}

/** 顶部状态概览条：共 N 台 · M 台运行中 · K 台已关机（· J 台其他状态）。白卡 1px 边，状态色只染文字。 */
@Composable
private fun StatusBar(state: CloudUiState) {
    val palette = LocalAppPalette.current
    val sep = stringResource(R.string.hosts_row_quota_sep)
    val totalStr = stringResource(R.string.cloud_health_total, state.total)
    val runningStr = stringResource(R.string.cloud_health_running, state.running)
    val offStr = stringResource(R.string.cloud_health_off, state.poweredOff)
    val otherStr = stringResource(R.string.cloud_health_other, state.other)

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = palette.textSecondary)) { append(totalStr) }
        append(" $sep ")
        withStyle(SpanStyle(color = palette.success)) { append(runningStr) }
        append(" $sep ")
        withStyle(SpanStyle(color = palette.textSecondary)) { append(offStr) }
        if (state.other > 0) {
            append(" $sep ")
            withStyle(SpanStyle(color = palette.warning)) { append(otherStr) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppDimens.CardGap)
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.surface, AppCorner.Control)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
    }
}

/** 分组头：站点名 + 「需重新绑定」红标 + 台数，右侧解绑入口。 */
@Composable
private fun SiteGroupHeader(
    siteName: String,
    needsRepair: Boolean,
    count: Int,
    onUnbind: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = siteName,
            style = MaterialTheme.typography.labelLarge,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (needsRepair) {
            Text(
                text = stringResource(R.string.cloud_site_needs_repair),
                style = MaterialTheme.typography.labelLarge,
                color = palette.error,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = stringResource(R.string.cloud_site_servers, count),
            style = MaterialTheme.typography.labelMedium,
            color = palette.textPlaceholder,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        TextButton(onClick = onUnbind) {
            Text(text = stringResource(R.string.cloud_site_unbind), color = palette.error)
        }
    }
}

/** 服务器卡片：名称 + IP 副行，右侧电源 chip + 到期（≤7 天红）。 */
@Composable
private fun ServerRow(server: CloudServerSummary, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    val dash = stringResource(R.string.value_dash)
    val due = cloudDueLabel(server.dueTimeEpochMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card)
            .clip(AppCorner.Card)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.RowHeightWithSubtitle)
                .padding(horizontal = AppDimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.dedicateIp?.takeIf { it.isNotBlank() } ?: dash,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            PowerChip(status = server.powerStatus, desc = server.powerDesc)

            if (due != null) {
                // 右列限宽，避免到期文字把名称挤没
                Text(
                    text = due.first,
                    style = MaterialTheme.typography.labelMedium,
                    color = due.second,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .width(72.dp),
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp)
                    .rotate(180f),
                tint = palette.textPlaceholder,
            )
        }
    }
}
