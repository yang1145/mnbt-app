package com.mnbt.app.data.remote.http

import android.os.Build
import com.mnbt.app.BuildConfig
import com.mnbt.app.data.local.secure.TokenVault
import java.util.UUID

/**
 * 本机身份。[id] 是安装级 UUID，存在 TokenVault 里，
 * 会随每个请求以 X-MNBT-Device 头发出，供服务端展示与吊销。
 */
class DeviceInfo(vault: TokenVault) {

    val id: String

    val model: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val androidVersion: String = Build.VERSION.RELEASE ?: "unknown"

    val appVersion: String = BuildConfig.VERSION_NAME

    /** 头部只允许 ASCII，非 ASCII 会让 OkHttp 在发请求时直接抛异常。 */
    val userAgent: String =
        asciiOnly("MNBT-App/$appVersion (Android $androidVersion; SDK ${Build.VERSION.SDK_INT})")

    init {
        val existing = vault.get(REF_DEVICE_ID)
        if (existing != null) {
            id = existing
        } else {
            id = UUID.randomUUID().toString()
            vault.put(REF_DEVICE_ID, id)
        }
    }

    private companion object {
        const val REF_DEVICE_ID = "device-id"

        fun asciiOnly(s: String): String = s.replace(Regex("[^\\x20-\\x7E]"), "")
    }
}
