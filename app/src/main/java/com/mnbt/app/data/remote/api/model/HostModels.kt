package com.mnbt.app.data.remote.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * host.overview 的负载（契约 §5.2）。
 *
 * 只声明三块配额：面板端没有按主机采集 CPU/内存/inode 的入口（核心只统计空间与流量），
 * `cpu`/`mem`/`inode_*` 恒为 `null`/`0`，画出来就是 0% 的假仪表，所以不声明也不渲染。
 *
 * 单位口径照搬面板（契约 §5.1）：网页/数据库空间=MB（十进制），流量=GB。
 */
@Serializable
data class HostOverview(
    @SerialName("web_quota") val webQuota: Quota? = null,
    @SerialName("sql_quota") val sqlQuota: Quota? = null,
    @SerialName("traffic_quota") val trafficQuota: Quota? = null,
)

/**
 * host.profile 的负载（§5.1，bridge 自实现）。
 * 契约 §5.2：原来的 `credential`（FTP/SQL 明文口令）块已从服务端删除，APP 这边也不再有对应字段；
 * 改口令走 `site.password.set`，属于「只改不读」。
 */
@Serializable
data class HostProfile(
    val user: String? = null,
    val domain: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    /** running / banned（契约 §5.1，来源是 MN_zj 状态列） */
    val status: String? = null,
    val expire: String? = null,
    @SerialName("expire_ts") val expireTs: Long? = null,
    val node: String? = null,
    /** 当前 PHP 版本，详情页「站点运行」分组要显示 */
    val php: String? = null,
    /** 最大可绑域名数（domain.add 超限返回 1007） */
    val ymbds: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val running: Boolean get() = status == null || status.equals("running", ignoreCase = true)
}

/**
 * 存进 Room overview_cache 的合并快照。
 * 冷启动先拿它渲染首屏，网络回来后 diff 更新（PRD §10.1）。
 */
@Serializable
data class HostSnapshot(
    val overview: HostOverview? = null,
    val profile: HostProfile? = null,
)

/** 到期剩余天数。无到期信息返回 null；已过期返回负数。 */
fun HostProfile?.daysToExpire(todayMillis: Long = System.currentTimeMillis()): Int? {
    val ts = this?.expireTs?.takeIf { it > 0 } ?: return null
    val days = ((ts * 1000L - todayMillis) / 86_400_000L)
    return days.toInt()
}
