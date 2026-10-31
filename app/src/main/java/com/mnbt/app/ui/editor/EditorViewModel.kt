package com.mnbt.app.ui.editor

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.domain.call.hostCallBytes
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 编辑器文档快照。generation 只在「视图需要被程序化重设文本」时递增
 * （初次载入、编码切换重解码），用户敲字不递增——否则 AndroidView update
 * 会反复 setText，光标跳动、输入法断链。
 */
data class EditorDoc(
    val text: String,
    val charset: FileCharset,
    val sizeBytes: Long,
    val generation: Int,
)

sealed interface EditorSavePhase {
    data object Idle : EditorSavePhase
    data object Saving : EditorSavePhase
    data object Done : EditorSavePhase
    /** raw 非空表示 Protocol 类错误，UI 附原始响应前 200 字。 */
    data class Error(val message: String, val raw: String? = null) : EditorSavePhase
    /** 保存时主机被判定到期/封禁：整页转只读。 */
    data class Blocked(val message: String) : EditorSavePhase
}

/**
 * P3 在线代码编辑器 VM。
 *
 * 内存纪律：内容只活在这里（切后台/旋转不丢，编辑器仅是视图）；
 * 原始字节 [originalBytes] 全程保留——它是编码切换重解码与字节透明保存的锚点。
 */
class EditorViewModel(
    private val container: AppContainer,
    private val hostId: Long,
    val path: String,
) : ViewModel() {

    companion object {
        const val BIG_FILE_BYTES = 1024 * 1024
    }

    private val _state = MutableStateFlow<LoadState<EditorDoc>>(LoadState.Loading)
    val state = _state.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty = _dirty.asStateFlow()

    private val _savePhase = MutableStateFlow<EditorSavePhase>(EditorSavePhase.Idle)
    val savePhase = _savePhase.asStateFlow()

    /** 非 null 时页面转只读并显示横幅（保存被 Blocked 拒绝后置位）。 */
    private val _blockedBanner = MutableStateFlow<String?>(null)
    val blockedBanner = _blockedBanner.asStateFlow()

    /** size>1MB 打开前确认：read 已回内容，确认后才渲染编辑器（省一次 file.size 往返）。 */
    private val _awaitBigConfirm = MutableStateFlow(false)
    val awaitBigConfirm = _awaitBigConfirm.asStateFlow()

    /** 当前编码（可能与打开判定不同——用户手动切换过）。 */
    private var charset: FileCharset = FileCharset.Utf8

    /** file.read 拿到的权威原字节。 */
    private var originalBytes: ByteArray? = null

    private var lastAppliedText: String = ""

    private var generation = 0

    /** 当前保存/解码使用的编码（UI 顶栏 chip 展示、点击切换目标由 UI 取反）。 */
    val currentCharset: FileCharset get() = charset

    val fileName: String get() = path.substringAfterLast('/')

    val isUserIni: Boolean get() = fileName == ".user.ini"

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            // mock/bridge 两参数名都兼容：file 优先、path 兜底，同值双发最稳
            val outcome = hostCall(
                container, hostId, MnbtActions.FILE_READ,
                mapOf("file" to path, "path" to path),
            ) { decodePayload<FileReadPayload>(it) }.toLoadState()

            when (outcome) {
                is LoadState.Ready -> onFileRead(outcome.data)
                LoadState.Loading -> _state.value = LoadState.Loading
                is LoadState.Failed -> _state.value = LoadState.Failed(outcome.message, outcome.raw)
                LoadState.TokenRejected -> _state.value = LoadState.TokenRejected
                LoadState.CredentialMissing -> _state.value = LoadState.CredentialMissing
                is LoadState.HostBlocked -> _state.value = LoadState.HostBlocked(outcome.message)
            }
        }
    }

    private fun onFileRead(payload: FileReadPayload) {
        val bytes = payload.contentBase64?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }
        if (bytes == null) {
            // content_base64 缺失或非法 base64：bridge 版本过低/行为异常。
            // lossy 的 content 只能看不能存，直接判失败比静默回写坏字节好。
            _state.value = LoadState.Failed(
                payload.content?.let { "响应缺少 content_base64（有损的 content 不可用于编辑回写）" }
                    ?: "响应缺少文件内容字段"
            )
            return
        }
        originalBytes = bytes
        val (text, detected) = decodeByDetection(bytes)
        charset = detected
        generation += 1
        lastAppliedText = text
        _state.value = LoadState.Ready(EditorDoc(text, detected, bytes.size.toLong(), generation))
        _dirty.value = false
        if (bytes.size > BIG_FILE_BYTES) _awaitBigConfirm.value = true
    }

    fun confirmBigFile() {
        _awaitBigConfirm.value = false
    }

    /** 编辑器文本变化回报（ContentChangeEvent → 视图调用）。 */
    fun onEditorTextChanged(text: String) {
        if (text == lastAppliedText) {
            _dirty.value = false
            return
        }
        lastAppliedText = text
        _dirty.value = true
    }

    /** 视图在程序化 setText 后同步基线（避免重解码被误标 dirty）。 */
    fun onTextApplied(text: String) {
        lastAppliedText = text
    }

    /**
     * 手动切换编码重解码。未保存内容不丢：
     * - 干净态：从 originalBytes 按新编码重解码并刷新视图；
     * - 脏态：保留当前文本（无法把「已改文本」与「新解码文本」合并），仅把保存目标编码换掉，
     *   UI 层必须先弹警告。
     */
    fun switchCharset(target: FileCharset) {
        if (target == charset) return
        charset = target
        val doc = (_state.value as? LoadState.Ready)?.data ?: return
        if (_dirty.value) {
            _state.value = LoadState.Ready(doc.copy(charset = target))
            return
        }
        val bytes = originalBytes ?: return
        val text = String(bytes, target.javaCharset)
        generation += 1
        lastAppliedText = text
        _state.value = LoadState.Ready(doc.copy(text = text, charset = target, generation = generation))
    }

    fun save() {
        val doc = (_state.value as? LoadState.Ready)?.data ?: return
        if (_dirty.value && lastAppliedText == doc.text) {
            // 理论上到不了：dirty 与文本快照应一致。防御空转保存。
            return
        }
        viewModelScope.launch {
            _savePhase.value = EditorSavePhase.Saving
            val contentBytes = lastAppliedText.toByteArray(charset.javaCharset)
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            // 保存必须走 hostCallBytes：form 值原样百分号编码，GBK/二进制字节透明（PRD §4.5）。
            val outcome = hostCallBytes(
                container, hostId, MnbtActions.FILE_SAVE,
                mapOf("file" to pathBytes, "path" to pathBytes, "content" to contentBytes),
            ) { decodePayload<FileSaveAck>(it) }

            _savePhase.value = when (outcome) {
                is HostOutcome.Ready -> {
                    // 不动 generation：视图里已是当前文本，重设 setText 只会把光标弹回。
                    _state.value = LoadState.Ready(doc.copy(text = lastAppliedText, charset = charset))
                    originalBytes = contentBytes
                    _dirty.value = false
                    EditorSavePhase.Done
                }
                HostOutcome.TokenRejected, HostOutcome.CredentialMissing ->
                    EditorSavePhase.Error("令牌已失效，请重新配对后再保存")
                is HostOutcome.HostBlocked -> {
                    _blockedBanner.value = outcome.message
                    EditorSavePhase.Blocked(outcome.message)
                }
                is HostOutcome.Failed -> EditorSavePhase.Error(outcome.message, outcome.raw)
            }
        }
    }

    fun consumeSavePhase() {
        if (_savePhase.value is EditorSavePhase.Done ||
            _savePhase.value is EditorSavePhase.Error
        ) {
            _savePhase.value = EditorSavePhase.Idle
        }
    }
}
