package com.mnbt.app.data.remote.trust

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** 证书指纹的计算与展示格式。存储用无分隔符小写 hex，UI 展示用冒号分隔大写。 */
object CertFingerprint {

    fun sha256(cert: X509Certificate): String = sha256(cert.encoded)

    fun sha256(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** 64 位 hex → "AB:CD:…"，供用户在确认弹窗里逐段比对。 */
    fun forDisplay(stored: String): String =
        stored.chunked(2).joinToString(":").uppercase()
}
