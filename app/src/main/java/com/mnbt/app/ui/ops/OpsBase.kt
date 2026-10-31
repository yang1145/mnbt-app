package com.mnbt.app.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 监控 / 通知 / 部署三页共用的视觉基座（PRD §8.2/§8.3）。
 * 与组 A 的 SiteABase 同理：仅供本组（ui/ops 包）自包含使用，不与并行组共享，
 * 避免互相覆盖。
 */

/**
 * 写操作阶段。与 LoadState 分开，因为写失败的 UI 反应不同：
 * Error 是 Snackbar/页内提示（带 1008 的 retryAfterSeconds），
 * Blocked 类由 ViewModel 直接替换整页状态。
 */
sealed interface OpsWritePhase {
    data object Idle : OpsWritePhase
    data object Saving : OpsWritePhase
    data class Done(val message: String) : OpsWritePhase
    data class Error(
        val message: String,
        val retryAfterSeconds: Int? = null,
    ) : OpsWritePhase
}

/** 自绘顶栏：返回 + 20sp 标题 + 右侧动作区，surface 底，不铺蓝底（§8.3）。 */
@Composable
fun OpsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
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
                    .background(palette.surface)
                    .height(OpsTopBarHeight),
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
                        contentDescription = stringResource(R.string.ops_back),
                        tint = palette.textPrimary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                actions()
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { content() }
    }
}

private val OpsTopBarHeight = 56.dp

/** 顶栏图标按钮：48dp 触控目标，单色描边图标。 */
@Composable
fun OpsTopBarAction(
    iconRes: Int,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) palette.textPrimary else palette.textPlaceholder,
        )
    }
}

/** 行内小图标动作（启停 / 更多），40dp 视觉、48dp 触控由 IconButton 保证。 */
@Composable
fun OpsInlineAction(
    iconRes: Int,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) palette.textSecondary else palette.textPlaceholder,
        )
    }
}

/**
 * 全状态渲染：Loading / Failed（重试）/ TokenRejected / CredentialMissing /
 * HostBlocked（整页只读引导）/ Ready。文案全走 strings_ops.xml。
 */
@Composable
fun <T> OpsStateView(
    state: LoadState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val palette = LocalAppPalette.current
    when (state) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.primary)
        }

        is LoadState.Failed -> OpsCenteredColumn(modifier) {
            OpsStatusGlyph(R.drawable.ic_alert_triangle, palette.error)
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.ops_retry), color = palette.primary)
            }
        }

        LoadState.TokenRejected, LoadState.CredentialMissing -> OpsCenteredColumn(modifier) {
            OpsStatusGlyph(R.drawable.ic_info_circle, palette.primary)
            Text(
                text = stringResource(R.string.ops_repair_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = stringResource(R.string.ops_repair_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.ops_retry), color = palette.primary)
            }
        }

        is LoadState.HostBlocked -> OpsCenteredColumn(modifier) {
            OpsStatusGlyph(R.drawable.ic_lock, palette.warning)
            Text(
                text = stringResource(R.string.ops_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
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
private fun OpsCenteredColumn(
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
private fun OpsStatusGlyph(iconRes: Int, color: Color) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .padding(bottom = 12.dp),
        tint = color,
    )
}

/** 行卡容器：1px 边 + surface，无内边距（行自己贴边），行间 1px 分隔线，不斑马纹。 */
@Composable
fun OpsListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card),
        content = content,
    )
}

@Composable
fun OpsRowDivider() {
    HorizontalDivider(
        thickness = AppDimens.Hairline,
        color = LocalAppPalette.current.outline,
    )
}

/** 单选框：选中=蓝边+蓝字+白底，未选中=灰边+正文字色；切换不改字重（§8.3）。 */
@Composable
fun OpsOptionBox(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .border(
                width = AppDimens.Hairline,
                color = if (selected) palette.primary else palette.outline,
                shape = AppCorner.Control,
            )
            .background(palette.surface, AppCorner.Control)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) palette.primary else palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
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

/** 描边输入框，配色走语义层。 */
@Composable
fun OpsOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportText: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val palette = LocalAppPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = palette.textPlaceholder) },
        supportingText = if (supportText.isNotEmpty()) {
            { Text(supportText, color = palette.textSecondary) }
        } else {
            null
        },
        singleLine = singleLine,
        enabled = enabled,
        shape = AppCorner.Control,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.primary,
            unfocusedBorderColor = palette.outline,
            focusedLabelColor = palette.primary,
            unfocusedLabelColor = palette.textSecondary,
            cursorColor = palette.primary,
            focusedContainerColor = palette.surface,
            unfocusedContainerColor = palette.surface,
        ),
    )
}

/** 等宽滚动日志区：限高 + 纵/横细滚动，禁止静默截断（§8.3）。 */
@Composable
fun OpsMonoBlock(text: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.background, AppCorner.Control)
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(vertical)
                .horizontalScroll(horizontal),
        ) {
            Text(
                text = text.ifBlank { stringResource(R.string.ops_monitor_logs_empty) },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.textPrimary,
            )
        }
    }
}

/** 页内提示条：1px 边 + 图标 + 文字，状态色只用于图标与文字。 */
@Composable
fun OpsNoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_info_circle,
    accent: Color = LocalAppPalette.current.textSecondary,
) {
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
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .padding(end = 4.dp),
            tint = accent,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
