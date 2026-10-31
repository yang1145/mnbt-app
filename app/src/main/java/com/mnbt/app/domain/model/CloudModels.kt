package com.mnbt.app.domain.model

enum class CloudPowerAction { ON, OFF, REBOOT, HARD_OFF, HARD_REBOOT }
enum class CloudSiteStatus { ACTIVE, NEEDS_REPAIR }

data class CloudSite(
    val id: Long, val name: String, val baseUrl: String,
    val status: CloudSiteStatus, val pairedAtEpochMs: Long,
)
data class CloudSiteConfig(val siteName: String, val operatePasswordRequired: Boolean)
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
