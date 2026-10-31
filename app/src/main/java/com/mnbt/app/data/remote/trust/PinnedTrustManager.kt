package com.mnbt.app.data.remote.trust

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 证书指纹与用户确认过的值不一致：可能是正常续期，也可能是中间人。一律断开。 */
class PinnedCertificateException(
    val expected: String,
    val actual: String,
) : CertificateException("证书指纹不匹配，已拒绝连接")

/** 指纹对得上但证书已过期，用户需要重新确认一次信任。 */
class PinnedCertificateExpiredException(
    val notAfter: Date,
) : CertificateException("已信任的证书已过期")

object SystemTrust {

    fun manager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("系统未提供 X509TrustManager")
    }
}

/**
 * 两级信任：先交给系统 CA；系统不认时，若用户曾为该主机手工确认过指纹，
 * 就用指纹放行。
 *
 * [pin] 为 null 时行为与系统默认完全一致（不接受任何自签证书）。
 * 指纹绑定已经排除了中间人（攻击者拿不到对应私钥），所以命中指纹时不再要求
 * 主机名匹配 —— 这正是让客户用 IP:端口 直连自建面板的前提。
 */
class PinnedTrustManager(
    private val system: X509TrustManager = SystemTrust.manager(),
    private val pin: String?,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val pin = this.pin
        if (pin == null) {
            system.checkServerTrusted(chain, authType)
            return
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemFailure: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw systemFailure
            val actual = CertFingerprint.sha256(leaf)
            if (actual != pin) throw PinnedCertificateException(pin, actual)

            val now = Date()
            if (leaf.notAfter?.before(now) == true || leaf.notBefore?.after(now) == true) {
                throw PinnedCertificateExpiredException(leaf.notAfter)
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/**
 * 与 [PinnedTrustManager] 配对，判定条件与它保持一致：
 * 指纹命中 pin，或者退回平台默认的主机名校验。
 *
 * 必须有第二条：客户先给了自签证书（我们记下 pin），后来换成正规 CA 证书时，
 * 新证书指纹必然对不上旧 pin，只认指纹会把一个完全合法的连接拒掉。
 */
class PinAwareHostnameVerifier(pin: String) : HostnameVerifier {

    private val pin: String = pin
    private val platform: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = try {
            session.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        if (leaf != null && CertFingerprint.sha256(leaf) == pin) return true
        return platform.verify(hostname, session)
    }
}
