package com.mnbt.app.ui.ssh

import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.ssh.ConnectOutcome
import com.mnbt.app.ssh.SshConnection
import com.mnbt.app.ssh.SshError
import com.termux.terminal.KeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 终端页状态机：
 * Connecting →（Connected | TrustPrompt 首次 TOFU | KeyChanged 指纹不符 | Failed）。
 *
 * [SshTerminalUiState.Connected] 直接持有 [SshConnection]：连接生命周期在
 * [com.mnbt.app.ssh.SshSessionRegistry]，离开本页**不**断开（切走再回来秒续用），
 * 只有显式「结束会话」或远端 exit 才关闭 channel。
 */
sealed interface SshTerminalUiState {
    data object Connecting : SshTerminalUiState

    /** 等待用户在 TOFU 对话框里确认，不渲染终端面 */
    data class TrustPrompt(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String,
    ) : SshTerminalUiState

    data class Connected(val connection: SshConnection) : SshTerminalUiState
    data class KeyChanged(val expected: String, val actual: String) : SshTerminalUiState
    data class Failed(val message: String) : SshTerminalUiState
}

/**
 * 辅助键。Esc/Tab/方向键/PgUp/PgDn 走 [com.termux.view.TerminalView.handleKeyCode]，
 * 由 KeyHandler 按当前光标键模式（普通/应用模式）选转义序列 —— vim 里方向键必须是 ESC O A。
 * 可打印键走 inputCodePoint，Ctrl 组合映射（Ctrl-/ → 0x1f 等）也由 view 完成。
 */
enum class SshHelperKey(val label: String, val keyCode: Int = -1, val codePoint: Int = -1) {
    Esc("Esc", KeyEvent.KEYCODE_ESCAPE),
    Tab("Tab", KeyEvent.KEYCODE_TAB),
    Ctrl("Ctrl"),
    ArrowLeft("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ArrowUp("↑", KeyEvent.KEYCODE_DPAD_UP),
    ArrowDown("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ArrowRight("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    PageUp("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    PageDown("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    Tilde("~", codePoint = '~'.code),
    Slash("/", codePoint = '/'.code),
    Dash("-", codePoint = '-'.code),
    Pipe("|", codePoint = '|'.code),
}

class SshTerminalViewModel(
    private val container: AppContainer,
    private val sessionId: Long,
) : ViewModel() {

    private val repository = container.sshRepository

    private val _state = MutableStateFlow<SshTerminalUiState>(SshTerminalUiState.Connecting)
    val state: StateFlow<SshTerminalUiState> = _state.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    /** TOFU 对话框要展示核对用的地址，init 时随标题一起载入。 */
    private var host: String = ""
    private var port: Int = 22

    /** Ctrl 一次性 latch：点亮后下一个辅助键按组合键发送，随后自动熄灭。 */
    private val _ctrlLatched = MutableStateFlow(false)
    val ctrlLatched: StateFlow<Boolean> = _ctrlLatched.asStateFlow()

    init {
        viewModelScope.launch {
            // 只取 label/地址作页面标题与 TOFU 展示；连接本身交给 repository.connect。
            val entity = repository.findById(sessionId)
            host = entity?.host.orEmpty()
            port = entity?.port ?: 22
            _title.value = entity?.let {
                buildString {
                    append(it.label)
                    append(" · ")
                    append(it.username)
                    append('@')
                    append(it.host)
                }
            }
            startConnect()
        }
    }

    fun retry() = startConnect()

    /** TOFU 确认：指纹落库后必须重连一次（第一次握手是验证器故意掐断的）。 */
    fun confirmTrust() {
        val prompt = _state.value as? SshTerminalUiState.TrustPrompt ?: return
        viewModelScope.launch {
            repository.trustHostKey(sessionId, prompt.fingerprint)
            startConnect()
        }
    }

    /** 公钥变更：用户显式确认服务器换钥后才覆盖旧指纹并重连。 */
    fun replaceKeyAndReconnect() {
        val changed = _state.value as? SshTerminalUiState.KeyChanged ?: return
        viewModelScope.launch {
            repository.trustHostKey(sessionId, changed.actual)
            startConnect()
        }
    }

    fun pressHelperKey(key: SshHelperKey) {
        if (key == SshHelperKey.Ctrl) {
            _ctrlLatched.value = !_ctrlLatched.value
            return
        }
        val connection = (_state.value as? SshTerminalUiState.Connected)?.connection ?: return
        val view = connection.terminalClient.view ?: return
        val withCtrl = _ctrlLatched.value
        if (key.keyCode >= 0) {
            view.handleKeyCode(key.keyCode, if (withCtrl) KeyHandler.KEYMOD_CTRL else 0)
        } else if (key.codePoint >= 0) {
            view.inputCodePoint(key.codePoint, withCtrl, false)
        }
        if (withCtrl) _ctrlLatched.value = false
    }

    /** 会话级字号调节，直接推给 TerminalView（[com.mnbt.app.ssh.SshTerminalClient.setFontSize]）。 */
    fun adjustFontSize(delta: Int) {
        val client = (_state.value as? SshTerminalUiState.Connected)?.connection?.terminalClient
            ?: return
        client.setFontSize(client.fontSizeDp.value + delta)
    }

    /** 显式结束会话：关 channel + 从 registry 摘除。列表页的连接状态点会随之熄灭。 */
    fun endSession() {
        repository.disconnect(sessionId)
    }

    /**
     * 软键盘显隐。上游 Termux 靠它自己那条 extra-keys 键栏上的键盘键，我们的键栏没有那个键，
     * 所以要自己拉 IME —— [com.termux.view.TerminalView] 是 `onCheckIsTextEditor()=true` 的
     * 自定义 View，不是 EditText，框架不会因为点击/聚焦就替它弹输入法。
     *
     * 可见性判断用 `isAcceptingText()` 而不是 WindowInsets 的 ime()：后者要 API 30+，
     * 本应用 minSdk 26，在 26–29 上 `ime()` 恒为不可见，按钮就只剩"能弹不能收"。
     */
    fun toggleKeyboard() {
        val view = (_state.value as? SshTerminalUiState.Connected)?.connection?.terminalClient?.view ?: return
        val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
        val accepting = imm.isAcceptingText
        view.requestFocus()
        if (accepting) imm.hideSoftInputFromWindow(view.windowToken, 0)
        else imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun startConnect() {
        viewModelScope.launch {
            _state.value = SshTerminalUiState.Connecting
            try {
                when (val outcome = repository.connect(sessionId)) {
                    is ConnectOutcome.Success -> {
                        _ctrlLatched.value = false
                        _state.value = SshTerminalUiState.Connected(outcome.connection)
                    }

                    is ConnectOutcome.HostKeyPrompt ->
                        _state.value = SshTerminalUiState.TrustPrompt(
                            host = host,
                            port = port,
                            algorithm = outcome.algorithm,
                            fingerprint = outcome.fingerprint,
                        )
                    is ConnectOutcome.Failure ->
                        _state.value = if (outcome.error is SshError.HostKeyChanged) {
                            SshTerminalUiState.KeyChanged(outcome.error.expected, outcome.error.actual)
                        } else {
                            SshTerminalUiState.Failed(outcome.error.message)
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SshTerminalUiState.Failed(e.message ?: "SSH 连接异常")
            }
        }
    }
}
