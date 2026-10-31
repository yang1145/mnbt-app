package com.mnbt.app.ui.cloud

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/**
 * 云服务器绑定流程（扫码/绑定两页）共用的小件，视觉对齐 ui/pair 三屏的写法：
 * 同款顶栏、同款 8dp 输入框、同款「进行中/失败」渲染。不直接 import ui/pair 的组件，
 * 沿用本项目「各功能自带视觉件」的惯例，避免两套绑定流程互相牵动。
 */

/** 绑定两页共用的顶栏：ic_arrow_left 返回 + titleLarge。 */
@Composable
internal fun CloudPairTopBar(title: String, navController: NavController) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.cloud_back),
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
 * 8dp 圆角输入框（PRD §8.2）：BasicTextField 包 1px 边框，理由与 ui/pair 相同——
 * 当前 compose-bom 下 OutlinedTextField 固定 4dp 圆角，守不住「输入框 8dp」规范。
 */
@Composable
internal fun CloudLabeledInput(
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

/** 绑定进行中的一行：96dp 细进度条 + 文案，不铺色块（§8.3）。 */
@Composable
internal fun CloudPairWorkingRow(label: String, modifier: Modifier = Modifier) {
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

/** 失败卡：状态点 + 服务端中文 message + 重试入口。 */
@Composable
internal fun CloudPairFailedCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    AppCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = palette.error)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.error,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
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
                    text = stringResource(R.string.cloud_pair_failed_retry),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
