package com.mnbt.app.domain.call

import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.MnbtException
import com.mnbt.app.data.remote.http.HostHttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * 功能页调用 bridge 的统一出口。错误分类不是为了文案，而是为了 UI 的不同反应
 * （见 [MnbtException] 的注释）：凭证类要禁写并引导重扫，封禁类要整页只读。
 */
sealed interface HostOutcome<out T> {
    data class Ready<T>(val data: T) : HostOutcome<T>

    /** 令牌在密文库里没了（Keystore 重置）——与远端拒绝同等处理：引导重扫 */
    data object CredentialMissing : HostOutcome<Nothing>

    /** 远端明确拒绝当前令牌（1001 / 「请登陆」），条目已标记需重新配对 */
    data object TokenRejected : HostOutcome<Nothing>

    /** 主机到期/封禁/面板关闭：整页只读，禁一切写操作 */
    data class HostBlocked(val message: String) : HostOutcome<Nothing>

    data class Failed(
        val message: String,
        val retryAfterSeconds: Int? = null,
        val raw: String? = null,
    ) : HostOutcome<Nothing>
}

/**
 * 契约 §1.3：这些动作服务端自己就要跑几十秒（核心逐步 sleep、ACME 校验、解压/导入），
 * 30s 默认读超时会在服务端还没写完之前就断掉，而且断掉后 APP 看不到包络里的真实结果。
 * 超时集中在这一张表，调用点不要再各自传数字。
 */
private val LongRunningTimeouts = mapOf(
    MnbtActions.DEPLOY_RUN to 180L,
    MnbtActions.SSL_APPLY to 120L,
    MnbtActions.SSL_RENEW to 120L,
    MnbtActions.SSL_CUSTOM to 120L,
    MnbtActions.FILE_UNZIP to 120L,
    MnbtActions.FILE_COMPRESS to 120L,
    MnbtActions.DB_SQL_IMPORT to 120L,
    MnbtActions.DOMAIN_ADD to 60L,
    MnbtActions.DOMAIN_DEL to 60L,
    // 改前缀 = 服务端删旧 + 加新，两次宝塔写
    MnbtActions.DOMAIN_PREFIX_SET to 60L,
)

fun readTimeoutFor(action: String): Long =
    LongRunningTimeouts[action] ?: HostHttpClientFactory.READ_TIMEOUT_SECONDS

/**
 * 取凭证 → 调 action → 解码 → 分类失败。负载类型由 [decode] 决定，
 * 这样每个功能页只声明自己的 DTO，不让 JsonElement 漏进 ViewModel 之外。
 */
suspend fun <T> hostCall(
    container: AppContainer,
    hostId: Long,
    action: String,
    params: Map<String, String> = emptyMap(),
    readTimeoutSeconds: Long = readTimeoutFor(action),
    decode: (JsonElement) -> T,
): HostOutcome<T> {
    val host = container.hostDao.findById(hostId)
        ?: return HostOutcome.Failed("主机条目不存在，可能已被移除")
    val credential = container.hostRepository.credentialFor(host)
        ?: return HostOutcome.CredentialMissing

    return try {
        val payload = container.client.call(credential, action, params, readTimeoutSeconds)
        if (action == "device.ping" || action == "host.overview") {
            // 与站点通过一次真实鉴权 = 令牌又可用了，撤销此前的「需重新配对」标记
            if (host.needsRepair) container.hostDao.updateNeedsRepair(hostId, false)
        }
        HostOutcome.Ready(decode(payload))
    } catch (e: CancellationException) {
        throw e
    } catch (e: MnbtException) {
        classify(container, hostId, e)
    } catch (e: Exception) {
        HostOutcome.Failed(e.describe())
    }
}

/**
 * 非协议异常的兜底文案：NPE / IllegalStateException 这类常常连 message 都没有，
 * 只回「请求失败」等于把现场丢了，所以一律带上类名。
 */
private fun Throwable.describe(): String =
    "${javaClass.simpleName.ifEmpty { javaClass.name }}: ${message ?: "无消息"}"

private suspend fun classify(container: AppContainer, hostId: Long, e: MnbtException): HostOutcome<Nothing> =
    when (e) {
        is MnbtException.TokenRejected -> {
            container.hostDao.updateNeedsRepair(hostId, true)
            HostOutcome.TokenRejected
        }
        is MnbtException.HostExpired, is MnbtException.AccountBanned, is MnbtException.PanelClosed ->
            HostOutcome.HostBlocked(e.message)
        is MnbtException.Throttled ->
            HostOutcome.Failed(e.message, e.retryAfterSeconds)
        is MnbtException.Protocol ->
            HostOutcome.Failed(e.message, raw = e.raw)
        else ->
            HostOutcome.Failed(e.message)
    }

/**
 * 字节透明版本，专给 file.save 这类可能携带非 UTF-8 内容的写操作。
 * 值全部自己百分号编码，GBK/二进制原样过，PHP urldecode 后就是原始字节。
 */
suspend fun <T> hostCallBytes(
    container: AppContainer,
    hostId: Long,
    action: String,
    params: Map<String, ByteArray>,
    readTimeoutSeconds: Long = HostHttpClientFactory.READ_TIMEOUT_SECONDS,
    decode: (JsonElement) -> T,
): HostOutcome<T> {
    val host = container.hostDao.findById(hostId)
        ?: return HostOutcome.Failed("主机条目不存在，可能已被移除")
    val credential = container.hostRepository.credentialFor(host)
        ?: return HostOutcome.CredentialMissing

    return try {
        val payload = container.client.callBytes(credential, action, params, readTimeoutSeconds)
        if (host.needsRepair) container.hostDao.updateNeedsRepair(hostId, false)
        HostOutcome.Ready(decode(payload))
    } catch (e: CancellationException) {
        throw e
    } catch (e: MnbtException) {
        classify(container, hostId, e)
    } catch (e: Exception) {
        HostOutcome.Failed(e.describe())
    }
}
