package com.mnbt.app.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个对接 ZJMF-CBAP（智简魔方V10业务系统）的云站点，通过扫码绑定。
 *
 * 与 [HostEntity] 同样的安全边界：[tokenRef] 只是 TokenVault 里的键，
 * JWT 本体永不落 Room（db 文件无加密，adb backup / 取证可直接读取）。
 *
 * [status] 存的是 `com.mnbt.app.domain.model.CloudSiteStatus` 的名字：
 * ACTIVE 可用；NEEDS_REPAIR = 令牌被服务端明确拒绝且自动续期也失败，
 * 条目保留但要求重新扫码（对齐 HostEntity.needs_repair 的"不自动删"策略）。
 */
@Entity(
    tableName = "cloud_sites",
    indices = [Index(value = ["base_url"], unique = true)],
)
data class CloudSiteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 站点显示名：绑定后用 bind/config 的 site_name 回填，配对前用 baseUrl host 兜底 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 规范化后的站点根地址，无尾斜杠，例如 https://idc.example.com */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    @ColumnInfo(name = "token_ref")
    val tokenRef: String,

    @ColumnInfo(name = "status")
    val status: String,

    /** 已探测命中的模块名（mf_cloud / remf_cloud），胜者缓存，省掉重复探测 */
    @ColumnInfo(name = "module_hint")
    val moduleHint: String? = null,

    @ColumnInfo(name = "paired_at")
    val pairedAtEpochMs: Long,
)
