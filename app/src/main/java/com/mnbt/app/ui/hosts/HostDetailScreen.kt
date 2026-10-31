package com.mnbt.app.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.model.HostSnapshot
import com.mnbt.app.data.remote.api.model.daysToExpire
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.AppPalette
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 主机详情页（PRD §7.2/§7.3）：sticky 头 + 可折叠用量区 + 搜索 + 六组功能列表，每行右侧当前值。
 *
 * route：Destinations.HOST_DETAIL（"host/{hostId}"，LongType）。调用点示例：
 * `composable(Destinations.HOST_DETAIL, arguments = listOf(Destinations.hostIdArg())) { entry -> HostDetailScreen(navController, entry.requireHostId()) }`
 *
 * 已知缺口：§7.2 ASCII 头里的「暂停/解停」在 §5 能力映射表没有对应 action，本版不做（见交付报告）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(navController: NavController, hostId: Long, modifier: Modifier = Modifier) {
    val vm = mnbtViewModel { container, _ -> HostDetailViewModel(container, hostId) }
    val ui by vm.ui.collectAsState()
    val palette = LocalAppPalette.current

    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var unbindOpen by remember { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()
    // 折叠判定简单版：用量块（列表 item 0）完全滚出视口即收拢进 sticky 头
    val usageCollapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    val host = ui.host
    when {
        !ui.hostLoaded -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                modifier = Modifier.height(24.dp),
                tint = palette.textPlaceholder,
            )
        }

        host == null -> EmptyState(
            iconRes = R.drawable.ic_server,
            title = stringResource(R.string.host_detail_missing_title),
            description = stringResource(R.string.host_detail_missing_desc),
            modifier = modifier.fillMaxSize(),
        )

        ui.tokenRejected -> Column(modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.ScreenPadding)) {
            HeaderBar(
                title = host.label,
                dotColor = if (host.needsRepair || ui.blockedMessage != null) palette.error else palette.textPlaceholder,
                menuOpen = menuOpen,
                onMenuToggle = { menuOpen = it },
                writeDisabled = true,
                navController = navController,
                onRefreshUsage = vm::refreshUsage,
                onRename = {
                    renameText = host.label
                    renameOpen = true
                },
                onUnbind = { unbindOpen = true },
            )
            EmptyState(
                iconRes = R.drawable.ic_alert_triangle,
                title = stringResource(R.string.host_detail_repair_title),
                description = stringResource(R.string.host_detail_repair_desc),
                primaryLabel = stringResource(R.string.host_detail_repair_action),
                onPrimaryClick = { navController.navigate(Destinations.PAIR_MANUAL) },
                modifier = Modifier.weight(1f),
            )
        }

        else -> {
            val groups = buildFeatureGroups(ui.values)
            val query = ui.query.trim()
            val filtered = groups.mapNotNull { group ->
                val rows = group.rows.filter {
                    query.isEmpty() || it.label.contains(query, ignoreCase = true)
                }
                if (rows.isNotEmpty()) group.copy(rows = rows) else null
            }

            Column(modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.ScreenPadding)) {
                HeaderBar(
                    title = host.label,
                    dotColor = detailDotColor(ui, palette),
                    menuOpen = menuOpen,
                    onMenuToggle = { menuOpen = it },
                    writeDisabled = ui.blockedMessage != null,
                    navController = navController,
                    onRefreshUsage = vm::refreshUsage,
                    onRename = {
                        renameText = host.label
                        renameOpen = true
                    },
                    onUnbind = { unbindOpen = true },
                )
                // 简单折叠：用量块滚出视口后，摘要一行收拢在 sticky 头下方
                if (usageCollapsed) {
                    CompactUsageLine(ui.snapshot)
                }
                HorizontalDivider(thickness = AppDimens.Hairline, color = palette.outline)

                val blockedMessage = ui.blockedMessage
                if (blockedMessage != null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        BlockedBanner(
                            message = blockedMessage,
                            note = stringResource(R.string.host_detail_blocked_note),
                        )
                    }
                }

                OutlinedTextField(
                    value = ui.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                    shape = AppCorner.Control,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.host_detail_search_hint),
                            color = palette.textPlaceholder,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = palette.textSecondary,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.outline,
                        cursorColor = palette.primary,
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                    ),
                )

                PullToRefreshBox(
                    isRefreshing = ui.refreshing,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
                        contentPadding = PaddingValues(top = AppDimens.CardGap, bottom = AppDimens.ScreenPadding),
                    ) {
                        item(key = "usage") {
                            UsageBlock(snapshot = ui.snapshot, refreshing = ui.refreshing)
                        }
                        if (filtered.isEmpty()) {
                            item(key = "search-empty") {
                                Text(
                                    text = stringResource(R.string.host_detail_search_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textPlaceholder,
                                    modifier = Modifier.padding(top = 24.dp),
                                )
                            }
                        }
                        filtered.forEach { group ->
                            item(key = "group-${group.title}") {
                                FeatureGroupCard(group = group) { row ->
                                    navController.navigate(Destinations.hostFeature(row.featureRoute, hostId))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(renameText)
                        renameOpen = false
                    },
                ) {
                    Text(text = stringResource(R.string.action_save), color = palette.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) {
                    Text(text = stringResource(R.string.action_cancel), color = palette.textSecondary)
                }
            },
            title = { Text(text = stringResource(R.string.host_detail_rename_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = AppCorner.Control,
                    label = { Text(stringResource(R.string.host_detail_rename_label)) },
                )
            },
            containerColor = palette.surface,
            textContentColor = palette.textPrimary,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }

    if (unbindOpen) {
        AlertDialog(
            onDismissRequest = { unbindOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        unbindOpen = false
                        // HostRepository.remove：先尽力 device.revoke，再删条目与令牌
                        vm.unbind { navController.popBackStack() }
                    },
                ) {
                    Text(text = stringResource(R.string.host_detail_unbind_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { unbindOpen = false }) {
                    Text(text = stringResource(R.string.action_cancel), color = palette.textSecondary)
                }
            },
            title = { Text(text = stringResource(R.string.host_detail_unbind_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(text = stringResource(R.string.host_detail_unbind_desc), color = palette.textSecondary) },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            shape = AppCorner.Dialog,
        )
    }
}

@Composable
private fun HeaderBar(
    title: String,
    dotColor: Color,
    menuOpen: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    writeDisabled: Boolean,
    navController: NavController,
    onRefreshUsage: () -> Unit,
    onRename: () -> Unit,
    onUnbind: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(AppDimens.RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.host_detail_back),
                tint = palette.textSecondary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        StatusDot(color = dotColor, modifier = Modifier.padding(end = 4.dp))
        Box {
            IconButton(onClick = { onMenuToggle(true) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_dots_vertical),
                    contentDescription = stringResource(R.string.host_detail_more),
                    tint = palette.textSecondary,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuToggle(false) },
                containerColor = palette.surface,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.host_detail_action_refresh),
                            color = if (writeDisabled) palette.textPlaceholder else palette.textPrimary,
                        )
                    },
                    onClick = {
                        onMenuToggle(false)
                        if (!writeDisabled) onRefreshUsage()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.host_detail_action_rename), color = palette.textPrimary) },
                    onClick = {
                        onMenuToggle(false)
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.host_detail_action_unbind), color = palette.error) },
                    onClick = {
                        onMenuToggle(false)
                        onUnbind()
                    },
                )
            }
        }
    }
}

private fun detailDotColor(ui: HostDetailUi, palette: AppPalette): Color = when {
    ui.tokenRejected || ui.host?.needsRepair == true -> palette.error
    ui.blockedMessage != null -> palette.error
    ui.snapshot?.profile?.running == true -> palette.success
    else -> palette.textPlaceholder
}

/** 用量区：空间/流量/数据库三条细进度 + 到期倒计时；首帧用 Room 快照（§10.1）。 */
@Composable
private fun UsageBlock(snapshot: HostSnapshot?, refreshing: Boolean) {
    val palette = LocalAppPalette.current
    val overview = snapshot?.overview
    val profile = snapshot?.profile
    val days = profile?.daysToExpire()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card)
            .padding(start = AppDimens.CardPadding, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        if (snapshot == null && !refreshing) {
            Text(
                text = stringResource(R.string.host_detail_no_usage),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPlaceholder,
            )
        } else {
            UsageBarRow(label = stringResource(R.string.hosts_quota_space), quota = overview?.webQuota)
            UsageBarRow(label = stringResource(R.string.hosts_quota_traffic), quota = overview?.trafficQuota)
            UsageBarRow(label = stringResource(R.string.hosts_quota_database), quota = overview?.sqlQuota)

            val expireText = when {
                days == null -> stringResource(R.string.value_dash)
                days < 0 -> stringResource(R.string.hosts_expired_days, -days)
                days == 0 -> stringResource(R.string.hosts_expires_today)
                else -> stringResource(R.string.value_days_left, days)
            }
            val expireColor = when {
                days == null -> palette.textPlaceholder
                days < 0 -> palette.error
                days <= 30 -> palette.warning
                else -> palette.textSecondary
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.host_detail_usage_expire_prefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    text = expireText,
                    style = MaterialTheme.typography.bodySmall,
                    color = expireColor,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(
                    text = profile?.expire ?: stringResource(R.string.value_dash),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPlaceholder,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

/** 折叠态摘要：空间 xx% · 流量 xx% · 数据库 xx% · 剩 N 天，紧贴 sticky 头下方一行小字。 */
@Composable
private fun CompactUsageLine(snapshot: HostSnapshot?) {
    val palette = LocalAppPalette.current
    val sep = stringResource(R.string.hosts_row_quota_sep)
    val dash = stringResource(R.string.value_dash)
    val unlimited = stringResource(R.string.hosts_quota_unlimited)
    val overview = snapshot?.overview

    fun segment(label: String, quota: com.mnbt.app.data.remote.api.model.Quota?): String = when {
        quota == null -> "$label $dash"
        quota.unlimited -> "$label $unlimited"
        else -> "$label ${Math.round(quota.fraction * 100)}%"
    }

    val days = snapshot?.profile?.daysToExpire()
    val daysText = when {
        days == null -> null
        days < 0 -> stringResource(R.string.hosts_expired)
        days == 0 -> stringResource(R.string.hosts_expires_today)
        else -> stringResource(R.string.value_days_left, days)
    }
    val daysColor = when {
        days == null -> palette.textSecondary
        days < 0 -> palette.error
        days <= 30 -> palette.warning
        else -> palette.textSecondary
    }

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = palette.textSecondary)) {
            append(
                listOf(
                    segment(stringResource(R.string.hosts_quota_space), overview?.webQuota),
                    segment(stringResource(R.string.hosts_quota_traffic), overview?.trafficQuota),
                    segment(stringResource(R.string.hosts_quota_database), overview?.sqlQuota),
                ).joinToString(" $sep "),
            )
        }
        if (daysText != null) {
            append(" $sep ")
            withStyle(SpanStyle(color = daysColor)) { append(daysText) }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp),
    )
}

// ---------------------------------------------------------------- 分组数据（§7.3 验收图）

private fun flagValue(
    on: Boolean?,
    onText: String,
    offText: String,
): RowValueData = when (on) {
    null -> RowValueData.Unknown
    true -> RowValueData.Text(onText, ValueTone.Secondary)
    false -> RowValueData.Text(offText, ValueTone.Secondary)
}

private fun plainText(value: String?): RowValueData =
    value?.takeIf { it.isNotBlank() }
        ?.let { RowValueData.Text(it, ValueTone.Primary) }
        ?: RowValueData.Unknown

@Composable
private fun buildFeatureGroups(values: FeatureValues): List<FeatureGroupData> {
    val onText = stringResource(R.string.value_on)
    val offText = stringResource(R.string.value_off)

    val sslValue: RowValueData = values.sslDaysLeft?.let { days ->
        when {
            days < 0 -> RowValueData.Text(stringResource(R.string.value_cert_expired), ValueTone.Error)
            days <= 30 -> RowValueData.Text(stringResource(R.string.value_days_left, days), ValueTone.Warning)
            else -> RowValueData.Text(stringResource(R.string.value_days_left, days), ValueTone.Primary)
        }
    } ?: RowValueData.Unknown

    val monitorValue: RowValueData = values.monitorCount?.let { count ->
        val alerts = values.monitorAlerts ?: 0
        if (alerts > 0) {
            RowValueData.Text(
                stringResource(R.string.value_monitor_count_alerts, count, alerts),
                ValueTone.Error,
            )
        } else {
            RowValueData.Text(stringResource(R.string.value_monitor_count, count), ValueTone.Primary)
        }
    } ?: RowValueData.Unknown

    return listOf(
        FeatureGroupData(
            title = stringResource(R.string.host_group_site),
            rows = listOf(
                FeatureRowData("php", stringResource(R.string.feature_php), R.drawable.ic_brand_php, plainText(values.phpVersion), Destinations.F_PHP),
                FeatureRowData("rundir", stringResource(R.string.feature_rundir), R.drawable.ic_folder_cog, plainText(values.rundir), Destinations.F_RUNDIR),
                FeatureRowData("rewrite", stringResource(R.string.feature_rewrite), R.drawable.ic_file_code, RowValueData.Unknown, Destinations.F_REWRITE),
                FeatureRowData("indexdoc", stringResource(R.string.feature_indexdoc), R.drawable.ic_file_text, plainText(values.indexdoc), Destinations.F_INDEXDOC),
            ),
        ),
        FeatureGroupData(
            title = stringResource(R.string.host_group_files_data),
            rows = listOf(
                FeatureRowData("files", stringResource(R.string.feature_files), R.drawable.ic_files, RowValueData.NoValueSlot, Destinations.F_FILES),
                FeatureRowData("database", stringResource(R.string.feature_database), R.drawable.ic_database, RowValueData.NoValueSlot, Destinations.F_DATABASE),
                FeatureRowData("sqlimport", stringResource(R.string.feature_sql_import), R.drawable.ic_database_import, RowValueData.NoValueSlot, Destinations.F_SQL_IMPORT),
            ),
        ),
        FeatureGroupData(
            title = stringResource(R.string.host_group_domain_ssl),
            rows = listOf(
                FeatureRowData(
                    "domains",
                    stringResource(R.string.feature_domains),
                    R.drawable.ic_world,
                    values.domainCount?.let {
                        RowValueData.Text(stringResource(R.string.value_domain_count, it), ValueTone.Primary)
                    } ?: RowValueData.Unknown,
                    Destinations.F_DOMAINS,
                ),
                FeatureRowData("ssl", stringResource(R.string.feature_ssl), R.drawable.ic_shield, sslValue, Destinations.F_SSL),
                FeatureRowData("forcehttps", stringResource(R.string.feature_force_https), R.drawable.ic_lock, flagValue(values.forceHttps, onText, offText), Destinations.F_SSL),
            ),
        ),
        FeatureGroupData(
            title = stringResource(R.string.host_group_perf_cache),
            rows = listOf(
                FeatureRowData("gzip", stringResource(R.string.feature_gzip), R.drawable.ic_file_zip, flagValue(values.gzipOn, onText, offText), Destinations.F_GZIP),
                FeatureRowData(
                    "cache",
                    stringResource(R.string.feature_cache),
                    R.drawable.ic_clock,
                    values.cacheCount?.let {
                        RowValueData.Text(stringResource(R.string.value_cache_count, it), ValueTone.Primary)
                    } ?: RowValueData.Unknown,
                    Destinations.F_CACHE,
                ),
                FeatureRowData("hotlink", stringResource(R.string.feature_hotlink), R.drawable.ic_photo_off, flagValue(values.hotlinkOn, onText, offText), Destinations.F_HOTLINK),
            ),
        ),
        FeatureGroupData(
            title = stringResource(R.string.host_group_monitor_deploy),
            rows = listOf(
                FeatureRowData("monitor", stringResource(R.string.feature_monitor), R.drawable.ic_activity, monitorValue, Destinations.F_MONITOR),
                FeatureRowData("notice", stringResource(R.string.feature_notice), R.drawable.ic_bell, RowValueData.NoValueSlot, Destinations.F_NOTICE),
                FeatureRowData("deploy", stringResource(R.string.feature_deploy), R.drawable.ic_rocket, RowValueData.NoValueSlot, Destinations.F_DEPLOY),
            ),
        ),
        FeatureGroupData(
            title = stringResource(R.string.host_group_account_security),
            rows = listOf(
                FeatureRowData("passdir", stringResource(R.string.feature_passdir), R.drawable.ic_folder_lock, RowValueData.NoValueSlot, Destinations.F_PASSDIR),
                FeatureRowData("password", stringResource(R.string.feature_password), R.drawable.ic_key, RowValueData.NoValueSlot, Destinations.F_PASSWORD),
            ),
        ),
    )
}
