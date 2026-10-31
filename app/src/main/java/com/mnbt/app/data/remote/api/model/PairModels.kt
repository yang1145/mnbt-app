package com.mnbt.app.data.remote.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 配对成功后 bridge 返回的 data（PRD §4.5）。 */
@Serializable
data class PairData(
    val token: String,
    val site: SiteInfo? = null,
    val host: HostBrief? = null,
)

@Serializable
data class SiteInfo(
    val name: String? = null,
    val base: String? = null,
    @SerialName("mnbt_version") val mnbtVersion: String? = null,
)

@Serializable
data class HostBrief(
    val user: String? = null,
    val domain: String? = null,
    val status: String? = null,
    val expire: String? = null,
    @SerialName("web_quota") val webQuota: Quota? = null,
    @SerialName("sql_quota") val sqlQuota: Quota? = null,
    @SerialName("traffic_quota") val trafficQuota: Quota? = null,
)

/**
 * 用量。max 为 0 或负数表示不限量，UI 要按"无上限"渲染，不能算出 0% 或除零。
 *
 * 单位口径照搬面板（契约 §5.1，别自作主张换算）：`unit` 服务端已给，
 * 网页/数据库空间=MB（核心按 `size/(1024*1000)` 写入，十进制），流量=GB
 * （库里 used 是字节、max 是 GB，插件把两者一起除以 1024³ 后统一报 GB）。
 */
@Serializable
data class Quota(
    val used: Double = 0.0,
    val max: Double = 0.0,
    val unit: String? = null,
) {
    val unlimited: Boolean get() = max <= 0.0
    val fraction: Double get() = if (unlimited) 0.0 else (used / max).coerceIn(0.0, 1.0)
}

@Serializable
data class DeviceInfoData(
    @SerialName("bridge_version") val bridgeVersion: String? = null,
    @SerialName("mnbt_version") val mnbtVersion: String? = null,
    /** 该站点 bridge 支持的 action 列表，缺省时按 bridge_version 推断 */
    val actions: List<String>? = null,
)
