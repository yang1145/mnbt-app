package com.mnbt.app.ui.mine

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

        OverviewCard(hosts, sshConfigs.size, cloudSites)

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
 * 资源概览卡。APP 没有账号体系，不再根据主机账户展示「用户」人格信息
 * （头像/账号名/站点地址/绑定日期/插件版本），只保留统计与告警：
 * 四格资源统计 + 条件告警行（主机需重新配对 / 云站点需重新绑定 / http 明文）。
 */
@Composable
private fun OverviewCard(hosts: List<HostEntity>, sshConfigCount: Int, cloudSites: List<CloudSite>) {
    val palette = LocalAppPalette.current
    val hostRepairCount = hosts.count { it.needsRepair }
    val cloudRepairCount = cloudSites.count { it.status == CloudSiteStatus.NEEDS_REPAIR }
    val cleartextCount = hosts.count { it.cleartext }
    // 「待修复」统计格口径 = MNBT 主机需重新配对 + 云站点需重新绑定
    val repairCount = hostRepairCount + cloudRepairCount

    AppCard {
        Row(modifier = Modifier.fillMaxWidth()) {
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

        if (hostRepairCount > 0 || cloudRepairCount > 0 || cleartextCount > 0) {
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
                    tint = if (hostRepairCount > 0 || cloudRepairCount > 0) palette.error else palette.warning,
                )
                Text(
                    text = buildString {
                        if (hostRepairCount > 0) {
                            append(stringResource(R.string.mine_repair_count, hostRepairCount))
                        }
                        if (cloudRepairCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(R.string.mine_cloud_repair_count, cloudRepairCount))
                        }
                        if (cleartextCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(R.string.mine_cleartext_count, cleartextCount))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hostRepairCount > 0 || cloudRepairCount > 0) palette.error else palette.warning,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
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
            style = MaterialTheme.typography.titleLarge,
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
