package com.mnbt.app.ui.ops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.MnbtApplication
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * 通知中心页（PRD §5.6，notice.list / notice.read）。
 * 未读用小蓝点、禁止整行底色；点击未读项标记已读，顶栏「全部已读」发不带 id 的 notice.read。
 * 契约 §10.2 的通知项有 type/level 但本页只用 title/content/time，副标题取 content。
 */
@Composable
fun NoticeScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val vm: NoticeViewModel = mnbtViewModel { container, extras ->
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication
        NoticeViewModel(app, container, hostId)
    }
    val palette = LocalAppPalette.current
    val context = LocalContext.current

    val listState by vm.listState.collectAsStateWithLifecycle()
    val readPhase by vm.readPhase.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 服务端回的 unread 是「标记之前」的真实未读数；本地镜像更新后这里同步归零，
    // 「全部已读」按钮因此自行禁用，不需要额外状态位。
    val unreadCount = (listState as? LoadState.Ready)?.data?.unread ?: 0

    LaunchedEffect(readPhase) {
        when (val phase = readPhase) {
            is OpsWritePhase.Done -> {
                snackbarHostState.showSnackbar(phase.message)
                vm.consumeReadPhase()
            }

            is OpsWritePhase.Error -> {
                val text = if (phase.retryAfterSeconds != null) {
                    phase.message + "，" +
                        context.getString(R.string.ops_throttle_suffix, phase.retryAfterSeconds)
                } else {
                    phase.message
                }
                snackbarHostState.showSnackbar(text)
                vm.consumeReadPhase()
            }

            else -> Unit
        }
    }

    OpsScaffold(
        title = stringResource(R.string.ops_notice_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
        actions = {
            OpsTopBarAction(
                iconRes = R.drawable.ic_check,
                description = stringResource(R.string.ops_notice_mark_all),
                enabled = unreadCount > 0 && readPhase !is OpsWritePhase.Saving,
            ) { vm.markAllRead() }
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
                    iconRes = R.drawable.ic_bell,
                    title = stringResource(R.string.ops_notice_empty_title),
                    description = stringResource(R.string.ops_notice_empty_desc),
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.ops_notice_summary, data.unread, data.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                OpsListCard {
                    data.list.forEachIndexed { index, item ->
                        NoticeRow(
                            item = item,
                            onClick = { vm.markRead(item) },
                        )
                        if (index != data.list.lastIndex) OpsRowDivider()
                    }
                }
            }
        }
    }
}

/** 通知行：标题（未读=小蓝点）+ 时间一行，content 作副标题；行高 72dp 带副标题。 */
@Composable
private fun NoticeRow(
    item: OpsNoticeItem,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val unread = item.read == 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.RowHeightWithSubtitle)
            .clickable(enabled = unread, onClick = onClick)
            .padding(horizontal = AppDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 未读标记：小蓝点，不给整行铺底色（§8.3 硬性红线）
                if (unread) {
                    StatusDot(color = palette.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                )
            }
            if (item.content.isNotBlank()) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.time,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textPlaceholder,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}
