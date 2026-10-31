package com.mnbt.app.ssh

import java.net.SocketAddress
import java.security.PublicKey
import java.util.Base64
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests

/**
 * 服务器公钥指纹。用 OpenSSH 的 `SHA256:<base64>` 形式，
 * 这样用户能拿 `ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub` 的输出直接对照。
 */
object SshFingerprint {

    fun of(key: PublicKey): String? = try {
        val raw = KeyUtils.getRawFingerprint(BuiltinDigests.sha256, key)
        if (raw == null) null else "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(raw)
    } catch (e: Exception) {
        null
    }

    /** `RSA` / `Ed25519` / `EC` 之类，只用于展示。 */
    fun algorithmOf(key: PublicKey): String = key.algorithm ?: "未知"
}

/**
 * TOFU 第一阶段：把服务器出示的公钥记下来，然后一律拒绝。
 *
 * 握手被掐断后调用方拿到指纹去问用户，接受后再用 [PinningVerifier] 重连一次。
 * 多花一个 TCP + KEX 往返，换来的是 [ServerKeyVerifier.verifyServerKey] 不必在 MINA 的 IO 线程上
 * 阻塞等用户点按钮 —— 那个线程是整个 SshClient 共享的，卡住会连带拖死其它会话。
 */
class CapturingVerifier : ServerKeyVerifier {

    @Volatile
    var captured: PublicKey? = null
        private set

    override fun verifyServerKey(
        clientSession: ClientSession,
        remoteAddress: SocketAddress,
        serverKey: PublicKey,
    ): Boolean {
        captured = serverKey
        return false
    }
}

/** TOFU 第二阶段：只认库里存着的那把公钥。 */
class PinningVerifier(private val expected: String) : ServerKeyVerifier {

    /** 服务器实际出示的指纹，用来在失败时区分"公钥变了"与"网络不通"。 */
    @Volatile
    var actual: String? = null
        private set

    override fun verifyServerKey(
        clientSession: ClientSession,
        remoteAddress: SocketAddress,
        serverKey: PublicKey,
    ): Boolean {
        val fingerprint = SshFingerprint.of(serverKey)
        actual = fingerprint
        return fingerprint != null && fingerprint == expected
    }
}
