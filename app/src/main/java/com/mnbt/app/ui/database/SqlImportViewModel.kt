package com.mnbt.app.ui.database

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.MnbtException
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SQL 导入页 ViewModel。
 *
 * 页面主体先拉 db.list 供选择目标库（覆盖 [LoadState] 全部分支）；导入用 SAF
 * 选到的 .sql 文件以 multipart 上传 db.sql.import。
 *
 * 上传路径决策：MnbtClient 已带 multipart 方法 [com.mnbt.app.data.remote.api.MnbtClient.upload]
 * （fields + fileField + chunk，内部用 rawClient 同源的 clientFor(pin) 并加 Authorization 头），
 * 故直接复用，不手搭 MultipartBody、不改 MnbtClient。db.sql.import 非分片，整文件一次 POST，
 * 默认 300s 读超时已覆盖契约 §1.3 要求的 120s；因此文件字节全部读进内存，
 * 超大 SQL 可能触发 HTTP 413（此时应改走「先 file.upload 进站点目录，再按路径导入」）。
 * 库名校验键是 data_account（插件兼容 db，**不读 name**），传错库名会被 1007 挡住而不是静默导入。
 */
class SqlImportViewModel(
    private val container: AppContainer,
    private val hostId: Long,
    private val appContext: Context,
) : ViewModel() {

    private val _listState = MutableStateFlow<LoadState<DbListData>>(LoadState.Loading)
    val listState = _listState.asStateFlow()

    private val _selectedDb = MutableStateFlow<String?>(null)
    val selectedDb = _selectedDb.asStateFlow()

    private val _fileName = MutableStateFlow<String?>(null)
    val fileName = _fileName.asStateFlow()

    private val _fileSize = MutableStateFlow<Long?>(null)
    val fileSize = _fileSize.asStateFlow()

    private val _phase = MutableStateFlow<DbOpPhase>(DbOpPhase.Idle)
    val phase = _phase.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private var pendingUri: Uri? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _listState.value = LoadState.Loading
            val state = hostCall(container, hostId, MnbtActions.DB_LIST) { decodePayload<DbListData>(it) }
                .toLoadState()
            _listState.value = state
            if (state is LoadState.Ready && _selectedDb.value == null) {
                _selectedDb.value = state.data.list.firstOrNull()?.name
            }
        }
    }

    fun selectDb(name: String) {
        if (_busy.value) return
        _selectedDb.value = name
    }

    /** SAF OpenDocument 回调：记录 uri 并解析显示名/大小供 UI 呈现。 */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) {
            pendingUri = null
            _fileName.value = null
            _fileSize.value = null
            return
        }
        pendingUri = uri
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { queryNameAndSize(uri) }.getOrNull() }
            _fileName.value = info?.first
            _fileSize.value = info?.second?.takeIf { it >= 0 }
        }
    }

    fun import() {
        val uri = pendingUri ?: return
        val db = _selectedDb.value ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _phase.value = DbOpPhase.Running
            _phase.value = runUpload(uri, db)
            _busy.value = false
        }
    }

    private suspend fun runUpload(uri: Uri, dbName: String): DbOpPhase {
        val host = container.hostDao.findById(hostId)
            ?: return DbOpPhase.Error("主机条目不存在，可能已被移除")
        val credential = container.hostRepository.credentialFor(host)
            ?: return DbOpPhase.NeedRepair
        return try {
            val bytes = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return DbOpPhase.Error("无法读取所选文件")
            if (bytes.isEmpty()) return DbOpPhase.Error("所选文件为空")
            val displayName = _fileName.value ?: "dump.sql"
            val json = container.client.upload(
                credential = credential,
                action = MnbtActions.DB_SQL_IMPORT,
                fields = mapOf("data_account" to dbName),
                fileField = "file",
                fileName = displayName,
                chunk = bytes,
            )
            val result = decodePayload<DbSqlImportResult>(json)
            if (result.imported) {
                DbOpPhase.Success("已向 ${result.db.ifBlank { dbName }} 导入 SQL")
            } else {
                DbOpPhase.Error("导入未确认（服务端未返回 imported=true）")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MnbtException) {
            if (e is MnbtException.TokenRejected) container.hostDao.updateNeedsRepair(hostId, true)
            e.toOpPhase()
        } catch (e: Exception) {
            DbOpPhase.Error(e.message ?: "导入失败")
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long>? {
        val cursor = appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !it.isNull(nameIdx)) it.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !it.isNull(sizeIdx)) it.getLong(sizeIdx) else -1L
            name to size
        }
    }

    fun consumePhase() {
        _phase.value = DbOpPhase.Idle
    }
}
