package com.mnbt.app.ui.domainssl

import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/*
 * 域名/SSL 两页共用的 DTO 与状态转换。字段名对齐 app_plugins/mobile_bridge
 * 的 `docs/api-contract.md` §9。包络 meta 字段由 ignoreUnknownKeys 忽略。
 *
 * 契约实录（mobile_bridge §9）：
 * - domain.list        total:Int limit:Int
 *                      list[]{domain, port:Int, ssl:Boolean, type:"main"/"top"/"subdir", status, dir}
 *                      （dir 是绑定目录，'/'=整站绑定，对应网页面板表格的「目录」列）
 * - domain.subdir.list list[]{domain, dir}（已存在的「域名→目录」绑定）
 *                      dirs:String[]（运行目录下**可选**的子目录名 —— 添加弹窗的候选取这里）
 * - domain.add         {domain, port, added, bound, limit}（`dir` 传子目录名=目录绑定，空/`/`=顶级绑定）
 * - domain.del         {domain, port, deleted}
 * - domain.prefix.set  {domain, old_domain, port, dir, added, bound, limit}
 *                      （面板没有重命名原语：服务端是「删旧 + 按新前缀加新」两步）
 * - site.init?section=url  {btip:String, als:String}（A 记录提示；als='false' 时提示指向 btip）
 * - ssl.get            {ssl{expire, issuer, domains[], status}, force_https:Boolean, pem, key}
 * - ssl.apply/renew    {order_id, domains[], result, expire}
 * - ssl.custom         {saved, has_key, has_pem}（入参 pem/key）
 * - ssl.close          {closed}
 * - ssl.force_https    {force_https}（入参 qk=0|1）
 */

@Serializable
data class DomainItemDto(
    val domain: String = "",
    val port: Int = 0,
    val ssl: Boolean = false,
    val type: String = "",
    val status: String = "",
    /** 绑定目录；'/' 表示整站绑定，其余是运行目录下的子目录名 */
    val dir: String = "/",
) {
    val isMain: Boolean get() = type.equals("main", ignoreCase = true)
    val isTop: Boolean get() = dir.isBlank() || dir == "/"
}

@Serializable
data class DomainListPayload(
    val list: List<DomainItemDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
)

/** 域名列表页数据：列表 + 配额 + A 记录提示（提示取不到时为 null，不占版面）。 */
data class DomainPageData(
    val items: List<DomainItemDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val resolveHint: String? = null,
)

/** site.init(section=url)：核心只给这两个字段，域名列表走 domain.list。 */
@Serializable
data class DomainResolveHintPayload(
    val btip: String = "",
    val als: String = "false",
)

/**
 * `domain.subdir.list` 只声明候选目录名：契约里另有 `list[]{domain,dir}`（已存在的绑定），
 * 但添加弹窗只需要候选，不渲染的字段一律不声明，免得服务端类型一变整包解码失败。
 */
@Serializable
data class SubdirListPayload(val dirs: List<String> = emptyList())

/** domain.add / domain.del / domain.prefix.set 的回显合用一个 DTO，取各自有的字段。 */
@Serializable
data class DomainOpPayload(
    val domain: String = "",
    val port: Int = 0,
    val added: Boolean = false,
    val bound: Int = 0,
    val limit: Int = 0,
    val deleted: Boolean = false,
)

@Serializable
data class SslCertDto(
    val expire: String = "",
    val issuer: String = "",
    val domains: List<String> = emptyList(),
    val status: String = "",
)

@Serializable
data class SslGetPayload(
    val ssl: SslCertDto? = null,
    @SerialName("force_https") val forceHttps: Boolean = false,
    val pem: String = "",
    val key: String = "",
)

@Serializable
data class SslApplyPayload(
    @SerialName("order_id") val orderId: String = "",
    val domains: List<String> = emptyList(),
    val result: String = "",
    val expire: String = "",
)

@Serializable
data class SslCustomPayload(
    val saved: Boolean = false,
    @SerialName("has_key") val hasKey: Boolean = false,
    @SerialName("has_pem") val hasPem: Boolean = false,
)

@Serializable
data class SslClosePayload(val closed: Boolean = false)

@Serializable
data class SslForcePayload(
    @SerialName("force_https") val forceHttps: Boolean = false,
)

/**
 * 限流秒数并进文案。LoadState.Failed 没有 retryAfter 字段（UiBase 禁改，只能在本地补），
 * suffix 由调用方从 string 资源构造，保证中文文案全部留在 XML。
 */
internal fun withRetryHint(
    message: String,
    retryAfterSeconds: Int?,
    suffix: (Int) -> String,
): String =
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        message + suffix(retryAfterSeconds)
    } else {
        message
    }

/**
 * 写操作失败分类：与全页加载失败不同，写失败大多是 Snackbar 级；
 * 但凭证失效 / 主机封禁必须升级为整页形态（Blocked），由 UI 置只读。
 * tokenRepairMessage 由调用方从 string 资源取（域名/SSL 页共用同一句引导）。
 */
internal fun classifyWriteOutcome(
    outcome: HostOutcome<*>,
    tokenRepairMessage: String,
    retrySuffix: (Int) -> String,
): OpPhase = when (outcome) {
    is HostOutcome.HostBlocked -> OpPhase.Blocked(outcome.message)
    HostOutcome.TokenRejected, HostOutcome.CredentialMissing -> OpPhase.Blocked(tokenRepairMessage)
    is HostOutcome.Failed -> OpPhase.Error(withRetryHint(outcome.message, outcome.retryAfterSeconds, retrySuffix))
    is HostOutcome.Ready -> OpPhase.Idle // 调用方自行给 Done 文案
}

/** 写操作阶段：驱动 Snackbar 与按钮禁点。Blocked 会让整页转只读。 */
sealed interface OpPhase {
    data object Idle : OpPhase
    data object Working : OpPhase
    data class Done(val message: String) : OpPhase
    data class Error(val message: String) : OpPhase
    data class Blocked(val message: String) : OpPhase
}

/**
 * LoadState 只有 Failed(message, raw)，retryAfterSeconds 会在 toLoadState() 里丢失，
 * 所以这里先自行把「限流秒数」并进文案再转全页状态（UiBase 禁改，只能在本地补）。
 */
internal fun <T> HostOutcome<T>.toFeatureLoadState(retrySuffix: (Int) -> String): LoadState<T> =
    when (this) {
        is HostOutcome.Failed ->
            LoadState.Failed(withRetryHint(message, retryAfterSeconds, retrySuffix), raw)
        else -> toLoadState()
    }
