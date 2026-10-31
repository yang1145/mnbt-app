package com.mnbt.app.ui.database

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 数据库管理页（PRD §7.2 / 契约 §8）。面板是「一主机一库一账号」，库随主机开通产生，
 * db.create / db.delete 恒 1010 —— 所以这一页没有新建/删除数据库：
 * 顶栏的加号是「立即备份」(db.backup.create)，删除只针对备份文件 (db.backup.delete)，
 * 整页级危险操作是「清空当前库的表」(db.delete_all)。
 *
 * db.list 恒 0 或 1 条，因此列表按单库卡片渲染，不套多库列表。
 * 每行只常显「权限」「备份」两个高频操作，删除收进 ⋮（§8.3）。
 * 配额两张卡：quota 是备份**次数**配额（unit=次），sql_space 才是空间配额（MB），max<=0 显示不限。
 */
@Composable
fun DatabaseScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val appContext = LocalContext.current.applicationContext
    val vm = mnbtViewModel { container, _ -> DatabaseViewModel(container, hostId, appContext) }

    val listState by vm.listState.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = LocalAppPalette.current

    DbOpEffect(phase, snackbarHostState, vm::consumePhase)
    LaunchedEffect(listState) {
        val state = listState
        if (state is LoadState.Failed && state.message.isNotBlank()) {
            snackbarHostState.showSnackbar(state.message)
        }
    }

    var showDeleteAll by rememberSaveable { mutableStateOf(false) }
    var accessTarget by remember { mutableStateOf<DbItem?>(null) }
    var restoreTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteBackupTarget by remember { mutableStateOf<String?>(null) }

    val writable = listState is LoadState.Ready && !busy

    DbScaffold(
        title = stringResource(R.string.db_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        actions = {
            DbTopBarIcon(
                iconRes = R.drawable.ic_refresh,
                contentDescription = stringResource(R.string.db_refresh),
                onClick = vm::load,
                enabled = !busy,
            )
            DbTopBarIcon(
                iconRes = R.drawable.ic_plus,
                contentDescription = stringResource(R.string.db_backup_now),
                onClick = vm::backupNow,
                enabled = writable,
            )
            var moreOpen by remember { mutableStateOf(false) }
            Box {
                DbTopBarIcon(
                    iconRes = R.drawable.ic_dots_vertical,
                    contentDescription = stringResource(R.string.db_more),
                    onClick = { moreOpen = true },
                    enabled = writable,
                )
                DropdownMenu(
                    expanded = moreOpen,
                    onDismissRequest = { moreOpen = false },
                    containerColor = palette.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    shape = AppCorner.Control,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.db_delete_all), color = palette.error)
                        },
                        onClick = {
                            moreOpen = false
                            showDeleteAll = true
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DbStateView(state = listState, onRetry = vm::load) { data ->
                if (busy) {
                    DbBusyIndicator(stringResource(R.string.db_working))
                }
                data.quota?.let {
                    DbQuotaCard(
                        label = stringResource(R.string.db_backup_quota_label),
                        quota = it,
                        defaultUnit = stringResource(R.string.db_unit_times),
                    )
                }
                data.sqlSpace?.let {
                    DbQuotaCard(
                        label = stringResource(R.string.db_space_quota_label),
                        quota = it,
                        defaultUnit = "MB",
                    )
                }
                val db = data.list.firstOrNull()
                if (db == null) {
                    EmptyState(
                        iconRes = R.drawable.ic_database,
                        title = stringResource(R.string.db_empty_title),
                        description = stringResource(R.string.db_empty_desc),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    DbItemCard(
                        item = db,
                        enabled = writable,
                        onAccess = { accessTarget = db },
                        onDownloadBackup = vm::downloadBackup,
                        onRestoreBackup = { dbName, backupName -> restoreTarget = dbName to backupName },
                        onDeleteBackup = { deleteBackupTarget = it },
                    )
                }
            }
        }
    }

    accessTarget?.let { item ->
        DbAccessDialog(
            item = item,
            onDismiss = { accessTarget = null },
            onApply = { allowRemote ->
                accessTarget = null
                vm.setAccess(allowRemote)
            },
        )
    }

    deleteBackupTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteBackupTarget = null },
            shape = DbDialogShape,
            containerColor = palette.surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.db_delete_backup_title)) },
            text = { Text(stringResource(R.string.db_delete_backup_desc, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteBackupTarget = null
                    vm.deleteBackup(name)
                }) {
                    Text(stringResource(R.string.db_delete_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBackupTarget = null }) {
                    Text(stringResource(R.string.db_cancel), color = palette.textSecondary)
                }
            },
        )
    }

    if (showDeleteAll) {
        DbDeleteAllDialog(
            onDismiss = { showDeleteAll = false },
            onConfirm = {
                showDeleteAll = false
                vm.deleteAll()
            },
        )
    }

    restoreTarget?.let { (dbName, backupName) ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            shape = DbDialogShape,
            containerColor = palette.surface,
            tonalElevation = 0.dp,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = LocalAppPalette.current.error,
                )
            },
            title = { Text(stringResource(R.string.db_restore_dialog_title)) },
            text = { Text(stringResource(R.string.db_restore_dialog_desc, backupName, dbName)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    vm.restoreBackup(dbName, backupName)
                }) {
                    Text(stringResource(R.string.db_restore_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text(stringResource(R.string.db_cancel), color = palette.textSecondary)
                }
            },
        )
    }
}

// ------------------------------------------------------------------ 单库卡片

/** 配额卡：细进度条 + 一行数值。max<=0 走「不限」，不画进度条（没有分母）。 */
@Composable
private fun DbQuotaCard(label: String, quota: DbQuota, defaultUnit: String) {
    val palette = LocalAppPalette.current
    val unit = quota.unit.ifBlank { defaultUnit }
    val unlimited = quota.max <= 0.0
    AppCard {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = palette.textPrimary,
        )
        if (!unlimited) {
            LinearProgressIndicator(
                progress = { (quota.used / quota.max).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(4.dp),
                color = palette.primary,
                trackColor = palette.background,
            )
        }
        Text(
            text = if (unlimited) {
                stringResource(R.string.db_quota_value_unlimited, formatNum(quota.used), unit)
            } else {
                stringResource(R.string.db_quota_value, formatNum(quota.used), formatNum(quota.max), unit)
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DbItemCard(
    item: DbItem,
    enabled: Boolean,
    onAccess: () -> Unit,
    onDownloadBackup: (String) -> Unit,
    onRestoreBackup: (String, String) -> Unit,
    onDeleteBackup: (String) -> Unit,
) {
    val palette = LocalAppPalette.current
    var expanded by remember { mutableStateOf(false) }

    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.RowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = dbStateColor(item.state))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.db_username_value, item.username),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onAccess, enabled = enabled) {
                Text(stringResource(R.string.db_access), color = palette.primary)
            }
            TextButton(
                onClick = { expanded = !expanded },
                enabled = enabled,
            ) {
                Text(
                    text = stringResource(R.string.db_backups_count, item.backups.size),
                    color = if (expanded) palette.primary else palette.textPrimary,
                )
            }
        }

        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)

        Text(
            text = stringResource(
                R.string.db_item_meta,
                item.charset.ifBlank { "—" },
                dbStateLabel(item.state),
                item.addtime.ifBlank { "—" },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (expanded) {
            if (item.backups.isEmpty()) {
                Text(
                    text = stringResource(R.string.db_backup_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPlaceholder,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                item.backups.forEach { backup ->
                    HorizontalDivider(
                        color = palette.outline,
                        thickness = AppDimens.Hairline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    DbBackupRow(
                        backup = backup,
                        enabled = enabled,
                        onDownload = { onDownloadBackup(backup.name) },
                        onRestore = { onRestoreBackup(item.name, backup.name) },
                        onDelete = { onDeleteBackup(backup.name) },
                    )
                }
            }
        }
    }
}

/** 备份行：下载 / 恢复常显，删除收进 ⋮（§8.3：一行不放三个以上按钮）。 */
@Composable
private fun DbBackupRow(
    backup: DbBackup,
    enabled: Boolean,
    onDownload: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeight)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = backup.name,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.db_backup_meta, formatBytes(backup.size), backup.time),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )
        }
        TextButton(onClick = onDownload, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.primary,
            )
            Text(
                text = stringResource(R.string.db_download),
                color = palette.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back_up),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.error,
            )
            Text(
                text = stringResource(R.string.db_restore),
                color = palette.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(enabled = enabled) { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_dots_vertical),
                    contentDescription = stringResource(R.string.db_more),
                    modifier = Modifier.size(18.dp),
                    tint = palette.textSecondary,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = palette.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                shape = AppCorner.Control,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.db_delete_backup), color = palette.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 对话框

/** 清空当前库的所有表：强确认 = 危险文案 + 勾选确认后才出现红色确认按钮。 */
@Composable
private fun DbDeleteAllDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DbDialogShape,
        containerColor = palette.surface,
        tonalElevation = 0.dp,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_alert_triangle),
                contentDescription = null,
                tint = palette.error,
            )
        },
        title = { Text(stringResource(R.string.db_delete_all_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.db_delete_all_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = palette.primary,
                            checkmarkColor = palette.onPrimary,
                            uncheckedColor = palette.textSecondary,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.db_delete_all_ack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            if (acknowledged) {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.db_delete_all_confirm), color = palette.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.db_cancel), color = palette.textSecondary)
            }
        },
    )
}

/**
 * 访问权限：本地/远程两态单选。没有端口输入框——面板改不了端口（契约 §10），
 * 传了也只是回显，与其放个假控件不如在文案里说明。选中态=蓝边蓝字白底（§8.3）。
 */
@Composable
private fun DbAccessDialog(
    item: DbItem,
    onDismiss: () -> Unit,
    onApply: (allowRemote: Boolean) -> Unit,
) {
    val palette = LocalAppPalette.current
    // db.list 不回当前访问设置，默认「仅本地」（面板初始值就是 127.0.0.1）
    var allowRemote by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DbDialogShape,
        containerColor = palette.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.db_access_dialog_title, item.name)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
            ) {
                DbOptionRow(
                    label = stringResource(R.string.db_access_local),
                    support = stringResource(R.string.db_access_local_desc),
                    selected = !allowRemote,
                    onClick = { allowRemote = false },
                )
                DbOptionRow(
                    label = stringResource(R.string.db_access_remote),
                    support = stringResource(R.string.db_access_remote_desc),
                    selected = allowRemote,
                    onClick = { allowRemote = true },
                )
                Text(
                    text = stringResource(R.string.db_access_port_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(allowRemote) }) {
                Text(stringResource(R.string.db_access_apply), color = palette.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.db_cancel), color = palette.textSecondary)
            }
        },
    )
}

// ------------------------------------------------------------------ 小工具

/** 状态色只用于点与文字（§8.3）：RUNNING=success，其余 warning，未知 placeholder。 */
@Composable
private fun dbStateColor(state: String): Color {
    val palette = LocalAppPalette.current
    return when {
        state.isBlank() -> palette.textPlaceholder
        state.equals("RUNNING", ignoreCase = true) -> palette.success
        else -> palette.warning
    }
}

@Composable
private fun dbStateLabel(state: String): String {
    val running = stringResource(R.string.db_state_running)
    return when {
        state.isBlank() -> stringResource(R.string.db_state_unknown)
        state.equals("RUNNING", ignoreCase = true) -> running
        else -> state
    }
}

private fun formatNum(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format("%.1f", value)
