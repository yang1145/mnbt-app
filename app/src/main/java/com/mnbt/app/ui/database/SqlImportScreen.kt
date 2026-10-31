package com.mnbt.app.ui.database

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.mnbt.app.R
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.components.AppCard
import com.mnbt.app.ui.nav.requireHostId
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette

/**
 * SQL 导入页（PRD §7.2「导入 SQL」）。主体先用 db.list 覆盖 LoadState 全分支并供选择目标库，
 * 再经 SAF [ActivityResultContracts.OpenDocument] 选 .sql 文件，multipart 上传 db.sql.import。
 *
 * OpenDocument 过滤器用通配 MIME：Android 对 .sql 没有稳定类型（text/plain、octet-stream、
 * text/x-sql 各家文件管理器不一），收窄反而会挡住合法文件；选到非 .sql 只做黄字提醒不禁用。
 */
@Composable
fun SqlImportScreen(navController: NavController, entry: NavBackStackEntry) {
    val hostId = entry.requireHostId()
    val appContext = LocalContext.current.applicationContext
    val vm = mnbtViewModel { container, _ -> SqlImportViewModel(container, hostId, appContext) }

    val listState by vm.listState.collectAsStateWithLifecycle()
    val selectedDb by vm.selectedDb.collectAsStateWithLifecycle()
    val fileName by vm.fileName.collectAsStateWithLifecycle()
    val fileSize by vm.fileSize.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = LocalAppPalette.current

    DbOpEffect(phase, snackbarHostState, vm::consumePhase)
    LaunchedEffect(listState) {
        val state = listState
        if (state is LoadState.Failed && state.message.isNotBlank()) {
            snackbarHostState.showSnackbar(state.message)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> vm.onFilePicked(uri) }

    DbScaffold(
        title = stringResource(R.string.sql_title),
        onBack = { navController.popBackStack() },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DbStateView(state = listState, onRetry = vm::load) { data ->
                if (busy) {
                    DbBusyIndicator(stringResource(R.string.sql_uploading))
                }
                Text(
                    text = stringResource(R.string.sql_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )

                AppCard {
                    Text(
                        text = stringResource(R.string.sql_db_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                    if (data.list.isEmpty()) {
                        Text(
                            text = stringResource(R.string.sql_no_db),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPlaceholder,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
                        ) {
                            data.list.forEach { db ->
                                DbOptionRow(
                                    label = db.name,
                                    support = db.username,
                                    selected = db.name == selectedDb,
                                    onClick = { vm.selectDb(db.name) },
                                )
                            }
                        }
                    }
                }

                SqlFileCard(
                    fileName = fileName,
                    fileSize = fileSize,
                    enabled = !busy,
                    onPick = { pickLauncher.launch(arrayOf("*/*")) },
                )

                Button(
                    onClick = vm::import,
                    enabled = !busy && selectedDb != null && fileName != null &&
                        data.list.isNotEmpty(),
                    shape = AppCorner.Control,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.primary,
                        contentColor = palette.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.sql_import), style = MaterialTheme.typography.labelLarge)
                }

                DbNotice(stringResource(R.string.sql_contract_gap))
            }
        }
    }
}

@Composable
private fun SqlFileCard(
    fileName: String?,
    fileSize: Long?,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    AppCard {
        Text(
            text = stringResource(R.string.sql_file_label),
            style = MaterialTheme.typography.labelLarge,
            color = palette.textPrimary,
        )
        if (fileName == null) {
            Text(
                text = stringResource(R.string.sql_no_file),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPlaceholder,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                text = buildString {
                    append(stringResource(R.string.sql_selected_file, fileName))
                    if (fileSize != null) {
                        append(" · ")
                        append(formatBytes(fileSize))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!fileName.endsWith(".sql", ignoreCase = true)) {
                Text(
                    text = stringResource(R.string.sql_warn_not_sql),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.warning,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(enabled = enabled, onClick = onPick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_database_import),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = palette.primary,
                )
            }
            Text(
                text = stringResource(R.string.sql_pick_file),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.primary,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onPick)
                    .padding(start = 4.dp),
            )
        }
    }
}
