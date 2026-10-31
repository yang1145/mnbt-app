package com.mnbt.app.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.appContainer
import com.mnbt.app.data.local.db.SshSessionEntity
import com.mnbt.app.ui.components.EmptyState
import com.mnbt.app.ui.components.StatusDot
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import java.text.DateFormat
import java.util.Date

/**
 * 终端 tab 的会话列表。点击行进终端页，⋮/铅笔进编辑页。
 * 列表只做快照展示，不代为建连（连接生命周期在终端页与 registry 里）。
 */
@Composable
fun TerminalTabScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.appContainer
    val sessions by container.sshRepository.observeConfigs().collectAsState(initial = emptyList())
    val palette = LocalAppPalette.current

    Column(modifier = modifier.fillMaxSize().padding(AppDimens.ScreenPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_terminal),
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { navController.navigate(Destinations.sshEditRoute(-1)) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.terminal_new_session),
                    tint = palette.textSecondary,
                )
            }
        }

        if (sessions.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_terminal_2,
                title = stringResource(R.string.terminal_empty_title),
                description = stringResource(R.string.terminal_empty_desc),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        hasLiveConnection = container.sshRepository.liveConnection(session.id) != null,
                        onOpen = { navController.navigate("ssh/terminal/${session.id}") },
                        onEdit = { navController.navigate(Destinations.sshEditRoute(session.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SshSessionEntity,
    hasLiveConnection: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = if (hasLiveConnection) palette.success else palette.textPlaceholder,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = session.label,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(session.username)
                    append('@')
                    append(session.host)
                    if (session.port != 22) append(':').append(session.port)
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        session.lastUsedAt?.let {
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(it)),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textPlaceholder,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.padding(start = 4.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_pencil),
                contentDescription = stringResource(R.string.terminal_edit_session),
                modifier = Modifier.size(18.dp),
                tint = palette.textSecondary,
            )
        }
    }
}
