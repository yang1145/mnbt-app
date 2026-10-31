package com.mnbt.app.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import com.mnbt.app.ssh.SshConnection
import com.mnbt.app.ssh.SshTerminalClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * 把 [SshConnection] 的渲染会话接到 Compose 上。
 *
 * 这里只负责 View 与会话的绑定/解绑，连接生命周期由 [com.mnbt.app.ssh.SshSessionRegistry] 管：
 * 切走 tab 触发 onRelease 时**只解绑不关闭会话**，切回来重新 attach，屏幕内容和滚动位置都还在。
 *
 * 字号变化不在这里跟：[SshTerminalClient.setFontSize] 直接推给 view，
 * 所以 update 里不调 setTextSize —— 它每次都新建一个 TerminalRenderer，重组时纯属白扔。
 */
@Composable
fun TerminalSurface(
    connection: SshConnection,
    modifier: Modifier = Modifier,
) {
    val client = connection.terminalClient
    val session = connection.terminal

    AndroidView(
        modifier = modifier,
        factory = { context -> bind(TerminalView(context, null), client, session) },
        update = { view ->
            client.view = view
            if (view.currentSession !== session) view.attachSession(session)
        },
        // AndroidView 会把 view 回收进池子复用，复用路径不走 factory，得自己补一遍绑定。
        onReset = { view -> bind(view, client, session) },
        onRelease = { view ->
            view.attachSession(null)
            if (client.view === view) client.view = null
        },
    )
}

private fun bind(
    view: TerminalView,
    client: SshTerminalClient,
    session: TerminalSession,
): TerminalView = view.apply {
    // 用 null AttributeSet 构造，上游 XML 里的 focusable 一个都不会生效。不手动打开的话
    // requestFocus() 恒为 false：软键盘起不来，长按选择也会静默失效
    // （startTextSelectionMode 第一行 requestFocus 失败就直接 return）。
    isFocusable = true
    isFocusableInTouchMode = true
    setTerminalViewClient(client)
    // 必须先 setTextSize 再 attachSession：attachSession 内部就调 updateSize()，
    // 而 updateSize() 直接解引用 mRenderer.mFontWidth。TerminalView 没有默认字号。
    setTextSize(client.fontSizeDp.value)
    client.view = this
    attachSession(session)
    // 抢焦点：TerminalView 是自定义 View 不是 EditText，没有焦点时输入法连 showSoftInput 都不接。
    // factory 阶段视图还没挂上窗口，此刻 requestFocus() 恒为 false，所以要等 attach 之后再要。
    doOnAttach { requestFocus() }
}
