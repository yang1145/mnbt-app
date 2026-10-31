package com.mnbt.app.data.remote.api

import com.mnbt.app.data.remote.api.model.PairData
import com.mnbt.app.data.remote.http.DeviceInfo
import com.mnbt.app.data.remote.http.HostHttpClientFactory
import com.mnbt.app.data.remote.trust.PinnedCertificateException
import com.mnbt.app.data.remote.trust.PinnedCertificateExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * 与单台 MNBT 站点通信的唯一入口。
 *
 * 请求体用 `application/x-www-form-urlencoded`（契约 §1.2）：PHP 会原生把它填进 `$_POST`，
 * bridge 不需要再解析 `php://input`；更关键的是表单编码对字节透明，而 JSON 要求合法 UTF-8 ——
 * 宝塔站点上大量 PHP 文件是 GBK 编码，走 JSON 会在 file.save 上静默丢数据。
 * 文件内容用 [callBytes] 原字节提交；读侧反过来，服务端用 `content_base64` 传原字节。
 */
class MnbtClient(
    private val clientFactory: HostHttpClientFactory,
    private val device: DeviceInfo,
) {

    /** 还没拿到令牌时用：扫码配对、探测证书。 */
    data class Target(val baseUrl: String, val pin: String? = null)

    /** 已绑定的主机。token 只在调用时从 TokenVault 取出，不做长期持有。 */
    data class Credential(val baseUrl: String, val pin: String?, val token: String) {
        fun target(): Target = Target(baseUrl, pin)
    }

    /** 文件下载、数据库备份下载走这个，复用同一套指纹信任。 */
    fun rawClient(pin: String?): OkHttpClient = clientFactory.clientFor(pin)

    suspend fun pair(target: Target, code: String, deviceName: String): PairData {
        val params = linkedMapOf(
            "code" to code,
            "device_id" to device.id,
            "device_name" to deviceName.ifBlank { device.model },
            "device_model" to device.model,
            "app_version" to device.appVersion,
            "android_version" to device.androidVersion,
        )
        val payload = post(target, MnbtActions.PAIR, params, token = null)
        return decodePayload(payload)
    }

    suspend fun call(
        credential: Credential,
        action: String,
        params: Map<String, String> = emptyMap(),
        readTimeoutSeconds: Long = HostHttpClientFactory.READ_TIMEOUT_SECONDS,
    ): JsonElement = post(credential.target(), action, params, credential.token, readTimeoutSeconds)

    /**
     * 字节透明的表单 POST：值以原始字节百分号编码后进 body，
     * PHP 侧 urldecode 还原出的就是原字节 —— GBK 文件内容只能走这条路。
     * [call] 的 Map<String,String> 版本会把值 UTF-8 化，编辑非 UTF-8 文件时必须用本方法。
     */
    suspend fun callBytes(
        credential: Credential,
        action: String,
        params: Map<String, ByteArray>,
        readTimeoutSeconds: Long = HostHttpClientFactory.READ_TIMEOUT_SECONDS,
    ): JsonElement {
        val body = params.entries.joinToString("&") { (key, value) ->
            percentEncode(key.toByteArray(Charsets.UTF_8)) + "=" + percentEncode(value)
        }.toRequestBody(FORM_URLENCODED)
        val raw = execute(credential.target(), action, body, credential.token, readTimeoutSeconds)
        return EnvelopeParser.parse(raw)
    }

    /** 分片上传。分片切分策略由上层决定，这里只负责一次 multipart POST。 */
    suspend fun upload(
        credential: Credential,
        action: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        chunk: ByteArray,
        readTimeoutSeconds: Long = 300,
    ): JsonElement {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply { fields.forEach { (k, v) -> addFormDataPart(k, v) } }
            .addFormDataPart(
                fileField,
                fileName,
                chunk.toRequestBody(OCTET_STREAM, 0, chunk.size),
            )
            .build()
        val raw = execute(credential.target(), action, body, credential.token, readTimeoutSeconds)
        return EnvelopeParser.parse(raw)
    }

    private suspend fun post(
        target: Target,
        action: String,
        params: Map<String, String>,
        token: String?,
        readTimeoutSeconds: Long = HostHttpClientFactory.READ_TIMEOUT_SECONDS,
    ): JsonElement {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val raw = execute(target, action, body, token, readTimeoutSeconds)
        return EnvelopeParser.parse(raw)
    }

    private suspend fun execute(
        target: Target,
        action: String,
        body: okhttp3.RequestBody,
        token: String?,
        readTimeoutSeconds: Long,
    ): String {
        val request = Request.Builder()
            .url(endpoint(target.baseUrl, action))
            .post(body)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()

        val base = clientFactory.clientFor(target.pin)
        val client = if (readTimeoutSeconds == HostHttpClientFactory.READ_TIMEOUT_SECONDS) {
            base
        } else {
            base.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        }

        val response = try {
            enqueue(client, request)
        } catch (e: SSLException) {
            throw toCertificateError(e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw toNetworkError(e)
        }
        // enqueue 只把「发请求」放到 OkHttp 线程，读 body 仍然在当前线程；
        // 上层全部是 viewModelScope.launch{}（主线程），不切 IO 就是主线程网络，
        // 直接 NetworkOnMainThreadException 且 message 为空。
        return response.use { withContext(Dispatchers.IO) { handle(it) } }
    }

    private fun endpoint(baseUrl: String, action: String): HttpUrl {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw MnbtException.Unreachable("站点地址无效：$baseUrl", null)
        return base.newBuilder()
            .addPathSegment("index.php")
            .addQueryParameter("_r", ROUTE_PREFIX + action)
            .build()
    }

    private fun handle(response: Response): String {
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) return body

        // 非 2xx 也先看响应体：MNBT 经常用 200/500 携带自己的错误包络，
        // 那条信息比状态码有用得多。
        val semantic = runCatching { EnvelopeParser.parse(body) }.exceptionOrNull()
        if (semantic is MnbtException && semantic !is MnbtException.Protocol) throw semantic
        throw MnbtException.HttpStatus(response.code, describeStatus(response.code))
    }

    private fun describeStatus(status: Int): String = when (status) {
        401, 403 -> "服务器拒绝了请求（HTTP $status）。若站点启用了 WAF，可能是它拦下了 Authorization 头。"
        404 -> "接口不存在（HTTP 404）。请确认站点已安装 APP 对接插件，且填的是站点根地址。"
        405 -> "请求方法不被允许（HTTP 405）。"
        413 -> "请求体过大（HTTP 413），请调小上传分片。"
        500 -> "服务器内部错误（HTTP 500），可查看站点 runtime/logs/php-error.log。"
        502, 503, 504 -> "网关错误（HTTP $status），后端 PHP 进程可能已崩溃或超时。"
        else -> "服务器返回 HTTP $status"
    }

    private fun toCertificateError(e: SSLException): MnbtException {
        val root = rootCause(e)
        val message = when (root) {
            is PinnedCertificateException ->
                "服务器证书与上次确认的指纹不一致。若站点确实换了证书，请重新确认信任。"
            is PinnedCertificateExpiredException ->
                "已信任的证书已过期，请重新确认信任。"
            else -> {
                val detail = root?.message ?: e.message ?: e.javaClass.simpleName
                // Conscrypt 对"没有可信锚点"的固定措辞，也就是自签证书的首次访问
                if (detail.contains("Trust anchor") || detail.contains("certification path")) {
                    "站点证书不是由受信任机构签发的（宝塔自签证书很常见），可以查看证书详情后手工确认信任。"
                } else {
                    "TLS 握手失败：$detail"
                }
            }
        }
        return MnbtException.Certificate(message, e)
    }

    private fun toNetworkError(e: IOException): MnbtException {
        val message = when (e) {
            is SocketTimeoutException -> "连接超时，请检查网络后重试"
            is InterruptedIOException -> "连接超时，请检查网络后重试"
            is UnknownHostException -> "无法解析域名，请检查站点地址或本机网络"
            is ConnectException -> "无法连接服务器，请确认地址、端口与防火墙放行"
            else -> e.message ?: "网络错误"
        }
        return MnbtException.Unreachable(message, e)
    }

    private fun rootCause(e: Throwable): Throwable? {
        var current: Throwable? = e
        var guard = 0
        while (current?.cause != null && current.cause !== current && guard++ < 16) {
            current = current.cause
        }
        return current
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
        const val ROUTE_PREFIX = "/app/v1/"
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val FORM_URLENCODED = "application/x-www-form-urlencoded".toMediaType()

        /** RFC 3986 unreserved 之外全部 %XX；十六进制大写，与 PHP urldecode 完全互逆。 */
        fun percentEncode(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size + bytes.size / 4)
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                when {
                    c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A ||
                        c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E -> sb.append(c.toChar())
                    else -> sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
                }
            }
            return sb.toString()
        }
    }
}

/** 把包络里的负载反序列化成具体类型，字段不符时转成协议错误而不是崩在调用点。 */
inline fun <reified T> decodePayload(element: JsonElement): T = try {
    MnbtJson.decodeFromJsonElement(serializer<T>(), element)
} catch (e: SerializationException) {
    throw MnbtException.Protocol(
        "响应字段与预期不符：${e.message}",
        element.toString().take(2048),
    )
} catch (e: IllegalArgumentException) {
    throw MnbtException.Protocol(
        "响应字段与预期不符：${e.message}",
        element.toString().take(2048),
    )
}
