package com.mnbt.app.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 全部机密的唯一存放处：MNBT Bearer 令牌、SSH 口令、SSH 私钥正文与私钥口令。
 *
 * Room 与 DataStore 里只允许出现这里返回的引用键（ref）。
 * 底层用 EncryptedSharedPreferences（Android Keystore 主密钥 + AES256-GCM），
 * 换实现时只需改这一个文件，调用方认 ref 不认存储。
 */
class TokenVault private constructor(private val prefs: SharedPreferences) {

    fun put(ref: String, secret: String) {
        prefs.edit().putString(ref, secret).apply()
    }

    fun get(ref: String): String? = prefs.getString(ref, null)

    fun remove(ref: String) {
        prefs.edit().remove(ref).apply()
    }

    /**
     * @param recovered true 表示原有密文库无法解密（Keystore 主密钥在系统升级或
     * 设备迁移后失效是已知故障），已被重建。此时所有 ref 全部失效，
     * UI 必须提示用户重新扫码绑定主机、重新录入 SSH 凭据，
     * 而不是静默地把它们当成"令牌错误"。
     */
    data class OpenResult(val vault: TokenVault, val recovered: Boolean)

    companion object {
        private const val FILE_NAME = "mnbt_secrets"

        fun open(context: Context): OpenResult {
            val app = context.applicationContext
            return try {
                OpenResult(TokenVault(create(app)), recovered = false)
            } catch (_: Exception) {
                app.deleteSharedPreferences(FILE_NAME)
                OpenResult(TokenVault(create(app)), recovered = true)
            }
        }

        private fun create(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
