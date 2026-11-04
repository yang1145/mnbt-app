package com.mnbt.app.domain.repo

import com.mnbt.app.data.local.db.CloudSiteDao
import com.mnbt.app.data.local.db.CloudSiteEntity
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.cloud.CloudSystemClient
import com.mnbt.app.data.remote.api.cloud.CloudSystemException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 401 双形态（HTTP 401 / 包络 status 401）兜底后的统一对外文案。 */
internal const val CLOUD_LOGIN_EXPIRED = "登录已失效，请重新扫码绑定"

/**
 * 把一次站点调用包成 Result。[CancellationException] 必须穿透 ——
 * 否则协程取消会被吞成 Result.failure，viewModelScope 取消后还会继续写状态。
 */
internal suspend inline fun <T> asResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * 云站点的令牌取用与 401 恢复策略，[CloudSiteRepository] 与 [CloudServerRepository] 共用。
 *
 * 令牌口径：addon 签发的站点级设备 JWT（30 天，滑动续期）。核心 JWT（约 1 天、无刷新）
 * 藏在服务端 addon 内部，APP 永远只接触设备令牌。
 *
 * 401 策略（HTTP 401 与包络 status===401 同义）：先调 `POST mnbt/refresh` 换新令牌
 * 并重试原请求一次；refresh 也失败 → 站点标记 NEEDS_REPAIR 并抛
 * 「登录已失效，请重新扫码绑定」。
 */
internal class CloudAuth(
    private val dao: CloudSiteDao,
    private val vault: TokenVault,
    private val client: CloudSystemClient,
) {

    /** 并发的 401 恢复只允许一个协程真正调 refresh，其余等锁后复用新令牌。 */
    private val refreshMutex = Mutex()

    data class Credential(val site: CloudSiteEntity, val token: String)

    /**
     * 站点不存在或令牌已从密文库丢失（Keystore 失效后重建）时返回 null，
     * 调用方必须按「登录失效」处理，而不是当成一次普通网络失败。
     */
    suspend fun credential(siteId: Long): Credential? {
        val site = dao.getById(siteId) ?: return null
        val token = vault.get(site.tokenRef) ?: return null
        return Credential(site, token)
    }

    /** 带恢复的认证调用。仅对 401 触发 refresh-重试；其余错误原样透传。 */
    suspend fun <T> call(siteId: Long, block: suspend (Credential) -> T): T {
        val cred = credential(siteId) ?: run {
            markNeedsRepair(siteId)
            throw CloudSystemException(401, CLOUD_LOGIN_EXPIRED)
        }
        return try {
            block(cred)
        } catch (first: CloudSystemException) {
            if (first.status != 401) throw first

            val refreshed = recoverAfter401(siteId, cred)
            try {
                block(refreshed)
            } catch (second: CloudSystemException) {
                if (second.status == 401) {
                    markNeedsRepair(siteId)
                    throw CloudSystemException(401, CLOUD_LOGIN_EXPIRED, second.errorCode)
                }
                throw second
            }
        }
    }

    /**
     * 401 后的续期，single-flight：列表/详情/状态等多个请求可能同时吃到同一个 401，
     * 不加锁会各自调一次 refresh —— refresh 是轮换式的（旧令牌即刻作废），
     * 后到的那个拿着已被作废的令牌必然失败，把好端端的站点误标成「需重新绑定」。
     * 拿到锁后先复查令牌是否已被其他协程换新（tokenRef 变了就直接复用），
     * 确认仍是最旧的才真正调 refresh。
     */
    private suspend fun recoverAfter401(siteId: Long, stale: Credential): Credential =
        refreshMutex.withLock {
            val latest = credential(siteId)
            if (latest != null && latest.site.tokenRef != stale.site.tokenRef) {
                latest
            } else {
                try {
                    refreshAndStore(latest ?: stale)
                } catch (e: CloudSystemException) {
                    markNeedsRepair(siteId)
                    throw CloudSystemException(401, CLOUD_LOGIN_EXPIRED, e.errorCode)
                }
            }
        }

    /** 换新令牌：新 ref 入库 → Room 指向新 ref → 旧 ref 从密文库移除（不留孤儿）。 */
    suspend fun refreshAndStore(cred: Credential): Credential {
        val data = client.refresh(cred.site.baseUrl, cred.token)
        val newRef = "cloud-site-" + UUID.randomUUID()
        vault.put(newRef, data.jwt)
        dao.updateToken(cred.site.id, newRef)
        vault.remove(cred.site.tokenRef)
        return cred.copy(site = cred.site.copy(tokenRef = newRef), token = data.jwt)
    }

    suspend fun markNeedsRepair(siteId: Long) {
        runCatching { dao.updateStatus(siteId, "NEEDS_REPAIR") }
    }
}
