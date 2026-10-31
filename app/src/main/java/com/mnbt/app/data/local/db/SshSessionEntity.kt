package com.mnbt.app.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SSH 会话配置。与 [HostEntity] 完全独立 —— SSH 是通用客户端，
 * 不复用 MNBT 主机条目，也不经过 MNBT 服务器中转。
 *
 * 口令、私钥、私钥口令全部只存 TokenVault，这里只留引用键。
 */
@Entity(tableName = "ssh_sessions")
data class SshSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "host")
    val host: String,

    @ColumnInfo(name = "port")
    val port: Int = 22,

    @ColumnInfo(name = "username")
    val username: String,

    /** password | key */
    @ColumnInfo(name = "auth_type")
    val authType: String = AUTH_PASSWORD,

    @ColumnInfo(name = "password_ref")
    val passwordRef: String? = null,

    /** PEM/OpenSSH 私钥正文的 TokenVault 引用键 */
    @ColumnInfo(name = "private_key_ref")
    val privateKeyRef: String? = null,

    @ColumnInfo(name = "passphrase_ref")
    val passphraseRef: String? = null,

    /** 首次连接时用户接受的主机公钥指纹（TOFU），变更即视为中间人告警 */
    @ColumnInfo(name = "known_host_fingerprint")
    val knownHostFingerprint: String? = null,

    @ColumnInfo(name = "term_type")
    val termType: String = "xterm-256color",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,
) {
    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }
}
