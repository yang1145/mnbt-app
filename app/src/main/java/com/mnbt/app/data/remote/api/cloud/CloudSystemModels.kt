package com.mnbt.app.data.remote.api.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「MNBT 云开放协议 v2」（docs/cloud-integration.md）的 DTO 集合。
 * 命名系统无关：协议对任何云业务系统（ZJMF / WHMCS / 自研面板…）一视同仁，
 * 服务商系统专属概念不允许出现在这里。
 */

/** 协议调用失败。UI 直接展示 [message]（服务端中文文案优先）；[status] 为 HTTP 码或包络 status。 */
class CloudSystemException(val status: Int, val msg: String, val errorCode: String? = null) : Exception(msg)

/** POST mnbt/bind 的 data */
@Serializable
data class CloudBindData(
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("site_name") val siteName: String? = null,
    /** 协议版本（v2 实现恒为 2）；缺省视为 2（宽容实现） */
    val proto: Int? = null,
    /** 能力清单（§6）；缺省视为全能力 */
    val capabilities: List<String>? = null,
)

/** POST mnbt/refresh 的 data：设备令牌（30 天滑动续期） */
@Serializable
data class CloudTokenData(
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** GET mnbt/config 的 data */
@Serializable
data class CloudConfigData(
    @SerialName("site_name") val siteName: String,
    @SerialName("operate_password_required") val operatePasswordRequired: Boolean = false,
    val proto: Int? = null,
    val capabilities: List<String>? = null,
)

/** addition 里的登录凭据（可能只给其中一部分；能力 `credentials`） */
@Serializable
data class CloudServerAddition(
    val username: String? = null,
    val password: String? = null,
    val port: Int? = null,
)

/**
 * 云服务器条目。列表（§5.5）、详情（§5.6）共用同一个形状：
 * 详情端点只是比列表多 assign_ip / os / addition / client_notes / config_text。
 */
@Serializable
data class CloudServerDto(
    val id: Int,
    val name: String? = null,
    @SerialName("product_name") val productName: String? = null,

    /** 业务状态（Active / Suspended …），服务商系统原值，APP 原样展示 */
    val status: String? = null,

    /**
     * v2 不再要求（v1 的模块直连路由已废除）。服务商可能仍下发，仅作展示元数据保留，
     * APP 不据此路由任何请求。
     */
    val module: String? = null,

    /** 电源状态：on / off / operating / pending / suspend / fault / unknown */
    @SerialName("power_status") val powerStatus: String? = null,
    @SerialName("power_desc") val powerDesc: String? = null,

    @SerialName("dedicate_ip") val dedicateIp: String? = null,

    /** ISO8601 或秒/毫秒时间戳字符串，由 [CloudTime.parseEpochMs] 统一转 epochMs */
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("billing_cycle_name") val billingCycleName: String? = null,
    @SerialName("active_time") val activeTime: String? = null,

    // ---- 以下字段仅详情端点（§5.6）返回 ----
    @SerialName("assign_ip") val assignIp: String? = null,
    val os: String? = null,
    val addition: CloudServerAddition? = null,
    @SerialName("client_notes") val clientNotes: String? = null,

    /** 服务端预格式化的配置文本，UI 原样展示（能力 `config_text`） */
    @SerialName("config_text") val configText: String? = null,
)

@Serializable
data class CloudServersData(val list: List<CloudServerDto> = emptyList())

@Serializable
data class CloudServerDetailData(val server: CloudServerDto)

/** POST mnbt/cloud/server/{id}/vnc 的 data。url 可能为相对路径，由仓库层拼站点根归一化。 */
@Serializable
data class CloudVncData(val url: String)

/** 流量条目（能力 `flow`，§5.7 归一化口径）。 */
@Serializable
data class CloudFlowEntry(
    val name: String? = null,
    val used: Double = 0.0,
    val total: Double? = null,
    val unit: String? = null,
)

@Serializable
data class CloudFlowData(val list: List<CloudFlowEntry> = emptyList())

/**
 * 服务端时间字段口径不一（ISO8601、秒时间戳、毫秒时间戳字符串），
 * 统一收敛为 epoch 毫秒；解析失败一律返回 null，调用方按「未知」渲染，不要猜。
 */
object CloudTime {

    fun parseEpochMs(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 纯数字：<=11 位按秒、>=12 位按毫秒
        if (text.all { it in '0'..'9' }) {
            return text.toLongOrNull()?.takeIf { it > 0 }?.let { if (text.length <= 11) it * 1000 else it }
        }

        // "2026-01-02 03:04:05" 这类空格分隔先归一成 T
        val normalized = text.replace(' ', 'T')
        return runCatching { java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(normalized).toEpochMilli() }
            .recoverCatching {
                java.time.LocalDateTime.parse(normalized).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }
            .recoverCatching {
                java.time.LocalDate.parse(normalized).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            .getOrNull()
    }
}
