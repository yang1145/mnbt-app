package com.mnbt.app.ui.filemanager

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.MnbtClient
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

enum class FileSort { NAME, TIME }

/** 分片上传进度（UI 显示 进度 n/N）。 */
data class UploadProgress(val fileName: String, val doneChunks: Int, val totalChunks: Int)

/** 一次性结果消息。retryable=true 时 Snackbar 给「重试」，重跑整个失败流程。 */
data class FileMessage(val text: String, val retryable: Boolean = false)

data class FileBrowserUiState(
    val path: String = "/",
    /** 目录进入历史（非面包屑祖先链）；系统返回先弹它，栈空才退屏。 */
    val dirStack: List<String> = emptyList(),
    val sort: FileSort = FileSort.NAME,
    val list: LoadState<List<FileEntry>> = LoadState.Loading,
    val busy: Boolean = false,
    val multiSelect: Boolean = false,
    val selection: Set<String> = emptySet(),
    /** 非 null = 到期/封禁（1003/1004），整屏只读、所有写入口 disable。 */
    val readonlyReason: String? = null,
    val upload: UploadProgress? = null,
)

private data class UploadRequest(val uri: Uri, val displayName: String, val size: Long)

/**
 * 文件管理屏状态机：当前目录列表 + 忙状态 + 每操作一次性 Result（messages Channel）。
 * 进入新目录即拉取 FILE_LIST；下拉刷新重拉当前目录。
 */
class FileBrowserViewModel(
    private val container: AppContainer,
    private val hostId: Long,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    private val _messages = Channel<FileMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** 写操作与上传下载共用一把锁：救火场景宁可串行，不给服务端制造竞态。 */
    private val opMutex = Mutex()

    private var pendingUpload: UploadRequest? = null
    private var pendingDownload: FileEntry? = null

    init {
        viewModelScope.launch { load(_state.value.path) }
    }

    val readonly: Boolean get() = _state.value.readonlyReason != null

    // ---------------------------------------------------------------- 浏览

    suspend fun refresh() = load(_state.value.path)

    fun enterDir(entry: FileEntry) {
        if (_state.value.busy) return
        _state.update { it.copy(dirStack = it.dirStack + it.path) }
        viewModelScope.launch { load(entry.path) }
    }

    /** 系统返回先退目录栈。栈空返回 false，由调用方（OnBackPressedCallback）放行退屏。 */
    fun goBackDir(): Boolean {
        val prev = _state.value.dirStack.lastOrNull() ?: return false
        _state.update { it.copy(dirStack = it.dirStack.dropLast(1)) }
        viewModelScope.launch { load(prev) }
        return true
    }

    /** 面包屑回跳：目录栈重建为目标路径的祖先链。 */
    fun openAbsolutePath(target: String) {
        if (_state.value.busy) return
        _state.update { it.copy(dirStack = ancestorChain(target)) }
        viewModelScope.launch { load(target) }
    }

    fun setSort(mode: FileSort) = _state.update { s ->
        s.copy(
            sort = mode,
            list = (s.list as? LoadState.Ready)?.let { LoadState.Ready(it.data.sortedFor(mode)) } ?: s.list,
        )
    }

    private suspend fun load(path: String) {
        _state.update { it.copy(list = LoadState.Loading) }
        val outcome = hostCall(
            container, hostId, MnbtActions.FILE_LIST,
            params = mapOf("path" to path),
        ) { decodePayload<FileListPayload>(it) }
        when (outcome) {
            is HostOutcome.Ready -> {
                val payload = outcome.data
                // 服务端只回该层直接子项，path 也已拼好，客户端不再做前缀过滤
                _state.update {
                    it.copy(
                        path = payload.cwd,
                        list = LoadState.Ready(payload.list.sortedFor(it.sort)),
                        readonlyReason = null,
                    )
                }
            }
            else -> {
                val blocked = (outcome as? HostOutcome.HostBlocked)?.message
                _state.update { it.copy(list = outcome.toErrorState(), readonlyReason = blocked ?: it.readonlyReason) }
            }
        }
    }

    // ---------------------------------------------------------------- 多选

    fun enterMultiSelect(entry: FileEntry) {
        if (readonly) {
            emit(appContext.getString(R.string.files_readonly_hint))
            return
        }
        _state.update { it.copy(multiSelect = true, selection = it.selection + entry.path) }
    }

    fun toggleSelect(entry: FileEntry) = _state.update { s ->
        val next = if (entry.path in s.selection) s.selection - entry.path else s.selection + entry.path
        s.copy(selection = next, multiSelect = next.isNotEmpty())
    }

    fun exitMultiSelect() = _state.update { it.copy(multiSelect = false, selection = emptySet()) }

    // ---------------------------------------------------------------- 写操作

    /**
     * 统一写操作骨架：只读 gate → 串行锁 → busy → 一次性消息 → 成功后静默刷新。
     * 写操作响应负载（deleted/renamed/applied…）UI 不消费，decode 直接用 JsonElement。
     */
    private suspend fun write(action: String, params: Map<String, String>): Boolean {
        if (readonly) {
            emit(appContext.getString(R.string.files_readonly_hint))
            return false
        }
        if (_state.value.busy) return false
        val outcome = opMutex.withLock {
            _state.update { it.copy(busy = true) }
            try {
                hostCall(container, hostId, action, params) { it }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        return when (outcome) {
            is HostOutcome.Ready -> {
                viewModelScope.launch { load(_state.value.path) }
                true
            }
            HostOutcome.TokenRejected, HostOutcome.CredentialMissing -> {
                emit(appContext.getString(R.string.files_msg_need_repair)); false
            }
            is HostOutcome.HostBlocked -> {
                _state.update { it.copy(readonlyReason = outcome.message) }
                emit(outcome.message); false
            }
            is HostOutcome.Failed -> {
                emit(outcome.message); false
            }
        }
    }

    suspend fun rename(entry: FileEntry, newName: String) {
        val trimmed = newName.trim().trimStart('/')
        if (trimmed.isEmpty() || trimmed.contains('/')) {
            emit(appContext.getString(R.string.files_msg_bad_name)); return
        }
        // 契约 §7 file.rename：src=旧全路径，dest=新全路径（这里取同目录下的改名）
        if (write(MnbtActions.FILE_RENAME, mapOf("src" to entry.path, "dest" to joinPath(parentPath(entry.path), trimmed)))) {
            emit(appContext.getString(R.string.files_msg_renamed))
        }
    }

    suspend fun delete(entry: FileEntry) {
        if (write(MnbtActions.FILE_DELETE, mapOf("path" to entry.path))) {
            emit(appContext.getString(R.string.files_msg_deleted))
        }
    }

    /**
     * 批量删除。契约 §7 的 `file.delete_batch` 接受 `paths` 的 JSON 数组串（重复键 `paths[]` 是同义写法），
     * 一次请求就能拿到 `deleted` 计数与 `failed` 明细；核心对 `.user.ini`、运行目录等有保护，
     * 任一项失败时服务端回 1009 + 中文 msg，那条 msg 直接展示就是用户能懂的话。
     */
    suspend fun deleteSelected() {
        if (readonly) {
            emit(appContext.getString(R.string.files_readonly_hint)); return
        }
        val paths = _state.value.selection.toList()
        if (paths.isEmpty() || _state.value.busy) return
        val outcome = opMutex.withLock {
            _state.update { it.copy(busy = true) }
            try {
                hostCall(container, hostId, MnbtActions.FILE_DELETE_BATCH, mapOf("paths" to paths.jsonArrayOf())) {
                    decodePayload<FileDeleteBatchPayload>(it)
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        when (outcome) {
            is HostOutcome.Ready -> emit(
                appContext.getString(R.string.files_msg_batch_deleted, outcome.data.deleted, paths.size),
            )
            HostOutcome.TokenRejected, HostOutcome.CredentialMissing ->
                emit(appContext.getString(R.string.files_msg_need_repair))
            is HostOutcome.HostBlocked -> {
                _state.update { it.copy(readonlyReason = outcome.message) }
                emit(outcome.message)
            }
            is HostOutcome.Failed -> emit(outcome.message)
        }
        exitMultiSelect()
        viewModelScope.launch { load(_state.value.path) }
    }

    suspend fun copyTo(entry: FileEntry, dest: String) {
        val trimmed = dest.trim()
        if (!trimmed.startsWith("/")) {
            emit(appContext.getString(R.string.files_msg_need_abs_path)); return
        }
        // 契约 §7 file.copy：src / dest（以 / 结尾按目录处理）/ type=copy|cut
        if (write(
                MnbtActions.FILE_COPY,
                mapOf("src" to entry.path, "dest" to trimmed, "type" to "copy"),
            )
        ) {
            emit(appContext.getString(R.string.files_msg_copied))
        }
    }

    /**
     * 压缩为…。契约 §7 file.compress：`files` 接受单个全路径（多选批量压缩要数组，
     * 面板这条原语本身按单项目标处理），`dest` 是压缩包完整路径，落当前目录。
     */
    suspend fun compressTo(entry: FileEntry, archiveName: String) {
        val name = archiveName.trim().ifEmpty { "archive.zip" }
        if (write(MnbtActions.FILE_COMPRESS, mapOf("files" to entry.path, "dest" to joinPath(_state.value.path, name)))) {
            emit(appContext.getString(R.string.files_msg_compressed))
        }
    }

    suspend fun unzip(entry: FileEntry) {
        // 契约 §7 file.unzip：src=压缩包，dest=解压目标目录（取包所在目录）；加密包还要 password，本屏不提交
        if (write(MnbtActions.FILE_UNZIP, mapOf("src" to entry.path, "dest" to parentPath(entry.path)))) {
            emit(appContext.getString(R.string.files_msg_unzipped))
        }
    }

    suspend fun applyAccess(path: String, perm: String, recurse: Boolean): Boolean {
        if (!perm.matches(Regex("[0-7]{3,4}"))) {
            emit(appContext.getString(R.string.files_msg_bad_perm)); return false
        }
        // 契约 §7 file.access.set：path / perm / recurse("1"|"0")
        val applied = write(
            MnbtActions.FILE_ACCESS_SET,
            mapOf("path" to path, "perm" to perm, "recurse" to if (recurse) "1" else "0"),
        )
        if (applied) emit(appContext.getString(R.string.files_msg_perm_set))
        return applied
    }

    suspend fun create(name: String, type: String) {
        val trimmed = name.trim().trimStart('/')
        if (trimmed.isEmpty()) {
            emit(appContext.getString(R.string.files_msg_bad_name)); return
        }
        // 契约 §7 file.create：path=完整目标路径，type=dir|file（目录和文件同一条 action）
        if (write(MnbtActions.FILE_CREATE, mapOf("path" to joinPath(_state.value.path, trimmed), "type" to type))) {
            emit(appContext.getString(R.string.files_msg_created))
        }
    }

    // ---------------------------------------------------------------- 只读查询（对话框回填）

    /** FILE_ACCESS_GET 回填用；失败发一次性消息并返回 null。 */
    suspend fun fetchAccess(path: String): FileAccessPayload? {
        val outcome = hostCall(container, hostId, MnbtActions.FILE_ACCESS_GET, mapOf("path" to path)) {
            decodePayload<FileAccessPayload>(it)
        }
        return when (outcome) {
            is HostOutcome.Ready -> outcome.data
            else -> { emit(outcome.failText()); null }
        }
    }

    /** FILE_SIZE：契约 §7 的 `unit` 恒为 "B"、`size` 是字节数，展示换算由客户端做。 */
    suspend fun fetchSize(entry: FileEntry): String? {
        val outcome = hostCall(container, hostId, MnbtActions.FILE_SIZE, mapOf("files" to entry.path)) {
            decodePayload<FileSizePayload>(it)
        }
        return when (outcome) {
            is HostOutcome.Ready -> humanReadableSize(outcome.data.size)
            else -> { emit(outcome.failText()); null }
        }
    }

    // ---------------------------------------------------------------- 下载

    /** FILE_DOWNLOAD 拿外链 url → rawClient(pin) 直接 GET → 落 Downloads。读操作，只读态不拦。 */
    suspend fun download(entry: FileEntry) {
        if (_state.value.busy) return
        pendingDownload = entry
        val outcome = hostCall(container, hostId, MnbtActions.FILE_DOWNLOAD, mapOf("file" to entry.path)) {
            decodePayload<FileDownloadPayload>(it)
        }
        val payload = when (outcome) {
            is HostOutcome.Ready -> outcome.data
            else -> { emit(outcome.failText()); return }
        }
        if (payload.url.isBlank()) {
            emit(appContext.getString(R.string.files_msg_no_download_url)); return
        }
        _state.update { it.copy(busy = true) }
        val result = withContext(Dispatchers.IO) { runCatching { saveRemoteFile(payload) } }
        _state.update { it.copy(busy = false) }
        result.fold(
            onSuccess = { name ->
                pendingDownload = null
                emit(appContext.getString(R.string.files_msg_download_done, name))
            },
            onFailure = { e ->
                emit(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.files_msg_download_failed),
                    retryable = true,
                )
            },
        )
    }

    /** 返回最终落盘文件名。API 29+ 走 MediaStore Downloads，低版本落 app 外部 Downloads 目录（避免申请存储权限）。 */
    private suspend fun saveRemoteFile(payload: FileDownloadPayload): String {
        val host = container.hostDao.findById(hostId)
        val client = container.client.rawClient(host?.pinnedFingerprint)
        val request = Request.Builder().url(payload.url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: error("响应体为空")
            // mock 的 file_download_raw 在令牌失效/封禁时回 200 + JSON 包络，按失败处理
            val jsonish = body.contentType()?.subtype?.contains("json") == true
            if (!response.isSuccessful || jsonish) {
                val text = runCatching { body.string() }.getOrNull()
                error(text?.take(120)?.ifBlank { null } ?: "HTTP ${response.code}")
            }
            val baseName = payload.filename.ifBlank { Uri.parse(payload.url).pathSegments.lastOrNull() ?: "download" }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(baseName, body.byteStream())
            } else {
                saveViaExternalDir(baseName, body.byteStream())
            }
        }
    }

    private fun saveViaMediaStore(baseName: String, input: InputStream): String {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = uniqueViaMediaStore(collection, baseName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("无法创建下载条目")
        try {
            resolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: error("无法写入下载目录")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return name
    }

    private fun saveViaExternalDir(baseName: String, input: InputStream): String {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("外部存储不可用")
        var name = baseName
        var index = 1
        while (File(dir, name).exists()) {
            name = suffixedName(baseName, index++)
        }
        val target = File(dir, name)
        FileOutputStream(target).use { out -> input.copyTo(out) }
        return name
    }

    /** MediaStore 本身会自动改名，但规格要求可见的「(1)」后缀冲突处理，先查再插。 */
    private fun uniqueViaMediaStore(collection: Uri, baseName: String): String {
        var name = baseName
        var index = 1
        while (mediaStoreHas(collection, name)) {
            name = suffixedName(baseName, index++)
        }
        return name
    }

    private fun mediaStoreHas(collection: Uri, name: String): Boolean =
        appContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.count > 0 } ?: false

    private fun suffixedName(baseName: String, index: Int): String {
        val dot = baseName.lastIndexOf('.')
        return if (dot > 0) "${baseName.substring(0, dot)} ($index)${baseName.substring(dot)}"
        else "$baseName ($index)"
    }

    // ---------------------------------------------------------------- 分片上传

    /** ACTION_OPEN_DOCUMENT 结果入口：FILE_UPLOAD_PREPARE 拿分片计划 → 循环 MnbtClient.upload。 */
    suspend fun uploadFile(uri: Uri) {
        if (readonly) {
            emit(appContext.getString(R.string.files_readonly_hint)); return
        }
        if (_state.value.upload != null || _state.value.busy) {
            emit(appContext.getString(R.string.files_msg_upload_busy)); return
        }
        val meta = withContext(Dispatchers.IO) { queryOpenable(uri) }
        if (meta == null) {
            emit(appContext.getString(R.string.files_msg_upload_unreadable)); return
        }
        pendingUpload = UploadRequest(uri, meta.first, meta.second)
        runUploadFlow()
    }

    /** Snackbar「重试」：上传失败重跑整个流程，下载失败重跑整个下载。 */
    suspend fun retryLast() {
        if (pendingUpload != null) runUploadFlow()
        else pendingDownload?.let { download(it) }
    }

    private suspend fun runUploadFlow() {
        val request = pendingUpload ?: return
        if (_state.value.busy) return

        // 契约 §7 file.upload_prepare 要的是「完整目标路径 + 整个文件的总字节数」
        val prepare = hostCall(
            container, hostId, MnbtActions.FILE_UPLOAD_PREPARE,
            mapOf(
                "path" to joinPath(_state.value.path, request.displayName),
                "size" to request.size.toString(),
            ),
        ) { decodePayload<UploadPreparePayload>(it) }
        val plan = when (prepare) {
            is HostOutcome.Ready -> prepare.data
            is HostOutcome.HostBlocked -> {
                _state.update { it.copy(readonlyReason = prepare.message) }
                emit(prepare.message); return
            }
            is HostOutcome.Failed -> { emit(prepare.message, retryable = true); return }
            else -> { emit(prepare.failText()); return }
        }
        if (plan.maxSize > 0 && request.size > plan.maxSize) {
            pendingUpload = null
            emit(appContext.getString(R.string.files_msg_upload_too_large, humanReadableSize(plan.maxSize)))
            return
        }
        val host = container.hostDao.findById(hostId)
        val credential = host?.let { container.hostRepository.credentialFor(it) }
        if (credential == null) {
            emit(appContext.getString(R.string.files_msg_need_repair)); return
        }
        // chunk_size 兜底：服务端回 2MB；异常值收敛到 [64KB, 8MB]
        val chunkSize = plan.chunkSize.coerceIn(64L * 1024, 8L * 1024 * 1024).toInt()
        val total = if (request.size <= 0) 1 else ((request.size + chunkSize - 1) / chunkSize).toInt()
        val startIndex = plan.resumeStart(request.size, chunkSize)

        _state.update {
            it.copy(busy = true, upload = UploadProgress(request.displayName, startIndex - 1, total))
        }
        val outcome = opMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { streamChunks(credential, request, plan, chunkSize, total, startIndex) }
            }
        }
        _state.update { it.copy(busy = false, upload = null) }
        outcome.fold(
            onSuccess = { sent ->
                if (sent == 0 && startIndex <= 1) {
                    emit(appContext.getString(R.string.files_msg_upload_unreadable))
                } else {
                    pendingUpload = null
                    emit(appContext.getString(R.string.files_msg_upload_done, request.displayName))
                    viewModelScope.launch { load(_state.value.path) }
                }
            },
            onFailure = { e ->
                emit(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.files_msg_upload_failed),
                    retryable = true,
                )
            },
        )
    }

    /**
     * 断点续传的起点（契约 §7.1）。服务端按 `已收字节数` 推进偏移量，只有偏移正好落在
     * 分片边界上才能接着传；否则一律从第 1 片重传 —— `index<=1` 会把服务端偏移重置为 0，
     * 半截对齐的续传反而会写出错位的文件。
     */
    private fun UploadPreparePayload.resumeStart(fileSize: Long, chunkSize: Int): Int {
        if (resumeOffset <= 0 || resumeOffset >= fileSize) return 1
        return if (resumeOffset % chunkSize == 0L) (resumeOffset / chunkSize).toInt() + 1 else 1
    }

    /**
     * 每分片一次 multipart POST（契约 §7 file.upload）：文件字段固定 `file`，
     * 平铺 `upload_id`、`size`=整个文件的总字节数（**每片都要带，缺了服务端直接 1007**）、
     * `index`(1-based)/`total`=分片序号与总数、`name`=文件名。
     * 服务端把 index/total 翻译成宝塔的累计偏移，同一文件必须串行上传。
     */
    private suspend fun streamChunks(
        credential: MnbtClient.Credential,
        request: UploadRequest,
        plan: UploadPreparePayload,
        chunkSize: Int,
        total: Int,
        startIndex: Int,
    ): Int {
        val input = appContext.contentResolver.openInputStream(request.uri) ?: return 0
        input.use { stream ->
            // 续传：偏移量之前的分片直接从输入流丢弃，服务端已经有那些字节了
            repeat(startIndex - 1) { readFixed(stream, chunkSize) }
            var index = startIndex
            while (true) {
                val chunk = readFixed(stream, chunkSize)
                if (chunk.isEmpty()) return index - 1
                container.client.upload(
                    credential,
                    MnbtActions.FILE_UPLOAD,
                    fields = linkedMapOf(
                        "upload_id" to plan.uploadId,
                        "size" to request.size.toString(),
                        "name" to request.displayName,
                        "index" to index.toString(),
                        "total" to total.toString(),
                    ),
                    fileField = "file",
                    fileName = request.displayName,
                    chunk = chunk,
                )
                _state.update { it.copy(upload = UploadProgress(request.displayName, index, total)) }
                index++
                if (chunk.size < chunkSize) return index - 1
            }
        }
    }

    private fun readFixed(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) buffer else buffer.copyOf(offset)
    }

    private fun queryOpenable(uri: Uri): Pair<String, Long>? = runCatching {
        var name = "upload.bin"
        var size = -1L
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            size = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        }
        if (size < 0) return@runCatching null
        name to size
    }.getOrNull()

    private fun emit(text: String, retryable: Boolean = false) {
        _messages.trySend(FileMessage(text, retryable))
    }

    /** 非 Ready 结果的统一文案；HostBlocked 顺带落只读态由调用点处理。 */
    private fun HostOutcome<*>.failText(): String = when (this) {
        is HostOutcome.Ready -> ""
        HostOutcome.TokenRejected, HostOutcome.CredentialMissing ->
            appContext.getString(R.string.files_msg_need_repair)
        is HostOutcome.HostBlocked -> message
        is HostOutcome.Failed -> message
    }
}

/** LoadState 是 out 协变的，但具体 T 的 Ready 塞不进 List<FileEntry> 槽位，这里只取错误分支。 */
private fun <T> HostOutcome<T>.toErrorState(): LoadState<Nothing> = when (this) {
    is HostOutcome.Ready -> LoadState.Failed("unexpected success")
    HostOutcome.TokenRejected -> LoadState.TokenRejected
    HostOutcome.CredentialMissing -> LoadState.CredentialMissing
    is HostOutcome.HostBlocked -> LoadState.HostBlocked(message)
    is HostOutcome.Failed -> LoadState.Failed(message, raw)
}
