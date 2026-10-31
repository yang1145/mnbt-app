package com.mnbt.app.data.remote.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** 一张证书里用户需要看清的全部信息，用于"确认信任"弹窗。 */
data class CertificateInfo(
    val fingerprintSha256: String,
    val subject: String,
    val issuer: String,
    val serial: String,
    val signatureAlgorithm: String,
    val notBefore: Long,
    val notAfter: Long,
    val selfSigned: Boolean,
) {
    val fingerprintDisplay: String get() = CertFingerprint.forDisplay(fingerprintSha256)
}

sealed interface ProbeResult {
    /** 站点是 http://，没有证书可确认，UI 必须走红色明文警告分支 */
    data class Plaintext(val url: String) : ProbeResult

    /** 证书由系统 CA 正常签发，不需要用户确认，也不需要指纹兜底 */
    data class SystemTrusted(val info: CertificateInfo) : ProbeResult

    /** 自签 / 私有 CA / 过期 / 主机名不符，需要用户逐项核对后手工确认 */
    data class Untrusted(val info: CertificateInfo, val reason: String) : ProbeResult

    /** 连不上：地址写错、端口没开、被 WAF 拦在 TLS 握手之前 */
    data class Unreachable(val message: String) : ProbeResult
}

/**
 * 主动握手抓证书。
 *
 * 这里刻意用一个"什么都不拒绝"的 TrustManager，因为探测的唯一目的就是
 * 在用户还没决定信不信之前把证书取回来给他看。该 TrustManager 是文件私有的，
 * 正常业务请求走 [PinnedTrustManager]，不会经过这条路径。
 */
object CertificateProbe {

    suspend fun probe(url: HttpUrl, timeoutMs: Int = 10_000): ProbeResult =
        withContext(Dispatchers.IO) {
            if (url.scheme != "https") {
                return@withContext ProbeResult.Plaintext(url.toString())
            }
            val host = url.host
            val port = if (url.port == -1) 443 else url.port

            val collector = CollectingTrustManager()
            try {
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf<X509TrustManager>(collector), SecureRandom())
                val socket = context.socketFactory.createSocket(host, port) as SSLSocket
                socket.use {
                    it.soTimeout = timeoutMs
                    val params = it.sslParameters
                    params.serverNames = listOf(SNIHostName(host))
                    it.sslParameters = params
                    it.startHandshake()
                }
            } catch (e: IOException) {
                return@withContext ProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                return@withContext ProbeResult.Unreachable(e.message ?: "地址无效")
            }

            val chain = collector.chain
                ?: return@withContext ProbeResult.Unreachable("服务器未返回证书")
            val leaf = chain.first()
            val info = CertificateInfo(
                fingerprintSha256 = CertFingerprint.sha256(leaf),
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                serial = leaf.serialNumber.toString(16),
                signatureAlgorithm = leaf.sigAlgName,
                notBefore = leaf.notBefore?.time ?: 0L,
                notAfter = leaf.notAfter?.time ?: 0L,
                selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
            )

            val reason = try {
                SystemTrust.manager().checkServerTrusted(chain, collector.authType)
                null
            } catch (e: CertificateException) {
                e.message ?: "证书链校验未通过"
            } catch (e: IllegalArgumentException) {
                "证书链校验未通过"
            }

            if (reason == null) ProbeResult.SystemTrusted(info)
            else ProbeResult.Untrusted(info, reason)
        }

    private class CollectingTrustManager : X509TrustManager {
        var chain: Array<X509Certificate>? = null
            private set
        var authType: String = "RSA"
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            this.chain = chain
            this.authType = authType
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
