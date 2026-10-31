@file:OptIn(ExperimentalMaterial3Api::class)

package com.mnbt.app.ui.filemanager

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.launch

/**
 * P3 文件管理屏（PRD §5.3 / §8.2 / §8.3 / §10.2）。
 * route：Destinations.hostFeature(Destinations.F_FILES, hostId) = "host/{hostId}/files"。
 *
 * 目录栈在 ViewModel 里（enterDir/goBackDir/openAbsolutePath），
 * 系统返回经 BackHandler 先退目录栈、栈空才退屏。
 */
@Composable
fun FileBrowserScreen(
    navController: NavController,
    entry: NavBackStackEntry,
    modifier: Modifier = Modifier,
) {
    val hostId = entry.requireHostId()
    val vm = mnbtViewModel { container, extras ->
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        FileBrowserViewModel(container, hostId, app.applicationContext)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val palette = LocalAppPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var inputDialog by remember { mutableStateOf<InputDialogSpec?>(null) }
    var accessEntry by remember { mutableStateOf<FileEntry?>(null) }
    var sizeEntry by remember { mutableStateOf<FileEntry?>(null) }
    var createSheet by remember { mutableStateOf(false) }
    var actionsEntry by remember { mutableStateOf<FileEntry?>(null) }

    // ---- 一次性结果消息（Snackbar，可重试操作带「重试」动作重跑整个流程）
    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            val actionLabel = if (msg.retryable) context.getString(R.string.files_retry_action) else null
            val result = snackbarHostState.showSnackbar(msg.text, actionLabel)
            if (result == SnackbarResult.ActionPerformed) vm.retryLast()
        }
    }

    // ---- 系统返回：先退目录栈，栈空才退屏
    BackHandler {
        if (!vm.goBackDir()) navController.popBackStack()
    }

    val navigateUp: () -> Unit = {
        if (!vm.goBackDir()) navController.popBackStack()
    }

    // ---- 上传选本地文件（ACTION_OPEN_DOCUMENT）
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.uploadFile(uri) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.multiSelect) {
                MultiSelectBar(
                    count = state.selection.size,
                    enabled = !state.busy,
                    onDelete = { scope.launch { vm.deleteSelected() } },
                    onCancel = { vm.exitMultiSelect() },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TopBar(
                busy = state.busy,
                readonly = state.readonlyReason != null,
                sort = state.sort,
                onNavigateUp = navigateUp,
                onToggleSort = { vm.setSort(if (state.sort == FileSort.NAME) FileSort.TIME else FileSort.NAME) },
                onCreate = { createSheet = true },
            )
            HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            BreadcrumbBar(path = state.path, onJump = { vm.openAbsolutePath(it) })
            state.upload?.let { UploadProgressLine(it) }
            state.readonlyReason?.let { BlockedBanner(it) }

            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = AppDimens.ScreenPadding, vertical = AppDimens.CardGap),
            ) {
                val ptrState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.list is LoadState.Loading,
                    onRefresh = { scope.launch { vm.refresh() } },
                    state = ptrState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FileListArea(
                        state = state,
                        onRetry = { scope.launch { vm.refresh() } },
                        onOpen = { e ->
                            when {
                                e.isDirectory -> vm.enterDir(e)
                                isTextFile(e.name) ->
                                    navController.navigate(Destinations.editorRoute(hostId, e.path))
                                else -> actionsEntry = e
                            }
                        },
                        onLongPress = { vm.enterMultiSelect(it) },
                        onToggleSelect = { vm.toggleSelect(it) },
                        onRename = { e ->
                            inputDialog = InputDialogSpec(
                                kind = DialogKind.RENAME,
                                titleRes = R.string.files_dialog_rename_title,
                                hintRes = R.string.files_input_name_hint,
                                entry = e,
                                initial = e.name,
                            )
                        },
                        onAskDelete = { e ->
                            inputDialog = InputDialogSpec(
                                kind = DialogKind.DELETE_CONFIRM,
                                titleRes = R.string.files_dialog_delete_title,
                                hintRes = 0,
                                entry = e,
                            )
                        },
                        onCopyTo = { e ->
                            inputDialog = InputDialogSpec(
                                kind = DialogKind.COPY_TO,
                                titleRes = R.string.files_dialog_copy_title,
                                hintRes = R.string.files_input_path_hint,
                                entry = e,
                                initial = e.path,
                            )
                        },
                        onCompressTo = { e ->
                            inputDialog = InputDialogSpec(
                                kind = DialogKind.COMPRESS_TO,
                                titleRes = R.string.files_dialog_compress_title,
                                hintRes = R.string.files_input_archive_hint,
                                entry = e,
                                initial = "archive.zip",
                            )
                        },
                        onUnzip = { e -> scope.launch { vm.unzip(e) } },
                        onAccess = { accessEntry = it },
                        onSize = { sizeEntry = it },
                    )
                }
            }
        }
    }

    // ---- 新建/上传 BottomSheet
    if (createSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { createSheet = false }, sheetState = sheetState) {
            SheetRow(R.drawable.ic_folder, stringResource(R.string.files_sheet_new_dir)) {
                createSheet = false
                inputDialog = InputDialogSpec(DialogKind.CREATE_DIR, R.string.files_dialog_create_dir_title, R.string.files_input_name_hint)
            }
            SheetRow(R.drawable.ic_pencil, stringResource(R.string.files_sheet_new_file)) {
                createSheet = false
                inputDialog = InputDialogSpec(DialogKind.CREATE_FILE, R.string.files_dialog_create_file_title, R.string.files_input_name_hint)
            }
            SheetRow(R.drawable.ic_arrow_left, stringResource(R.string.files_sheet_upload), iconRotation = 90f) {
                createSheet = false
                uploadPicker.launch(arrayOf("*/*"))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 非文本文件行点击的底部动作（下载/查看权限）
    actionsEntry?.let { target ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { actionsEntry = null }, sheetState = sheetState) {
            SheetRow(R.drawable.ic_arrow_left, stringResource(R.string.files_download), iconRotation = -90f) {
                actionsEntry = null
                scope.launch { vm.download(target) }
            }
            SheetRow(R.drawable.ic_lock, stringResource(R.string.files_view_access)) {
                actionsEntry = null
                accessEntry = target
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 对话框
    inputDialog?.let { spec ->
        InputDialog(
            spec = spec,
            onDismiss = { inputDialog = null },
            onConfirm = { text ->
                inputDialog = null
                scope.launch {
                    when (spec.kind) {
                        DialogKind.RENAME -> spec.entry?.let { vm.rename(it, text) }
                        DialogKind.DELETE_CONFIRM -> spec.entry?.let { vm.delete(it) }
                        DialogKind.COPY_TO -> spec.entry?.let { vm.copyTo(it, text) }
                        DialogKind.COMPRESS_TO -> spec.entry?.let { vm.compressTo(it, text) }
                        DialogKind.CREATE_DIR -> vm.create(text, "dir")
                        DialogKind.CREATE_FILE -> vm.create(text, "file")
                    }
                }
            },
        )
    }
    accessEntry?.let { target ->
        AccessDialog(
            entry = target,
            readonly = state.readonlyReason != null,
            busy = state.busy,
            onFetch = { vm.fetchAccess(it) },
            onApply = { path, perm, recurse -> vm.applyAccess(path, perm, recurse) },
            onDismiss = { accessEntry = null },
        )
    }
    sizeEntry?.let { target ->
        SizeDialog(entry = target, onFetch = { vm.fetchSize(it) }, onDismiss = { sizeEntry = null })
    }
}

// ---------------------------------------------------------------- 顶栏 / 面包屑

@Composable
private fun TopBar(
    busy: Boolean,
    readonly: Boolean,
    sort: FileSort,
    onNavigateUp: () -> Unit,
    onToggleSort: () -> Unit,
    onCreate: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateUp) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.files_nav_up),
                tint = palette.textSecondary,
            )
        }
        Text(
            text = stringResource(R.string.files_title),
            style = MaterialTheme.typography.titleLarge,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 排序小按钮：名称 / 时间 两种（目录在前两种排序都保持）
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = !busy) { onToggleSort() }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_list),
                contentDescription = stringResource(R.string.files_sort_toggle),
                modifier = Modifier.size(18.dp),
                tint = palette.primary,
            )
            Text(
                text = stringResource(
                    if (sort == FileSort.NAME) R.string.files_sort_name else R.string.files_sort_time,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = palette.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        IconButton(onClick = onCreate, enabled = !readonly) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = stringResource(R.string.files_new_or_upload),
                tint = if (readonly) palette.textPlaceholder else palette.textSecondary,
            )
        }
    }
}

/** 面包屑：当前路径全串、分段可点回跳；超长时细横向滚动，不截断（PRD §8.3）。 */
@Composable
private fun BreadcrumbBar(path: String, onJump: (String) -> Unit) {
    val palette = LocalAppPalette.current
    val scrollState = rememberScrollState()
    var viewportWidth by remember { mutableStateOf(0) }
    var contentWidth by remember { mutableStateOf(0) }
    val current by rememberUpdatedState(path)

    LaunchedEffect(current) { scrollState.scrollTo(scrollState.maxValue) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .horizontalScroll(scrollState)
                .padding(horizontal = AppDimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.onSizeChanged { contentWidth = it.width },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BreadcrumbSegment(
                    label = stringResource(R.string.files_root_label),
                    selected = path == "/",
                    onClick = { onJump("/") },
                )
                breadcrumbSegments(path).forEach { (label, target) ->
                    Text(
                        text = " / ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPlaceholder,
                    )
                    BreadcrumbSegment(label, selected = target == path) { onJump(target) }
                }
            }
        }
        BreadcrumbScrollbar(scrollState.value, viewportWidth, contentWidth, palette.primary, palette.outline)
    }
}

@Composable
private fun BreadcrumbSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) palette.textPrimary else palette.primary,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 2.dp),
    )
}

/** 细横向滚动指示条：thumb 宽度=视口/内容比，位置=滚动进度。仅内容超宽时出现。 */
@Composable
private fun BreadcrumbScrollbar(scrollPx: Int, viewportPx: Int, contentPx: Int, thumbColor: Color, trackColor: Color) {
    if (viewportPx <= 0 || contentPx <= viewportPx) return
    val fraction = viewportPx.toFloat() / contentPx
    val thumbW = (viewportPx * fraction).coerceAtLeast(24f)
    val trackRange = viewportPx - thumbW
    val scrollRange = (contentPx - viewportPx).coerceAtLeast(1)
    val thumbX = (scrollPx.toFloat() / scrollRange * trackRange).coerceIn(0f, trackRange)
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.ScreenPadding)
            .height(2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(trackColor.copy(alpha = 0.6f), CircleShape),
        )
        Box(
            Modifier
                .padding(start = with(density) { thumbX.toDp() })
                .width(with(density) { thumbW.toDp() })
                .height(2.dp)
                .background(thumbColor.copy(alpha = 0.4f), CircleShape),
        )
    }
}

@Composable
private fun UploadProgressLine(progress: UploadProgress) {
    val palette = LocalAppPalette.current
    Column(Modifier.padding(horizontal = AppDimens.ScreenPadding, vertical = 6.dp)) {
        Text(
            text = stringResource(
                R.string.files_upload_progress,
                progress.fileName,
                progress.doneChunks,
                progress.totalChunks,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = {
                if (progress.totalChunks <= 0) 0f
                else progress.doneChunks.toFloat() / progress.totalChunks
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(2.dp),
            color = palette.primary,
            trackColor = palette.outline,
        )
    }
}

/** 到期/封禁横幅（§4.6 1003/1004）：只读提示，写入口全部 disable。 */
@Composable
private fun BlockedBanner(reason: String) {
    val palette = LocalAppPalette.current
    Row(
        Modifier
            .padding(horizontal = AppDimens.ScreenPadding, vertical = AppDimens.CardGap)
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card)
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
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.warning,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ---------------------------------------------------------------- 列表

@Composable
private fun FileListArea(
    state: FileBrowserUiState,
    onRetry: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    onToggleSelect: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
    onAskDelete: (FileEntry) -> Unit,
    onCopyTo: (FileEntry) -> Unit,
    onCompressTo: (FileEntry) -> Unit,
    onUnzip: (FileEntry) -> Unit,
    onAccess: (FileEntry) -> Unit,
    onSize: (FileEntry) -> Unit,
) {
    val palette = LocalAppPalette.current
    when (val ls = state.list) {
        is LoadState.Ready -> {
            val entries = ls.data
            if (entries.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.ic_folder,
                    title = stringResource(R.string.files_empty_title),
                    description = stringResource(R.string.files_empty_desc),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
                        .background(palette.surface, AppCorner.Card),
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(entries, key = { _, e -> e.path }) { index, e ->
                            Column {
                                if (index > 0) {
                                    HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                                }
                                FileRow(
                                    entry = e,
                                    multiSelect = state.multiSelect,
                                    selected = e.path in state.selection,
                                    busy = state.busy,
                                    writeEnabled = state.readonlyReason == null && !state.busy,
                                    onClick = {
                                        if (state.multiSelect) onToggleSelect(e) else onOpen(e)
                                    },
                                    onLongPress = { onLongPress(e) },
                                    onRename = { onRename(e) },
                                    onDelete = { onAskDelete(e) },
                                    menu = { expanded, dismiss ->
                                        FileMoreMenu(
                                            expanded = expanded,
                                            onDismiss = dismiss,
                                            entry = e,
                                            writeEnabled = state.readonlyReason == null && !state.busy,
                                            onCopyTo = { onCopyTo(e) },
                                            onCompressTo = { onCompressTo(e) },
                                            onUnzip = { onUnzip(e) },
                                            onAccess = { onAccess(e) },
                                            onSize = { onSize(e) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.primary)
        }
        is LoadState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                iconRes = R.drawable.ic_info_circle,
                title = stringResource(R.string.files_load_failed_title),
                description = ls.message,
                primaryLabel = stringResource(R.string.files_retry_action),
                onPrimaryClick = onRetry,
            )
        }
        LoadState.TokenRejected, LoadState.CredentialMissing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                iconRes = R.drawable.ic_lock,
                title = stringResource(R.string.files_need_repair_title),
                description = stringResource(R.string.files_need_repair_desc),
            )
        }
        is LoadState.HostBlocked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                iconRes = R.drawable.ic_alert_triangle,
                title = stringResource(R.string.files_readonly_title),
                description = ls.message,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    multiSelect: Boolean,
    selected: Boolean,
    busy: Boolean,
    writeEnabled: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    menu: @Composable (expanded: Boolean, dismiss: () -> Unit) -> Unit,
) {
    val palette = LocalAppPalette.current
    var menuExpanded by remember(entry.path) { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // 多选态：行首 ic_check 切换（红线：不给行加蓝边/浅蓝底）
            multiSelect -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.files_selected_mark),
                        modifier = Modifier.size(22.dp),
                        tint = palette.primary,
                    )
                } else {
                    Box(
                        Modifier
                            .size(20.dp)
                            .border(1.dp, palette.textPlaceholder, AppCorner.Control),
                    )
                }
            }
            else -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(
                        if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = palette.textSecondary,
                )
            }
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 8.dp),
        )
        // 行右侧当前值（PRD §7.2「必须显示当前值」）：size + mtime。
        // 权限不在这里显示：file.list 的单层接口拿不到 perms（恒空串），要看权限走 ⋮ 的权限对话框
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (entry.isDirectory) "—" else humanReadableSize(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
            )
            Text(
                text = formatMtime(entry.mtime),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textPlaceholder,
                maxLines = 1,
            )
        }
        if (!multiSelect) {
            // 常显 2 个高频：重命名 / 删除；其余进 ⋮
            IconButton(onClick = onRename, enabled = writeEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_pencil),
                    contentDescription = stringResource(R.string.files_action_rename),
                    modifier = Modifier.size(20.dp),
                    tint = if (writeEnabled) palette.textSecondary else palette.textPlaceholder,
                )
            }
            IconButton(onClick = onDelete, enabled = writeEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.files_action_delete),
                    modifier = Modifier.size(20.dp),
                    tint = if (writeEnabled) palette.textSecondary else palette.textPlaceholder,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dots_vertical),
                        contentDescription = stringResource(R.string.files_action_more),
                        modifier = Modifier.size(20.dp),
                        tint = palette.textSecondary,
                    )
                }
                menu(menuExpanded) { menuExpanded = false }
            }
        }
    }
}

/**
 * ⋮ 更多：复制到… / 压缩为… / 解压 / 改权限 / 查看大小。
 * 解压只对压缩包扩展名出现；写类项在只读态 disable。
 */
@Composable
private fun FileMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entry: FileEntry,
    writeEnabled: Boolean,
    onCopyTo: () -> Unit,
    onCompressTo: () -> Unit,
    onUnzip: () -> Unit,
    onAccess: () -> Unit,
    onSize: () -> Unit,
) {
    val palette = LocalAppPalette.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_menu_copy_to)) },
            onClick = { onDismiss(); onCopyTo() },
            enabled = writeEnabled,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_menu_compress_to)) },
            onClick = { onDismiss(); onCompressTo() },
            enabled = writeEnabled,
        )
        if (isArchiveFile(entry.name)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_menu_unzip)) },
                onClick = { onDismiss(); onUnzip() },
                enabled = writeEnabled,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_menu_access)) },
            onClick = { onDismiss(); onAccess() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_menu_size)) },
            onClick = { onDismiss(); onSize() },
        )
        // 分隔视觉：菜单尾行浅色说明「改权限/查看大小为读优先」——不放实际内容，保持克制
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.path,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textPlaceholder,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------- 多选底栏 / Sheet 行

@Composable
private fun MultiSelectBar(count: Int, enabled: Boolean, onDelete: () -> Unit, onCancel: () -> Unit) {
    val palette = LocalAppPalette.current
    Column {
        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
        Row(
            Modifier
                .fillMaxWidth()
                .height(AppDimens.RowHeight)
                .background(palette.surface)
                .padding(horizontal = AppDimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.files_selected_n, count),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(
                    text = stringResource(R.string.files_batch_delete),
                    color = if (enabled) palette.error else palette.textPlaceholder,
                )
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.files_batch_cancel), color = palette.primary)
            }
        }
    }
}

@Composable
private fun SheetRow(@StringRes labelRes: Int, iconRes: Int, iconRotation: Float = 0f, onClick: () -> Unit) {
    SheetRow(iconRes, stringResource(labelRes), iconRotation, onClick)
}

@Composable
private fun SheetRow(iconRes: Int, label: String, iconRotation: Float = 0f, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = AppDimens.ScreenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(22.dp)
                .rotate(iconRotation),
            tint = palette.textSecondary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textPrimary,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

// ---------------------------------------------------------------- 对话框

private enum class DialogKind { RENAME, DELETE_CONFIRM, COPY_TO, COMPRESS_TO, CREATE_DIR, CREATE_FILE }

private data class InputDialogSpec(
    val kind: DialogKind,
    @StringRes val titleRes: Int,
    @StringRes val hintRes: Int,
    val entry: FileEntry? = null,
    val initial: String = "",
)

@Composable
private fun InputDialog(
    spec: InputDialogSpec,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val palette = LocalAppPalette.current
    var text by remember { mutableStateOf(spec.initial) }
    val isDelete = spec.kind == DialogKind.DELETE_CONFIRM
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppCorner.Dialog,
        containerColor = palette.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(spec.titleRes), style = MaterialTheme.typography.titleMedium) },
        text = {
            if (isDelete) {
                Text(
                    text = stringResource(R.string.files_dialog_delete_text, spec.entry?.path.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(spec.hintRes)) },
                    singleLine = spec.kind != DialogKind.COPY_TO,
                    shape = AppCorner.Control,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(
                    text = stringResource(
                        if (isDelete) R.string.files_dialog_delete_confirm else R.string.files_confirm,
                    ),
                    color = if (isDelete) palette.error else palette.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_cancel), color = palette.textSecondary)
            }
        },
    )
}

/** 改权限对话框：FILE_ACCESS_GET 回填 → 编辑 → FILE_ACCESS_SET（perm 八进制 + recurse）。 */
@Composable
private fun AccessDialog(
    entry: FileEntry,
    readonly: Boolean,
    busy: Boolean,
    onFetch: suspend (String) -> FileAccessPayload?,
    onApply: suspend (String, String, Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val scope = rememberCoroutineScope()
    var payload by remember(entry.path) { mutableStateOf<FileAccessPayload?>(null) }
    var perm by remember(entry.path) { mutableStateOf<String?>(null) }
    var recurse by remember(entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.path) {
        val fetched = onFetch(entry.path)
        if (fetched == null) {
            onDismiss()
        } else {
            payload = fetched
            perm = prettyPerms(fetched.perm)
        }
    }
    val data = payload ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppCorner.Dialog,
        containerColor = palette.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.files_dialog_access_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPlaceholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = perm ?: "",
                    onValueChange = { perm = it.filter { c -> c in '0'..'7' }.take(4) },
                    label = { Text(stringResource(R.string.files_perm_hint)) },
                    singleLine = true,
                    enabled = !readonly,
                    shape = AppCorner.Control,
                    colors = fieldColors(),
                )
                // 递归开关：行首 ic_check 切换（红线：选中态=蓝边蓝字，不铺蓝底）
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !readonly) { recurse = !recurse }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (recurse) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = palette.primary,
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .border(1.dp, palette.textPlaceholder, AppCorner.Control),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.files_perm_recurse),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (recurse) palette.primary else palette.textSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.files_owner_label, data.owner, data.group),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        if (onApply(entry.path, (perm ?: "").trim(), recurse)) onDismiss()
                    }
                },
                enabled = !readonly && !busy,
            ) {
                Text(stringResource(R.string.files_apply), color = palette.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_cancel), color = palette.textSecondary)
            }
        },
    )
}

/** FILE_SIZE 查看大小：服务端回字节数 + unit="B"，文案由 VM 换算好再传进来。 */
@Composable
private fun SizeDialog(
    entry: FileEntry,
    onFetch: suspend (FileEntry) -> String?,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var text by remember(entry.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.path) { text = onFetch(entry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppCorner.Dialog,
        containerColor = palette.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.files_dialog_size_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                text = text ?: stringResource(R.string.files_loading_size),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_dismiss), color = palette.primary)
            }
        },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalAppPalette.current.primary,
    unfocusedBorderColor = LocalAppPalette.current.outline,
    cursorColor = LocalAppPalette.current.primary,
    focusedLabelColor = LocalAppPalette.current.primary,
    unfocusedLabelColor = LocalAppPalette.current.textPlaceholder,
    focusedTextColor = LocalAppPalette.current.textPrimary,
    unfocusedTextColor = LocalAppPalette.current.textPrimary,
    disabledBorderColor = LocalAppPalette.current.outline,
    disabledTextColor = LocalAppPalette.current.textPlaceholder,
)
