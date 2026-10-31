package com.mnbt.app.data.remote.http

import com.mnbt.app.data.remote.trust.PinAwareHostnameVerifier
import com.mnbt.app.data.remote.trust.PinnedTrustManager
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * 每台主机一个 OkHttpClient，但都从同一个 [root] 用 newBuilder() 派生，
 * 因此共享 Dispatcher 线程池与 ConnectionPool —— 多主机首页并发拉取时
 * 不会每台各起一套连接池。
 *
 * 按 pin 而非按主机缓存：指纹相同的主机本来就该复用同一个 client，
 * OkHttp 的 Address 相等性包含 SSLSocketFactory，不同 pin 不会串用连接。
 */
class HostHttpClientFactory(private val root: OkHttpClient) {

    private val pinned = ConcurrentHashMap<String, OkHttpClient>()

    fun clientFor(pin: String?): OkHttpClient {
        if (pin == null) return root
        return pinned.getOrPut(pin) { buildPinned(pin) }
    }

    private fun buildPinned(pin: String): OkHttpClient {
        val trustManager = PinnedTrustManager(pin = pin)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        return root.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(PinAwareHostnameVerifier(pin))
            .build()
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L

        /** UA / Accept / 设备号对所有主机都一样，装在 root 上，避免每台主机重复拼装。 */
        fun buildRoot(device: DeviceInfo, debugLogging: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", device.userAgent)
                        .header("Accept", "application/json")
                        .header("X-MNBT-Device", device.id)
                        .build()
                    chain.proceed(request)
                }
            if (debugLogging) {
                // BASIC 只记录方法/URL/状态码/耗时，不含头部与响应体，
                // 因此不会把 Bearer 令牌写进 logcat。
                builder.addInterceptor(
                    okhttp3.logging.HttpLoggingInterceptor().setLevel(
                        okhttp3.logging.HttpLoggingInterceptor.Level.BASIC,
                    ),
                )
            }
            return builder.build()
        }
    }
}
