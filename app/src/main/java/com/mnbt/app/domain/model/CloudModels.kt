package com.mnbt.app.domain.model

enum class CloudPowerAction { ON, OFF, REBOOT, HARD_OFF, HARD_REBOOT }
enum class CloudSiteStatus { ACTIVE, NEEDS_REPAIR }

/** 一个通过「MNBT 云开放协议 v2」绑定的云站点（系统无关，type 为服务商自报的系统标识）。 */
data class CloudSite(
    val id: Long, val name: String, val baseUrl: String,
    /** 服务商系统标识（二维码 type 字段，如 zjmf/whmcs）；手输绑定为空串，仅展示 */
    val systemType: String,
    val status: CloudSiteStatus, val pairedAtEpochMs: Long,
)

/**
 * 站点能力协商结果（协议 §6）。服务端未下发 capabilities 数组时按全能力处理（宽容旧实现）；
 * 声明了却做不到的功能服务端自担，APP 不兜底。
 */
data class CloudSiteCapabilities(
    val hardPower: Boolean = true,
    val vnc: Boolean = true,
    val flow: Boolean = true,
    val credentials: Boolean = true,
    val configText: Boolean = true,
) {
    companion object {
        val FULL = CloudSiteCapabilities()

        /** 协议能力名 → 领域开关。raw=null 视为全能力。 */
        fun fromList(raw: List<String>?): CloudSiteCapabilities = when (raw) {
            null -> FULL
            else -> CloudSiteCapabilities(
                hardPower = "hard_power" in raw,
                vnc = "vnc" in raw,
                flow = "flow" in raw,
                credentials = "credentials" in raw,
                configText = "config_text" in raw,
            )
        }
    }
}

data class CloudSiteConfig(
    val siteName: String,
    val operatePasswordRequired: Boolean,
    /** 协议版本；服务端未给视为 2（宽容旧实现） */
    val proto: Int,
    val capabilities: CloudSiteCapabilities,
)

data class CloudServerSummary(
    val siteId: Long, val hostId: Long, val name: String, val productName: String,
    val hostStatus: String, val module: String,
    val powerStatus: String, val powerDesc: String,
    val dedicateIp: String?, val dueTimeEpochMs: Long?,
    val billingCycleName: String?, val activeTimeEpochMs: Long?,
)
data class CloudServerDetail(
    val summary: CloudServerSummary,
    val assignIp: String?, val os: String?,
    val username: String?, val password: String?, val port: Int?,
    val clientNotes: String?, val configText: String?,
)
data class CloudFlowItem(val name: String, val used: Double, val total: Double?, val unit: String)
