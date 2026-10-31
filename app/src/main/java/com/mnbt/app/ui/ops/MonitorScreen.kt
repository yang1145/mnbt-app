package com.mnbt.app.ui.ops

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.MnbtApplication
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
import kotlinx.coroutines.launch

/**
 * 网站监控页（PRD §5.6 / §7.2「监控任务」）。
 * 列表：URL/周期/状态点/上次结果；写操作：新增编辑（monitor.save）、
 * 删除确认（monitor.delete）、启停（monitor.toggle）；日志（monitor.log）二级弹层，
 * 等宽滚动区。路由注册由主控统一做，本屏签名约定 hostId 走 entry。
 */

/** 表单草稿：id=null 表示新增 */
private data class MonitorDraft(
    val id: Int?,
    val name: String,
    val type: String,
    val target: String,
    val cycle: String,
)

/**
 * 资源监控的三类目标（契约 §10.1）：`type=resource` 时 `target` 存的就是这三个词之一，
 * 面板没有第四种资源口径，所以表单给选择器而不是自由输入。
 */
private val ResourceTargets = setOf("web", "sql", "traffic")

@StringRes
private fun resourceTargetLabelRes(target: String): Int = when (target) {
    "sql" -> R.string.ops_monitor_target_sql
    "traffic" -> R.string.ops_monitor_target_traffic
    else -> R.string.ops_monitor_target_web
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm: MonitorViewModel = mnbtViewModel { container, extras ->
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication
        MonitorViewModel(app, container, hostId)
    }
    val palette = LocalAppPalette.current
    val context = LocalContext.current

    val listState by vm.listState.collectAsStateWithLifecycle()
    val writePhase by vm.writePhase.collectAsStateWithLifecycle()
    val logEntry by vm.logState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    var draft by remember { mutableStateOf<MonitorDraft?>(null) }
    var deletingTask by remember { mutableStateOf<OpsMonitorTask?>(null) }

    // 写操作反馈：Done/Error 弹 Snackbar 后消费掉（Error 附 1008 的 retryAfter 文案）
    LaunchedEffect(writePhase) {
        when (val phase = writePhase) {
            is OpsWritePhase.Done -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeWritePhase()
            }

            is OpsWritePhase.Error -> {
                val text = if (phase.retryAfterSeconds != null) {
                    phase.message + "，" + context.getString(
                        R.string.ops_throttle_suffix,
                        phase.retryAfterSeconds,
                    )
                } else {
                    phase.message
                }
                snackbarHostState.showSnackbar(text)
                vm.consumeWritePhase()
            }

            else -> Unit
        }
    }

    val readyData = (listState as? LoadState.Ready)?.data

    OpsScaffold(
        title = stringResource(R.string.ops_monitor_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        actions = {
            OpsTopBarAction(
                iconRes = R.drawable.ic_refresh,
                description = stringResource(R.string.ops_refresh),
            ) { vm.load() }
            OpsTopBarAction(
                iconRes = R.drawable.ic_plus,
                description = stringResource(R.string.ops_monitor_add_action),
                enabled = readyData != null,
            ) {
                val data = readyData
                if (data != null) {
                    if (data.list.size >= data.limit.maxTasks) {
                        uiScope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.ops_monitor_task_max, data.limit.maxTasks),
                            )
                        }
                    } else {
                        draft = MonitorDraft(id = null, name = "", type = "url", target = "", cycle = "")
                    }
                }
            }
        },
    ) {
        OpsStateView(state = listState, onRetry = { vm.load() }) { data ->
            if (data.list.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.ic_list,
                    title = stringResource(R.string.ops_monitor_empty_title),
                    description = stringResource(R.string.ops_monitor_empty_desc),
                    primaryLabel = stringResource(R.string.ops_monitor_add_action),
                    onPrimaryClick = {
                        if (data.list.size >= data.limit.maxTasks) {
                            uiScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.ops_monitor_task_max, data.limit.maxTasks),
                                )
                            }
                        } else {
                            draft = MonitorDraft(null, "", "url", "", "")
                        }
                    },
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                OpsListCard {
                    data.list.forEachIndexed { index, task ->
                        MonitorTaskRow(
                            task = task,
                            busy = writePhase is OpsWritePhase.Saving,
                            onToggle = { vm.toggle(task) },
                            onEdit = {
                                draft = MonitorDraft(
                                    id = task.id,
                                    name = task.name,
                                    type = task.type,
                                    target = task.target,
                                    cycle = if (task.cycle > 0) task.cycle.toString() else "",
                                )
                            },
                            onDelete = { deletingTask = task },
                            onLogs = { vm.loadLog(task) },
                        )
                        if (index != data.list.lastIndex) OpsRowDivider()
                    }
                }
                OpsNoticeBar(
                    text = stringResource(
                        R.string.ops_monitor_limit_note,
                        data.limit.maxTasks,
                        data.limit.urlMinCycle,
                        data.limit.resourceCycle,
                    ),
                )
            }
        }
    }

    // 新增 / 编辑弹层
    draft?.let { current ->
        MonitorFormDialog(
            draft = current,
            limit = readyData?.limit ?: OpsMonitorLimit(),
            saving = writePhase is OpsWritePhase.Saving,
            onDismiss = { draft = null },
            onSave = { name, type, target, cycle ->
                vm.save(current.id, name, type, target, cycle)
                draft = null
            },
        )
    }

    // 删除确认
    deletingTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deletingTask = null },
            shape = AppCorner.Card,
            title = { Text(stringResource(R.string.ops_monitor_delete_title)) },
            text = { Text(stringResource(R.string.ops_monitor_delete_desc, task.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingTask = null
                    vm.delete(task.id)
                }) {
                    Text(
                        stringResource(R.string.ops_monitor_delete_confirm),
                        color = palette.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTask = null }) {
                    Text(stringResource(R.string.ops_cancel), color = palette.textSecondary)
                }
            },
        )
    }

    // 日志弹层（monitor.log）
    logEntry?.let { (taskName, logState) ->
        Dialog(onDismissRequest = { vm.closeLog() }) {
            AppCard {
                Text(
                    text = stringResource(R.string.ops_monitor_logs_title, taskName),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(12.dp))
                when (logState) {
                    LoadState.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = palette.primary, modifier = Modifier.size(28.dp)) }

                    is LoadState.Failed -> Column {
                        Text(
                            text = logState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                        )
                        TextButton(onClick = {
                            // 重试当前任务：logState 键里只有任务名，重新按名找任务并拉取
                            val task = (listState as? LoadState.Ready)?.data?.list
                                ?.firstOrNull { it.name == taskName }
                            if (task != null) vm.loadLog(task) else vm.closeLog()
                        }) {
                            Text(stringResource(R.string.ops_retry), color = palette.primary)
                        }
                    }

                    is LoadState.Ready -> {
                        val lines = remember(logState.data) {
                            if (logState.data.list.isEmpty()) {
                                ""
                            } else {
                                logState.data.list.joinToString("\n") { entry ->
                                    "${entry.time}  [${entry.code}]  ${entry.usedMs}ms  ${entry.status}  ${entry.target}"
                                }
                            }
                        }
                        OpsMonoBlock(text = lines)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.ops_monitor_logs_count, logState.data.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }

                    else -> vm.closeLog()
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { vm.closeLog() }) {
                        Text(stringResource(R.string.ops_close), color = palette.primary)
                    }
                }
            }
        }
    }
}

/** 任务行：状态点 + 名称 + 启停/更多两个常显动作；第二行长文本细横向滚动不截断。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorTaskRow(
    task: OpsMonitorTask,
    busy: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLogs: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeightWithSubtitle)
            .padding(horizontal = AppDimens.ScreenPadding, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (task.status == "on") palette.success else palette.textPlaceholder,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = task.name,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // 常显 2 个：启停 + 更多（§8.3 行内操作收敛）
            OpsInlineAction(
                iconRes = if (task.status == "on") R.drawable.ic_player_pause else R.drawable.ic_player_play,
                description = stringResource(
                    if (task.status == "on") R.string.ops_monitor_action_disable else R.string.ops_monitor_action_enable,
                ),
                enabled = !busy,
                onClick = onToggle,
            )
            Box {
                OpsInlineAction(
                    iconRes = R.drawable.ic_dots_vertical,
                    description = stringResource(R.string.ops_more),
                    enabled = !busy,
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.ops_monitor_action_logs), color = palette.textPrimary)
                        },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_list), null, tint = palette.textSecondary)
                        },
                        onClick = {
                            menuExpanded = false
                            onLogs()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.ops_monitor_action_edit), color = palette.textPrimary)
                        },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_pencil), null, tint = palette.textSecondary)
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.ops_monitor_action_delete), color = palette.error)
                        },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_trash), null, tint = palette.error)
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
        // 第二行：类型 · 周期 · URL · 上次结果，长文本横向滚动，禁止静默截断
        val typeLabel = stringResource(
            if (task.type == "resource") R.string.ops_monitor_type_resource else R.string.ops_monitor_type_url,
        )
        val intervalLabel = if (task.cycle > 0) {
            stringResource(R.string.ops_monitor_interval, task.cycle)
        } else {
            ""
        }
        val resultLabel = if (task.lastCode != null) {
            stringResource(R.string.ops_monitor_last_result, task.lastCode)
        } else {
            stringResource(R.string.ops_monitor_no_result)
        }
        val detail = buildString {
            append(typeLabel)
            if (intervalLabel.isNotEmpty()) {
                append(" · ")
                append(intervalLabel)
            }
            if (task.target.isNotBlank()) {
                append(" · ")
                // 资源任务的 target 是 web/sql/traffic 这类类别词，直接显示英文标识没法读
                append(
                    if (task.type == "resource" && task.target in ResourceTargets) {
                        stringResource(resourceTargetLabelRes(task.target))
                    } else {
                        task.target
                    },
                )
            }
            append(" · ")
            append(resultLabel)
            if (task.lastTime.isNotBlank()) {
                append(" (")
                append(task.lastTime)
                append(")")
            }
        }
        val lastOk = task.lastCode != null && task.lastCode in 200..399
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )
            if (task.lastCode != null) {
                Spacer(Modifier.width(6.dp))
                StatusDot(color = if (lastOk) palette.success else palette.error)
            }
        }
    }
}

/** 新增/编辑表单弹层：类型单选（蓝边蓝字白底）、URL 与周期校验按核心限制。 */
@Composable
private fun MonitorFormDialog(
    draft: MonitorDraft,
    limit: OpsMonitorLimit,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, type: String, target: String, cycle: Int) -> Unit,
) {
    val palette = LocalAppPalette.current
    val context = LocalContext.current
    var name by remember(draft) { mutableStateOf(draft.name) }
    var type by remember(draft) { mutableStateOf(draft.type) }
    var target by remember(draft) { mutableStateOf(draft.target) }
    var cycle by remember(draft) { mutableStateOf(draft.cycle) }
    var error by remember(draft) { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        AppCard {
            Text(
                text = stringResource(
                    if (draft.id == null) R.string.ops_monitor_add else R.string.ops_monitor_edit,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(12.dp))

            OpsOutlinedField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.ops_monitor_field_name),
                enabled = !saving,
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.ops_monitor_field_type),
                style = MaterialTheme.typography.labelLarge,
                color = palette.textPrimary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.CardGap)) {
                OpsOptionBox(
                    label = stringResource(R.string.ops_monitor_type_url),
                    selected = type == "url",
                    onClick = {
                        type = "url"
                        // 资源任务的 target 是类别词，切回 URL 时不能留在输入框里
                        if (target in ResourceTargets) target = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                OpsOptionBox(
                    label = stringResource(R.string.ops_monitor_type_resource),
                    selected = type == "resource",
                    onClick = {
                        type = "resource"
                        target = if (target in ResourceTargets) target else "web"
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (type == "url") {
                OpsOutlinedField(
                    value = target,
                    onValueChange = { target = it },
                    label = stringResource(R.string.ops_monitor_field_target),
                    placeholder = "https://",
                    supportText = stringResource(R.string.ops_monitor_target_hint),
                    enabled = !saving,
                )
                Spacer(Modifier.height(12.dp))
                OpsOutlinedField(
                    value = cycle,
                    onValueChange = { input -> cycle = input.filter { it.isDigit() }.take(6) },
                    label = stringResource(R.string.ops_monitor_field_cycle),
                    supportText = stringResource(R.string.ops_monitor_cycle_hint_url, limit.urlMinCycle),
                    keyboardType = KeyboardType.Number,
                    enabled = !saving,
                )
            } else {
                Text(
                    text = stringResource(R.string.ops_monitor_field_resource_target),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ResourceTargets.forEach { key ->
                    OpsOptionBox(
                        label = stringResource(resourceTargetLabelRes(key)),
                        selected = target == key,
                        onClick = { target = key },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.ops_monitor_resource_target_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPlaceholder,
                )
                Spacer(Modifier.height(12.dp))
                OpsOutlinedField(
                    value = limit.resourceCycle.toString(),
                    onValueChange = {},
                    label = stringResource(R.string.ops_monitor_field_cycle),
                    supportText = stringResource(R.string.ops_monitor_cycle_hint_resource, limit.resourceCycle),
                    enabled = false,
                )
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.error)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.CardGap, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.ops_cancel), color = palette.textSecondary)
                }
                Button(
                    onClick = {
                        val trimmed = name.trim()
                        val cycleValue = if (type == "resource") {
                            limit.resourceCycle
                        } else {
                            cycle.toIntOrNull() ?: 0
                        }
                        error = when {
                            trimmed.isEmpty() -> context.getString(R.string.ops_monitor_name_required)
                            type == "url" && target.isBlank() ->
                                context.getString(R.string.ops_monitor_target_required)
                            type == "url" && !target.startsWith("http://") && !target.startsWith("https://") ->
                                context.getString(R.string.ops_monitor_target_invalid)
                            type == "url" && cycleValue < limit.urlMinCycle ->
                                context.getString(R.string.ops_monitor_cycle_invalid, limit.urlMinCycle)
                            type == "resource" && target !in ResourceTargets ->
                                context.getString(R.string.ops_monitor_target_required)
                            else -> null
                        }
                        if (error == null) onSave(trimmed, type, target.trim(), cycleValue)
                    },
                    enabled = !saving,
                    shape = AppCorner.Control,
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
                        Text(stringResource(R.string.ops_monitor_form_save), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
