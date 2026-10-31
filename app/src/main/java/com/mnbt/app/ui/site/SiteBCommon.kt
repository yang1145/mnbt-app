package com.mnbt.app.ui.site

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------- 页面骨架

/** 页头：返回 + 20sp 页标题 + 右侧「添加」（§8.2 字号，48dp 触控）。 */
@Composable
internal fun SiteBTopBar(
    title: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.site_b_back),
                tint = palette.textPrimary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(actionLabel, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** HostBlocked（1003/1004）顶部横幅：白卡 + 1px 边 + 警告色文字图标，不铺大面积色块（§8.3）。 */
@Composable
internal fun SiteBBlockedBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.surface, AppCorner.Control)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_alert_triangle),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = palette.warning,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.warning,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 令牌失效 / 本地凭证丢失时的页内重扫引导（PRD §4.6 1001）。 */
@Composable
private fun SiteBRepairGuide(
    onRepair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        iconRes = R.drawable.ic_scan,
        title = stringResource(R.string.site_b_repair_title),
        description = stringResource(R.string.site_b_repair_desc),
        primaryLabel = stringResource(R.string.site_b_repair_action),
        onPrimaryClick = onRepair,
        modifier = modifier,
    )
}

/**
 * LoadState 统一分发：Loading / Failed(重试) / TokenRejected·CredentialMissing(引导重扫) /
 * HostBlocked(只读) / Ready(content)。content 挂在 ColumnScope 上，列表直接用 weight(1f) 滚动。
 */
@Composable
internal fun <T> SiteBStateBody(
    state: LoadState<T>,
    repair: Boolean,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(modifier = modifier) {
        when {
            repair -> SiteBRepairGuide(onRepair, modifier = Modifier.fillMaxWidth())
            state is LoadState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = palette.primary,
                    strokeWidth = 2.dp,
                )
            }
            state is LoadState.Failed -> EmptyState(
                iconRes = R.drawable.ic_info_circle,
                title = stringResource(R.string.site_b_load_failed),
                description = state.message,
                primaryLabel = stringResource(R.string.site_b_retry),
                onPrimaryClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
            state is LoadState.TokenRejected || state is LoadState.CredentialMissing ->
                SiteBRepairGuide(onRepair, modifier = Modifier.fillMaxWidth())
            state is LoadState.HostBlocked -> {
                SiteBBlockedBanner(state.message)
                Text(
                    text = stringResource(R.string.site_b_blocked_readonly),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            state is LoadState.Ready -> content(state.data)
        }
    }
}

// ---------------------------------------------------------------- 输入件

@Composable
internal fun SiteBTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalAppPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = palette.textPlaceholder) } },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = palette.error) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = AppCorner.Control,
    )
}

/**
 * 密码输入：默认 PasswordVisualTransformation，眼睛图标切换明文/掩码。
 */
@Composable
internal fun SiteBPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = LocalAppPalette.current.error) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painter = painterResource(
                        if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                    ),
                    contentDescription = stringResource(
                        if (visible) R.string.site_b_hide else R.string.site_b_show,
                    ),
                    modifier = Modifier.size(20.dp),
                    tint = LocalAppPalette.current.textSecondary,
                )
            }
        },
        shape = AppCorner.Control,
    )
}

/** 删除二次确认（§8.3 硬性要求 AlertDialog）：确认钮仅文字用 error 色，不铺色块。 */
@Composable
internal fun SiteBConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large, // 弹框 16dp（AppShapes.large 即 16dp，§8.2）
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = palette.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.site_b_cancel), color = palette.textSecondary)
            }
        },
    )
}

/** BottomSheet 统一外壳：AppCorner.Sheet 顶部 16dp + surface 底 + 页面 16dp 边距。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteBSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppCorner.Sheet,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppDimens.ScreenPadding)
                .padding(bottom = 32.dp),
            content = content,
        )
    }
}

/** Sheet / 表单主提交钮：全宽、48dp 最小触控、进行中显示细进度且禁点。 */
@Composable
internal fun SiteBSubmitButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = AppCorner.Control,
        colors = ButtonDefaults.buttonColors(), // primary/onPrimary 已由 Theme 从色板映射，无硬编码
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 行按压缩浅蓝反馈（§8.3）：仓库未引 material-ripple 编译期依赖，
 * 用 interactionSource pressed 态以 primary 8% 透明底实现，色值仍取自色板。
 */
@Composable
internal fun Modifier.siteBRowFeedback(
    onClick: (() -> Unit)?,
    palette: com.mnbt.app.ui.theme.AppPalette,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    return this
        .background(if (pressed) palette.primary.copy(alpha = 0.08f) else Color.Transparent)
        .then(
            if (onClick != null) {
                Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            } else {
                Modifier
            },
        )
}

/** 列表行 1px 分隔线，无斑马纹（§8.3）。 */
@Composable
internal fun SiteBRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = LocalAppPalette.current.outline,
        thickness = AppDimens.Hairline,
    )
}

// ---------------------------------------------------------------- ViewModel 基座

/**
 * 三页共用的写通道：busy 防连点、HostBlocked 顶部横幅、
 * 1001/凭证缺失升级为整页重扫引导，Failed 走 Snackbar（PRD §4.6 的 APP 行为列）。
 * 读通道 load() 直接把 HostOutcome 折进页面的 LoadState。
 */
internal abstract class SiteBFeatureViewModel(
    protected val container: AppContainer,
    protected val hostId: Long,
    protected val context: Context,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 写操作撞上到期/封禁时的顶部禁写横幅（读请求已放行、写被服务端拒）。 */
    private val _writeBanner = MutableStateFlow<String?>(null)
    val writeBanner: StateFlow<String?> = _writeBanner.asStateFlow()

    private val _repair = MutableStateFlow(false)
    val repair: StateFlow<Boolean> = _repair.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    abstract fun reload()

    protected suspend fun <T> load(
        target: MutableStateFlow<LoadState<T>>,
        action: String,
        params: Map<String, String> = emptyMap(),
        decode: (JsonElement) -> T,
    ) {
        target.value = LoadState.Loading
        target.value = hostCall(container, hostId, action, params) { decode(it) }.toLoadState()
    }

    protected fun write(
        action: String,
        params: Map<String, String>,
        @StringRes successMessageRes: Int,
        afterSuccess: suspend () -> Unit = {},
    ) {
        writeWithPrepare(action, successMessageRes, abortMessageRes = null) { params }
    }

    /**
     * 参数必须在写之前先问一次服务端时用（典型是静态缓存：id 只是列表序号，
     * 页面停留期间网页面板可能已经改了顺序）。[prepare] 返回 null 表示前置读取没成功
     * 或目标已经不在了，此时放弃本次写入并发 [abortMessageRes]，不要拿旧 id 硬写。
     */
    protected fun writeWithPrepare(
        action: String,
        @StringRes successMessageRes: Int,
        @StringRes abortMessageRes: Int? = null,
        afterSuccess: suspend () -> Unit = {},
        prepare: suspend () -> Map<String, String>?,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val params = prepare()
                if (params == null) {
                    if (abortMessageRes != null) _toast.emit(context.getString(abortMessageRes))
                } else {
                    sendWrite(action, params, successMessageRes, afterSuccess)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun sendWrite(
        action: String,
        params: Map<String, String>,
        @StringRes successMessageRes: Int,
        afterSuccess: suspend () -> Unit,
    ) {
        when (val outcome = hostCall(container, hostId, action, params) { decodePayload<SiteBAck>(it) }) {
            is HostOutcome.Ready -> {
                _toast.emit(context.getString(successMessageRes))
                afterSuccess()
            }
            HostOutcome.TokenRejected,
            HostOutcome.CredentialMissing,
            -> _repair.value = true
            is HostOutcome.HostBlocked -> _writeBanner.value = outcome.message
            is HostOutcome.Failed -> _toast.emit(outcome.message)
        }
    }
}

/** 14sp 表单小标签（type 选择组上方）。 */
@Composable
internal fun SiteBFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalAppPalette.current.textSecondary,
        modifier = modifier,
    )
}

/** Sheet 内 16sp 标题。 */
@Composable
internal fun SiteBSheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalAppPalette.current.textPrimary,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
}
