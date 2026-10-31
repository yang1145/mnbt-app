package com.mnbt.app.ui.database

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 数据库管理页 ViewModel。
 *
 * - 页面主体走 [LoadState]（db.list，整页形态：Loading 骨架 / Ready / Failed /
 *   TokenRejected / CredentialMissing / HostBlocked）。
 * - 单条写操作走 [DbOpPhase]（Snackbar 级 + 忙时禁提交）。
 * - 面板一主机一库、且没有建库/删库原语，所以本页的两个写动作是备份的增删
 *   （db.backup.create / db.backup.delete），不是 db.create / db.delete（那两个恒 1010）。
 * - 备份下载：db.backup.download 回的 url 是节点直连的宝塔 token 型外链，
 *   用 [com.mnbt.app.data.remote.api.MnbtClient.rawClient]（带该主机证书指纹信任）直接 GET，
 *   并且**不带 Authorization**——令牌已经在那条 URL 里，再挂 bearer 只是把凭据多发一份。
 *   字节写入 app cacheDir/db_backups。
 */
class DatabaseViewModel(
    private val container: AppContainer,
    private val hostId: Long,
    private val appContext: Context,
) : ViewModel() {

    private val _listState = MutableStateFlow<LoadState<DbListData>>(LoadState.Loading)
    val listState = _listState.asStateFlow()

    private val _phase = MutableStateFlow<DbOpPhase>(DbOpPhase.Idle)
    val phase = _phase.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _listState.value = LoadState.Loading
            _listState.value = fetchList()
        }
    }

    private suspend fun fetchList(): LoadState<DbListData> =
        hostCall(container, hostId, MnbtActions.DB_LIST) { decodePayload<DbListData>(it) }
            .toLoadState()

    /**
     * 统一写操作骨架：忙守卫 → Running → 执行 → 落 phase → 成功时静默刷新列表
     * （不闪 Loading 骨架）。[refresh] 为 false 用于下载这类不改列表的操作。
     */
    private fun op(refresh: Boolean = true, body: suspend () -> DbOpPhase) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _phase.value = DbOpPhase.Running
            val result = body()
            _phase.value = result
            _busy.value = false
            if (result is DbOpPhase.Success && refresh) {
                val reloaded = fetchList()
                if (reloaded is LoadState.Ready) _listState.value = reloaded
            }
        }
    }

    /**
     * 立即备份（db.backup.create）。无入参——插件自己拿本主机的 hxd，
     * 回的 name/username 是库账号名而不是备份文件名，所以提示里不提它。
     * 备份由节点异步落盘，成功回执不代表文件已经在列表里，故文案只说「已提交」。
     */
    fun backupNow() {
        op {
            hostCall(container, hostId, MnbtActions.DB_BACKUP_CREATE) {
                decodePayload<DbBackupCreateResult>(it)
            }.toOpPhase { "备份请求已提交，稍后刷新可看到备份文件" }
        }
    }

    /** 删除备份文件（db.backup.delete：只传文件名，插件按名字反查宝塔数字 id）。确认框由 UI 负责。 */
    fun deleteBackup(name: String) {
        if (name.isBlank()) return
        op {
            hostCall(container, hostId, MnbtActions.DB_BACKUP_DELETE, mapOf("name" to name)) {
                decodePayload<DbBackupDeleteResult>(it)
            }.toOpPhase { "备份 $name 已删除" }
        }
    }

    /** 清空当前库的所有表（db.delete_all）。强确认（勾选后才可点红色确认）由 UI 负责。 */
    fun deleteAll() {
        op {
            hostCall(container, hostId, MnbtActions.DB_DELETE_ALL) {
                decodePayload<DbDeleteAllResult>(it)
            }.toOpPhase { r -> appContext.getString(R.string.db_delete_all_done, r.deleted) }
        }
    }

    /**
     * 访问权限（db.access.set）。插件只需要 host=localhost|%，库名它自己取 sqluser。
     * port 也不发：面板没有改端口能力，传了只是回显，留着输入框等于骗用户。
     * db.list 不回当前设置，所以对话框默认「仅本地」，并在文案里说明以服务端为准。
     */
    fun setAccess(allowRemote: Boolean) {
        op {
            hostCall(
                container,
                hostId,
                MnbtActions.DB_ACCESS_SET,
                mapOf("host" to if (allowRemote) "%" else "localhost"),
            ) { decodePayload<DbAccessResult>(it) }.toOpPhase { r ->
                val mode = if (r.access == "localhost" || r.access.isBlank()) {
                    appContext.getString(R.string.db_access_mode_local)
                } else {
                    appContext.getString(R.string.db_access_mode_remote)
                }
                appContext.getString(R.string.db_access_done, r.db, mode)
            }
        }
    }

    /** 备份下载：先 hostCall db.backup.download 拿 JSON{url}，再 rawClient GET 落盘。 */
    fun downloadBackup(backupName: String) {
        if (backupName.isBlank()) return
        op(refresh = false) {
            val outcome = hostCall(
                container,
                hostId,
                MnbtActions.DB_BACKUP_DOWNLOAD,
                mapOf("file" to backupName),
            ) { decodePayload<DbBackupDownloadResult>(it) }
            when (outcome) {
                is HostOutcome.Ready -> {
                    val url = outcome.data.url
                    if (url.isBlank()) {
                        DbOpPhase.Error("服务端未返回下载链接（url 为空）")
                    } else {
                        downloadToCache(url, outcome.data.file.ifBlank { backupName })
                    }
                }
                else -> outcome.toOpPhase { "" }
            }
        }
    }

    private suspend fun downloadToCache(url: String, fileName: String): DbOpPhase {
        val host = container.hostDao.findById(hostId)
            ?: return DbOpPhase.Error("主机条目不存在，可能已被移除")
        // token 型外链自身可鉴权，不再挂 Authorization：凭据少发一处就少一处泄漏面。
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: Exception) {
            return DbOpPhase.Error("下载链接无效：${e.message ?: url}")
        }
        val client = container.client.rawClient(host.pinnedFingerprint)
        // 同步 execute 必须切到 IO，否则在 viewModelScope 主线程上会 NetworkOnMainThreadException。
        return withContext(Dispatchers.IO) {
            try {
                val (bytes, httpCode) = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        null to response.code
                    } else {
                        response.body?.bytes() to null
                    }
                }
                when {
                    httpCode != null -> DbOpPhase.Error("下载失败（HTTP $httpCode）")
                    bytes == null || bytes.isEmpty() -> DbOpPhase.Error("下载响应为空")
                    else -> {
                        val dir = File(appContext.cacheDir, "db_backups").apply { mkdirs() }
                        val safe = fileName.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
                            .ifBlank { "backup_${System.currentTimeMillis()}.sql.gz" }
                        val out = File(dir, safe)
                        out.writeBytes(bytes)
                        DbOpPhase.Success("已保存到 ${out.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                DbOpPhase.Error("下载失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 备份恢复（db.backup.restore：name=备份文件，data_account=目标库）。覆盖性危险操作，确认框由 UI 负责。 */
    fun restoreBackup(dbName: String, backupName: String) {
        if (dbName.isBlank() || backupName.isBlank()) return
        op {
            hostCall(
                container,
                hostId,
                MnbtActions.DB_BACKUP_RESTORE,
                mapOf("name" to backupName, "data_account" to dbName),
            ) { decodePayload<DbRestoreResult>(it) }
                .toOpPhase { r -> "已用 $backupName 恢复 ${r.db.ifBlank { dbName }}" }
        }
    }

    fun consumePhase() {
        _phase.value = DbOpPhase.Idle
    }
}
