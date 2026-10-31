package com.mnbt.app.ssh

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 活着的 SSH 连接登记处。
 *
 * 终端 tab 要能同时开多条会话并来回切换，所以连接的生命周期必须脱离 Compose 的 composition：
 * 屏幕退出、旋转、切后台都不能把 shell 断掉。会话对象存这里，Compose 只订阅它暴露的
 * [com.termux.terminal.TerminalSession] 与 StateFlow。
 */
class SshSessionRegistry(context: Context) {

    private val appContext = context.applicationContext
    private val holder = SshClientHolder()
    private val live = ConcurrentHashMap<Long, SshConnection>()

    operator fun get(configId: Long): SshConnection? = live[configId]

    fun snapshot(): List<SshConnection> = live.values.toList()

    /**
     * @return [ConnectOutcome.HostKeyPrompt] 表示这是首次连接、服务器公钥待用户确认，
     *   此时不登记任何连接；调用方拿到指纹后用 [SshRepository.trustHostKey] 落库再连一次。
     */
    suspend fun connect(target: SshTarget): ConnectOutcome {
        SshPlatform.init(appContext)
        val terminalClient = SshTerminalClient(appContext, onFinished = { forget(target.configId) })
        val outcome = SshConnector.connect(holder, target, terminalClient)
        if (outcome is ConnectOutcome.Success) {
            val connection = outcome.connection
            live.put(target.configId, connection)?.close()
            // 远端可能在 put 之前就把 shell 关了（登录后立刻 exit 之类），
            // 那样 onSessionFinished 已经跑完，不会再有人来摘这一项。
            if (!connection.terminal.isRunning) forget(target.configId)
        }
        return outcome
    }

    fun disconnect(configId: Long) = forget(configId)

    fun disconnectAll() {
        live.keys.toList().forEach { forget(it) }
    }

    private fun forget(configId: Long) {
        live.remove(configId)?.close()
    }
}
