package com.mnbt.app.data.remote.api.cloud

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
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
 * 与一个实现「MNBT 云开放协议 v2」（docs/cloud-integration.md）的云业务系统站点
 * 通信的唯一入口。无 Retrofit，手写 OkHttp，风格对齐 [com.mnbt.app.data.remote.api.MnbtClient]。
 *
 * 契约要点：
 * - URL = `{站点根}/console/v1/...`，鉴权 `Authorization: Bearer <jwt>`；
 * - 响应包络 `{status, msg, data}`：status===200 取 data；HTTP 401 或 body status===401
 *   都视为登录失效（抛 [CloudSystemException] 且 status=401，由仓库层走 refresh-重试）；
 * - 分支逻辑看 data.error_code（可能缺失）；429 时 data.retry_after 为秒；
 * - 所有 POST 请求体带 `_r`（当前毫秒时间戳字符串）防重放；
 * - POST body 用 `application/x-www-form-urlencoded`（与 MnbtClient 一致，
 *   服务端实现以表单读取，PHP/Node/Java 皆原生可读）；
 * - 超时 15 秒；TLS 用系统默认信任（clientFor(null) 即 root client）。
 *
 * v2 起所有服务器操作（电源/状态/VNC/流量）都走 `mnbt/cloud/server/{id}/...` 统一路由，
 * 不再有 v1 的 `{module}/{id}/...` 模块直连与探测逻辑。
 */
class CloudSystemClient(
    private val clientFactory: HostHttpClientFactory,
    private val device: DeviceInfo,
) {

    private val http: OkHttpClient by lazy {
        clientFactory.clientFor(null) // 系统 CA 信任，不做 pinning
            .newBuilder()
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // ---- 站点管理端点（mnbt/*） ----

    /** 配对：用扫码得到的配对码换站点级设备令牌（无 Bearer）。 */
    suspend fun bind(baseUrl: String, code: String, deviceName: String): CloudBindData =
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
    suspend fun refresh(baseUrl: String, token: String): CloudTokenData =
        decode(call(baseUrl, "mnbt/refresh", POST, token, form = emptyMap()))

    /** 尽力通知站点吊销本设备令牌；调用方（unbindSite）容忍失败。 */
    suspend fun unbind(baseUrl: String, token: String) {
        call(baseUrl, "mnbt/unbind", POST, token, form = emptyMap())
    }

    /** 站点配置与能力协商：operate_password_required + proto + capabilities。 */
    suspend fun config(baseUrl: String, token: String): CloudConfigData =
        decode(call(baseUrl, "mnbt/config", GET, token))

    suspend fun cloudServers(baseUrl: String, token: String): CloudServersData =
        decode(call(baseUrl, "mnbt/cloud/servers", GET, token))

    /**
     * 云服务器详情（§5.6）。v2 起兼作状态轮询源：下发电源指令后轮询本端点，
     * 以 power_status / power_desc 的变化观察执行结果。
     */
    suspend fun cloudServerDetail(baseUrl: String, token: String, hostId: Long): CloudServerDetailData =
        decode(call(baseUrl, "mnbt/cloud/server/$hostId", GET, token))

    /** 归一化流量（能力 `flow`，§5.7）。 */
    suspend fun flow(baseUrl: String, token: String, hostId: Long): CloudFlowData =
        decode(call(baseUrl, "mnbt/cloud/server/$hostId/flow", GET, token))

    /**
     * 电源操作（§5.8）。func ∈ on / off / reboot / hard_off / hard_reboot（后两个为可选能力
     * hard_power）。[clientOperatePassword] 仅当站点 config.operate_password_required=true 时
     * 由 UI 传入，为 null 时不带该字段。data 恒为 {}，200 即成功。
     */
    suspend fun power(
        baseUrl: String,
        token: String,
        hostId: Long,
        func: String,
        clientOperatePassword: String?,
    ) {
        val form = linkedMapOf("func" to func)
        if (clientOperatePassword != null) form["client_operate_password"] = clientOperatePassword
        call(baseUrl, "mnbt/cloud/server/$hostId/power", POST, token, form = form)
    }

    /** VNC 控制台地址（能力 `vnc`，§5.9）。url 可能为相对路径，由仓库层拼站点根。 */
    suspend fun vnc(baseUrl: String, token: String, hostId: Long): CloudVncData =
        decode(call(baseUrl, "mnbt/cloud/server/$hostId/vnc", POST, token, form = emptyMap()))

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
            ?: throw CloudSystemException(0, "站点地址无效：$baseUrl")
        return base.newBuilder()
            .addPathSegments("console/v1/${path.trimStart('/')}")
            .build()
    }

    /**
     * 统一包络解析：
     * HTTP 2xx 且 body.status==200 → data；
     * HTTP 401 或 body.status==401 → 401 语义（登录失效）；
     * 其他 → CloudSystemException(status, msg, data.error_code)。
     */
    private fun unwrap(httpCode: Int, body: String): JsonElement {
        if (httpCode == 401) throw CloudSystemException(401, LOGIN_EXPIRED)

        val root = runCatching { MnbtJson.parseToJsonElement(body.trim()) }.getOrNull()
        if (root !is JsonObject) {
            throw CloudSystemException(httpCode, "服务器返回了无法解析的数据（HTTP $httpCode）")
        }

        val status = intOf(root["status"]) ?: httpCode
        val msg = stringOf(root["msg"]).orEmpty()
        val data = root["data"]?.takeUnless { it is JsonNull } ?: JsonObject(emptyMap())
        val errorCode = (data as? JsonObject)?.let { stringOf(it["error_code"]) }
            ?: stringOf(root["error_code"])

        if (status == 401) throw CloudSystemException(401, msg.ifBlank { LOGIN_EXPIRED }, errorCode)
        if (status == 200 && httpCode in 200..299) return data

        var message = msg.ifBlank { fallbackMessage(status, httpCode) }
        val retryAfter = (data as? JsonObject)?.let { intOf(it["retry_after"]) }
        if (status == 429 && retryAfter != null && retryAfter > 0) message += "（$retryAfter 秒后可重试）"
        throw CloudSystemException(status, message, errorCode)
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
        throw CloudSystemException(0, "响应字段与预期不符：${e.message}")
    } catch (e: IllegalArgumentException) {
        throw CloudSystemException(0, "响应字段与预期不符：${e.message}")
    }

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

    private fun toCertificateError(e: SSLException): CloudSystemException =
        CloudSystemException(0, "TLS 握手失败：${e.message ?: e.javaClass.simpleName}", null)

    private fun toNetworkError(e: IOException): CloudSystemException {
        val message = when (e) {
            is SocketTimeoutException -> "连接超时，请检查网络后重试"
            is InterruptedIOException -> "连接超时，请检查网络后重试"
            is UnknownHostException -> "无法解析域名，请检查站点地址或本机网络"
            is ConnectException -> "无法连接服务器，请确认地址、端口与防火墙放行"
            else -> e.message ?: "网络错误"
        }
        return CloudSystemException(0, message)
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
