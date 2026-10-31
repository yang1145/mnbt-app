package com.mnbt.app.data.remote.api.zjmf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ZJMF-CBAP 调用失败。UI 直接展示 [message]（服务端中文文案优先）；[status] 为 HTTP 码或包络 status。 */
class ZjmfException(val status: Int, val msg: String, val errorCode: String? = null) : Exception(msg)

/** POST mnbt/bind 的 data */
@Serializable
data class ZjmfBindData(
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("site_name") val siteName: String? = null,
)

/** POST mnbt/refresh 的 data：addon 签发的设备令牌（30 天滑动续期） */
@Serializable
data class ZjmfTokenData(
    val jwt: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** GET mnbt/config 的 data */
@Serializable
data class ZjmfConfigData(
    @SerialName("site_name") val siteName: String,
    @SerialName("operate_password_required") val operatePasswordRequired: Boolean = false,
)

/** addon addition 里的登录凭据（可能只给其中一部分） */
@Serializable
data class ZjmfServerAddition(
    val username: String? = null,
    val password: String? = null,
    val port: Int? = null,
)

/**
 * 云服务器条目。列表（契约 4）、详情（契约 5）共用同一个形状：
 * 详情端点只是比列表多 assign_ip / os / addition / client_notes / config_text。
 */
@Serializable
data class ZjmfCloudServer(
    val id: Int,
    val name: String? = null,
    @SerialName("product_name") val productName: String? = null,

    /** 主机业务状态（Active / Suspended …），ZJMF host 表口径 */
    val status: String? = null,

    /** 模块直连路由用："mf_cloud" | "remf_cloud" */
    val module: String? = null,

    /** 电源状态：on / off / operating / pending / suspend / fault / unknown */
    @SerialName("power_status") val powerStatus: String? = null,
    @SerialName("power_desc") val powerDesc: String? = null,

    @SerialName("dedicate_ip") val dedicateIp: String? = null,

    /** ISO8601 或秒/毫秒时间戳字符串，由 [ZjmfTime.parseEpochMs] 统一转 epochMs */
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("billing_cycle_name") val billingCycleName: String? = null,
    @SerialName("active_time") val activeTime: String? = null,

    // ---- 以下字段仅详情端点（契约 5）返回 ----
    @SerialName("assign_ip") val assignIp: String? = null,
    val os: String? = null,
    val addition: ZjmfServerAddition? = null,
    @SerialName("client_notes") val clientNotes: String? = null,

    /** 服务端预格式化的配置文本，UI 原样展示 */
    @SerialName("config_text") val configText: String? = null,
)

@Serializable
data class ZjmfCloudServersData(val list: List<ZjmfCloudServer> = emptyList())

@Serializable
data class ZjmfCloudServerDetailData(val server: ZjmfCloudServer)

/** GET {module}/{id}/status 的 data。status 声明为可空：模块探测只认「有 status 字段」为命中。 */
@Serializable
data class ZjmfModuleStatusData(val status: String? = null, val desc: String? = null)

/** POST {module}/{id}/vnc 的 data。url 可能为相对路径，由仓库层拼站点根归一化。 */
@Serializable
data class ZjmfVncData(val url: String)

/** 流量条目。addon 归一化端点（契约 6）与模块回退端点（契约 10）共用。 */
@Serializable
data class ZjmfFlowItem(
    val name: String? = null,
    val used: Double = 0.0,
    val total: Double? = null,
    val unit: String? = null,
)

@Serializable
data class ZjmfFlowData(val list: List<ZjmfFlowItem> = emptyList())

/**
 * ZJMF 各接口的时间字段口径不一（ISO8601、秒时间戳、毫秒时间戳字符串），
 * 统一收敛为 epoch 毫秒；解析失败一律返回 null，调用方按「未知」渲染，不要猜。
 */
object ZjmfTime {

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
