package com.mnbt.app.domain.repo

import android.database.sqlite.SQLiteConstraintException
import com.mnbt.app.data.local.db.HostDao
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.MnbtClient
import com.mnbt.app.data.remote.http.HostUrl
import com.mnbt.app.data.remote.http.PairQr
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * 主机条目的唯一写入口。
 *
 * 两条不变量由这里守住：
 * 1. 令牌只进 [TokenVault]，Room 里只留 ref；删条目时必须连带删 ref，
 *    否则密文库里会堆满孤儿令牌。
 * 2. 配对响应里 bridge 回传的 site.base 只有与扫码地址同源才采纳，
 *    否则服务端能把 APP 引到别的机器上去发令牌。
 */
class HostRepository(
    private val hostDao: HostDao,
    private val vault: TokenVault,
    private val client: MnbtClient,
) {

    fun observeHosts(): Flow<List<HostEntity>> = hostDao.observeAll()

    fun observeHost(id: Long): Flow<HostEntity?> = hostDao.observeById(id)

    suspend fun findById(id: Long): HostEntity? = hostDao.findById(id)

    /**
     * [pin] 由调用方在证书确认页决定：系统 CA 正常签发时为 null，
     * 用户手工确认自签证书时为该证书的 SHA-256 指纹。
     *
     * 同地址重扫的两种命运：条目还带着可用令牌 → 拒绝（引导用现有条目）；
     * 条目已被标记需重新配对、或令牌在密文库里找不到（Keystore 重置）→
     * 原地换绑：只换 tokenRef/pin/凭据字段，label、概览缓存、创建时间都保留，
     * 这样「需重新配对」的行点「重新配对」不需要先解绑。
     */
    suspend fun pair(qr: PairQr, pin: String?, deviceName: String): HostEntity {
        val bound = hostDao.findByBaseUrl(qr.baseUrl)
        if (bound != null && !isRebindable(bound)) throw HostAlreadyBoundException(bound.baseUrl)

        val data = client.pair(MnbtClient.Target(qr.baseUrl, pin), qr.code, deviceName)

        val baseUrl = data.site?.base
            ?.takeIf { HostUrl.isSameOrigin(qr.baseUrl, it) }
            ?.let { HostUrl.normalize(it) }
            ?: qr.baseUrl

        val tokenRef = "host-" + UUID.randomUUID()
        vault.put(tokenRef, data.token)

        if (bound != null) {
            val rebound = bound.copy(
                username = data.host?.user.orEmpty(),
                tokenRef = tokenRef,
                pinnedFingerprint = pin,
                cleartext = HostUrl.isCleartext(baseUrl),
                pluginVersion = data.site?.mnbtVersion,
                needsRepair = false,
            )
            hostDao.update(rebound)
            vault.remove(bound.tokenRef)
            return rebound
        }

        val entity = HostEntity(
            baseUrl = baseUrl,
            label = data.site?.name?.takeIf { it.isNotBlank() } ?: displayHost(baseUrl),
            username = data.host?.user.orEmpty(),
            tokenRef = tokenRef,
            pinnedFingerprint = pin,
            cleartext = HostUrl.isCleartext(baseUrl),
            pluginVersion = data.site?.mnbtVersion,
            createdAt = System.currentTimeMillis(),
        )

        val id = try {
            hostDao.insert(entity)
        } catch (e: SQLiteConstraintException) {
            // findByBaseUrl 与 insert 之间有并发窗口（比如同时在两台设备上扫同一个码）
            vault.remove(tokenRef)
            throw HostAlreadyBoundException(baseUrl)
        }
        return entity.copy(id = id)
    }

    /**
     * 令牌已从密文库中丢失（Keystore 失效后重建）时返回 null，
     * 调用方应显示"需要重新扫码绑定"，而不是当成一次普通的网络失败。
     */
    suspend fun credentialFor(host: HostEntity): MnbtClient.Credential? {
        val token = vault.get(host.tokenRef) ?: return null
        return MnbtClient.Credential(host.baseUrl, host.pinnedFingerprint, token)
    }

    suspend fun rename(id: Long, label: String) = hostDao.updateLabel(id, label.trim())

    suspend fun setPin(id: Long, pin: String?) = hostDao.updatePin(id, pin)

    suspend fun cacheOverview(id: Long, json: String?) =
        hostDao.updateOverview(id, json, System.currentTimeMillis())

    suspend fun touch(id: Long) = hostDao.touchLastSeen(id, System.currentTimeMillis())

    /**
     * 先尽力通知服务端吊销，再清本地。远端吊销失败不阻断删除 ——
     * 用户可能正是因为站点连不上才要移除它。
     */
    suspend fun remove(id: Long) {
        val host = hostDao.findById(id) ?: return
        credentialFor(host)?.let { cred ->
            runCatching { client.call(cred, MnbtActions.DEVICE_REVOKE) }
        }
        hostDao.deleteById(id)
        vault.remove(host.tokenRef)
    }

    private fun isRebindable(host: HostEntity): Boolean =
        host.needsRepair || vault.get(host.tokenRef) == null

    private fun displayHost(baseUrl: String): String {
        val url = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return url.host + port
    }
}
