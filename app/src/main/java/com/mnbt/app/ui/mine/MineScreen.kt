package com.mnbt.app.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.BuildConfig
import com.mnbt.app.R
import com.mnbt.app.appContainer
import com.mnbt.app.data.local.ThemeMode
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.domain.model.CloudSite
import com.mnbt.app.domain.model.CloudSiteStatus
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MineScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.appContainer
    val themeMode by container.settings.themeMode.collectAsState()
    val palette = LocalAppPalette.current
    val hosts by container.hostRepository.observeHosts().collectAsState(initial = emptyList())
    val cloudSites by container.cloudSiteRepository.sites().collectAsState(initial = emptyList())
    val sshConfigs by container.sshRepository.observeConfigs().collectAsState(initial = emptyList())

    Column(
        modifier = modifier.fillMaxSize().padding(AppDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
    ) {
        Text(
            text = stringResource(R.string.tab_mine),
            style = MaterialTheme.typography.titleLarge,
            color = palette.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        AccountCard(hosts, sshConfigs.size, cloudSites)

        SettingsGroup {
            SettingsRow(icon = R.drawable.ic_server, title = stringResource(R.string.mine_hosts),
                trailing = hosts.size.toString()) { navController.navigate(Destinations.MINE_HOSTS) }
            HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            SettingsRow(icon = R.drawable.ic_cloud, title = stringResource(R.string.mine_cloud_sites),
                trailing = cloudSites.size.toString()) { navController.navigate(Destinations.MINE_CLOUD_SITES) }
            HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            SettingsRow(icon = R.drawable.ic_shield, title = stringResource(R.string.mine_certs)) {
                navController.navigate(Destinations.MINE_CERTS)
            }
            HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            AppearanceRow(themeMode) { container.settings.setThemeMode(it) }
            HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            SettingsRow(icon = R.drawable.ic_trash, title = stringResource(R.string.mine_clear_cache)) {
                /* P2+: 清 Room 的 overview 缓存与 Coil 磁盘缓存，不删令牌 */
            }
        }

        SettingsGroup {
            SettingsRow(icon = R.drawable.ic_info_circle, title = stringResource(R.string.mine_version),
                trailing = BuildConfig.VERSION_NAME) {}
            HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            SettingsRow(icon = R.drawable.ic_list, title = stringResource(R.string.mine_licenses)) {
                /* P6: 开源许可声明页 */
            }
        }
    }
}

/**
 * 用户信息区 + 概览一行。APP 没有独立账号体系，这里的「用户」取最近使用那台主机的
 * MNBT 账号（配对时服务端回传的 username）；其余台数进统计而不是再画头像。
 * 只绑了云站点、没绑 MNBT 主机时，主位回退展示第一个云站点（无账号概念，直接用站点名）。
 */
@Composable
private fun AccountCard(hosts: List<HostEntity>, sshConfigCount: Int, cloudSites: List<CloudSite>) {
    val palette = LocalAppPalette.current
    val primary = hosts.maxByOrNull { it.lastSeenAt ?: it.createdAt }
    val cloudPrimary = cloudSites.firstOrNull()
    // 「待修复」统计格口径 = MNBT 主机需重新配对 + 云站点需重新绑定
    val repairCount = hosts.count { it.needsRepair } +
        cloudSites.count { it.status == CloudSiteStatus.NEEDS_REPAIR }
    // 告警行的文案是主机专用（「需要重新扫码配对」），只统计主机，云站点问题走统计格红字
    val hostRepairCount = hosts.count { it.needsRepair }
    val cleartextCount = hosts.count { it.cleartext }
    val initials = initialOf(primary).ifEmpty { initialOf(cloudPrimary) }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FramedAvatar(initials)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = primary?.let { accountName(it) }
                        ?: cloudPrimary?.name
                        ?: stringResource(R.string.mine_guest),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = primary?.baseUrl
                        ?: cloudPrimary?.baseUrl
                        ?: stringResource(R.string.mine_guest_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (primary != null) {
                    Text(
                        text = buildString {
                            append(stringResource(R.string.mine_bound_at, formatDate(primary.createdAt)))
                            primary.pluginVersion?.let {
                                append(" · ")
                                append(stringResource(R.string.mine_plugin_version, it))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (cloudPrimary != null) {
                    // 云站点没有插件版本可展示，只给绑定日期
                    Text(
                        text = stringResource(R.string.mine_bound_at, formatDate(cloudPrimary.pairedAtEpochMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        if (hostRepairCount > 0 || cleartextCount > 0) {
            HorizontalDivider(
                color = palette.outline,
                thickness = AppDimens.Hairline,
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (hostRepairCount > 0) palette.error else palette.warning,
                )
                Text(
                    text = buildString {
                        if (hostRepairCount > 0) {
                            append(stringResource(R.string.mine_repair_count, hostRepairCount))
                        }
                        if (cleartextCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(R.string.mine_cleartext_count, cleartextCount))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hostRepairCount > 0) palette.error else palette.warning,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        HorizontalDivider(
            color = palette.outline,
            thickness = AppDimens.Hairline,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            StatCell(
                value = hosts.size.toString(),
                label = stringResource(R.string.mine_stat_hosts),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = cloudSites.size.toString(),
                label = stringResource(R.string.mine_stat_cloud_sites),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = sshConfigCount.toString(),
                label = stringResource(R.string.mine_stat_ssh),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = repairCount.toString(),
                label = stringResource(R.string.mine_stat_repair),
                valueColor = if (repairCount > 0) palette.error else palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalAppPalette.current.textPrimary,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalAppPalette.current.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// 头像框几何量：外框 60dp、内环 48dp（两者各留 6dp 间隙），括号边长 11dp、线宽 1.5dp
private val AvatarOuter = 60.dp
private val AvatarInner = 48.dp
private val BracketArm = 11.dp
private val BracketStroke = 1.5.dp

/**
 * 纯装饰的头像框：白底 + hairline 蓝环 + 四角直角括号。
 * 括号画在外层方框边上、蓝环在内层，两套线隔开 6dp，叠在一起会糊。
 */
@Composable
private fun FramedAvatar(initials: String) {
    val palette = LocalAppPalette.current
    Box(modifier = Modifier.size(AvatarOuter), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(AvatarInner)
                .clip(AppCorner.Control)
                .border(AppDimens.Hairline, palette.primary, AppCorner.Control)
                .background(palette.surface, AppCorner.Control),
            contentAlignment = Alignment.Center,
        ) {
            if (initials.isEmpty()) {
                Icon(
                    painter = painterResource(R.drawable.ic_user),
                    contentDescription = stringResource(R.string.mine_avatar_desc),
                    modifier = Modifier.size(22.dp),
                    tint = palette.primary,
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primary,
                )
            }
        }
        Canvas(modifier = Modifier.size(AvatarOuter)) {
            val arm = BracketArm.toPx()
            val stroke = BracketStroke.toPx()
            val inset = stroke / 2f
            val right = size.width - inset
            val bottom = size.height - inset
            listOf(
                Offset(inset, inset) to Offset(inset + arm, inset),
                Offset(inset, inset) to Offset(inset, inset + arm),
                Offset(right, inset) to Offset(right - arm, inset),
                Offset(right, inset) to Offset(right, inset + arm),
                Offset(inset, bottom) to Offset(inset + arm, bottom),
                Offset(inset, bottom) to Offset(inset, bottom - arm),
                Offset(right, bottom) to Offset(right - arm, bottom),
                Offset(right, bottom) to Offset(right, bottom - arm),
            ).forEach { (from, to) -> drawLine(palette.primary, from, to, stroke) }
        }
    }
}

private fun accountName(host: HostEntity): String = host.username.ifBlank { host.label }

private fun initialOf(host: HostEntity?): String = initialOf(host?.let(::accountName))

/** 云站点没有账号概念，头像首字母直接取站点显示名，规则与主机一致。 */
private fun initialOf(site: CloudSite?): String = initialOf(site?.name)

private fun initialOf(name: String?): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val first = trimmed.first()
    // 中文首字缩成"字"没有辨识度，只在 ASCII 字母/数字时取首字母，否则用图标兜底
    return if (first.isLetterOrDigit() && first.code < 128) first.uppercaseChar().toString() else ""
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    AppCard { content() }
}

@Composable
private fun SettingsRow(
    icon: Int,
    title: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = palette.textSecondary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
        }
    }
}

/** 选中态 = 蓝边 + 蓝字 + 白底，切换不改字重（PRD §8.3）。 */
@Composable
private fun AppearanceRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = palette.textSecondary,
        )
        Text(
            text = stringResource(R.string.mine_appearance),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 12.dp),
        )
        ThemeMode.entries.forEach { mode ->
            val active = mode == selected
            Text(
                text = stringResource(
                    when (mode) {
                        ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.DARK -> R.string.theme_dark
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (active) palette.primary else palette.textSecondary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(AppCorner.Control)
                    .border(
                        width = AppDimens.Hairline,
                        color = if (active) palette.primary else palette.outline,
                        shape = AppCorner.Control,
                    )
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
