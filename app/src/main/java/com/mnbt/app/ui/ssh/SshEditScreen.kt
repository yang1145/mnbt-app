package com.mnbt.app.ui.ssh

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.data.local.db.SshSessionEntity
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.pair.LabeledInput
import com.mnbt.app.ui.pair.PairTopBar
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/** 私钥是几 KB 的文本文件；超过这个上限的基本不是密钥，直接拒读防呆。 */
private const val MAX_KEY_FILE_BYTES = 256 * 1024

/**
 * SSH 会话编辑页（终端 tab 二级页）。sessionId < 0 为新建。
 * 路由注册由主控统一做；本页只按 [Destinations.SSH_EDIT] 的入参形态取参。
 */
@Composable
fun SshEditScreen(navController: NavController, entry: NavBackStackEntry) {
    val sessionId = entry.sessionIdFromArgs()
    val vm: SshEditViewModel = mnbtViewModel { container, _ -> SshEditViewModel(container) }
    val state by vm.state.collectAsState()
    val palette = LocalAppPalette.current
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    // 保存/删除成功后退页：新建与编辑共用同一条返回路径
    LaunchedEffect(state.savedId, state.deleted) {
        if (state.savedId != null || state.deleted) navController.popBackStack()
    }

    val keyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { readPlainText(context, uri) }
            .onSuccess { (name, text) -> vm.onPrivateKeyLoaded(name, text) }
            .onFailure { e -> vm.onPrivateKeyLoadFailed(e.message ?: "无法读取所选文件") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppDimens.ScreenPadding),
    ) {
        PairTopBar(
            title = stringResource(if (state.isNew) R.string.ssh_edit_new_title else R.string.ssh_edit_title),
            onBack = { navController.popBackStack() },
        )
        if (state.loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .widthIn(min = 96.dp)
                    .height(2.dp),
                color = palette.primary,
                trackColor = palette.outline,
            )
            return@Column
        }
        if (state.missing) {
            EmptyState(
                iconRes = R.drawable.ic_alert_triangle,
                title = stringResource(R.string.ssh_missing_config),
                description = "",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            LabeledInput(
                label = stringResource(R.string.ssh_field_label),
                value = state.label,
                onValueChange = vm::onLabelChange,
                placeholder = stringResource(R.string.ssh_field_label_placeholder),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledInput(
                    label = stringResource(R.string.ssh_field_host),
                    value = state.host,
                    onValueChange = vm::onHostChange,
                    placeholder = stringResource(R.string.ssh_field_host_placeholder),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.weight(3f),
                )
                LabeledInput(
                    label = stringResource(R.string.ssh_field_port),
                    value = state.port,
                    onValueChange = vm::onPortChange,
                    placeholder = stringResource(R.string.ssh_field_port_placeholder),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            LabeledInput(
                label = stringResource(R.string.ssh_field_username),
                value = state.username,
                onValueChange = vm::onUsernameChange,
                placeholder = stringResource(R.string.ssh_field_username_placeholder),
            )

            AuthSegment(
                selected = state.authType,
                onSelect = vm::onAuthTypeChange,
            )

            when (state.authType) {
                SshSessionEntity.AUTH_PASSWORD -> PasswordSection(
                    state = state,
                    onPasswordChange = vm::onPasswordChange,
                    onToggleVisibility = vm::onTogglePasswordVisibility,
                )
                else -> KeySection(
                    state = state,
                    onImportClick = { keyLauncher.launch(arrayOf("*/*")) },
                    onPassphraseChange = vm::onPassphraseChange,
                )
            }

            HostKeyFingerprintCard(state.knownHostFingerprint)

            state.error?.let { message ->
                AppCard {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.error,
                    )
                }
            }
        }

        EditFooter(
            isNew = state.isNew,
            saving = state.saving,
            onSave = vm::save,
            onDelete = { showDeleteConfirm = true },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.ssh_delete_confirm_title)) },
            text = { Text(stringResource(R.string.ssh_delete_confirm_message)) },
            shape = AppCorner.Dialog,
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        vm.delete()
                    },
                ) {
                    Text(stringResource(R.string.ssh_delete_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.ssh_cancel), color = palette.textSecondary)
                }
            },
        )
    }
}

/** 认证方式二选一。选中态 = 蓝边 + 蓝字 + 白底，不铺蓝底、不改字重（PRD §8.3）。 */
@Composable
private fun AuthSegment(selected: String, onSelect: (String) -> Unit) {
    val palette = LocalAppPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ssh_auth_method),
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentOption(
                label = stringResource(R.string.ssh_auth_password),
                isSelected = selected == SshSessionEntity.AUTH_PASSWORD,
                onClick = { onSelect(SshSessionEntity.AUTH_PASSWORD) },
                modifier = Modifier.weight(1f),
            )
            SegmentOption(
                label = stringResource(R.string.ssh_auth_key),
                isSelected = selected == SshSessionEntity.AUTH_KEY,
                onClick = { onSelect(SshSessionEntity.AUTH_KEY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegmentOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    Box(
        modifier = modifier
            .height(AppDimens.RowHeight)
            .border(
                AppDimens.Hairline,
                if (isSelected) palette.primary else palette.outline,
                AppCorner.Control,
            )
            .background(palette.surface, AppCorner.Control)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) palette.primary else palette.textPrimary,
        )
    }
}

@Composable
private fun PasswordSection(
    state: SshEditUiState,
    onPasswordChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(verticalAlignment = Alignment.Bottom) {
        LabeledInput(
            label = stringResource(R.string.ssh_field_password),
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = stringResource(
                if (state.hasStoredPassword) R.string.ssh_password_saved
                else R.string.ssh_field_password_placeholder
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password,
            ),
            visualTransformation = if (state.showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onToggleVisibility,
            modifier = Modifier
                .size(48.dp)
                .padding(start = 4.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (state.showPassword) R.drawable.ic_eye_off else R.drawable.ic_eye,
                ),
                contentDescription = stringResource(
                    if (state.showPassword) R.string.ssh_hide else R.string.ssh_show,
                ),
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
        }
    }
}

@Composable
private fun KeySection(
    state: SshEditUiState,
    onImportClick: () -> Unit,
    onPassphraseChange: (String) -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ssh_private_key),
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
        )
        val importedKey = state.privateKeyText
        val status = when {
            importedKey != null -> stringResource(
                R.string.ssh_key_imported,
                state.privateKeyFileName.orEmpty(),
                importedKey.length,
            )
            state.hasStoredPrivateKey -> stringResource(R.string.ssh_key_saved)
            else -> stringResource(R.string.ssh_key_none)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
                .background(palette.surface, AppCorner.Control)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.privateKeyText != null) palette.textPrimary
                else palette.textPlaceholder,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onImportClick, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.ssh_import_key), color = palette.primary)
            }
        }
        LabeledInput(
            label = stringResource(R.string.ssh_passphrase),
            value = state.passphrase,
            onValueChange = onPassphraseChange,
            placeholder = stringResource(
                if (state.hasStoredPassphrase) R.string.ssh_passphrase_saved
                else R.string.ssh_passphrase_placeholder
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password,
            ),
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

@Composable
private fun HostKeyFingerprintCard(fingerprint: String?) {
    val palette = LocalAppPalette.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_shield),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = palette.textSecondary,
            )
            Text(
                text = stringResource(R.string.ssh_host_key),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (fingerprint == null) {
            Text(
                text = stringResource(R.string.ssh_host_key_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPlaceholder,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = stringResource(R.string.ssh_host_key_note),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 保存 + 删除同行区：删除只给已存在的会话；同行控件等高（PRD §8.3）。 */
@Composable
private fun EditFooter(
    isNew: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSave,
            enabled = !saving,
            shape = AppCorner.Control,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.ssh_save))
        }
        if (!isNew) {
            TextButton(
                onClick = onDelete,
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = palette.error,
                )
                Text(
                    text = stringResource(R.string.ssh_delete_session),
                    color = palette.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** 从 NavBackStackEntry 取 sessionId：-1 = 新建。注册形态以 LongType 为准，IntType 兜底。 */
internal fun NavBackStackEntry.sessionIdFromArgs(): Long =
    arguments?.let { args ->
        runCatching { args.getLong(Destinations.ARG_SESSION_ID) }
            .getOrElse { runCatching { args.getInt(Destinations.ARG_SESSION_ID).toLong() }.getOrDefault(-1L) }
    } ?: -1L

private fun readPlainText(context: Context, uri: Uri): Pair<String, String> {
    val name = queryDisplayName(context, uri)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法读取所选文件")
    if (bytes.size > MAX_KEY_FILE_BYTES) error("文件超过 256KB，不像私钥文本")
    return name to String(bytes, Charsets.UTF_8)
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
    }
    return uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "私钥文件" }
}
