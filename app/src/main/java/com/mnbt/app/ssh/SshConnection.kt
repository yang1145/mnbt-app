package com.mnbt.app.ssh

import com.termux.terminal.TerminalSession
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.future.AuthFuture
import org.apache.sshd.client.future.ConnectFuture
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.channel.PtyChannelConfiguration
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils

/**
 * 一次连接所需的全部输入。凭据是明文，只在这一层短暂存在：
 * 不落日志、不进 toString、不写回 Room。
 */
class SshTarget(
    val configId: Long,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKeyPem: String?,
    val passphrase: String?,
    val termType: String,
    /** null = 首次连接，走 TOFU 采集；非 null = 只认这把公钥。 */
    val knownHostFingerprint: String?,
) {
    override fun toString(): String = "SshTarget($username@$host:$port, id=$configId)"
}

sealed interface ConnectOutcome {

    class Success(val connection: SshConnection) : ConnectOutcome

    /** 首次连接：服务器公钥已采集，等用户确认后带上指纹重连一次。 */
    class HostKeyPrompt(val fingerprint: String, val algorithm: String) : ConnectOutcome

    class Failure(val error: SshError) : ConnectOutcome
}

/**
 * 一条活着的 SSH 会话：MINA 的 ClientSession + shell 通道 + Termux 的渲染会话。
 *
 * 线程模型：
 * - 读线程（[SshConnector.startReader] 起的）从 [ChannelShell.getInvertedOut] 拉数据喂给
 *   TerminalSession。故意不用 `setOut()`：那样 MINA 的 IO 线程会在 ByteQueue 写满时被 UI 阻塞住，
 *   而那个线程是整个 SshClient 共享的，卡住会连带拖死其它会话。
 * - 写方向由 [TerminalSession] 自己的 writer 线程调 [ShellChannel.write]，阻塞只影响它自己。
 * - [com.termux.terminal.TerminalSessionClient] 回调全部在主线程。
 */
class SshConnection internal constructor(
    val configId: Long,
    val label: String,
    val terminalClient: SshTerminalClient,
    val terminal: TerminalSession,
    private val session: ClientSession,
    private val shell: ChannelShell,
    val startedAt: Long,
) {

    fun close() {
        // finishIfRunning() 关掉 shell 通道并驱动 TerminalSession 的退出流程；
        // 读线程随后撞到 EOF，再调一次 onRemoteClosed 也无害（cleanupResources 幂等）。
        terminal.finishIfRunning()
        runCatching { session.close(false) }
    }
}

/**
 * 把 MINA 的 shell 通道适配成 [TerminalSession.Channel]。
 *
 * 三个方法都吞掉 IOException：连接断了之后写失败是常态，而 TerminalSession 的 writer 线程一旦
 * 抛出就会退出，之后 ByteQueue 写满会把主线程一起卡死（ANR）。真正的收尾交给读线程。
 */
private class ShellChannel(
    private val shell: ChannelShell,
    private val stdin: OutputStream,
) : TerminalSession.Channel {

    override fun write(data: ByteArray, offset: Int, count: Int) {
        try {
            stdin.write(data, offset, count)
        } catch (ignored: Exception) {
        }
    }

    override fun resize(columns: Int, rows: Int) {
        try {
            shell.sendWindowChange(columns, rows)
        } catch (ignored: Exception) {
        }
    }

    override fun close() {
        try {
            shell.close(true)
        } catch (ignored: Exception) {
        }
    }
}

/**
 * 建连流程。阻塞调用一律走带毫秒参数的 `future.await()`，
 * 不去碰 MINA 的属性配置（那套在 Android 上还要额外的 SharedPreferences 桥接）。
 */
internal object SshConnector {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val AUTH_TIMEOUT_MS = 20_000L
    private const val CHANNEL_TIMEOUT_MS = 15_000L
    private const val CLOSE_WAIT_MS = 5_000L
    private const val READ_CHUNK = 8192
    private const val INITIAL_COLUMNS = 80
    private const val INITIAL_ROWS = 24
    const val TRANSCRIPT_ROWS = 2000

    suspend fun connect(
        holder: SshClientHolder,
        target: SshTarget,
        terminalClient: SshTerminalClient,
    ): ConnectOutcome = withContext(Dispatchers.IO) {
        val address = InetSocketAddress.createUnresolved(target.host, target.port)
        val pinning = target.knownHostFingerprint?.let { PinningVerifier(it) }
        val capturing = if (pinning == null) CapturingVerifier() else null
        holder.register(address, pinning ?: capturing!!)

        val outcome = try {
            handshake(holder, target, terminalClient)
        } catch (e: Exception) {
            ConnectOutcome.Failure(classifyTransport(e, target))
        } finally {
            holder.unregister(address)
        }
        refine(outcome, target, capturing, pinning)
    }

    /**
     * 自己负责收尾：任何一条失败路径都要把已开的 shell / session 关掉，
     * 只有成功返回 [ConnectOutcome.Success] 时才把资源交给 [SshConnection]。
     */
    private fun handshake(
        holder: SshClientHolder,
        target: SshTarget,
        terminalClient: SshTerminalClient,
    ): ConnectOutcome {
        var sessionRef: ClientSession? = null
        var shellRef: ChannelShell? = null
        var handedOver = false
        try {
            val connectFuture: ConnectFuture =
                holder.instance.connect(target.username, target.host, target.port)
            if (!connectFuture.await(CONNECT_TIMEOUT_MS)) {
                return ConnectOutcome.Failure(SshError.Timeout(target.host, target.port))
            }
            if (!connectFuture.isConnected) {
                return ConnectOutcome.Failure(classifyTransport(connectFuture.exception, target))
            }

            val session: ClientSession = connectFuture.session
            sessionRef = session
            authenticate(session, target)?.let { return ConnectOutcome.Failure(it) }

            val pty = PtyChannelConfiguration().apply {
                // 显式给全 PTY 参数。不传的话会走 resolvePtyType → SttySupport，
                // 那条路要 System.exec("stty")，在 Android 上必然出问题（官方文档 Caveat Emptor 点名了）。
                ptyType = target.termType
                ptyColumns = INITIAL_COLUMNS
                ptyLines = INITIAL_ROWS
            }
            val shell: ChannelShell = session.createShellChannel(pty, emptyMap<String, String>())
            shellRef = shell
            // 必须在 open() 之前调用，之后再调没有效果。
            shell.setRedirectErrorStream(true)
            try {
                shell.open().verify(CHANNEL_TIMEOUT_MS)
            } catch (e: Exception) {
                return ConnectOutcome.Failure(SshError.NoShell(e.message ?: "通道打开失败"))
            }

            // 不调 setOut/setErr，改用 invertedOut：这样阻塞发生在下面自己起的读线程上，
            // 不会把 MINA 那个全 client 共享的 IO 线程拖住。
            val stdin: OutputStream = shell.invertedIn
            val stdout: InputStream = shell.invertedOut
            // 初始 80x24 只是占位：TerminalView 一 attach 就会经 updateSize → sendWindowChange 校正。
            val terminal = TerminalSession(ShellChannel(shell, stdin), TRANSCRIPT_ROWS, terminalClient)
            val connection = SshConnection(
                configId = target.configId,
                label = target.label,
                terminalClient = terminalClient,
                terminal = terminal,
                session = session,
                shell = shell,
                startedAt = System.currentTimeMillis(),
            )
            startReader(connection.label, shell, stdout, terminal)

            handedOver = true
            return ConnectOutcome.Success(connection)
        } finally {
            if (!handedOver) {
                shellRef?.let { runCatching { it.close(true) } }
                sessionRef?.let { runCatching { it.close(false) } }
            }
        }
    }

    private fun authenticate(session: ClientSession, target: SshTarget): SshError? {
        val pem = target.privateKeyPem
        if (!pem.isNullOrBlank()) {
            val provider = target.passphrase?.takeIf { it.isNotEmpty() }
                ?.let { FilePasswordProvider.of(it) }
                ?: FilePasswordProvider.EMPTY
            val pairs: Iterable<KeyPair>? = try {
                SecurityUtils.loadKeyPairIdentities(
                    session,
                    NamedResource.ofName("mnbt-ssh-key/${target.configId}"),
                    ByteArrayInputStream(pem.toByteArray(StandardCharsets.UTF_8)),
                    provider,
                )
            } catch (e: Exception) {
                return SshError.BadPrivateKey(e.message ?: e.javaClass.simpleName)
            }
            if (pairs == null || !pairs.iterator().hasNext()) {
                return SshError.BadPrivateKey("文件里没有可识别的私钥")
            }
            for (pair in pairs) session.addPublicKeyIdentity(pair)
        }
        val password = target.password
        if (!password.isNullOrEmpty()) session.addPasswordIdentity(password)

        val auth: AuthFuture = session.auth()
        if (!auth.await(AUTH_TIMEOUT_MS)) {
            return SshError.Timeout(target.host, target.port)
        }
        if (auth.isSuccess) return null
        return SshError.AuthFailed(auth.exception?.message ?: "服务器拒绝了提供的凭据")
    }

    private fun startReader(
        label: String,
        shell: ChannelShell,
        stdout: InputStream,
        terminal: TerminalSession,
    ) {
        thread(name = "SshReader[$label]", isDaemon = true) {
            val buffer = ByteArray(READ_CHUNK)
            var broken = false
            try {
                while (true) {
                    val n = stdout.read(buffer)
                    if (n < 0) break
                    if (n > 0) terminal.onRemoteOutput(buffer, 0, n)
                }
            } catch (e: Exception) {
                broken = true
            }
            // EOF 之后再等一次 CHANNEL_CLOSE，好把远端的退出码取回来。
            runCatching {
                shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), CLOSE_WAIT_MS)
            }
            val status = runCatching { shell.exitStatus }.getOrNull()
            terminal.onRemoteClosed(status ?: if (broken) -1 else 0)
        }
    }

    /**
     * 握手被验证器故意掐断时，MINA 抛出来的异常没有信息量，真正的原因在验证器里。
     * 这里把通用的 Failure 收敛成 TOFU 提示或公钥变更告警。
     */
    private fun refine(
        outcome: ConnectOutcome,
        target: SshTarget,
        capturing: CapturingVerifier?,
        pinning: PinningVerifier?,
    ): ConnectOutcome {
        if (outcome !is ConnectOutcome.Failure) return outcome

        val captured = capturing?.captured
        if (captured != null) {
            val fingerprint = SshFingerprint.of(captured)
            if (fingerprint != null) {
                return ConnectOutcome.HostKeyPrompt(fingerprint, SshFingerprint.algorithmOf(captured))
            }
        }
        val actual = pinning?.actual
        val expected = target.knownHostFingerprint
        if (actual != null && expected != null && actual != expected) {
            return ConnectOutcome.Failure(SshError.HostKeyChanged(expected, actual))
        }
        return outcome
    }

    private fun classifyTransport(e: Throwable?, target: SshTarget): SshError {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException ->
                    return SshError.Unreachable(target.host, target.port, "域名解析不到")
                is SocketTimeoutException ->
                    return SshError.Timeout(target.host, target.port)
                is ConnectException ->
                    return SshError.Unreachable(target.host, target.port, cause.message ?: "连接被拒绝")
            }
            cause = cause.cause
        }
        val text = e?.message.orEmpty()
        return SshError.Unknown(text.ifBlank { e?.javaClass?.simpleName ?: "未知原因" })
    }
}

/**
 * 一个进程内共享一个 [SshClient]：MINA 的 NIO2 线程池不便宜，而它本来就支持多路复用。
 *
 * 代价是 ServerKeyVerifier 是 client 级的全局设置，所以这里按 host:port 派发，并在连接结束后注销。
 * 查不到派发项时一律拒绝 —— 宁可连不上，也不默认信任。
 */
internal class SshClientHolder {

    private val verifiers = ConcurrentHashMap<String, ServerKeyVerifier>()

    private val dispatching = ServerKeyVerifier { session, remoteAddress, serverKey ->
        verifiers[key(remoteAddress)]?.verifyServerKey(session, remoteAddress, serverKey) ?: false
    }

    @Volatile
    private var client: SshClient? = null

    val instance: SshClient
        @Synchronized get() {
            val existing = client
            if (existing != null && existing.isStarted) return existing
            val created = SshClient.setUpDefaultClient()
            created.setServerKeyVerifier(dispatching)
            created.start()
            client = created
            return created
        }

    fun register(address: SocketAddress, verifier: ServerKeyVerifier) {
        verifiers[key(address)] = verifier
    }

    fun unregister(address: SocketAddress) {
        verifiers.remove(key(address))
    }

    private fun key(address: SocketAddress): String =
        (address as? InetSocketAddress)?.let { "${it.hostString}:${it.port}" } ?: address.toString()
}
