package com.mnbt.app.data.remote.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 主机详情页（PRD §7.3）各取数 action 的负载 DTO。字段名以 tools/mock/server.mjs 的
 * dataFor() 为准；MNBT 历史上同一语义可能是布尔 / 0-1 / "0"/"1"，
 * 所以开关类字段一律收成 JsonElement，由 asFlag() 宽容判定。
 */

// ---- domain.list（gn=listurl）
@Serializable
data class DomainListData(
    val list: List<DomainEntry>? = null,
)

@Serializable
data class DomainEntry(
    val domain: String? = null,
    val port: Int? = null,
    val ssl: JsonElement? = null,
    val type: String? = null,
    val status: String? = null,
)

// ---- ssl.get（gn=getssl）
@Serializable
data class SslGetData(
    val ssl: SslInfo? = null,
    /** mock/bridge 顶层布尔；真实 MNBT 可能回 0/1 或字符串，用 asFlag() 判定 */
    @SerialName("force_https") val forceHttps: JsonElement? = null,
)

@Serializable
data class SslInfo(
    /** "2027-01-15 00:00:00"，也可能只有日期，parsePanelDate 两种都吃 */
    val expire: String? = null,
    val issuer: String? = null,
    val domains: List<String>? = null,
    /** valid / invalid / none 等，语义以 bridge 为准 */
    val status: String? = null,
)

// ---- monitor.list（gn=monitor_list）
@Serializable
data class MonitorListData(
    val list: List<MonitorTask>? = null,
)

@Serializable
data class MonitorTask(
    val id: Int? = null,
    val name: String? = null,
    val type: String? = null,
    val status: String? = null,
    /** URL 监控最近一次 HTTP 状态码，>=400 视作告警；资源监控没有这个字段 */
    @SerialName("last_code") val lastCode: JsonElement? = null,
)

// ---- site.cache.list（gn=cache_list）
@Serializable
data class CacheListData(
    val list: List<CacheRule>? = null,
)

@Serializable
data class CacheRule(
    val id: Int? = null,
    val url: String? = null,
    val type: String? = null,
    val seconds: Long? = null,
)

// ---- site.hotlink.get（gn=getfdl）
@Serializable
data class HotlinkGetData(
    /** mock 回 1/0 整数，不是布尔 */
    val open: JsonElement? = null,
)

// ---- site.init（gn=set_init&section=…），详情页只用 current 一个字段
@Serializable
data class SiteInitData(
    val section: String? = null,
    val current: String? = null,
)

// ---------------------------------------------------------------- 宽容取值 helpers

/** 布尔 / "true"/"false" / 1/0 / "1"/"0" 都判定成 Boolean，判不了返回 null。 */
fun JsonElement?.asFlag(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (this is JsonNull) return null
    p.booleanOrNull?.let { return it }
    return when (p.content.trim().lowercase(Locale.US)) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> null
    }
}

fun JsonElement?.asIntOrNull(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (this is JsonNull) return null
    return p.content.trim().toIntOrNull()
}

/** MNBT 常见的 "yyyy-MM-dd HH:mm:ss" 与 "yyyy-MM-dd"，解析失败返回 null。 */
fun parsePanelDate(text: String?): Long? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val patterns = arrayOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd",
    )
    for (pattern in patterns) {
        runCatching { SimpleDateFormat(pattern, Locale.US).parse(t)?.time }
            .getOrNull()?.let { return it }
    }
    return null
}

/** 距今剩余整天数（向下取整，已过期为负）。解析失败返回 null。 */
fun millisToDaysFromNow(millis: Long?, todayMillis: Long = System.currentTimeMillis()): Int? {
    val m = millis ?: return null
    return ((m - todayMillis) / 86_400_000L).toInt()
}
