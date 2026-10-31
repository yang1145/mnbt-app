package com.mnbt.app.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.local.db.SshSessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SSH 会话表单状态。
 *
 * 机密字段的约定与 [com.mnbt.app.ssh.SshRepository.save] 一一对应：
 * 已存的密码/私钥/口令**永远不回显**（也不从 TokenVault 取回），编辑态只展示
 * 「已保存，输入新值可覆盖」；用户没动过的字段保存时传 null（= 保持原值），
 * 动过才传输入框内容（空串 = 显式清除）。
 */
data class SshEditUiState(
    val loading: Boolean = true,
    /** 按 id 查不到条目（已被删除）：整页只出提示，不渲染表单 */
    val missing: Boolean = false,
    val isNew: Boolean = true,
    val sessionId: Long = -1L,
    val termType: String = "xterm-256color",

    val label: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: String = SshSessionEntity.AUTH_PASSWORD,

    val password: String = "",
    val passwordEdited: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val showPassword: Boolean = false,

    /** SAF 导入的私钥正文；null = 本次没有导入 */
    val privateKeyText: String? = null,
    val privateKeyFileName: String? = null,
    val hasStoredPrivateKey: Boolean = false,

    val passphrase: String = "",
    val passphraseEdited: Boolean = false,
    val hasStoredPassphrase: Boolean = false,

    val knownHostFingerprint: String? = null,

    val saving: Boolean = false,
    val error: String? = null,
    /** 保存成功后的条目 id，UI 消费后 popBackStack */
    val savedId: Long? = null,
    val deleted: Boolean = false,
)

class SshEditViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sshRepository

    private val _state = MutableStateFlow(SshEditUiState())
    val state: StateFlow<SshEditUiState> = _state.asStateFlow()

    private var loadedSessionId: Long? = null

    /** [sessionId] < 0 表示新建；非负则从 Room 载入快照（机密值只载入「是否存在」标记）。 */
    fun load(sessionId: Long) {
        if (loadedSessionId == sessionId) return
        loadedSessionId = sessionId
        viewModelScope.launch {
            if (sessionId < 0L) {
                _state.value = SshEditUiState(loading = false, isNew = true)
                return@launch
            }
            val entity = repository.findById(sessionId)
            _state.value = if (entity == null) {
                SshEditUiState(loading = false, missing = true, isNew = false, sessionId = sessionId)
            } else {
                SshEditUiState(
                    loading = false,
                    isNew = false,
                    sessionId = entity.id,
                    termType = entity.termType,
                    label = entity.label,
                    host = entity.host,
                    port = entity.port.toString(),
                    username = entity.username,
                    authType = entity.authType,
                    hasStoredPassword = entity.passwordRef != null,
                    hasStoredPrivateKey = entity.privateKeyRef != null,
                    hasStoredPassphrase = entity.passphraseRef != null,
                    knownHostFingerprint = entity.knownHostFingerprint,
                )
            }
        }
    }

    fun onLabelChange(value: String) = update { it.copy(label = value, error = null) }

    fun onHostChange(value: String) = update { it.copy(host = value, error = null) }

    fun onPortChange(value: String) = update {
        it.copy(port = value.filter { c -> c.isDigit() }.take(5), error = null)
    }

    fun onUsernameChange(value: String) = update { it.copy(username = value, error = null) }

    fun onAuthTypeChange(value: String) = update { it.copy(authType = value, error = null) }

    fun onPasswordChange(value: String) = update {
        it.copy(password = value, passwordEdited = true, error = null)
    }

    fun onTogglePasswordVisibility() = update { it.copy(showPassword = !it.showPassword) }

    fun onPassphraseChange(value: String) = update {
        it.copy(passphrase = value, passphraseEdited = true, error = null)
    }

    /** SAF 读取成功回调：正文只留在内存状态里，保存时才经 [com.mnbt.app.ssh.SshRepository] 进 TokenVault。 */
    fun onPrivateKeyLoaded(fileName: String, text: String) {
        if (text.isBlank()) {
            update { it.copy(error = "所选文件内容是空的") }
            return
        }
        update { it.copy(privateKeyText = text, privateKeyFileName = fileName, error = null) }
    }

    fun onPrivateKeyLoadFailed(detail: String) {
        update { it.copy(error = "私钥读取失败：$detail") }
    }

    fun dismissError() = update { it.copy(error = null) }

    fun save() {
        val snapshot = _state.value
        if (snapshot.saving || snapshot.missing) return
        val validationError = validate(snapshot)
        if (validationError != null) {
            update { it.copy(error = validationError) }
            return
        }
        viewModelScope.launch {
            update { it.copy(saving = true, error = null) }
            try {
                val id = repository.save(
                    id = if (snapshot.isNew) null else snapshot.sessionId,
                    label = snapshot.label,
                    host = snapshot.host,
                    port = snapshot.port.toIntOrNull() ?: 22,
                    username = snapshot.username,
                    authType = snapshot.authType,
                    password = if (snapshot.passwordEdited) snapshot.password else null,
                    privateKeyPem = snapshot.privateKeyText,
                    passphrase = if (snapshot.passphraseEdited) snapshot.passphrase else null,
                    termType = snapshot.termType,
                )
                update { it.copy(saving = false, savedId = id) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(saving = false, error = e.message ?: "保存失败，请重试") }
            }
        }
    }

    /** 删除走 [com.mnbt.app.ssh.SshRepository.delete]：连带断开活连接并清 TokenVault 引用。 */
    fun delete() {
        val snapshot = _state.value
        if (snapshot.isNew || snapshot.saving) return
        viewModelScope.launch {
            update { it.copy(saving = true) }
            try {
                repository.delete(snapshot.sessionId)
                update { it.copy(saving = false, deleted = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(saving = false, error = e.message ?: "删除失败，请重试") }
            }
        }
    }

    private fun validate(s: SshEditUiState): String? = when {
        s.label.isBlank() -> "请填写名称"
        s.host.isBlank() -> "请填写主机地址"
        s.port.toIntOrNull() == null || s.port.toInt() !in 1..65535 -> "端口需在 1–65535 之间"
        s.username.isBlank() -> "请填写用户名"
        s.authType == SshSessionEntity.AUTH_PASSWORD && !s.hasStoredPassword && s.password.isEmpty() ->
            "请输入密码，或改用私钥认证"
        s.authType == SshSessionEntity.AUTH_KEY && !s.hasStoredPrivateKey && s.privateKeyText == null ->
            "请导入私钥文件"
        else -> null
    }

    private inline fun update(transform: (SshEditUiState) -> SshEditUiState) {
        _state.value = transform(_state.value)
    }
}
