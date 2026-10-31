package com.mnbt.app.data.remote.api.zjmf

import com.mnbt.app.data.remote.api.MnbtJson
import com.mnbt.app.data.remote.http.DeviceInfo
import com.mnbt.app.data.remote.http.HostHttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 与一个 ZJMF-CBAP（智简魔方V10业务系统，ThinkPHP 6）站点通信的唯一入口。
 * 无 Retrofit，手写 OkHttp，风格对齐 [com.mnbt.app.data.remote.api.MnbtClient]。
 *
 * 契约要点（docs/cloud-integration.md）：
 * - URL = `{站点根}/console/v1/...`，鉴权 `Authorization: Bearer <jwt>`；
 * - 响应包络 `{status, msg, data}`：status===200 取 data；HTTP 401 或 body status===401
 *   都视为登录失效（抛 [ZjmfException] 且 status=401，由仓库层走 refresh-重试）；
 * - 分支逻辑看 data.error_code（可能缺失）；429 时 data.retry_after 为秒；
 * - 所有 POST 请求体带 `_r`（当前毫秒时间戳字符串）防重放；
 * - POST body 用 `application/x-www-form-urlencoded`（与 MnbtClient 一致，
 *   ThinkPHP 的 param() 原生可读，PHP 侧 $_POST 直接可用）；
 * - 超时 15 秒；TLS 用系统默认信任（第一版不做 pinning，clientFor(null) 即 root client）。
 */
class ZjmfClient(
    private val clientFactory: HostHttpClientFactory,
    private val device: DeviceInfo,
) {

    private val http: OkHttpClient by lazy {
        clientFactory.clientFor(null) // 系统 CA 信任，不做 pinning
            .newBuilder()
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // ---- 调用点：addon 管理端（mnbt/*） ----

    /** 配对：用扫码得到的配对码换站点级设备令牌（无 Bearer）。 */
    suspend fun bind(baseUrl: String, code: String, deviceName: String): ZjmfBindData =
        decode(
            call(
                baseUrl, "mnbt/bind", POST, token = null,
                form = linkedMapOf(
                    "code" to code,
                    "device_name" to deviceName.ifBlank { device.model },
                ),
            ),
        )

    /** 换新令牌。带当前 Bearer；成功后由仓库层更新 TokenVault 并重试原请求。 */
    suspend fun refresh(baseUrl: String, token: String): ZjmfTokenData =
        decode(call(baseUrl, "mnbt/refresh", POST, token, form = emptyMap()))

    /** 尽力通知站点吊销本设备令牌；调用方（unbindSite）容忍失败。 */
    suspend fun unbind(baseUrl: String, token: String) {
        call(baseUrl, "mnbt/unbind", POST, token, form = emptyMap())
    }

    /** 站点配置：operate_password_required 决定电源操作是否要带 client_operate_password。 */
    suspend fun config(baseUrl: String, token: String): ZjmfConfigData =
        decode(call(baseUrl, "mnbt/config", GET, token))

    suspend fun cloudServers(baseUrl: String, token: String): ZjmfCloudServersData =
        decode(call(baseUrl, "mnbt/cloud/servers", GET, token))

    suspend fun cloudServerDetail(baseUrl: String, token: String, hostId: Long): ZjmfCloudServerDetailData =
        decode(call(baseUrl, "mnbt/cloud/server/$hostId", GET, token))

    /** addon 归一化流量（契约 6）；404 时由仓库层回退到 [moduleFlow]。 */
    suspend fun addonFlow(baseUrl: String, token: String, hostId: Long): ZjmfFlowData =
        decode(call(baseUrl, "mnbt/cloud/server/$hostId/flow", GET, token))

    // ---- 调用点：模块直连路由（{module}/{id}/...，module ∈ mf_cloud | remf_cloud） ----

    /** 模块探测与状态查询共用：包络 200 且 data 有 status 字段才算命中。 */
    suspend fun moduleStatus(baseUrl: String, token: String, module: String, hostId: Long): ZjmfModuleStatusData =
        decode(call(baseUrl, "$module/$hostId/status", GET, token))

    /**
     * 电源操作。func ∈ on / off / reboot / hard_off / hard_reboot。
     * [clientOperatePassword] 仅当站点 config.operate_password_required=true 时由 UI 传入，
     * 为 null 时不带该字段。data 恒为 {}，200 即成功。
     */
    suspend fun modulePower(
        baseUrl: String,
        token: String,
        module: String,
        hostId: Long,
        func: String,
        clientOperatePassword: String?,
    ) {
        val form = linkedMapOf<String, String>()
        if (clientOperatePassword != null) form["client_operate_password"] = clientOperatePassword
        call(baseUrl, "$module/$hostId/$func", POST, token, form = form)
    }

    suspend fun moduleVnc(baseUrl: String, token: String, module: String, hostId: Long): ZjmfVncData =
        decode(call(baseUrl, "$module/$hostId/vnc", POST, token, form = emptyMap()))

    /**
     * 回退流量端点（契约 10）：形状不定，尽力解析。解析不出返回空列表而不抛错；
     * 端点本身 404 / 401 / 网络错误照常抛 [ZjmfException]（401 由仓库层续期重试、404 由调用方定夺）。
     */
    suspend fun moduleFlow(baseUrl: String, token: String, module: String, hostId: Long): List<ZjmfFlowItem> {
        val data = call(baseUrl, "$module/$hostId/flow", GET, token)
        return parseFlowLoose(data)
    }

    // ---- 包络与传输 ----

    private suspend fun call(
        baseUrl: String,
        path: String,
        method: String,
        token: String?,
        form: Map<String, String>? = null,
    ): JsonElement {
        val request = Request.Builder()
            .url(endpoint(baseUrl, path))
            .apply {
                if (method == POST) {
                    post(
                        FormBody.Builder()
                            .apply {
                                // 防重放：所有 POST 必须带 _r（当前毫秒时间戳字符串）
                                add("_r", System.currentTimeMillis().toString())
                                form?.forEach { (k, v) -> add(k, v) }
                            }
                            .build(),
                    )
                } else {
                    get()
                }
                if (token != null) header("Authorization", "Bearer $token")
            }
            .build()

        val response = try {
            enqueue(http, request)
        } catch (e: SSLException) {
            throw toCertificateError(e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw toNetworkError(e)
        }
        // 与 MnbtClient 相同：读 body 切到 IO，上层协程在主线程
        val body = response.use { withContext(Dispatchers.IO) { it.body?.string().orEmpty() } }
        return unwrap(response.code, body)
    }

    private fun endpoint(baseUrl: String, path: String): HttpUrl {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw ZjmfException(0, "站点地址无效：$baseUrl")
        return base.newBuilder()
            .addPathSegments("console/v1/${path.trimStart('/')}")
            .build()
    }

    /**
     * 统一包络解析：
     * HTTP 2xx 且 body.status==200 → data；
     * HTTP 401 或 body.status==401 → 401 语义（登录失效）；
     * 其他 → ZjmfException(status, msg, data.error_code)。
     */
    private fun unwrap(httpCode: Int, body: String): JsonElement {
        if (httpCode == 401) throw ZjmfException(401, LOGIN_EXPIRED)

        val root = runCatching { MnbtJson.parseToJsonElement(body.trim()) }.getOrNull()
        if (root !is JsonObject) {
            throw ZjmfException(httpCode, "服务器返回了无法解析的数据（HTTP $httpCode）")
        }

        val status = intOf(root["status"]) ?: httpCode
        val msg = stringOf(root["msg"]).orEmpty()
        val data = root["data"]?.takeUnless { it is JsonNull } ?: JsonObject(emptyMap())
        val errorCode = (data as? JsonObject)?.let { stringOf(it["error_code"]) }
            ?: stringOf(root["error_code"])

        if (status == 401) throw ZjmfException(401, msg.ifBlank { LOGIN_EXPIRED }, errorCode)
        if (status == 200 && httpCode in 200..299) return data

        var message = msg.ifBlank { fallbackMessage(status, httpCode) }
        val retryAfter = (data as? JsonObject)?.let { intOf(it["retry_after"]) }
        if (status == 429 && retryAfter != null && retryAfter > 0) message += "（$retryAfter 秒后可重试）"
        throw ZjmfException(status, message, errorCode)
    }

    private fun fallbackMessage(status: Int, httpCode: Int): String = when {
        httpCode !in 200..299 -> when (httpCode) {
            403 -> "服务器拒绝了请求（HTTP 403），可能被 WAF 拦截"
            404 -> "接口不存在（HTTP 404），请确认站点已安装对接插件"
            500 -> "服务器内部错误（HTTP 500）"
            502, 503, 504 -> "网关错误（HTTP $httpCode），请稍后重试"
            else -> "请求失败（HTTP $httpCode）"
        }
        status == 429 -> "请求过于频繁，请稍后重试"
        else -> "请求失败（status=$status）"
    }

    /** 字段不符时转成协议错误而不是崩在调用点。 */
    private inline fun <reified T> decode(element: JsonElement): T = try {
        MnbtJson.decodeFromJsonElement(serializer<T>(), element)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        throw ZjmfException(0, "响应字段与预期不符：${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ZjmfException(0, "响应字段与预期不符：${e.message}")
    }

    // ---- 契约 10：回退流量的尽力解析 ----

    private fun parseFlowLoose(data: JsonElement): List<ZjmfFlowItem> = runCatching {
        val array: JsonArray? = when (data) {
            is JsonArray -> data
            is JsonObject ->
                if (looksLikeFlow(data)) {
                    JsonArray(listOf(data))
                } else {
                    // 常见容器键，找不到就取第一个数组值兜底
                    (data["list"] ?: data["items"] ?: data["flows"] ?: data["traffic"] ?: data["data"]
                        ?: data.entries.firstOrNull { it.value is JsonArray }?.value) as? JsonArray
                }
            else -> null
        }
        array?.mapNotNull { el -> (el as? JsonObject)?.takeIf(::looksLikeFlow)?.let(::flowItemOf) }.orEmpty()
    }.getOrDefault(emptyList())

    /** 只认「有 used 字段」的对象为流量条目，避免把其他形状误读成 0 值。 */
    private fun looksLikeFlow(o: JsonObject): Boolean = o.containsKey("used")

    private fun flowItemOf(o: JsonObject): ZjmfFlowItem = ZjmfFlowItem(
        name = stringOf(o["name"]) ?: stringOf(o["title"]) ?: stringOf(o["label"]) ?: "流量",
        used = num(o["used"]) ?: num(o["used_amount"]) ?: 0.0,
        total = num(o["total"]) ?: num(o["total_amount"]),
        unit = stringOf(o["unit"]) ?: stringOf(o["unit_name"]),
    )

    // ---- 小工具 ----

    private fun stringOf(el: JsonElement?): String? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content
    }

    private fun intOf(el: JsonElement?): Int? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.intOrNull ?: p.content.toIntOrNull()
    }

    private fun num(el: JsonElement?): Double? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.content.toDoubleOrNull()
    }

    private fun toCertificateError(e: SSLException): ZjmfException =
        ZjmfException(0, "TLS 握手失败：${e.message ?: e.javaClass.simpleName}", null)

    private fun toNetworkError(e: IOException): ZjmfException {
        val message = when (e) {
            is SocketTimeoutException -> "连接超时，请检查网络后重试"
            is InterruptedIOException -> "连接超时，请检查网络后重试"
            is UnknownHostException -> "无法解析域名，请检查站点地址或本机网络"
            is ConnectException -> "无法连接服务器，请确认地址、端口与防火墙放行"
            else -> e.message ?: "网络错误"
        }
        return ZjmfException(0, message)
    }

    private suspend fun enqueue(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // 协程已取消时必须自己关掉，否则连接不会归还连接池
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val GET = "GET"
        const val POST = "POST"

        /** 401 双形态兜底后的统一对外文案（与仓库层 NEEDS_REPAIR 提示一致）。 */
        const val LOGIN_EXPIRED = "登录已失效，请重新扫码绑定"
    }
}
