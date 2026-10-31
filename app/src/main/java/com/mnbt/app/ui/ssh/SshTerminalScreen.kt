package com.mnbt.app.ui.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ssh.SshConnection
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.terminal.TerminalSurface
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * SSH 终端页（终端 tab 二级页）。
 *
 * 断开时机按 [com.mnbt.app.ssh.SshSessionRegistry] 的语义定：
 * 返回上一页**不**结束会话（registry 保持连接、transcript 不丢，列表页的连接点还亮着）；
 * 只有顶栏「结束会话」显式调 [SshRepository] 的 disconnect，或远端 exit 由 registry 自动摘除。
 */
@Composable
fun SshTerminalScreen(navController: NavController, entry: NavBackStackEntry) {
    val sessionId = entry.sessionIdFromArgs()
    val vm: SshTerminalViewModel = mnbtViewModel { container, _ ->
        SshTerminalViewModel(container, sessionId)
    }
    val state by vm.state.collectAsState()
    val title by vm.title.collectAsState()
    val ctrlLatched by vm.ctrlLatched.collectAsState()
    var showEndConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.ssh_back),
                    modifier = Modifier.size(24.dp),
                    tint = LocalAppPalette.current.textPrimary,
                )
            }
            Text(
                text = title ?: stringResource(R.string.tab_terminal),
                style = MaterialTheme.typography.titleMedium,
                color = LocalAppPalette.current.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            )
            if (state is SshTerminalUiState.Connected) {
                FontSizeButton(delta = -1, label = "A−", description = stringResource(R.string.ssh_terminal_font_decrease), onClick = vm::adjustFontSize)
                FontSizeButton(delta = 1, label = "A+", description = stringResource(R.string.ssh_terminal_font_increase), onClick = vm::adjustFontSize)
                IconButton(
                    onClick = { showEndConfirm = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_logout),
                        contentDescription = stringResource(R.string.ssh_terminal_end_session),
                        tint = LocalAppPalette.current.textSecondary,
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (val current = state) {
                is SshTerminalUiState.Connected -> ConnectedContent(current.connection)
                is SshTerminalUiState.Connecting -> ConnectingContent(
                    label = stringResource(R.string.ssh_terminal_connecting),
                )
                is SshTerminalUiState.TrustPrompt -> {
                    ConnectingContent(label = stringResource(R.string.ssh_terminal_waiting_trust))
                    TrustDialog(prompt = current, vm = vm, onCancel = { navController.popBackStack() })
                }
                is SshTerminalUiState.KeyChanged -> KeyChangedCard(
                    changed = current,
                    vm = vm,
                    onCancel = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                is SshTerminalUiState.Failed -> FailedCard(
                    message = current.message,
                    onRetry = vm::retry,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        if (state is SshTerminalUiState.Connected) {
            SshHelperBar(
                ctrlLatched = ctrlLatched,
                onKey = vm::pressHelperKey,
                onKeyboard = vm::toggleKeyboard,
            )
        }
    }

    if (showEndConfirm) {
        val palette = LocalAppPalette.current
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text(stringResource(R.string.ssh_terminal_end_confirm_title)) },
            text = { Text(stringResource(R.string.ssh_terminal_end_confirm_message)) },
            shape = AppCorner.Dialog,
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndConfirm = false
                        vm.endSession()
                        navController.popBackStack()
                    },
                ) {
                    Text(stringResource(R.string.ssh_terminal_end_session), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) {
                    Text(stringResource(R.string.ssh_cancel), color = palette.textSecondary)
                }
            },
        )
    }
}

/**
 * 终端面。深底是 Termux 渲染器自己的默认配色（PRD §8.3 允许终端区为深色面），
 * 不碰全局主题。长按选中复制/粘贴由 TerminalView + SshTerminalClient 内建。
 * 尺寸自适应：adjustResize/imePadding 改高度 → onSizeChanged → updateSize → sendWindowChange。
 */
@Composable
private fun ConnectedContent(connection: SshConnection) {
    val palette = LocalAppPalette.current
    val ended by connection.terminalClient.sessionEnded.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF000000))) {
        TerminalSurface(
            connection = connection,
            modifier = Modifier.fillMaxSize(),
        )
        if (ended) {
            AppCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(AppDimens.ScreenPadding),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = palette.warning)
                    Text(
                        text = stringResource(R.string.ssh_terminal_ended),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectingContent(label: String) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .widthIn(min = 96.dp)
                .height(2.dp),
            color = palette.primary,
            trackColor = palette.outline,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun FailedCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    AppCard(
        modifier = modifier.padding(AppDimens.ScreenPadding),
    ) {
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
                    text = stringResource(R.string.ssh_terminal_retry),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** 公钥变更：§9.2 的 SSH 版，给足核对信息；更换信任是显式动作，不默认放行。 */
@Composable
private fun KeyChangedCard(
    changed: SshTerminalUiState.KeyChanged,
    vm: SshTerminalViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    AppCard(
        modifier = modifier.padding(AppDimens.ScreenPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_alert_triangle),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = palette.warning,
            )
            Text(
                text = stringResource(R.string.ssh_key_changed_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = stringResource(R.string.ssh_key_changed_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.ssh_key_changed_expected, changed.expected),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.ssh_key_changed_actual, changed.actual),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = palette.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.ssh_cancel), color = palette.textSecondary)
            }
            TextButton(onClick = vm::replaceKeyAndReconnect) {
                Text(stringResource(R.string.ssh_key_changed_replace), color = palette.primary)
            }
        }
    }
}

@Composable
private fun TrustDialog(
    prompt: SshTerminalUiState.TrustPrompt,
    vm: SshTerminalViewModel,
    onCancel: () -> Unit,
) {
    val palette = LocalAppPalette.current
    AlertDialog(
        // 强制二选一：不确认就不连；取消 = 离开本页，下次进来重新走一次 TOFU
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ssh_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ssh_trust_intro, prompt.host, prompt.port),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                Row {
                    Text(
                        text = stringResource(R.string.ssh_trust_algorithm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                    Text(
                        text = " ${prompt.algorithm}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                    )
                }
                Text(
                    text = stringResource(R.string.ssh_trust_fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                Text(
                    text = prompt.fingerprint,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = palette.textPrimary,
                )
            }
        },
        shape = AppCorner.Dialog,
        confirmButton = {
            TextButton(onClick = vm::confirmTrust) {
                Text(stringResource(R.string.ssh_trust_confirm), color = palette.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.ssh_cancel), color = palette.textSecondary)
            }
        },
    )
}

/**
 * 辅助键条（PRD §7.2 + §8.3）：白底细描边小方块，按下/latch = 蓝边框蓝字，
 * 无蓝底色块；行高 56dp；超宽横向滚动。
 * 末尾的「键盘」不在 PRD 的键位清单里：Termux 靠它自己的 extra-keys 键盘键唤输入法，
 * 我们那条没有，缺了就没有任何入口能弹软键盘（TerminalView 不是 EditText）。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SshHelperBar(ctrlLatched: Boolean, onKey: (SshHelperKey) -> Unit, onKeyboard: () -> Unit) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .imePadding(),
    ) {
        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.RowHeight)
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SshHelperKey.entries.forEach { key ->
                HelperKeyButton(
                    label = key.label,
                    latched = key == SshHelperKey.Ctrl && ctrlLatched,
                    description = null,
                    onClick = { onKey(key) },
                )
            }
            HelperKeyButton(
                label = stringResource(R.string.ssh_terminal_keyboard),
                latched = false,
                description = stringResource(R.string.ssh_terminal_keyboard_desc),
                onClick = onKeyboard,
            )
        }
    }
}

@Composable
private fun HelperKeyButton(
    label: String,
    latched: Boolean,
    description: String?,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val highlighted = pressed || latched
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(AppCorner.Control)
            .border(
                AppDimens.Hairline,
                if (highlighted) palette.primary else palette.outline,
                AppCorner.Control,
            )
            .background(palette.surface, AppCorner.Control)
            .clickable(
                interactionSource = interactionSource,
                // 默认 ripple 是面状色块，红线禁止；高亮反馈只有描边与文字变色
                indication = null,
                onClick = onClick,
            )
            .then(
                description?.let { text ->
                    Modifier.semantics { contentDescription = text }
                } ?: Modifier,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) palette.primary else palette.textPrimary,
        )
    }
}

@Composable
private fun FontSizeButton(delta: Int, label: String, description: String, onClick: (Int) -> Unit) {
    IconButton(
        onClick = { onClick(delta) },
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = LocalAppPalette.current.textSecondary,
        )
    }
}
