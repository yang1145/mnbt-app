package com.mnbt.app.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mnbt.app.R
import com.mnbt.app.domain.model.CloudServerSummary
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.AppPalette
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 云服务器模块共用的小件：电源状态取色 / 状态 chip / 到期文案。
 * 状态色只上点与文字（PRD §8.3），chip 自身只是 1px 边的胶囊，不铺色块。
 */

private const val DayMs = 86_400_000L

/** 电源状态 → 语义色：on 绿、off 灰、operating 橙、fault 红，未知状态走中性灰。 */
internal fun cloudPowerTone(status: String, palette: AppPalette): Color = when (status) {
    "on" -> palette.success
    "off" -> palette.textPlaceholder
    "operating" -> palette.warning
    "fault" -> palette.error
    else -> palette.textSecondary
}

/** 电源状态 → 文案：四个已知状态给固定词，其余原样展示服务端 desc。 */
@Composable
internal fun cloudPowerLabel(status: String, desc: String): String = when (status) {
    "on" -> stringResource(R.string.cloud_power_on)
    "off" -> stringResource(R.string.cloud_power_off)
    "operating" -> stringResource(R.string.cloud_power_operating)
    "fault" -> stringResource(R.string.cloud_power_fault)
    else -> desc.ifBlank { status }
}

/** 状态胶囊：1px 边 + 状态点 + 状态色文字，不用底色块（§8.3）。 */
@Composable
internal fun PowerChip(status: String, desc: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    val tone = cloudPowerTone(status, palette)
    Row(
        modifier = modifier
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.surface, AppCorner.Control)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = tone)
        Text(
            text = cloudPowerLabel(status, desc),
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * 到期文案与颜色（对齐小程序规则：≤7 天红色高亮，已到期同红，其余中性灰）。
 * 天数口径对齐 HostProfile.daysToExpire：整日截断，不足一天不计。无到期数据返回 null。
 */
@Composable
internal fun cloudDueLabel(dueTimeEpochMs: Long?): Pair<String, Color>? {
    if (dueTimeEpochMs == null) return null
    val palette = LocalAppPalette.current
    val days = ((dueTimeEpochMs - System.currentTimeMillis()) / DayMs).toInt()
    return when {
        days < 0 -> stringResource(R.string.cloud_due_expired) to palette.error
        days == 0 -> stringResource(R.string.cloud_due_today) to palette.error
        days <= 7 -> stringResource(R.string.cloud_days_left, days) to palette.error
        else -> stringResource(R.string.cloud_due_date, formatDay(dueTimeEpochMs)) to palette.textSecondary
    }
}

/** yyyy-MM-dd，列表卡与详情信息卡共用一个口径。 */
internal fun formatDay(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

/**
 * 服务器卡片：名称 + IP 副行，右侧电源 chip + 到期（≤7 天红）。
 * 云服务器 tab 分组列表与站点详情页共用；整卡可点进服务器详情。
 */
@Composable
internal fun CloudServerRow(server: CloudServerSummary, onClick: () -> Unit) {
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
