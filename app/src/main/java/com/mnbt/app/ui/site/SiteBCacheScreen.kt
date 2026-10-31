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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.MnbtApplication
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sheet 打开态：null=关闭；rule=null 表示新增，否则编辑该条（记住它的原后缀）。 */
private data class SiteBCacheSheetTarget(val rule: SiteBCacheRule?)

/**
 * 缓存规则 CRUD（PRD §5.2 site.cache.*，口径见契约 §6.2）。
 * 行内常显操作按 §8.3「≤2 个高频」取 2 个：删除（ic_trash 常显）+ 编辑（整行点击），
 * 不再提供 ⋮ 更多菜单 —— 该列表只有这两个动作，收进 ⋮ 反而多一步。
 *
 * 一条规则 = 「文件后缀 + 缓存时长」，不是路径：宝塔没有缓存规则主键，
 * 列表里的 id 只是按出现顺序排的序号，网页面板一改顺序旧 id 就会指到别的规则。
 * 所以改删前先重取一次 site.cache.list 拿最新 id，并把 suffix/old_suffix 一并提交
 * （插件优先按后缀解析，id 只兜底）；id 也从不落库。
 * type 由后缀推导，APP 不再当输入项。
 *
 * 入口签名：CacheRulesScreen(hostId, onBack, onRepair)
 */
@Composable
fun CacheRulesScreen(
    hostId: Long,
    onBack: () -> Unit = {},
    onRepair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm = mnbtViewModel { container, extras ->
        SiteBCacheViewModel(
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

    var sheetTarget by remember { mutableStateOf<SiteBCacheSheetTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<SiteBCacheRule?>(null) }

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
                title = stringResource(R.string.site_b_cache_title),
                onBack = onBack,
                actionLabel = stringResource(R.string.site_b_add),
                onAction = { if (!readOnly) sheetTarget = SiteBCacheSheetTarget(null) },
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
            ) { list ->
                if (list.list.isEmpty()) {
                    EmptyState(
                        iconRes = R.drawable.ic_list,
                        title = stringResource(R.string.site_b_cache_empty_title),
                        description = stringResource(R.string.site_b_cache_empty_desc),
                        primaryLabel = stringResource(R.string.site_b_cache_add_first),
                        onPrimaryClick = { if (!readOnly) sheetTarget = SiteBCacheSheetTarget(null) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(list.list) { index, rule ->
                            SiteBCacheRow(
                                rule = rule,
                                enabled = !readOnly,
                                onEdit = { sheetTarget = SiteBCacheSheetTarget(rule) },
                                onDelete = { pendingDelete = rule },
                            )
                            if (index < list.list.lastIndex) SiteBRowDivider()
                        }
                    }
                }
            }
        }
    }

    sheetTarget?.let { target ->
        val rule = target.rule
        SiteBCacheSheet(
            isEdit = rule != null,
            initial = rule,
            busy = busy,
            onDismiss = { sheetTarget = null },
            onSave = { suffix, seconds ->
                if (rule == null) {
                    vm.add(suffix, seconds) { sheetTarget = null }
                } else {
                    vm.edit(rule.url, suffix, seconds) { sheetTarget = null }
                }
            },
        )
    }

    pendingDelete?.let { rule ->
        SiteBConfirmDialog(
            title = stringResource(R.string.site_b_cache_delete_title),
            message = stringResource(R.string.site_b_cache_delete_message, rule.url),
            confirmLabel = stringResource(R.string.site_b_delete),
            onConfirm = {
                pendingDelete = null
                vm.delete(rule)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SiteBCacheRow(
    rule: SiteBCacheRule,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeightWithSubtitle)
            .siteBRowFeedback(if (enabled) onEdit else null, palette),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = rule.url,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = siteBCacheTypeLabel(rule.type),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )
        }
        // 右侧秒数：文字承载状态，不铺色块（§8.3）
        Text(
            text = stringResource(R.string.site_b_cache_seconds, rule.seconds),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.site_b_delete),
                tint = if (enabled) palette.textSecondary else palette.textPlaceholder,
            )
        }
    }
}

/** 类型只是后缀的推导结果（服务端算的），列表行用它做副标题；表单里不再当输入项。 */
@Composable
private fun siteBCacheTypeLabel(code: String): String = when (code) {
    SiteBCacheType.Page -> stringResource(R.string.site_b_cache_type_page)
    SiteBCacheType.Image -> stringResource(R.string.site_b_cache_type_image)
    SiteBCacheType.File -> stringResource(R.string.site_b_cache_type_file)
    else -> code // 真实 bridge 返回未知枚举时原样展示，不吞数据
}

@Composable
private fun SiteBCacheSheet(
    isEdit: Boolean,
    initial: SiteBCacheRule?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (suffix: String, seconds: Int) -> Unit,
) {
    var suffix by remember { mutableStateOf(initial?.url ?: "") }
    var seconds by remember { mutableStateOf(initial?.seconds?.toString() ?: "") }
    var submitted by remember { mutableStateOf(false) }

    val trimmedSuffix = suffix.trim()
    val secondsValue = seconds.trim().toIntOrNull()
    val suffixError = when {
        submitted && trimmedSuffix.isEmpty() -> stringResource(R.string.site_b_err_suffix_required)
        submitted && trimmedSuffix.contains("/") -> stringResource(R.string.site_b_err_suffix_slash)
        else -> null
    }
    val secondsError = when {
        submitted && seconds.isBlank() -> stringResource(R.string.site_b_err_seconds_required)
        submitted && (secondsValue == null || secondsValue <= 0) ->
            stringResource(R.string.site_b_err_seconds_positive)
        else -> null
    }
    val valid = trimmedSuffix.isNotEmpty() && !trimmedSuffix.contains("/") &&
        secondsValue != null && secondsValue > 0

    SiteBSheet(onDismiss = onDismiss) {
        SiteBSheetTitle(
            stringResource(if (isEdit) R.string.site_b_cache_edit_title else R.string.site_b_cache_add_title),
        )
        SiteBTextField(
            value = suffix,
            onValueChange = { suffix = it },
            label = stringResource(R.string.site_b_cache_suffix_label),
            placeholder = stringResource(R.string.site_b_cache_suffix_placeholder),
            error = suffixError,
            enabled = !busy,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.site_b_cache_type_note),
            style = MaterialTheme.typography.bodySmall,
            color = LocalAppPalette.current.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        SiteBTextField(
            value = seconds,
            onValueChange = { seconds = it },
            label = stringResource(R.string.site_b_cache_seconds_label),
            placeholder = stringResource(R.string.site_b_cache_seconds_placeholder),
            error = secondsError,
            enabled = !busy,
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(24.dp))
        SiteBSubmitButton(
            label = stringResource(if (isEdit) R.string.site_b_save else R.string.site_b_add),
            enabled = true,
            busy = busy,
            onClick = {
                submitted = true
                val secondsInput = secondsValue ?: return@SiteBSubmitButton
                if (valid) onSave(trimmedSuffix, secondsInput)
            },
        )
    }
}

internal class SiteBCacheViewModel(
    container: AppContainer,
    hostId: Long,
    context: Context,
) : SiteBFeatureViewModel(container, hostId, context) {

    private val _state = MutableStateFlow<LoadState<SiteBCacheList>>(LoadState.Loading)
    val state: StateFlow<LoadState<SiteBCacheList>> = _state.asStateFlow()

    init {
        reload()
    }

    /** site.cache.list 无初始化 section，直接返回 list[{id,url,type,seconds,time_out}]。 */
    override fun reload() {
        viewModelScope.launch {
            load(_state, MnbtActions.SITE_CACHE_LIST) { decodePayload<SiteBCacheList>(it) }
        }
    }

    /** 新增：url 就是后缀，type 由服务端从后缀推导，不需要 APP 传。 */
    fun add(suffix: String, seconds: Int, onDone: suspend () -> Unit = {}) {
        write(
            MnbtActions.SITE_CACHE_ADD,
            mapOf("url" to suffix, "seconds" to seconds.toString()),
            R.string.site_b_cache_added,
        ) {
            onDone()
            reload()
        }
    }

    /**
     * 编辑：old_suffix=原后缀、url=新后缀。id 在写之前重取一次列表，
     * 拿不到（规则已被网页面板删掉/顺序变了）就放弃本次写入而不是硬写。
     */
    fun edit(oldSuffix: String, suffix: String, seconds: Int, onDone: suspend () -> Unit = {}) {
        writeWithPrepare(
            MnbtActions.SITE_CACHE_EDIT,
            R.string.site_b_cache_saved,
            R.string.site_b_cache_stale,
            afterSuccess = {
                onDone()
                reload()
            },
        ) {
            val fresh = latestRule(oldSuffix) ?: return@writeWithPrepare null
            mapOf(
                "id" to fresh.id.toString(),
                "url" to suffix,
                "old_suffix" to oldSuffix,
                "seconds" to seconds.toString(),
            )
        }
    }

    /** 删除：同上先取最新 id，并把 suffix 一起提交（插件按后缀优先）。 */
    fun delete(rule: SiteBCacheRule, onDone: suspend () -> Unit = {}) {
        writeWithPrepare(
            MnbtActions.SITE_CACHE_DEL,
            R.string.site_b_cache_deleted,
            R.string.site_b_cache_stale,
            afterSuccess = {
                onDone()
                reload()
            },
        ) {
            val fresh = latestRule(rule.url) ?: return@writeWithPrepare null
            mapOf("id" to fresh.id.toString(), "suffix" to rule.url)
        }
    }

    /** 缓存规则的真实主键是后缀；id 只是列表序号，每次都从服务端最新列表反查。 */
    private suspend fun latestRule(suffix: String): SiteBCacheRule? {
        val outcome = hostCall(container, hostId, MnbtActions.SITE_CACHE_LIST) {
            decodePayload<SiteBCacheList>(it)
        }
        val list = (outcome as? HostOutcome.Ready)?.data?.list ?: return null
        return list.firstOrNull { it.url == suffix }
    }
}
