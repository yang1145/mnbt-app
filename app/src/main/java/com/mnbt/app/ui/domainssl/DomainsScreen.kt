package com.mnbt.app.ui.domainssl

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 域名管理页（PRD §5.5 domain.* / §7.2），功能对齐网页面板的域名页：
 * 列表（域名 / 端口 / 目录 / SSL）、A 记录提示、添加（可选目录绑定）、改前缀、删除。
 * 路由：Destinations.hostFeature(Destinations.F_DOMAINS, hostId)，注册由主控统一做。
 *
 * 主域名以服务端 `type=="main"` 为准（bridge 把主机主域固定排第一并打标），
 * 不再按「首条即主域」猜；主域不可删、也不可改前缀（网页端同样禁止）。
 */
@Composable
fun DomainsScreen(navController: NavController, entry: NavBackStackEntry) {
    val context = LocalContext.current
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { container, _ ->
        DomainsViewModel(container, hostId, context.applicationContext)
    }

    val state by vm.state.collectAsState()
    val opPhase by vm.opPhase.collectAsState()
    val readOnly by vm.readOnly.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DomainItemDto?>(null) }
    var prefixTarget by remember { mutableStateOf<DomainItemDto?>(null) }

    // 写操作结果统一走 Snackbar（Done/Error/Blocked 都提示一次后复位）
    LaunchedEffect(opPhase) {
        when (val phase = opPhase) {
            is OpPhase.Done -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeOpPhase()
            }
            is OpPhase.Error -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeOpPhase()
            }
            is OpPhase.Blocked -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeOpPhase()
            }
            else -> Unit
        }
    }

    DsslScaffold(
        title = stringResource(R.string.domains_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { vm.refresh() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.domains_refresh),
                    tint = LocalAppPalette.current.textSecondary,
                )
            }
            IconButton(onClick = { showAddDialog = true }, enabled = !readOnly) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.domains_add),
                    tint = LocalAppPalette.current.primary,
                )
            }
        },
    ) {
        DsslStateView(state = state, onRetry = { vm.refresh() }) { page ->
            if (readOnly) {
                DsslNotice(stringResource(R.string.domainssl_readonly_banner), warning = true)
            }
            // 与网页面板同源：als 不是 'false' 时整句用 als，否则提示把 A 记录指到节点 IP
            page.resolveHint?.let { DsslNotice(it) }
            if (page.items.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.ic_world,
                    title = stringResource(R.string.domains_empty_title),
                    description = stringResource(R.string.domains_empty_desc),
                    primaryLabel = stringResource(R.string.domains_add),
                    onPrimaryClick = { showAddDialog = true },
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else {
                if (page.limit > 0) {
                    DsslNotice(
                        stringResource(R.string.domains_quota_notice, page.total, page.limit),
                    )
                }
                DsslCard {
                    page.items.forEachIndexed { index, item ->
                        DomainRow(
                            item = item,
                            editable = !readOnly && !item.isMain,
                            deletable = !readOnly && !item.isMain,
                            onEdit = { prefixTarget = item },
                            onDelete = { deleteTarget = item },
                        )
                        if (index != page.items.lastIndex) DsslDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddDomainDialog(
            vm = vm,
            busy = opPhase is OpPhase.Working,
            onDismiss = { showAddDialog = false },
        )
    }

    prefixTarget?.let { target ->
        PrefixDialog(
            vm = vm,
            item = target,
            busy = opPhase is OpPhase.Working,
            onDismiss = { prefixTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = LocalAppPalette.current.surface,
            shape = AppCorner.Card,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = LocalAppPalette.current.error,
                )
            },
            title = { Text(stringResource(R.string.domains_delete_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.domains_delete_dialog_desc, target.domain),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        vm.deleteDomain(target)
                    },
                ) {
                    Text(
                        stringResource(R.string.domains_delete_confirm),
                        color = LocalAppPalette.current.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.domainssl_cancel))
                }
            },
        )
    }
}

/** 域名行：状态点 + 域名（超长给细横向滚动，不静默截断）+ 副信息 + 改前缀 / 删除。行高 56dp。 */
@Composable
private fun DomainRow(
    item: DomainItemDto,
    editable: Boolean,
    deletable: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val okStatus = item.status.equals("ok", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeight)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = if (okStatus) palette.success else palette.error)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = item.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(if (item.isMain) R.string.domains_main else R.string.domains_addon),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isMain) palette.primary else palette.textSecondary,
                )
                Text(
                    text = "${stringResource(R.string.domains_port_prefix)} ${item.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                // 目录列：'/' 就是整站绑定，与网页面板表格一致只在非整站时点出目录名
                if (!item.isTop) {
                    Text(
                        text = "${stringResource(R.string.domains_dir_prefix)} ${item.dir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }
                Text(
                    text = stringResource(if (item.ssl) R.string.domains_ssl_on else R.string.domains_ssl_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.ssl) palette.success else palette.textSecondary,
                )
                if (!okStatus) {
                    Text(
                        text = stringResource(R.string.domains_status_abnormal),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                    )
                }
            }
        }
        if (editable) {
            IconButton(onClick = onEdit) {
                Icon(
                    painter = painterResource(R.drawable.ic_pencil),
                    contentDescription = stringResource(R.string.domains_edit_prefix),
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (deletable) {
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.domains_delete),
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 添加域名弹窗：域名输入 + 绑定目录（打开时拉 domain.subdir.list），与网页端一样一次只提交一条。 */
@Composable
private fun AddDomainDialog(
    vm: DomainsViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val subdirs by vm.subdirs.collectAsState()

    LaunchedEffect(Unit) { vm.loadSubdirs() }

    var domainInput by remember { mutableStateOf("") }
    var selectedDir by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = palette.surface,
        shape = AppCorner.Card,
        title = { Text(stringResource(R.string.domains_add_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DsslTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = stringResource(R.string.domains_domain_label),
                    supportText = stringResource(R.string.domains_domain_support),
                )

                Text(
                    text = stringResource(R.string.domains_subdir_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textPrimary,
                )
                when (val s = subdirs) {
                    SubdirsState.NotLoaded, SubdirsState.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = palette.primary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.domains_subdir_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                    is SubdirsState.Error -> {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.error,
                        )
                        TextButton(onClick = { vm.loadSubdirs() }) {
                            Text(
                                stringResource(R.string.domains_subdir_retry),
                                color = palette.primary,
                            )
                        }
                    }
                    is SubdirsState.Loaded -> {
                        DsslOptionRow(
                            label = stringResource(R.string.domains_subdir_none),
                            selected = selectedDir == null,
                            onClick = { selectedDir = null },
                        )
                        if (s.options.isEmpty()) {
                            Text(
                                text = stringResource(R.string.domains_subdir_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textPlaceholder,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        } else {
                            s.options.forEach { dir ->
                                DsslOptionRow(
                                    label = dir,
                                    selected = selectedDir == dir,
                                    onClick = { selectedDir = dir },
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.domains_subdir_gap),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPlaceholder,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    vm.addDomain(domainInput, selectedDir)
                    if (!busy) onDismiss()
                },
                enabled = !busy && domainInput.isNotBlank(),
                shape = AppCorner.Control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.primary,
                    contentColor = palette.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.domains_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.domainssl_cancel), color = palette.textSecondary)
            }
        },
    )
}

/**
 * 改前缀弹窗：只改第一段标签，主域、端口、绑定目录都沿用原行。
 * 服务端实现是「删旧 + 加新」，所以文案要明说这是替换而不是改名。
 */
@Composable
private fun PrefixDialog(
    vm: DomainsViewModel,
    item: DomainItemDto,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val currentPrefix = item.domain.substringBefore('.', "")
    var newPrefix by remember(item.domain) { mutableStateOf(currentPrefix) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = palette.surface,
        shape = AppCorner.Card,
        title = { Text(stringResource(R.string.domains_prefix_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DsslTextField(
                    value = newPrefix,
                    onValueChange = { newPrefix = it },
                    label = stringResource(R.string.domains_prefix_label),
                    supportText = stringResource(R.string.domains_prefix_support),
                    placeholder = currentPrefix,
                )
                Text(
                    text = stringResource(R.string.domains_prefix_desc, item.domain),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                if (!item.isTop) {
                    Text(
                        text = "${stringResource(R.string.domains_dir_prefix)} ${item.dir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textPlaceholder,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    vm.setPrefix(item, newPrefix)
                    if (!busy) onDismiss()
                },
                enabled = !busy && newPrefix.isNotBlank() && newPrefix.trim() != currentPrefix,
                shape = AppCorner.Control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.primary,
                    contentColor = palette.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.domains_prefix_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.domainssl_cancel), color = palette.textSecondary)
            }
        },
    )
}

/* ------------------------------------------------------------------ */
/*  两页共用的 UI 基座（本组自包含，不依赖并行组文件；视觉规则 PRD §8.2/§8.3） */
/* ------------------------------------------------------------------ */

/** 自绘顶栏：surface 底 + 返回 + 20sp 标题 + 尾部动作，绝不铺蓝底。 */
@Composable
internal fun DsslScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
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
                    .height(AppDimens.RowHeight),
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
                        contentDescription = stringResource(R.string.domainssl_back),
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
                    maxLines = 1,
                )
                actions?.invoke(this)
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

/** LoadState 全分支渲染：Loading / Failed 重试 / TokenRejected / CredentialMissing / HostBlocked / Ready。 */
@Composable
internal fun <T> DsslStateView(
    state: LoadState<T>,
    onRetry: () -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val palette = LocalAppPalette.current
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.primary)
        }

        is LoadState.Failed -> DsslCenteredColumn {
            Icon(
                painter = painterResource(R.drawable.ic_alert_triangle),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = palette.error,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.domainssl_retry), color = palette.primary)
            }
        }

        LoadState.TokenRejected, LoadState.CredentialMissing -> DsslCenteredColumn {
            Icon(
                painter = painterResource(R.drawable.ic_info_circle),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = palette.primary,
            )
            Text(
                text = stringResource(R.string.domainssl_repair_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.domainssl_repair_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.domainssl_retry), color = palette.primary)
            }
        }

        is LoadState.HostBlocked -> DsslCenteredColumn {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = palette.warning,
            )
            Text(
                text = stringResource(R.string.domainssl_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
        }

        is LoadState.Ready -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = AppDimens.ScreenPadding,
                    vertical = AppDimens.CardGap,
                ),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        ) { content(state.data) }
    }
}

@Composable
private fun DsslCenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/** 白卡 + 1px 边无阴影（复用 components/AppCard 之外的竖向堆叠版）。 */
@Composable
internal fun DsslCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card)
            .padding(horizontal = AppDimens.CardPadding, vertical = 4.dp),
        content = content,
    )
}

@Composable
internal fun DsslDivider() {
    val palette = LocalAppPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppDimens.Hairline)
            .background(palette.outline),
    )
}

/** 页内提示条：1px 边白卡，状态色只用于图标与文字。 */
@Composable
internal fun DsslNotice(text: String, warning: Boolean = false) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Control)
            .background(palette.surface, AppCorner.Control)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(if (warning) R.drawable.ic_alert_triangle else R.drawable.ic_info_circle),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (warning) palette.warning else palette.textSecondary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (warning) palette.warning else palette.textSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 选中行：蓝边蓝字白底 + 勾；未选中灰边；切换不改字重（PRD §8.3）。 */
@Composable
internal fun DsslOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

/** 表单输入框：8dp 圆角、白底、语义边框色。 */
@Composable
internal fun DsslTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportText: String = "",
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    val palette = LocalAppPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, color = palette.textPlaceholder) }
        } else {
            null
        },
        supportingText = if (supportText.isNotEmpty()) {
            { Text(supportText, color = palette.textSecondary) }
        } else {
            null
        },
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
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
            disabledBorderColor = palette.outline,
            disabledLabelColor = palette.textPlaceholder,
        ),
    )
}

/** 主按钮：filled primary、8dp、等高 48dp；工作态转圈禁点。 */
@Composable
internal fun DsslPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    working: Boolean = false,
    errorTone: Boolean = false,
) {
    val palette = LocalAppPalette.current
    Button(
        onClick = onClick,
        enabled = enabled && !working,
        shape = AppCorner.Control,
        modifier = modifier.height(48.dp),
        colors = if (errorTone) {
            ButtonDefaults.buttonColors(
                containerColor = palette.error,
                contentColor = palette.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = palette.primary,
                contentColor = palette.onPrimary,
            )
        },
    ) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = if (errorTone) palette.onPrimary else palette.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 次按钮：白底蓝边蓝字（选中态同款配色，PRD §8.3）。 */
@Composable
internal fun DsslOutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalAppPalette.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppCorner.Control,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = palette.primary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
