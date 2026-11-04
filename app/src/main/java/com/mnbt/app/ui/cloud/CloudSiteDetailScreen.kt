package com.mnbt.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.domain.model.CloudSite
import com.mnbt.app.domain.model.CloudSiteStatus
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.nav.Destinations.openCloudServerDetail
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 云站点详情页（route：Destinations.CLOUD_SITE_DETAIL，siteId LongType）。
 *
 * 从列表页分组头点入：站点信息卡（名称/地址/绑定时间/状态）+ 该站点服务器列表，
 * 服务器卡点击进服务器详情；NEEDS_REPAIR 时给重新扫码绑定入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSiteDetailScreen(
    navController: NavController,
    siteId: Long,
    modifier: Modifier = Modifier,
) {
    val vm: CloudSiteDetailViewModel = mnbtViewModel { container, _ ->
        CloudSiteDetailViewModel(container, siteId)
    }
    val state by vm.state.collectAsState()
    val palette = LocalAppPalette.current

    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.ScreenPadding)) {
        CloudPairTopBar(
            title = state.site?.name ?: stringResource(R.string.cloudsite_title),
            navController = navController,
        )

        val site = state.site
        if (site == null) {
            EmptyState(
                iconRes = R.drawable.ic_cloud,
                title = stringResource(R.string.cloudsite_missing),
                description = stringResource(R.string.cloudmgmt_empty_desc),
                modifier = Modifier.weight(1f),
            )
        } else {
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
                    item(key = "info") { SiteInfoCard(site) }

                    if (site.status == CloudSiteStatus.NEEDS_REPAIR) {
                        item(key = "repair") {
                            RepairNoticeCard(onRebind = { navController.navigate(Destinations.CLOUD_PAIR_SCAN) })
                        }
                    }

                    item(key = "list-title") {
                        Text(
                            text = stringResource(R.string.cloud_site_servers, state.servers.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }

                    if (state.servers.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.cloud_site_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textPlaceholder,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                            )
                        }
                    }

                    items(
                        count = state.servers.size,
                        key = { index -> "srv-${state.servers[index].hostId}" },
                    ) { index ->
                        CloudServerRow(server = state.servers[index]) {
                            navController.openCloudServerDetail(siteId, state.servers[index].hostId)
                        }
                    }
                }
            }
        }
    }
}

/** 站点信息卡：显示名、地址、绑定时间与状态（正常/需重新绑定）。 */
@Composable
private fun SiteInfoCard(site: CloudSite) {
    val palette = LocalAppPalette.current
    val active = site.status == CloudSiteStatus.ACTIVE

    AppCard {
        Column {
            Text(
                text = site.name,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = site.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(R.string.cloudmgmt_paired_at, formatDay(site.pairedAtEpochMs)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (site.systemType.isNotBlank()) {
                Text(
                    text = stringResource(R.string.cloudsite_system_type, site.systemType),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                StatusDot(color = if (active) palette.success else palette.error)
                Text(
                    text = stringResource(
                        if (active) R.string.cloudsite_status_ok else R.string.cloud_site_needs_repair,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) palette.success else palette.error,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** 令牌失效提示卡：说明影响 + 重新扫码绑定入口（跳扫码页，原地址重扫即原地换绑）。 */
@Composable
private fun RepairNoticeCard(onRebind: () -> Unit) {
    val palette = LocalAppPalette.current

    AppCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = palette.error,
                )
                Text(
                    text = stringResource(R.string.cloud_site_needs_repair),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.cloudsite_repair_desc),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = onRebind, modifier = Modifier.padding(top = 4.dp)) {
                Text(text = stringResource(R.string.cloudsite_rebind))
            }
        }
    }
}
