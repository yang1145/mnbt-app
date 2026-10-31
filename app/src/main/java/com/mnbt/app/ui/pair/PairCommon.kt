package com.mnbt.app.ui.pair

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.delay

/** 配对三页共用的顶栏：ic_arrow_left 返回 + titleLarge（PRD 页面通用样式）。 */
@Composable
fun PairTopBar(title: String, onBack: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.pair_back),
                modifier = Modifier.size(24.dp),
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
}

/**
 * 配对流程共用的进行中 / 成功 / 失败三态渲染。
 * 进度只用细进度条与文字，不铺色块（PRD §8.3）。
 */
@Composable
fun PairStateViews(
    state: PairState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    when (state) {
        is PairState.Probing -> PairWorkingRow(stringResource(R.string.pair_working_probing), modifier)
        is PairState.Pairing -> PairWorkingRow(stringResource(R.string.pair_working_pairing), modifier)
        is PairState.Bound -> Box(modifier.fillMaxWidth()) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = palette.success,
                    )
                    Text(
                        text = stringResource(R.string.pair_bound_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.pair_bound_host, state.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.pair_bound_redirect),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPlaceholder,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        is PairState.Failed -> AppCard(modifier = modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = palette.error)
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.error,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
            }
            state.retryAfterSeconds?.let { seconds ->
                ThrottleCountdown(seconds)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRetry) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.pair_failed_retry),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        else -> Unit
    }
}

/**
 * 1008 限流的等待提示：服务端给的 retry_after 是秒数，这里逐秒走完再消失，
 * 不锁死「重试」按钮 —— 服务端的计数口径可能和这一屏的存活时长不一致。
 */
@Composable
private fun ThrottleCountdown(initialSeconds: Int) {
    val palette = LocalAppPalette.current
    var remaining by remember(initialSeconds) { mutableStateOf(initialSeconds) }
    LaunchedEffect(initialSeconds) {
        while (remaining > 0) {
            delay(1000L)
            remaining -= 1
        }
    }
    if (remaining > 0) {
        Text(
            text = stringResource(R.string.pair_failed_retry_in, remaining),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PairWorkingRow(label: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .width(96.dp)
                .height(2.dp),
            color = palette.primary,
            trackColor = palette.outline,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** 「信任并继续」主按钮 + 「取消」：同行控件等高（PRD §8.3）。 */
@Composable
fun PairConfirmButtons(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onConfirm,
            enabled = enabled,
            shape = AppCorner.Control,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shield),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.pair_trust_confirm),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(
            onClick = onCancel,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.pair_trust_cancel))
        }
    }
}

/**
 * 8dp 圆角输入框（PRD §8.2）。这里刻意用 BasicTextField 包一层 1px 边框，
 * 而不是 OutlinedTextField：M3 的 OutlinedTextField 开放自定义 shape 的版本较新，
 * 当前 compose-bom 下它固定 4dp 圆角，守不住「输入框 8dp」这条规范。
 */
@Composable
fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val palette = LocalAppPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
                .background(palette.surface, AppCorner.Control)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(palette.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.textPlaceholder,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/**
 * 绑定成功后跳主机详情：popUpTo 主机 tab 把扫码/手动/信任整条配对栈全部清掉，
 * 主机详情压回 tab 栈之上，返回键直接回到主机列表。
 */
fun NavController.finishPairingAndOpenHost(hostId: Long) {
    navigate("host/$hostId") {
        popUpTo("hosts")
        launchSingleTop = true
    }
}
