package com.mnbt.app.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.model.Quota
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.AppPalette
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 主机详情页专用组件（PRD §7.3 分组列表）。
 * 只放 ui/hosts 自用，不进 ui components 包——那里是全站基座。
 */

enum class ValueTone { Primary, Secondary, Placeholder, Warning, Error }

sealed interface RowValueData {
    /** 设计无值（图标+名称行，如文件/数据库/通知/一键部署/密码目录） */
    data object NoValueSlot : RowValueData

    /** 取数策略拿不到当前值：显示「—」，行可点进功能页加载最新值 */
    data object Unknown : RowValueData

    data class Text(val text: String, val tone: ValueTone) : RowValueData
}

data class FeatureRowData(
    val key: String,
    val label: String,
    val iconRes: Int,
    val value: RowValueData,
    val featureRoute: String,
)

data class FeatureGroupData(
    val title: String,
    val rows: List<FeatureRowData>,
)

@Composable
internal fun FeatureGroupCard(group: FeatureGroupData, onRowClick: (FeatureRowData) -> Unit) {
    val palette = LocalAppPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelLarge,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
                .background(palette.surface, AppCorner.Card)
                .clip(AppCorner.Card),
        ) {
            group.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    // 列表用 1px 分隔线不用斑马纹（§8.3），分隔线对齐到文本起始
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        thickness = AppDimens.Hairline,
                        color = palette.outline,
                    )
                }
                FeatureRowView(row = row, onClick = { onRowClick(row) })
            }
        }
    }
}

@Composable
private fun FeatureRowView(row: FeatureRowData, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppDimens.RowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = AppDimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(row.iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = palette.textSecondary,
        )
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 12.dp),
        )
        when (val value = row.value) {
            RowValueData.NoValueSlot -> Unit
            RowValueData.Unknown -> Text(
                text = stringResource(R.string.value_dash),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPlaceholder,
            )
            is RowValueData.Text -> ScrollableValue(text = value.text, color = valueColor(value.tone, palette))
        }
    }
}

internal fun valueColor(tone: ValueTone, palette: AppPalette): Color = when (tone) {
    ValueTone.Primary -> palette.textPrimary
    ValueTone.Secondary -> palette.textSecondary
    ValueTone.Placeholder -> palette.textPlaceholder
    ValueTone.Warning -> palette.warning
    ValueTone.Error -> palette.error
}

/**
 * 行右值：右对齐、限宽；超长时横向滚动 + 2dp 细滚动条（§8.3 禁止静默截断）。
 * 同行等高由外层行的固定行高保证。
 */
@Composable
internal fun ScrollableValue(text: String, color: Color, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    var textPx by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .widthIn(max = 160.dp)
            .onSizeChanged { viewportPx = it.width },
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            modifier = Modifier
                .horizontalScroll(scroll)
                .onSizeChanged { textPx = it.width },
        )
        val overflow = viewportPx > 0 && textPx > viewportPx + 1
        if (overflow) {
            Spacer(modifier = Modifier.height(3.dp))
            val trackW = viewportPx.toFloat()
            val minThumb = with(density) { 12.dp.toPx() }
            val thumbW = (trackW * trackW / textPx).coerceIn(minThumb, trackW)
            val maxScroll = scroll.maxValue.toFloat().coerceAtLeast(1f)
            val frac = (scroll.value.toFloat() / maxScroll).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(with(density) { trackW.toInt().toDp() })
                    .height(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(palette.outline, RoundedCornerShape(1.dp)),
                )
                Box(
                    modifier = Modifier
                        .offset { IntOffset((frac * (trackW - thumbW)).toInt(), 0) }
                        .width(with(density) { thumbW.toInt().toDp() })
                        .height(2.dp)
                        .background(palette.textPlaceholder, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/** 用量区细进度条行：label + 3dp 进度 + 百分比。状态色只上细进度条（§8.3）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageBarRow(label: String, quota: Quota?) {
    val palette = LocalAppPalette.current
    val unlimitedLabel = stringResource(R.string.hosts_quota_unlimited)
    val dash = stringResource(R.string.value_dash)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.width(48.dp),
        )
        val fraction = quota?.fraction?.toFloat() ?: 0f
        val tone = when {
            quota == null || quota.unlimited -> palette.textPlaceholder
            fraction >= 0.95f -> palette.error
            fraction >= 0.8f -> palette.warning
            else -> palette.success
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = tone,
            trackColor = palette.outline,
        )
        Text(
            text = when {
                quota == null -> dash
                quota.unlimited -> unlimitedLabel
                else -> stringResource(R.string.hosts_quota_percent, Math.round(quota.fraction * 100).toInt())
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (quota?.unlimited == true) palette.textSecondary else tone,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 10.dp)
                .width(56.dp),
        )
    }
}

/** HostBlocked 顶部只读横幅：1px 边 + error 文字，不铺底色（§8.3）。 */
@Composable
internal fun BlockedBanner(message: String, note: String) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.surface, AppCorner.Control)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_alert_triangle),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.error,
        )
        Text(
            text = message + note,
            style = MaterialTheme.typography.bodySmall,
            color = palette.error,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
