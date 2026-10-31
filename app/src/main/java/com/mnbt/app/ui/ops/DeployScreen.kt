package com.mnbt.app.ui.ops

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import coil.request.ImageRequest
import com.mnbt.app.MnbtApplication
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 一键部署页（PRD §5.7）。deploy.list 图标经 Coil：ImageLoader 由本主机专属
 * rawClient(pin) 构建（DeployViewModel 内按 hostId 持有，不动全局 loader），
 * 相对 src 按主机 baseUrl 补全。
 *
 * 契约 §10.3：deploy.form 只有 input（无 select、default 恒空当 placeholder 用），
 * deploy.run 是**破坏性**动作（先删光站点根目录再解压）且同步执行几十秒 ——
 * 因此这里做两步：先校验必填，再二次确认；执行期间弹层不可关、按钮不可连点。
 * 1008 限流的 retryAfter 文案仍显示在弹层内。
 */
@Composable
fun DeployScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm: DeployViewModel = mnbtViewModel { container, extras ->
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication
        DeployViewModel(app, container, hostId)
    }
    val palette = LocalAppPalette.current
    val context = LocalContext.current

    val listState by vm.listState.collectAsStateWithLifecycle()
    val formState by vm.formState.collectAsStateWithLifecycle()
    val fieldValues by vm.fieldValues.collectAsStateWithLifecycle()
    val runPhase by vm.runPhase.collectAsStateWithLifecycle()
    val iconLoader by vm.iconLoader.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    /** 二次确认状态：第一次点「部署」只做必填校验并进入确认，再点才真正发 deploy.run。 */
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(formState) {
        if (formState == null) confirming = false
    }

    // 提交成功：Snackbar 反馈并收起表单
    LaunchedEffect(runPhase) {
        val phase = runPhase
        if (phase is OpsWritePhase.Done) {
            snackbarHostState.showSnackbar(phase.message)
            vm.consumeRunPhase()
            vm.dismissForm()
        }
    }

    OpsScaffold(
        title = stringResource(R.string.ops_deploy_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        actions = {
            OpsTopBarAction(
                iconRes = R.drawable.ic_refresh,
                description = stringResource(R.string.ops_refresh),
                enabled = listState !is LoadState.Loading,
            ) { vm.load() }
        },
    ) {
        OpsStateView(state = listState, onRetry = { vm.load() }) { data ->
            if (data.list.isEmpty()) {
                EmptyState(
                    iconRes = R.drawable.ic_box,
                    title = stringResource(R.string.ops_deploy_empty_title),
                    description = stringResource(R.string.ops_deploy_empty_desc),
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                OpsListCard {
                    data.list.forEachIndexed { index, appItem ->
                        DeployAppRow(
                            appItem = appItem,
                            iconUrl = vm.iconUrl(appItem.src),
                            iconLoader = iconLoader,
                            onClick = { vm.openForm(appItem) },
                        )
                        if (index != data.list.lastIndex) OpsRowDivider()
                    }
                }
                OpsNoticeBar(
                    text = stringResource(R.string.ops_deploy_same_site_note),
                )
            }
        }
    }

    // 部署表单弹层（deploy.form / deploy.run）
    formState?.let { state ->
        // 执行期间不可关：deploy.run 同步跑几十秒，中途关弹层等于看不到结果就以为失败
        Dialog(onDismissRequest = {
            if (runPhase !is OpsWritePhase.Saving && state !is LoadState.Loading) {
                confirming = false
                vm.dismissForm()
            }
        }) {
            AppCard {
                when (state) {
                    LoadState.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = palette.primary) }

                    is LoadState.Failed -> Column {
                        Text(
                            text = stringResource(R.string.ops_deploy_form_load_failed),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = palette.error)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { vm.dismissForm() }) {
                                Text(stringResource(R.string.ops_close), color = palette.primary)
                            }
                        }
                    }

                    is LoadState.Ready -> DeployFormContent(
                        form = state.data,
                        values = fieldValues,
                        phase = runPhase,
                        confirming = confirming,
                        onValueChange = vm::setFieldValue,
                        onDismiss = {
                            confirming = false
                            vm.dismissForm()
                        },
                        onConfirmRun = {
                            // 第一步只做必填校验并进入确认；确认那一次才真的提交
                            if (confirming) {
                                vm.run()
                            } else if (vm.missingRequiredFields(state.data).isEmpty()) {
                                confirming = true
                            } else {
                                vm.run()
                            }
                        },
                        onAbortConfirm = { confirming = false },
                    )

                    else -> vm.dismissForm()
                }
            }
        }
    }
}

/** 应用行：40dp 描边图标 + 名称/版本 + 安装目录，行高 72dp。 */
@Composable
private fun DeployAppRow(
    appItem: OpsDeployApp,
    iconUrl: String,
    iconLoader: coil.ImageLoader?,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeightWithSubtitle)
            .clickable(onClick = onClick)
            .padding(horizontal = AppDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标容器：1px 描边圆形；loader/src 未就绪时用 ic_box 线框占位，不铺色块
        Box(
            modifier = Modifier
                .size(44.dp)
                .border(AppDimens.Hairline, palette.outline, CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (iconUrl.isNotBlank() && iconLoader != null) {
                // PRD §5.7：ImageRequest 走本主机专属 ImageLoader
                // （okHttpClient = rawClient(pin)，由 ViewModel 构建并持有）
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(iconUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader = iconLoader,
                    contentDescription = stringResource(R.string.ops_deploy_icon_alt, appItem.name),
                    placeholder = painterResource(R.drawable.ic_box),
                    error = painterResource(R.drawable.ic_box),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_box),
                    contentDescription = null,
                    tint = palette.textPlaceholder,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appItem.name + if (appItem.ver.isNotBlank()) "  v${appItem.ver}" else "",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                text = stringResource(R.string.ops_deploy_dir, appItem.dir.ifBlank { "/" }),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )
        }
        Text(
            text = stringResource(R.string.ops_deploy_action),
            style = MaterialTheme.typography.labelLarge,
            color = palette.primary,
        )
    }
}

/**
 * 表单内容：说明 + 参数逐项渲染。
 * 契约 §10.3：面板的部署表单只有文本/数字 input（`type` 恒 input、`options` 恒空），
 * 所以这里没有 select 分支；placeholder/required/max_length 直接取自服务端字段。
 */
@Composable
private fun DeployFormContent(
    form: OpsDeployFormData,
    values: Map<String, String>,
    phase: OpsWritePhase,
    confirming: Boolean,
    onValueChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onConfirmRun: () -> Unit,
    onAbortConfirm: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val saving = phase is OpsWritePhase.Saving

    Text(
        text = stringResource(R.string.ops_deploy_form_title, form.name),
        style = MaterialTheme.typography.titleMedium,
        color = palette.textPrimary,
    )
    Spacer(Modifier.height(8.dp))
    OpsNoticeBar(
        text = stringResource(R.string.ops_deploy_form_dir, form.createDir.ifBlank { "/" }),
        iconRes = R.drawable.ic_folder,
    )
    if (form.needDb) {
        Spacer(Modifier.height(6.dp))
        OpsNoticeBar(
            text = stringResource(R.string.ops_deploy_form_need_db),
            iconRes = R.drawable.ic_database,
        )
    }
    // deploy.run 会先清空站点根目录（契约 §10.3），这行警示必须常显
    Spacer(Modifier.height(6.dp))
    OpsNoticeBar(
        text = stringResource(R.string.ops_deploy_form_destructive),
        iconRes = R.drawable.ic_alert_triangle,
        accent = palette.error,
    )
    Spacer(Modifier.height(12.dp))

    form.fields.forEach { field ->
        OpsOutlinedField(
            value = values[field.key].orEmpty(),
            onValueChange = { input ->
                onValueChange(
                    field.key,
                    if (field.maxLength > 0) input.take(field.maxLength) else input,
                )
            },
            label = field.label.ifBlank { field.key } +
                if (field.required) stringResource(R.string.ops_deploy_field_required_mark) else "",
            placeholder = field.placeholder,
            enabled = !saving,
            keyboardType = if (field.key.contains("port")) KeyboardType.Number else KeyboardType.Text,
        )
        Spacer(Modifier.height(12.dp))
    }

    // 失败留在弹层内展示；1008 限流附「请 N 秒后重试」
    if (phase is OpsWritePhase.Error) {
        OpsNoticeBar(
            text = if (phase.retryAfterSeconds != null) {
                phase.message + "，" + stringResource(R.string.ops_throttle_suffix, phase.retryAfterSeconds)
            } else {
                phase.message
            },
            iconRes = R.drawable.ic_alert_triangle,
            accent = palette.error,
        )
        Spacer(Modifier.height(12.dp))
    }

    if (confirming && !saving) {
        OpsNoticeBar(
            text = stringResource(R.string.ops_deploy_confirm_desc),
            iconRes = R.drawable.ic_alert_triangle,
            accent = palette.error,
        )
        Spacer(Modifier.height(12.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.CardGap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = if (confirming && !saving) onAbortConfirm else onDismiss, enabled = !saving) {
            Text(
                text = stringResource(
                    if (confirming && !saving) R.string.ops_cancel else R.string.ops_close,
                ),
                color = palette.textSecondary,
            )
        }
        Button(
            onClick = onConfirmRun,
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
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ops_deploy_running), style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_player_play),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (confirming) R.string.ops_deploy_confirm_ok else R.string.ops_deploy_run,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
