package com.mnbt.app.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.mnbt.app.BuildConfig
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Termux 把这两个回调接口拆在 Service 与 Activity 两侧，我们没有那层拆分，
 * 合成一个类，好在内部直接持有 [TerminalView] 去触发重绘与改字号。
 *
 * 全部回调都在主线程上（由 [TerminalSession] 的 MainThreadHandler 派发），可以直接碰 View。
 */
class SshTerminalClient(
    private val context: Context,
    initialFontSizeDp: Int = DEFAULT_FONT_SIZE_DP,
    private val onFinished: (TerminalSession) -> Unit = {},
) : TerminalSessionClient, TerminalViewClient {

    /** Compose 侧用 AndroidView 建好 TerminalView 之后回填。 */
    @Volatile
    var view: TerminalView? = null

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    private val _fontSizeDp = MutableStateFlow(initialFontSizeDp.coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP))
    val fontSizeDp: StateFlow<Int> = _fontSizeDp.asStateFlow()

    private val _sessionEnded = MutableStateFlow(false)
    val sessionEnded: StateFlow<Boolean> = _sessionEnded.asStateFlow()

    /** TerminalView 累加的捏合倍率，越过阈值就换一档字号并把它归 1。 */
    private var scaleFactor = 1.0f

    fun setFontSize(dp: Int) {
        val bounded = dp.coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)
        _fontSizeDp.value = bounded
        view?.setTextSize(bounded)
    }

    // region TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.invalidate()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        _title.value = changedSession.title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        _sessionEnded.value = true
        onFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val item = clipboard.primaryClip?.getItemAt(0) ?: return
        val text = item.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) session.write(text)
    }

    override fun onBell(session: TerminalSession) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(BELL_VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        view?.invalidate()
    }

    /** null = 用模拟器自己的默认光标样式。 */
    override fun getTerminalCursorStyle(): Int? = null

    // 下面七个 log* 在 TerminalSessionClient 与 TerminalViewClient 里签名完全一致，
    // 一次 override 同时满足两个接口；重复声明会撞 JVM signature。
    override fun logError(tag: String, message: String) = log(Log.ERROR, tag, message, null)

    override fun logWarn(tag: String, message: String) = log(Log.WARN, tag, message, null)

    override fun logInfo(tag: String, message: String) = log(Log.INFO, tag, message, null)

    override fun logDebug(tag: String, message: String) = log(Log.DEBUG, tag, message, null)

    override fun logVerbose(tag: String, message: String) = log(Log.VERBOSE, tag, message, null)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        log(Log.ERROR, tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) = log(Log.ERROR, tag, null, e)

    private fun log(priority: Int, tag: String, message: String?, e: Exception?) {
        if (priority < Log.INFO && !BuildConfig.DEBUG) return
        Log.println(priority, tag, buildString {
            if (message != null) append(message)
            if (e != null) {
                if (isNotEmpty()) append('\n')
                append(Log.getStackTraceString(e))
            }
        })
    }

    // endregion

    // region TerminalViewClient

    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        setFontSize(_fontSizeDp.value + if (scale > 1f) 1 else -1)
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) = Unit

    /** 返回键交给导航，不当 ESC —— 终端里的 ESC 由软键盘扩展行提供。 */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * false → TerminalView 用 InputType.TYPE_NULL，按上游注释这是"最正确"的取值。
     * 打开它只在三星自带键盘那种不重置内部状态的场景下才需要，代价是中文输入法拿不到候选窗。
     */
    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    // 没有 extra-keys 行，这四个修饰键状态恒为假，Ctrl/Alt 走软键盘自带的组合键。
    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    // endregion

    companion object {
        /** TerminalView 不自带默认字号，宿主必须先 setTextSize，否则 onDraw 直接 NPE。 */
        const val DEFAULT_FONT_SIZE_DP = 12
        const val MIN_FONT_SIZE_DP = 6
        const val MAX_FONT_SIZE_DP = 32
        private const val BELL_VIBRATE_MS = 50L
    }
}
