package com.mnbt.app.data.remote.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 扫码得到的一次性配对信息：站点根地址 + 配对码。 */
data class PairQr(val baseUrl: String, val code: String)

/**
 * MNBT 站点地址的规范化与解析。
 *
 * 之所以要有这一层：客户站点可能部署在子目录（`https://x.com/panel/`）、
 * 可能带端口、可能是裸 IP、可能是 http。二维码里带的是完整 `index.php?_r=...`，
 * 而本地存的是「站点根」，两者必须可逆地互转，否则子目录部署会直接连不上。
 */
object HostUrl {

    private const val PAIR_ROUTE = "/app/v1/pair_page"

    fun parsePairQr(raw: String): PairQr? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val route = url.queryParameter("_r")?.trim().orEmpty()
        if (!route.trimEnd('/').endsWith(PAIR_ROUTE)) return null
        val code = url.queryParameter("c")?.trim()?.uppercase().orEmpty()
        if (code.isEmpty()) return null
        val base = baseOf(url) ?: return null
        return PairQr(base, code)
    }

    /** `https://x.com/panel/index.php?_r=…` → `https://x.com/panel` */
    fun baseOf(url: HttpUrl): String {
        val segments = url.pathSegments.toMutableList()
        while (segments.isNotEmpty() && segments.last().isBlank()) segments.removeAt(segments.size - 1)
        if (segments.isNotEmpty() && segments.last().equals("index.php", ignoreCase = true)) {
            segments.removeAt(segments.size - 1)
        }
        val path = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port$path"
    }

    /** 用户手输地址时的兜底：没写协议默认按 https 试。 */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        return baseOf(url)
    }

    fun isCleartext(baseUrl: String): Boolean = baseUrl.startsWith("http://")

    /**
     * 配对响应里 bridge 会回传它自己认为的站点 base。只有同源才采纳，
     * 否则服务端就能把 APP 引到另一台机器上去发令牌。
     */
    fun isSameOrigin(a: String, b: String): Boolean {
        val ua = a.toHttpUrlOrNull() ?: return false
        val ub = b.toHttpUrlOrNull() ?: return false
        return ua.scheme == ub.scheme && ua.host == ub.host && ua.port == ub.port
    }
}
