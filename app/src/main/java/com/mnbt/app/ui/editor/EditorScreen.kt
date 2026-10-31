package com.mnbt.app.ui.editor

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.DarkPalette
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * P3 在线代码编辑器页（PRD §7.2 FILE_EDITOR）。
 *
 * 结构纪律：
 * - 内容/字节/dirty 全部在 [EditorViewModel]，AndroidView 里的 CodeEditor 只是视图，
 *   进程内切后台、View 重建都不丢内容（update 按 generation 差量 setText）。
 * - onRelease 必须 destroy 语言对象并 release 编辑器，TextMate grammar 本体留在
 *   [TextmateSupport] 的 Registry 里进程共享，不随视图销毁。
 *
 * path 参数注：route 由 Destinations.editorRoute 用 Uri.encode 生成，
 * NavHost 解析 query 时已自动解码一次，这里直接 getString 取值（与 trustQueryBase 同规约）。
 */
@Composable
fun EditorScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    // NavType.StringType 已对 query 值解码；再 Uri.decode 会把路径里合法的 %2F 吃掉。
    val path = entry.arguments?.getString(Destinations.ARG_PATH).orEmpty()
    val vm = mnbtViewModel { c, _ -> EditorViewModel(c, hostId, path) }

    val state by vm.state.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val phase by vm.savePhase.collectAsStateWithLifecycle()
    val blockedBanner by vm.blockedBanner.collectAsStateWithLifecycle()
    val awaitBig by vm.awaitBigConfirm.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val palette = LocalAppPalette.current
    val isDark = palette === DarkPalette
    val snackbarHostState = remember { SnackbarHostState() }

    val textmateReady by TextmateSupport.ready.collectAsState()
    val textmateFailure by TextmateSupport.failure.collectAsState()
    LaunchedEffect(Unit) {
        TextmateSupport.ensureInitialized(context.applicationContext)
    }

    var showDiscard by remember { mutableStateOf(false) }
    var charsetPending by remember { mutableStateOf<FileCharset?>(null) }

    // ---- 保存反馈：Snackbar；Protocol 错附 raw 前 200 字（PRD §4.6 1009 透传） ----
    val savedMsg = stringResource(R.string.editor_saved)
    val rawLabel = stringResource(R.string.editor_protocol_raw_prefix)
    LaunchedEffect(phase) {
        when (val p = phase) {
            EditorSavePhase.Done -> {
                snackbarHostState.showSnackbar(savedMsg)
                vm.consumeSavePhase()
            }
            is EditorSavePhase.Error -> {
                val suffix = p.raw?.take(200)?.let { "\n$rawLabel $it" }.orEmpty()
                snackbarHostState.showSnackbar("${p.message}$suffix")
                vm.consumeSavePhase()
            }
            is EditorSavePhase.Blocked -> {
                snackbarHostState.showSnackbar(p.message)
                vm.consumeSavePhase()
            }
            else -> Unit
        }
    }

    // ---- 返回拦截：dirty 时二次确认 ----
    BackHandler(enabled = dirty && state is LoadState.Ready) {
        showDiscard = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            EditorTopBar(
                title = vm.fileName.ifBlank { stringResource(R.string.editor_title) },
                dirty = dirty,
                charsetLabel = (state as? LoadState.Ready)?.data?.charset?.label,
                canSave = dirty && state is LoadState.Ready && blockedBanner == null,
                saving = phase == EditorSavePhase.Saving,
                onBack = { navController.popBackStack() },
                onSave = vm::save,
                onCharsetClick = {
                    charsetPending =
                        if (vm.currentCharset == FileCharset.Gbk) FileCharset.Utf8 else FileCharset.Gbk
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                LoadState.Loading -> CenterBox {
                    CircularProgressIndicator(color = palette.primary)
                }

                is LoadState.Failed -> CenterMessage(
                    iconRes = R.drawable.ic_alert_triangle,
                    accent = palette.error,
                    title = stringResource(R.string.editor_load_failed),
                    detail = s.message + s.raw?.take(200)?.let { "\n$rawLabel $it" }.orEmpty(),
                    actionLabel = stringResource(R.string.editor_retry),
                    onAction = vm::load,
                )

                LoadState.TokenRejected, LoadState.CredentialMissing -> CenterMessage(
                    iconRes = R.drawable.ic_info_circle,
                    accent = palette.primary,
                    title = stringResource(R.string.host_detail_repair_title),
                    detail = stringResource(R.string.editor_repair_desc),
                    actionLabel = stringResource(R.string.editor_retry),
                    onAction = vm::load,
                )

                is LoadState.HostBlocked -> CenterMessage(
                    iconRes = R.drawable.ic_lock,
                    accent = palette.warning,
                    title = stringResource(R.string.editor_title),
                    detail = s.message,
                    actionLabel = null,
                    onAction = {},
                )

                is LoadState.Ready -> {
                    if (awaitBig) {
                        // 大文件护栏：read 已回内容，确认后才渲染编辑器（不另调 file.size）
                    } else {
                        EditorBody(
                            vm = vm,
                            doc = s.data,
                            isDark = isDark,
                            textmateReady = textmateReady,
                            textmateFailure = textmateFailure,
                            readOnly = blockedBanner != null,
                            blockedBanner = blockedBanner,
                            showUserIniNotice = vm.isUserIni,
                        )
                    }
                }
            }
        }
    }

    // ---- 大文件确认 ----
    if (awaitBig && state is LoadState.Ready) {
        val sizeMb = ((state as LoadState.Ready).data.sizeBytes + 1023) / 1024.0 / 1024.0
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.editor_bigfile_title)) },
            text = {
                Text(stringResource(R.string.editor_bigfile_desc, format1(sizeMb)))
            },
            confirmButton = {
                TextButton(onClick = vm::confirmBigFile) {
                    Text(stringResource(R.string.editor_bigfile_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { navController.popBackStack() }) {
                    Text(stringResource(R.string.editor_bigfile_cancel))
                }
            },
        )
    }

    // ---- 放弃修改确认 ----
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    navController.popBackStack()
                }) {
                    Text(
                        text = stringResource(R.string.editor_discard_giveup),
                        color = palette.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.editor_discard_keep))
                }
            },
        )
    }

    // ---- 编码切换确认（脏态给警告，干净态说明可安全切换） ----
    charsetPending?.let { target ->
        val dirtyNow = dirty
        AlertDialog(
            onDismissRequest = { charsetPending = null },
            title = { Text(stringResource(R.string.editor_charset_title, target.label)) },
            text = {
                Text(
                    if (dirtyNow) stringResource(R.string.editor_charset_desc)
                    else stringResource(R.string.editor_charset_clean_desc, target.label)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.switchCharset(target)
                    charsetPending = null
                }) {
                    Text(stringResource(R.string.editor_charset_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { charsetPending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    dirty: Boolean,
    charsetLabel: String?,
    canSave: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onCharsetClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .height(56.dp),
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
                contentDescription = stringResource(R.string.editor_back),
                tint = palette.textPrimary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        if (dirty) {
            StatusDot(color = palette.warning, modifier = Modifier.padding(end = 8.dp))
        }
        if (charsetLabel != null) {
            TextButton(onClick = onCharsetClick) {
                Text(
                    text = charsetLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }
        TextButton(onClick = onSave, enabled = canSave && !saving) {
            Text(
                text = if (saving) stringResource(R.string.editor_saving)
                else stringResource(R.string.action_save),
                style = MaterialTheme.typography.labelLarge,
                color = if (canSave && !saving) palette.primary else palette.textPlaceholder,
            )
        }
    }
}

@Composable
private fun EditorBody(
    vm: EditorViewModel,
    doc: EditorDoc,
    isDark: Boolean,
    textmateReady: Boolean,
    textmateFailure: String?,
    readOnly: Boolean,
    blockedBanner: String?,
    showUserIniNotice: Boolean,
) {
    val palette = LocalAppPalette.current
    val holder = remember { EditorViewHolder() }

    // 主题跟随色板：dark→mnbt-dark。TextMateColorScheme 需重建才会重绘配色。
    // 这里必须兜 Throwable：setTheme/TextMateColorScheme.create 走的是 tm4e，
    // 版本错位时抛的是 NoSuchMethodError 这类 Error，不兜就是进编辑器直接闪退；
    // 配色失败只该丢掉高亮，不该丢掉编辑能力。
    LaunchedEffect(isDark, textmateReady) {
        if (!textmateReady) return@LaunchedEffect
        runCatching {
            TextmateSupport.applyTheme(isDark)
            holder.editor?.colorScheme = TextmateSupport.createColorScheme()
        }.onFailure {
            holder.languageInitFailed = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { holder.destroyLanguage() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (blockedBanner != null) {
            NoticeBar(
                text = stringResource(R.string.editor_save_blocked_readonly) + "\n" + blockedBanner,
                iconRes = R.drawable.ic_lock,
                accent = palette.warning,
            )
        }
        if (textmateFailure != null) {
            NoticeBar(
                text = stringResource(R.string.editor_grammar_failed),
                iconRes = R.drawable.ic_alert_triangle,
                accent = palette.warning,
            )
        }
        if (showUserIniNotice) {
            NoticeBar(
                text = stringResource(R.string.editor_userini_notice),
                iconRes = R.drawable.ic_info_circle,
                accent = palette.textSecondary,
            )
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(palette.surface),
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    // 代码文件：不自动换行、横向滚动；行号显示为 sora 默认开启。
                    setWordwrap(false)
                    setHorizontalScrollBarEnabled(true)
                    setTypefaceText(Typeface.MONOSPACE)
                    setEditable(!readOnly)
                    holder.editor = this
                    // 0.22.2 没有 reified 的 subscribeAlways 扩展，用实例方法 subscribeEvent 等价订阅。
                    subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                        vm.onEditorTextChanged(text.toString())
                    }
                }
            },
            update = { editor ->
                editor.setEditable(!readOnly)
                if (textmateReady && holder.language == null && !holder.languageInitFailed) {
                    runCatching {
                        val lang = TextmateSupport.createLanguage()
                        editor.setEditorLanguage(lang)
                        holder.language = lang
                        TextmateSupport.applyTheme(isDark)
                        editor.colorScheme = TextmateSupport.createColorScheme()
                    }.onFailure {
                        holder.languageInitFailed = true
                    }
                }
                if (doc.generation != holder.appliedGeneration) {
                    editor.setText(doc.text)
                    holder.appliedGeneration = doc.generation
                    vm.onTextApplied(doc.text)
                }
            },
            onRelease = { editor ->
                // 内存纪律：视图走，语言对象与 native 缓冲一起走；grammar 留在 Registry。
                editor.setEditorLanguage(null)
                holder.destroyLanguage()
                holder.editor = null
                editor.release()
            },
        )
    }

    // 语法初始化失败在 update 里只试一次（holder.languageInitFailed），
    // 失败降级为纯文本编辑器，由顶部 NoticeBar 提示，不重试不崩溃。
}

private class EditorViewHolder {
    var editor: CodeEditor? = null
    var language: io.github.rosemoe.sora.langs.textmate.TextMateLanguage? = null
    var appliedGeneration: Int = -1
    var languageInitFailed: Boolean = false

    fun destroyLanguage() {
        language?.destroy()
        language = null
    }
}

@Composable
private fun NoticeBar(
    text: String,
    iconRes: Int,
    accent: Color,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = AppDimens.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = accent,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CenterMessage(
    iconRes: Int,
    accent: Color,
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = accent,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                Text(actionLabel, color = palette.primary)
            }
        }
    }
}

private fun format1(value: Double): String = String.format("%.1f", value)
