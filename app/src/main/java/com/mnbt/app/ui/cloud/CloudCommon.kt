package com.mnbt.app.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mnbt.app.R
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
