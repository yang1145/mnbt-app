package com.mnbt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/** 状态点。状态色只允许出现在点、文字、细进度条上（PRD §8.3）。 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** 白卡压在灰底 + 1px 边，不用阴影（PRD §8.2）。 */
@Composable
fun AppCard(
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
fun EmptyState(
    iconRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimaryClick: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondaryClick: () -> Unit = {},
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = palette.textPlaceholder,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = 32.dp),
        )
        if (primaryLabel != null) {
            Button(
                onClick = onPrimaryClick,
                shape = AppCorner.Control,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(primaryLabel)
            }
        }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondaryClick) {
                Text(
                    text = secondaryLabel,
                    color = palette.primary,
                )
            }
        }
    }
}
