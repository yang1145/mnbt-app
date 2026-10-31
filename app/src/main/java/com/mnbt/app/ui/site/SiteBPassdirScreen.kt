package com.mnbt.app.ui.site

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.MnbtApplication
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 密码访问目录（PRD §5.2 site.passdir.*）。
 * 初始化走 site.init&section=pass；行内仅常显「删除」，添加走 BottomSheet。
 *
 * 入口签名（AppRoot 接线时，P2-D 禁改 AppRoot，此处只声明）：
 *   composable(Destinations.hostFeature(F_PASSDIR, ...)) { PassdirScreen(hostId = ..., onBack = navBack, onRepair = { scan }) }
 */
@Composable
fun PassdirScreen(
    hostId: Long,
    onBack: () -> Unit = {},
    onRepair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm = mnbtViewModel { container, extras ->
        SiteBPassdirViewModel(
            container = container,
            hostId = hostId,
            context = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication,
        )
    }

    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val banner by vm.writeBanner.collectAsState()
    val repair by vm.repair.collectAsState()
    val palette = LocalAppPalette.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.toast.collect { snackbarHostState.showSnackbar(it) } }

    var showAddSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SiteBPassdirEntry?>(null) }
    val readOnly = repair || banner != null

    Scaffold(
        modifier = modifier,
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(AppDimens.ScreenPadding),
        ) {
            SiteBTopBar(
                title = stringResource(R.string.site_b_passdir_title),
                onBack = onBack,
                actionLabel = stringResource(R.string.site_b_add),
                onAction = { if (!readOnly) showAddSheet = true },
            )
            banner?.let {
                Spacer(Modifier.height(AppDimens.CardGap))
                SiteBBlockedBanner(it)
            }
            Spacer(Modifier.height(AppDimens.CardGap))

            SiteBStateBody(
                state = state,
                repair = repair,
                onRetry = { vm.reload() },
                onRepair = onRepair,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { init ->
                if (init.list.isEmpty()) {
                    EmptyState(
                        iconRes = R.drawable.ic_folder_lock,
                        title = stringResource(R.string.site_b_passdir_empty_title),
                        description = stringResource(R.string.site_b_passdir_empty_desc),
                        primaryLabel = stringResource(R.string.site_b_passdir_add_first),
                        onPrimaryClick = { if (!readOnly) showAddSheet = true },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(init.list) { index, entry ->
                            SiteBPassdirRow(
                                entry = entry,
                                enabled = !readOnly,
                                onDelete = { pendingDelete = entry },
                            )
                            if (index < init.list.lastIndex) SiteBRowDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        SiteBPassdirAddSheet(
            busy = busy,
            onDismiss = { showAddSheet = false },
            onSubmit = { dir, user, pass ->
                vm.add(dir, user, pass) { showAddSheet = false }
            },
        )
    }

    pendingDelete?.let { entry ->
        SiteBConfirmDialog(
            title = stringResource(R.string.site_b_passdir_delete_title),
            message = stringResource(R.string.site_b_passdir_delete_message, entry.dir),
            confirmLabel = stringResource(R.string.site_b_delete),
            onConfirm = {
                pendingDelete = null
                vm.delete(entry)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SiteBPassdirRow(
    entry: SiteBPassdirEntry,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeightWithSubtitle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = entry.dir,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.site_b_passdir_account, entry.user ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 行内只常显删除一个操作（§8.3），二次确认在调用方
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.site_b_delete),
                tint = if (enabled) palette.textSecondary else palette.textPlaceholder,
            )
        }
    }
}

@Composable
private fun SiteBPassdirAddSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (dir: String, user: String, pass: String) -> Unit,
) {
    var dir by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    val trimmedDir = dir.trim()
    val trimmedUser = user.trim()
    val dirError = when {
        submitted && trimmedDir.isEmpty() -> stringResource(R.string.site_b_err_dir_required)
        submitted && !trimmedDir.startsWith("/") -> stringResource(R.string.site_b_err_dir_slash)
        else -> null
    }
    val userError = if (submitted && trimmedUser.isEmpty()) stringResource(R.string.site_b_err_user_required) else null
    val passError = if (submitted && pass.isEmpty()) stringResource(R.string.site_b_err_pass_required) else null
    val valid = trimmedDir.startsWith("/") && trimmedUser.isNotEmpty() && pass.isNotEmpty()

    SiteBSheet(onDismiss = onDismiss) {
        SiteBSheetTitle(stringResource(R.string.site_b_passdir_add_title))
        SiteBTextField(
            value = dir,
            onValueChange = { dir = it },
            label = stringResource(R.string.site_b_passdir_dir_label),
            placeholder = stringResource(R.string.site_b_passdir_dir_placeholder),
            error = dirError,
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
        SiteBTextField(
            value = user,
            onValueChange = { user = it },
            label = stringResource(R.string.site_b_passdir_user_label),
            error = userError,
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
        SiteBPasswordField(
            value = pass,
            onValueChange = { pass = it },
            label = stringResource(R.string.site_b_passdir_pass_label),
            error = passError,
            enabled = !busy,
        )
        Spacer(Modifier.height(24.dp))
        SiteBSubmitButton(
            label = stringResource(R.string.site_b_passdir_add_confirm),
            enabled = true,
            busy = busy,
            onClick = {
                submitted = true
                if (valid) onSubmit(trimmedDir, trimmedUser, pass)
            },
        )
    }
}

internal class SiteBPassdirViewModel(
    container: AppContainer,
    hostId: Long,
    context: Context,
) : SiteBFeatureViewModel(container, hostId, context) {

    private val _state = MutableStateFlow<LoadState<SiteBPassdirInit>>(LoadState.Loading)
    val state: StateFlow<LoadState<SiteBPassdirInit>> = _state.asStateFlow()

    init {
        reload()
    }

    /** 初始化 = site.init section=pass（§5.2 表格「初始化数据」列）。 */
    override fun reload() {
        viewModelScope.launch {
            load(_state, MnbtActions.SITE_INIT, mapOf("section" to "pass")) {
                decodePayload<SiteBPassdirInit>(it)
            }
        }
    }

    /**
     * 参数名对照 mock dataFor('site.passdir.add')：读 user_dir / user，
     * 密码参数 mock 未读取 —— 按网页端 tjmmfw 惯例以 `pass` 上送，P5 需对齐（见报告）。
     */
    fun add(dir: String, user: String, pass: String, onAdded: suspend () -> Unit = {}) {
        write(
            MnbtActions.SITE_PASSDIR_ADD,
            mapOf("user_dir" to dir, "user" to user, "pass" to pass),
            R.string.site_b_passdir_added,
        ) {
            onAdded()
            reload()
        }
    }

    /** mock dataFor('site.passdir.del') 按 user_dir 删除。 */
    fun delete(entry: SiteBPassdirEntry) {
        write(
            MnbtActions.SITE_PASSDIR_DEL,
            mapOf("user_dir" to entry.dir),
            R.string.site_b_passdir_deleted,
        ) { reload() }
    }
}
