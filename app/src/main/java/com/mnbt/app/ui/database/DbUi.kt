package com.mnbt.app.ui.database

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 数据库页组（DatabaseScreen / SqlImportScreen）专用基座，仿 ui/site/SiteABase 的
 * 做法自包含在本包内，不与并行组共享。视觉遵循 PRD §8.2/§8.3：
 * 白卡 1px 描边无阴影、状态色只用于点/文字/细进度条、行高 56dp、选中=蓝边蓝字白底。
 */

/** §8.2：弹框圆角 16dp（AppCorner 未给全角弹框档位，此处按规范数值建形，不涉颜色）。 */
val DbDialogShape = RoundedCornerShape(16.dp)

/** 顶栏自绘：surface 底 + 返回 + 20sp 标题 + 右侧操作区，不铺蓝底。 */
@Composable
fun DbScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (contentPadding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val palette = LocalAppPalette.current
    Scaffold(
        modifier = modifier,
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppDimens.RowHeight)
                    .background(palette.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.db_back),
                        tint = palette.textPrimary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, end = 8.dp),
                )
                actions()
            }
        },
        content = content,
    )
}

@Composable
fun DbTopBarIcon(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = LocalAppPalette.current.textPrimary,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}

/** LoadState 全分支：Loading 骨架 / Ready / Failed 可重试 / TokenRejected+CredentialMissing 引导重扫 / HostBlocked 只读。 */
@Composable
fun <T> DbStateView(
    state: LoadState<T>,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val palette = LocalAppPalette.current
    when (state) {
        LoadState.Loading -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(AppDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        ) {
            // 骨架 = 白卡轮廓占位，不铺色块（§8.3）
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
                        .background(palette.surface, AppCorner.Card),
                )
            }
        }

        is LoadState.Failed -> DbCentered(modifier) {
            DbIconGlyph(R.drawable.ic_alert_triangle, palette.error)
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.db_retry), color = palette.primary)
            }
        }

        LoadState.TokenRejected, LoadState.CredentialMissing -> DbCentered(modifier) {
            DbIconGlyph(R.drawable.ic_info_circle, palette.primary)
            Text(
                text = stringResource(R.string.db_repair_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = stringResource(R.string.db_repair_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.db_retry), color = palette.primary)
            }
        }

        is LoadState.HostBlocked -> DbCentered(modifier) {
            DbIconGlyph(R.drawable.ic_lock, palette.warning)
            Text(
                text = stringResource(R.string.db_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.db_blocked_desc),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        is LoadState.Ready -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppDimens.ScreenPadding, vertical = AppDimens.CardGap),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        ) { content(state.data) }
    }
}

@Composable
private fun DbCentered(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun DbIconGlyph(iconRes: Int, color: Color) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .padding(bottom = 12.dp),
        tint = color,
    )
}

/** 写操作阶段 → Snackbar。Success/Error/NeedRepair/Blocked 均提示；Running 不提示。 */
@Composable
fun DbOpEffect(
    phase: DbOpPhase,
    snackbarHostState: SnackbarHostState,
    onConsume: () -> Unit,
) {
    val needRepairMsg = stringResource(R.string.db_need_repair_snackbar)
    LaunchedEffect(phase) {
        when (phase) {
            is DbOpPhase.Success -> {
                snackbarHostState.showSnackbar(phase.message)
                onConsume()
            }
            is DbOpPhase.Error -> {
                snackbarHostState.showSnackbar(phase.message)
                onConsume()
            }
            DbOpPhase.NeedRepair -> {
                snackbarHostState.showSnackbar(needRepairMsg)
                onConsume()
            }
            is DbOpPhase.Blocked -> {
                snackbarHostState.showSnackbar(phase.message)
                onConsume()
            }
            else -> Unit
        }
    }
}

/** 提交中遮罩提示：细圈 + 文案，不铺底色。 */
@Composable
fun DbBusyIndicator(label: String) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = palette.primary,
            strokeWidth = 2.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 单选行（访问本地/远程、字符集）：选中=蓝边+蓝字+白底+勾，不改字重（§8.3）。 */
@Composable
fun DbOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    support: String = "",
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppDimens.RowHeight)
            .border(
                width = AppDimens.Hairline,
                color = if (selected) palette.primary else palette.outline,
                shape = AppCorner.Control,
            )
            .background(palette.surface, AppCorner.Control)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) palette.primary else palette.textPrimary,
            )
            if (support.isNotEmpty()) {
                Text(
                    text = support,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.primary,
            )
        }
    }
}

/** 页内提示条：1px 边白卡，图标+文字。契约缺口说明用，状态色只给图标/文字。 */
@Composable
fun DbNotice(text: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.surface, AppCorner.Control)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info_circle),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.textSecondary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
