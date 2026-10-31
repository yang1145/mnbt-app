package com.mnbt.app.ui.hosts

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.model.HostProfile
import com.mnbt.app.data.remote.api.model.Quota
import com.mnbt.app.data.remote.api.model.daysToExpire
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.nav.Destinations.openHostDetail
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.AppPalette
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 主机列表实时版（PRD §5.1/§7.2/§10.1）。
 *
 * 首屏：Room 的 HostEntity + overview_cache 快照立即渲染，不等网络；
 * 进入即后台静默并发刷新（HostSync 逐台，overview+profile 各自并发），
 * 行随 Room diff 更新，刷新中的行底部 2dp 进度，失败只标记该行。
 *
 * 入口签名新增 [navController]：调用点与 PAIR_SCAN / PAIR_MANUAL / HOST_DETAIL
 * 路由的注册由集成方在 AppRoot 接线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(navController: NavController, modifier: Modifier = Modifier) {
    val vm = mnbtViewModel { container, _ -> HostsViewModel(container) }
    val state by vm.state.collectAsState()
    val palette = LocalAppPalette.current

    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.ScreenPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(AppDimens.RowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_hosts),
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { navController.navigate(Destinations.PAIR_SCAN) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_scan),
                    contentDescription = stringResource(R.string.hosts_scan),
                    tint = palette.textSecondary,
                )
            }
        }

        if (state.rows.isNotEmpty()) {
            HealthBar(state)
        }

        if (state.loaded && state.rows.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_server,
                title = stringResource(R.string.hosts_empty_title),
                description = stringResource(R.string.hosts_empty_desc),
                primaryLabel = stringResource(R.string.hosts_empty_scan),
                onPrimaryClick = { navController.navigate(Destinations.PAIR_SCAN) },
                secondaryLabel = stringResource(R.string.hosts_empty_manual),
                onSecondaryClick = { navController.navigate(Destinations.PAIR_MANUAL) },
                modifier = Modifier.weight(1f),
            )
        } else if (state.rows.isNotEmpty()) {
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
                    items(state.rows, key = { it.host.id }) { row ->
                        HostRow(
                            row = row,
                            onClick = { navController.openHostDetail(row.host.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 顶部健康总览条：N 台 · M 台需处理 · K 台 30 天内到期（· J 台已到期）。白卡 1px 边，状态色只染文字。 */
@Composable
private fun HealthBar(state: HostsUiState) {
    val palette = LocalAppPalette.current
    val sep = stringResource(R.string.hosts_row_quota_sep)
    val totalStr = stringResource(R.string.hosts_health_total, state.total)
    val attentionStr = stringResource(R.string.hosts_health_attention, state.needAttention)
    val expiringStr = stringResource(R.string.hosts_health_expiring, state.expiringSoon)
    val expiredStr = stringResource(R.string.hosts_health_expired, state.expiredCount)

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = palette.textSecondary)) { append(totalStr) }
        if (state.needAttention > 0) {
            append(" $sep ")
            withStyle(SpanStyle(color = palette.error)) { append(attentionStr) }
        }
        if (state.expiringSoon > 0) {
            append(" $sep ")
            withStyle(SpanStyle(color = palette.warning)) { append(expiringStr) }
        }
        if (state.expiredCount > 0) {
            append(" $sep ")
            withStyle(SpanStyle(color = palette.error)) { append(expiredStr) }
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

@Composable
private fun HostRow(row: HostRowUi, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    val host = row.host
    val days = row.profile?.daysToExpire()

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
            StatusDot(color = hostDotColor(host.needsRepair, row.profile, palette))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = host.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleOf(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (host.cleartext) {
                // 明文站点常驻灰色标记（PRD §9.3）
                Text(
                    text = stringResource(R.string.hosts_cleartext_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textPlaceholder,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            if (row.failed) {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = stringResource(R.string.hosts_refresh_failed),
                    modifier = Modifier.size(16.dp),
                    tint = palette.error,
                )
            }

            if (days != null) {
                val (label, tone) = expireLabel(days)
                // 右列限宽，避免到期文字把副行挤没
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
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

        if (row.refreshing) {
            // 细微进度指示：行底 2dp 不确定进度条用交互蓝（§8.3 状态色不铺大面积）
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp),
                color = palette.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}

private fun hostDotColor(needsRepair: Boolean, profile: HostProfile?, palette: AppPalette): Color = when {
    needsRepair -> palette.error
    profile == null -> palette.textPlaceholder
    profile.running -> palette.success
    else -> palette.textPlaceholder
}

/** 到期天数文字：≤30 天 warning、已到期 error、其余 textSecondary（§7.3 行尾约定）。 */
@Composable
private fun expireLabel(days: Int): Pair<String, Color> {
    val palette = LocalAppPalette.current
    return when {
        days < 0 -> stringResource(R.string.hosts_expired_days, -days) to palette.error
        days == 0 -> stringResource(R.string.hosts_expires_today) to palette.warning
        days <= 30 -> stringResource(R.string.hosts_days_left, days) to palette.warning
        else -> stringResource(R.string.hosts_days_left, days) to palette.textSecondary
    }
}

/**
 * 副行：username · 空间 xx% · 流量 xx%（unlimited 显示「不限量」，不是 0%）；
 * needsRepair 行整体前置红色「需重新配对」。
 */
@Composable
private fun subtitleOf(row: HostRowUi): AnnotatedString {
    val palette = LocalAppPalette.current
    val host = row.host
    val unlimited = stringResource(R.string.hosts_quota_unlimited)
    val repair = stringResource(R.string.hosts_needs_repair)
    val sep = stringResource(R.string.hosts_row_quota_sep)
    val spaceSegment = quotaSegment(row.snapshot?.overview?.webQuota, stringResource(R.string.hosts_quota_space), unlimited)
    val trafficSegment = quotaSegment(row.snapshot?.overview?.trafficQuota, stringResource(R.string.hosts_quota_traffic), unlimited)

    return buildAnnotatedString {
        if (host.needsRepair) {
            withStyle(SpanStyle(color = palette.error)) { append(repair) }
        }
        if (host.username.isNotBlank()) {
            if (length > 0) append(" $sep ")
            withStyle(SpanStyle(color = palette.textSecondary)) { append(host.username) }
        }
        val quotaParts = listOfNotNull(spaceSegment, trafficSegment)
        if (quotaParts.isNotEmpty()) {
            if (length > 0) append(" $sep ")
            withStyle(SpanStyle(color = palette.textSecondary)) { append(quotaParts.joinToString(" $sep ")) }
        }
    }
}

@Composable
private fun quotaSegment(quota: Quota?, label: String, unlimited: String): String? {
    val q = quota ?: return null
    if (q.unlimited) return "$label $unlimited"
    return "$label ${stringResource(R.string.hosts_quota_percent, Math.round(q.fraction * 100).toInt())}"
}
