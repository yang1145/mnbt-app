package com.mnbt.app.data.remote.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 把 MNBT 的响应体解析成业务负载，失败时抛 [MnbtException]。
 *
 * 必须容忍四种形态，前三种都在 MNBT 现有代码里真实存在：
 *
 * A. `MPHX/Response.php::build` / `function.php::mnbt_json_encode`
 *    `{"success":true,"code":"...","msg":"...","redirect":null,"data":{...}}`
 *    注意 mnbt_json_encode 会把 $extra 同时塞进 data 和顶层（function.php:518-522），
 *    所以同一个字段可能出现在两个位置。bridge 插件走这一种。
 *
 * B. `user/api` 下的 php 里大量裸 `exit(json_encode([...]))`
 *    `{"qk":1,"code":"获取成功","msg":{"rows":[...]}}` —— msg 是负载不是提示文案；
 *    `{"qk":4,"code":"错误！禁止留空！"}` —— code 是提示文案；
 *    `{"code":"修改成功","phpversion":"7.4"}` —— 连 qk 都没有，成败无从判断。
 *    qk=1 成功、qk=4 失败是 MNBT 全站约定。
 *
 * C. 裸数组 / 裸标量，少数旧接口直接 `exit(json_encode(array_values($list)))`。
 *
 * D. 非 JSON。`function.php:238 sysmsg()` 会打出一整张
 *    `<title>站点提示信息</title>` 的 HTML 页然后 exit，主机到期、被封禁、
 *    面板关闭都走这条路；此外还有 WAF 拦截页和 PHP 致命错误输出。
 *
 * 判定成败的优先级：success 布尔 > qk > 数字 code > 已知错误文案。
 * 数字 code 沿用 Response::build 的规则（0/4/100/300 为失败），
 * 这与 bridge 协议"0=成功"相反，靠 success 布尔优先来化解。
 */
object EnvelopeParser {

    private const val MAX_RAW = 2048

    private val ENVELOPE_KEYS = setOf("success", "code", "msg", "redirect", "data", "qk")
    private val NUMERIC_FAILURE_CODES = setOf(0, 4, 100, 300)

    private const val SYSMSG_TITLE = "站点提示信息"
    private const val SYSMSG_HEADING = "<h3>站点提示信息</h3>"

    /** 返回业务负载；[MnbtException] 表示这次调用失败。 */
    fun parse(body: String?): JsonElement {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) throw MnbtException.Protocol("服务器返回了空响应", "")

        val root = tryParse(text) ?: throw classifyNonJson(text)
        if (root is JsonArray) return root

        val obj = root as? JsonObject
            ?: throw MnbtException.Protocol("无法识别的响应结构", truncate(text))

        detectFailure(obj)?.let { throw it }
        return payloadOf(obj)
    }

    private fun tryParse(text: String): JsonElement? {
        runCatching { MnbtJson.parseToJsonElement(text) }.getOrNull()?.let { return it }
        // 客户把 display_errors 打开时，PHP 会把 Warning/Notice 打在 JSON 前面
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val end = if (text[start] == '{') text.lastIndexOf('}') else text.lastIndexOf(']')
        if (end <= start) return null
        return runCatching {
            MnbtJson.parseToJsonElement(text.substring(start, end + 1))
        }.getOrNull()
    }

    private fun classifyNonJson(text: String): MnbtException {
        val lower = text.lowercase()
        if (text.contains(SYSMSG_TITLE) || lower.contains("id=\"error-page\"")) {
            val msg = extractSysmsgMessage(text)
            return classifyPhrase(msg)
                ?: MnbtException.Server(MnbtCodes.SERVER_ERROR, msg.ifBlank { "服务器返回了提示页" })
        }
        if (lower.contains("<html") || lower.startsWith("<!doctype")) {
            return MnbtException.Protocol(
                "服务器返回了网页而不是数据，可能被安全软件拦截",
                truncate(text),
            )
        }
        return MnbtException.Protocol("无法解析服务器响应", truncate(text))
    }

    private fun extractSysmsgMessage(html: String): String {
        val headingAt = html.indexOf(SYSMSG_HEADING)
        val from = if (headingAt >= 0) {
            headingAt + SYSMSG_HEADING.length
        } else {
            html.indexOf("<body").let { if (it >= 0) it else 0 }
        }
        val to = html.indexOf("</body>").let { if (it > from) it else html.length }
        return html.substring(from, to)
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * MNBT 源码里写死的拦截文案。逐字对应：
     * member.php:43 主机到期、member.php:44-45 面板关闭、member.php:47 封禁、
     * user/ajax.php:16 宝塔节点配置错误、user/ajax.php:11 未登录、
     * user/ajax.php:40 动作未匹配。
     */
    private fun classifyPhrase(text: String): MnbtException? = when {
        text.contains("控制面板已经") -> MnbtException.PanelClosed(text)
        text.contains("您的主机已到期") -> MnbtException.HostExpired(text)
        text.contains("您已被禁止登陆") -> MnbtException.AccountBanned(text)
        text.contains("宝塔服务器配置错误") -> MnbtException.NodeError(text)
        text.contains("请登陆") -> MnbtException.TokenRejected()
        text.contains("请求错误") -> MnbtException.Server(
            MnbtCodes.SERVER_ERROR,
            "服务器未识别该请求，请确认站点已安装 APP 对接插件",
        )
        else -> null
    }

    private fun detectFailure(root: JsonObject): MnbtException? {
        boolOf(root["success"])?.let { ok -> return if (ok) null else failureFrom(root) }
        intOf(root["qk"])?.let { qk -> return if (qk != 4) null else failureFrom(root) }

        val code = root["code"] as? JsonPrimitive ?: return null
        val numeric = code.intOrNull ?: code.content.toIntOrNull()
        if (numeric != null) {
            return if (numeric in NUMERIC_FAILURE_CODES) failureFrom(root) else null
        }
        // 只有字符串 code 且没有任何成败标记：MNBT 网页端的做法是当成功并把 code 弹出来，
        // 除了下面这几条固定的错误串。
        return classifyPhrase(code.content)
    }

    private fun failureFrom(root: JsonObject): MnbtException {
        val text = stringOf(root["msg"])?.takeIf { it.isNotBlank() }
            ?: stringOf(root["code"])?.takeIf { it.isNotBlank() }
            ?: "服务器返回错误"
        classifyPhrase(text)?.let { return it }

        return when (val code = intOf(root["code"])) {
            MnbtCodes.TOKEN_INVALID, MnbtCodes.HOST_REMOVED -> MnbtException.TokenRejected(text)
            MnbtCodes.HOST_EXPIRED -> MnbtException.HostExpired(text)
            MnbtCodes.ACCOUNT_BANNED -> MnbtException.AccountBanned(text)
            MnbtCodes.NODE_ERROR -> MnbtException.NodeError(text)
            MnbtCodes.PLUGIN_TOO_OLD -> MnbtException.PluginTooOld(text)
            MnbtCodes.THROTTLED ->
                MnbtException.Throttled(intOf(root["retry_after"]) ?: 60, text)
            else -> MnbtException.Server(code ?: MnbtCodes.SERVER_ERROR, text.withTraceId(root))
        }
    }

    /**
     * 契约 §3：`5000` = 服务端异常（核心致命错误、WAF 拦截页、非 JSON 输出都收敛到这里），
     * 包络里的 `data.trace_id` 是客服唯一能对上服务端日志的串，必须随文案带出去。
     * 负载已平铺到顶层，所以先看顶层再看 data；空串按「没有」处理。
     */
    private fun String.withTraceId(root: JsonObject): String {
        val fromData = (root["data"] as? JsonObject)?.let { stringOf(it["trace_id"]) }
        val traceId = stringOf(root["trace_id"])?.takeIf { it.isNotBlank() }
            ?: fromData?.takeIf { it.isNotBlank() }
            ?: return this
        return "$this（追踪号 $traceId）"
    }

    /**
     * 负载可能出现在 data、顶层扁平字段、或 msg 里，按此优先级合并取出。
     * data 内只剔除 qk（json_exit_success 注入的状态位），其余字段一律保留，
     * 避免误删真实负载里恰好叫 msg / code 的键。
     */
    private fun payloadOf(root: JsonObject): JsonElement {
        val extra = root.filterKeys { it !in ENVELOPE_KEYS }
        val data = root["data"]

        if (data is JsonObject) {
            val merged = LinkedHashMap(extra)
            merged.putAll(data.filterKeys { it != "qk" })
            return JsonObject(merged)
        }
        if (data != null && data !is JsonNull) return data
        if (extra.isNotEmpty()) return JsonObject(extra)

        val msg = root["msg"]
        if (msg is JsonObject || msg is JsonArray) return msg
        return JsonObject(emptyMap())
    }

    private fun boolOf(el: JsonElement?): Boolean? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.booleanOrNull?.let { return it }
        return when (p.content) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }

    private fun intOf(el: JsonElement?): Int? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.intOrNull ?: p.content.toIntOrNull()
    }

    private fun stringOf(el: JsonElement?): String? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_RAW) s else s.take(MAX_RAW) + "…"
}
