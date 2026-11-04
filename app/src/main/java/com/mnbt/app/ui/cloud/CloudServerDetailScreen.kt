package com.mnbt.app.ui.cloud

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.domain.model.CloudFlowItem
import com.mnbt.app.domain.model.CloudPowerAction
import com.mnbt.app.domain.model.CloudServerDetail
import com.mnbt.app.domain.model.CloudSiteCapabilities
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 云服务器详情页（route：Destinations.CLOUD_SERVER_DETAIL，siteId/hostId 均 LongType）。
 *
 * 六个区块：状态卡（电源大字 + 手动刷新）、产品信息、登录凭据（密码打码 + 复制）、
 * 配置文本、流量用量（自绘细进度条）、电源操作按钮组 + VNC。
 * 区块显隐按站点能力协商（协议 §6）：credentials/config_text/flow/vnc/hard_power
 * 未声明的能力整块隐藏。
 * 电源操作：确认对话框（强制类危险色文案）→ 需要时先输操作密码 → 失败留在对话框
 * 可改密码重试，成功 toast「指令已下发」并轮询刷新状态卡。
 */
@Composable
fun CloudServerDetailScreen(
    navController: NavController,
    siteId: Long,
    hostId: Long,
    modifier: Modifier = Modifier,
) {
    val vm: CloudServerDetailViewModel = mnbtViewModel { container, _ ->
        CloudServerDetailViewModel(container, siteId, hostId)
    }
    val ui by vm.state.collectAsState()
    val powerOp by vm.powerOp.collectAsState()
    val refreshing by vm.isRefreshing.collectAsState()
    val vncUrl by vm.vncUrl.collectAsState()
    val toastMessage by vm.toastMessage.collectAsState()
    val context = LocalContext.current
    val palette = LocalAppPalette.current

    // 对话框状态：pendingAction 非空即弹；站点要求操作密码时带密码输入
    var pendingAction by remember { mutableStateOf<CloudPowerAction?>(null) }
    var passwordText by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vncUrl) {
        vncUrl?.let { url ->
            vm.consumeVnc()
            // url 由仓库层保证是绝对地址，直接交给系统浏览器
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.cloud_vnc_no_browser), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            vm.consumeToast()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(powerOp) {
        when (val op = powerOp) {
            is CloudPowerOp.Dispatched -> {
                pendingAction = null
                dialogError = null
                vm.consumePowerOp()
                Toast.makeText(context, context.getString(R.string.cloud_power_dispatched), Toast.LENGTH_SHORT).show()
            }
            is CloudPowerOp.Failed -> dialogError = op.message
            else -> Unit
        }
    }

    when (val s = ui) {
        is LoadState.Ready -> {
            val data = s.data
            Column(modifier = modifier.fillMaxSize().padding(horizontal = AppDimens.ScreenPadding)) {
                DetailHeader(
                    title = data.detail.summary.name,
                    onBack = { navController.popBackStack() },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SectionTitle(stringResource(R.string.cloud_detail_status_title))
                    StatusCard(
                        powerStatus = data.powerStatus,
                        powerDesc = data.powerDesc,
                        hostStatus = data.detail.summary.hostStatus,
                        refreshing = refreshing,
                        onRefresh = vm::refresh,
                    )

                    SectionTitle(stringResource(R.string.cloud_detail_info_title))
                    InfoCard(data.detail.summary.dueTimeEpochMs, data.detail)

                    if (data.capabilities.credentials) {
                        SectionTitle(stringResource(R.string.cloud_detail_credential_title))
                        CredentialCard(data.detail)
                    }

                    if (data.capabilities.configText && !data.detail.configText.isNullOrBlank()) {
                        SectionTitle(stringResource(R.string.cloud_detail_config_title))
                        AppCard {
                            Text(
                                text = data.detail.configText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textPrimary,
                            )
                        }
                    }

                    if (data.capabilities.flow) {
                        SectionTitle(stringResource(R.string.cloud_detail_flow_title))
                        FlowCard(data.flows)
                    }

                    SectionTitle(stringResource(R.string.cloud_detail_actions_title))
                    ActionsCard(
                        capabilities = data.capabilities,
                        onAction = { action ->
                            passwordText = ""
                            passwordVisible = false
                            dialogError = null
                            pendingAction = action
                        },
                        onVnc = vm::openVnc,
                    )

                    Spacer(modifier = Modifier.height(AppDimens.ScreenPadding))
                }
            }
        }

        is LoadState.Failed -> EmptyState(
            iconRes = R.drawable.ic_alert_triangle,
            title = stringResource(R.string.cloud_detail_load_failed),
            description = s.message,
            primaryLabel = stringResource(R.string.cloud_detail_retry),
            onPrimaryClick = vm::retry,
            modifier = modifier.fillMaxSize(),
        )

        else -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 首屏加载中：与主机详情一致，用刷新图标占位，不铺大转圈
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = palette.textPlaceholder,
            )
        }
    }

    pendingAction?.let { action ->
        PowerConfirmDialog(
            action = action,
            needPassword = vm.needsOperatePassword(),
            passwordText = passwordText,
            passwordVisible = passwordVisible,
            onPasswordChange = { passwordText = it },
            onTogglePassword = { passwordVisible = !passwordVisible },
            error = dialogError,
            working = powerOp is CloudPowerOp.Working,
            onConfirm = {
                dialogError = null
                vm.power(action, passwordText.takeIf { vm.needsOperatePassword() })
            },
            onDismiss = {
                pendingAction = null
                dialogError = null
                vm.consumePowerOp()
            },
        )
    }
}

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(AppDimens.RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.cloud_back),
                tint = palette.textSecondary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
    }
}

/** 区块标题：对齐主机详情分组标题（labelLarge 次要色，左侧 4dp）。 */
@Composable
private fun SectionTitle(text: String) {
    val palette = LocalAppPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = palette.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** ① 状态卡：电源状态大字（状态色）+ 主机状态小字 + 手动刷新按钮，刷新时卡片底部 2dp 细条。 */
@Composable
private fun StatusCard(
    powerStatus: String,
    powerDesc: String,
    hostStatus: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val tone = cloudPowerTone(powerStatus, palette)
    val bigLabel = powerDesc.ifBlank { cloudPowerLabel(powerStatus, powerDesc) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
            .background(palette.surface, AppCorner.Card)
            .padding(AppDimens.CardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bigLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    color = tone,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.cloud_field_host_status),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                    Text(
                        text = hostStatus.ifBlank { stringResource(R.string.value_dash) },
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.cloud_detail_refresh),
                    tint = palette.textSecondary,
                )
            }
        }

        if (refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp),
                color = palette.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** ② 信息卡：label 上 value 下的行叠放（长值不截断），行间 1px 分隔线。 */
@Composable
private fun InfoCard(dueTimeEpochMs: Long?, detail: CloudServerDetail) {
    val palette = LocalAppPalette.current
    val summary = detail.summary
    // 到期值颜色对齐小程序规则：≤7 天红色高亮，其余正常主文字色
    val dueColor = if (dueTimeEpochMs != null &&
        (dueTimeEpochMs - System.currentTimeMillis()) <= 7L * 86_400_000L
    ) {
        palette.error
    } else {
        palette.textPrimary
    }

    val rows = listOf(
        stringResource(R.string.cloud_field_product) to (summary.productName to palette.textPrimary),
        stringResource(R.string.cloud_field_main_ip) to (summary.dedicateIp to palette.textPrimary),
        stringResource(R.string.cloud_field_extra_ip) to (detail.assignIp to palette.textPrimary),
        stringResource(R.string.cloud_field_os) to (detail.os to palette.textPrimary),
        stringResource(R.string.cloud_field_due) to (dueTimeEpochMs?.let { formatDay(it) } to dueColor),
        stringResource(R.string.cloud_field_billing) to (summary.billingCycleName to palette.textPrimary),
        stringResource(R.string.cloud_field_active) to (summary.activeTimeEpochMs?.let { formatDay(it) } to palette.textPrimary),
    )

    AppCard {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
            }
            FieldLine(label = label, value = value.first, valueColor = value.second)
        }
    }
}

/** ③ 凭据卡：用户名/端口明文，密码打码 + 复制按钮（复制原文）。 */
@Composable
private fun CredentialCard(detail: CloudServerDetail) {
    val palette = LocalAppPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    AppCard {
        FieldLine(
            label = stringResource(R.string.cloud_field_username),
            value = detail.username,
            valueColor = palette.textPrimary,
        )
        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
        FieldLine(
            label = stringResource(R.string.cloud_field_port),
            value = detail.port?.toString(),
            valueColor = palette.textPrimary,
        )
        HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_field_password),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
                // 打码展示：密码本体只进剪贴板，不上屏（对齐凭据展示约定）
                Text(
                    text = detail.password?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.cloud_password_mask) }
                        ?: stringResource(R.string.value_dash),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!detail.password.isNullOrBlank()) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(detail.password))
                    Toast.makeText(context, context.getString(R.string.cloud_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.cloud_copy),
                        modifier = Modifier.size(18.dp),
                        tint = palette.textSecondary,
                    )
                }
            }
        }
    }
}

/** ⑤ 流量卡：名称 + 已用/总量 + 自绘 3dp 细进度条（≥95% 红、≥80% 橙，对齐用量条口径）。 */
@Composable
private fun FlowCard(flows: List<CloudFlowItem>?) {
    val palette = LocalAppPalette.current

    AppCard {
        val items = flows.orEmpty()
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.cloud_flow_empty),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPlaceholder,
            )
        } else {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = palette.outline, thickness = AppDimens.Hairline)
                }
                FlowLine(item)
            }
        }
    }
}

@Composable
private fun FlowLine(item: CloudFlowItem) {
    val palette = LocalAppPalette.current
    val total = item.total
    val unlimited = total == null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (total == null) {
                    stringResource(R.string.cloud_flow_unlimited)
                } else {
                    stringResource(
                        R.string.cloud_flow_usage,
                        formatAmount(item.used, item.unit),
                        formatAmount(total, item.unit),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (unlimited) palette.textSecondary else palette.textPrimary,
            )
        }
        val fraction = if (total != null && total > 0) (item.used / total).toFloat().coerceIn(0f, 1f) else 0f
        val tone = when {
            unlimited -> palette.textPlaceholder
            fraction >= 0.95f -> palette.error
            fraction >= 0.8f -> palette.warning
            else -> palette.success
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.outline),
        ) {
            if (!unlimited && fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tone),
                )
            }
        }
    }
}

/**
 * ⑥ 操作区：常规操作实心按钮，强制操作 1px 边 + 危险色文字。
 * 按站点能力组装（协议 §6）：hard_power 未声明隐藏两个强制按钮，vnc 未声明隐藏 VNC；
 * 可见按钮按每行两个自适应排布。
 */
@Composable
private fun ActionsCard(
    capabilities: CloudSiteCapabilities,
    onAction: (CloudPowerAction) -> Unit,
    onVnc: () -> Unit,
) {
    data class Entry(val label: String, val danger: Boolean, val onClick: () -> Unit)

    val entries = buildList {
        add(Entry(stringResource(R.string.cloud_action_power_on), false) { onAction(CloudPowerAction.ON) })
        add(Entry(stringResource(R.string.cloud_action_power_off), false) { onAction(CloudPowerAction.OFF) })
        add(Entry(stringResource(R.string.cloud_action_reboot), false) { onAction(CloudPowerAction.REBOOT) })
        if (capabilities.hardPower) {
            add(Entry(stringResource(R.string.cloud_action_hard_off), true) { onAction(CloudPowerAction.HARD_OFF) })
            add(Entry(stringResource(R.string.cloud_action_hard_reboot), true) {
                onAction(CloudPowerAction.HARD_REBOOT)
            })
        }
        if (capabilities.vnc) {
            add(Entry(stringResource(R.string.cloud_action_vnc), false, onClick = onVnc))
        }
    }

    AppCard {
        entries.chunked(2).forEachIndexed { rowIndex, rowEntries ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (rowIndex == 0) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowEntries.forEach { entry ->
                    ActionButton(
                        label = entry.label,
                        danger = entry.danger,
                        modifier = Modifier.weight(1f),
                        onClick = entry.onClick,
                    )
                }
                // 末行单按钮时补一个空位占宽，避免最后一个按钮被拉满整行
                repeat(2 - rowEntries.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 单个操作按钮：danger 走 1px 边 + error 文字，常规走实心主按钮；等高 44dp。 */
@Composable
private fun ActionButton(
    label: String,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    if (danger) {
        OutlinedButton(
            onClick = onClick,
            shape = AppCorner.Control,
            border = BorderStroke(AppDimens.Hairline, palette.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = palette.surface,
                contentColor = palette.error,
            ),
            modifier = modifier.height(44.dp),
        ) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Button(
            onClick = onClick,
            shape = AppCorner.Control,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.primary,
                contentColor = palette.onPrimary,
            ),
            modifier = modifier.height(44.dp),
        ) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 电源操作确认框：强制类用危险色文案；站点要求操作密码时带密码输入（记住本次会话，
 * 见 VM）；上一次失败的原因留在框内，允许改密码重试。
 */
@Composable
private fun PowerConfirmDialog(
    action: CloudPowerAction,
    needPassword: Boolean,
    passwordText: String,
    passwordVisible: Boolean,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    error: String?,
    working: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val label = when (action) {
        CloudPowerAction.ON -> stringResource(R.string.cloud_action_power_on)
        CloudPowerAction.OFF -> stringResource(R.string.cloud_action_power_off)
        CloudPowerAction.REBOOT -> stringResource(R.string.cloud_action_reboot)
        CloudPowerAction.HARD_OFF -> stringResource(R.string.cloud_action_hard_off)
        CloudPowerAction.HARD_REBOOT -> stringResource(R.string.cloud_action_hard_reboot)
    }
    val danger = action == CloudPowerAction.HARD_OFF || action == CloudPowerAction.HARD_REBOOT

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !working && (!needPassword || passwordText.isNotBlank()),
            ) {
                Text(
                    text = stringResource(R.string.cloud_power_confirm),
                    color = if (danger) palette.error else palette.primary,
                )
            }
        },
        dismissButton = {
            // 下发中也允许取消对话框：请求在 VM 里继续走完，结果以 toast 收尾
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cloud_cancel), color = palette.textSecondary)
            }
        },
        title = {
            Text(
                text = stringResource(R.string.cloud_power_confirm_title, label),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    text = if (danger) {
                        stringResource(R.string.cloud_power_hard_confirm_desc)
                    } else {
                        stringResource(R.string.cloud_power_confirm_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (danger) palette.error else palette.textSecondary,
                )
                if (needPassword) {
                    Text(
                        text = stringResource(R.string.cloud_op_password_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = onPasswordChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        shape = AppCorner.Control,
                        label = { Text(stringResource(R.string.cloud_op_password_label)) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onTogglePassword) {
                                Icon(
                                    painter = painterResource(
                                        if (passwordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                                    ),
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.cloud_op_password_hide
                                        else R.string.cloud_op_password_show,
                                    ),
                                    tint = palette.textSecondary,
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.outline,
                            cursorColor = palette.primary,
                            focusedContainerColor = palette.surface,
                            unfocusedContainerColor = palette.surface,
                        ),
                    )
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (working) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(2.dp),
                        color = palette.primary,
                        trackColor = palette.outline,
                    )
                }
            }
        },
        containerColor = palette.surface,
        textContentColor = palette.textPrimary,
        titleContentColor = palette.textPrimary,
        shape = AppCorner.Dialog,
    )
}

/** 信息卡/凭据卡共用的 label 上 value 下行；value 空显示 —。 */
@Composable
private fun FieldLine(label: String, value: String?, valueColor: Color) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.value_dash),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 流量数字：整数不带小数，其余保留两位（如 12 GB / 2.50 GB）。 */
private fun formatAmount(value: Double, unit: String): String =
    if (value == Math.floor(value) && !value.isInfinite()) "${value.toLong()} $unit"
    else String.format(java.util.Locale.US, "%.2f %s", value, unit)

