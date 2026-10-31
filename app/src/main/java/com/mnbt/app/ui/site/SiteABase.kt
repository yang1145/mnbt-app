package com.mnbt.app.ui.site

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 组 A 八个设置页共用的视觉基座（PRD §8.2/§8.3）。
 * 仅供本组自包含使用，不与并行组共享。
 */

/** 保存流程阶段：VM 侧唯一写回入口，UI 据此驱动 Snackbar / 禁提交。 */
sealed interface SiteASavePhase {
    data object Idle : SiteASavePhase
    data object Saving : SiteASavePhase
    /** 保存成功：文案由页面用 string 资源呈现，VM 不携带字面串。 */
    data object Done : SiteASavePhase
    /** 保存失败：message 为服务端原样返回的提示。 */
    data class Error(val message: String) : SiteASavePhase
    /** 主机到期/封禁/面板关闭：留在页面上持续禁提交。 */
    data class Blocked(val message: String) : SiteASavePhase
}

/** 全屏页骨架：自绘顶栏（返回 + 20sp 标题，铺 surface 不铺蓝底），可选底部保存条。 */
@Composable
fun SiteAScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
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
                    .height(IntrinsicHeightTopBar),
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
                        contentDescription = stringResource(R.string.sitea_back),
                        tint = palette.textPrimary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
        bottomBar = { bottomBar?.invoke() },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { content() }
    }
}

private val IntrinsicHeightTopBar = 56.dp

/** 通用状态渲染：Loading/Failed/TokenRejected/CredentialMissing/HostBlocked/Ready。 */
@Composable
fun <T> SiteAStateView(
    state: LoadState<T>,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    val palette = LocalAppPalette.current
    when (state) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.primary)
        }

        is LoadState.Failed -> CenteredColumn(modifier) {
            StatusGlyph(R.drawable.ic_alert_triangle, palette.error)
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.sitea_retry), color = palette.primary)
            }
        }

        LoadState.TokenRejected, LoadState.CredentialMissing -> CenteredColumn(modifier) {
            StatusGlyph(R.drawable.ic_info_circle, palette.primary)
            Text(
                text = stringResource(R.string.sitea_repair_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = stringResource(R.string.sitea_repair_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.sitea_retry), color = palette.primary)
            }
        }

        is LoadState.HostBlocked -> CenteredColumn(modifier) {
            StatusGlyph(R.drawable.ic_lock, palette.warning)
            Text(
                text = stringResource(R.string.sitea_blocked_title),
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
private fun CenteredColumn(
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
private fun StatusGlyph(iconRes: Int, color: Color) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .padding(bottom = 12.dp),
        tint = color,
    )
}

/** 底部主保存按钮：filled primary、8dp、宽 fillMaxWidth-32dp、最小触控 48dp。 */
@Composable
fun SiteASaveBar(
    enabled: Boolean,
    saving: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .border(width = AppDimens.Hairline, color = palette.outline)
            .padding(horizontal = AppDimens.ScreenPadding, vertical = AppDimens.CardGap),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled && !saving,
            shape = AppCorner.Control,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.primary,
                contentColor = palette.onPrimary,
            ),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = palette.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.sitea_save), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 白卡压在灰底 + 1px 边，无阴影。 */
@Composable
fun SiteASectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card)
            .padding(AppDimens.CardPadding),
        content = content,
    )
}

@Composable
fun SiteAFormLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalAppPalette.current.textPrimary,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun SiteASupportText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppPalette.current.textSecondary,
        modifier = modifier,
    )
}

/** 单选行卡：选中=蓝边+蓝字+白底+勾；未选中=灰边+正文字色；切换不改字重。 */
@Composable
fun SiteAOptionBox(
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

/** 横向单选列表：FlowRow 铺开，卡片宽度自适应。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SiteAOptionFlow(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
    ) {
        options.forEach { option ->
            SiteAOptionBox(
                label = option,
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.widthIn(min = 96.dp),
            )
        }
    }
}

@Composable
fun SiteAOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportText: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
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
        minLines = minLines,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = AppCorner.Control,
        textStyle = MaterialTheme.typography.bodyMedium,
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

/** 只读代码框：等宽 12sp，限高 + 横/纵细滚动，绝不静默截断。 */
@Composable
fun SiteACodeBox(text: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
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
                text = text.ifBlank { "（无内容）" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.textPrimary,
            )
        }
    }
}

/** 页内提示条：1px 边 + 图标 + 文字，状态色只用于图标与文字（不铺蓝底/色块）。 */
@Composable
fun SiteANotice(
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

/** 保存阶段 → Snackbar。Done/Error 提示后复位；Blocked 保留以持续禁提交。 */
@Composable
fun SiteASaveEffect(
    phase: SiteASavePhase,
    snackbarHostState: SnackbarHostState,
    onConsume: () -> Unit,
) {
    val savedMsg = stringResource(R.string.sitea_saved)
    LaunchedEffect(phase) {
        when (phase) {
            SiteASavePhase.Done -> {
                snackbarHostState.showSnackbar(savedMsg)
                onConsume()
            }
            is SiteASavePhase.Error -> {
                snackbarHostState.showSnackbar(phase.message)
                onConsume()
            }
            is SiteASavePhase.Blocked -> snackbarHostState.showSnackbar(phase.message)
            else -> Unit
        }
    }
}

