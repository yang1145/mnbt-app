package com.mnbt.app.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一台通过扫码绑定的 MNBT 主机。
 *
 * [tokenRef] 是 TokenVault 里的键，令牌本身永不落 Room —— Room 数据库没有加密，
 * 而 adb backup / 设备取证都能直接读到 db 文件。
 *
 * [pinnedFingerprint] 为 null 表示该站点由系统 CA 正常签发，不需要指纹兜底；
 * 非 null 表示用户手工确认过自签证书，值为叶子证书 DER 的 SHA-256（小写 hex，无分隔符）。
 */
@Entity(
    tableName = "hosts",
    indices = [Index(value = ["base_url"], unique = true)],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 规范化后的站点根地址，无尾斜杠，例如 https://pan.example.com 或 http://1.2.3.4/mnbt */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    @ColumnInfo(name = "label")
    val label: String,

    /** 配对时 MNBT 回传的用户名，只用于展示 */
    @ColumnInfo(name = "username")
    val username: String = "",

    @ColumnInfo(name = "token_ref")
    val tokenRef: String,

    @ColumnInfo(name = "pinned_fingerprint")
    val pinnedFingerprint: String? = null,

    /** baseUrl 是否为 http:// —— UI 需要据此常驻红色明文警告 */
    @ColumnInfo(name = "cleartext")
    val cleartext: Boolean = false,

    @ColumnInfo(name = "plugin_version")
    val pluginVersion: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long? = null,

    /**
     * 上一次 overview 的原始 JSON。冷启动时先用它渲染，再后台刷新，
     * 否则多主机首页在网络往返完成前是一片空白。
     */
    @ColumnInfo(name = "overview_cache")
    val overviewCache: String? = null,

    @ColumnInfo(name = "overview_cached_at")
    val overviewCachedAt: Long = 0,

    /**
     * true = 服务端明确拒绝过令牌（1001/未登录文案），条目保留但写操作全部封禁，
     * 直到重新扫码配对。PRD §4.6：不自动删除，用户自己决定何时重扫。
     */
    @ColumnInfo(name = "needs_repair")
    val needsRepair: Boolean = false,
)
