package com.mnbt.app.domain.repo

import android.database.sqlite.SQLiteConstraintException
import com.mnbt.app.data.local.db.CloudSiteDao
import com.mnbt.app.data.local.db.CloudSiteEntity
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.zjmf.ZjmfClient
import com.mnbt.app.data.remote.http.HostUrl
import com.mnbt.app.domain.model.CloudSite
import com.mnbt.app.domain.model.CloudSiteConfig
import com.mnbt.app.domain.model.CloudSiteStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** 站点显示名长度上限（云站点管理页重命名同口径）。 */
private const val SITE_NAME_MAX_LENGTH = 50

/**
 * 云站点（ZJMF-CBAP）条目的唯一写入口。不变量与 [HostRepository] 一致：
 * 令牌只进 [TokenVault]，Room 里只留 ref；删条目必须连带删 ref。
 */
class CloudSiteRepository(
    private val cloudSiteDao: CloudSiteDao,
    private val vault: TokenVault,
    private val client: ZjmfClient,
) {

    private val auth = CloudAuth(cloudSiteDao, vault, client)

    fun sites(): Flow<List<CloudSite>> =
        cloudSiteDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 扫码配对：规范化 baseUrl → mnbt/bind 换令牌 → TokenVault 存 JWT → Room 落行。
     * 同地址重扫 = 原地换绑（换 tokenRef、回填站点名、清空模块提示、清 NEEDS_REPAIR），
     * 对齐 HostRepository.pair 的语义。
     */
    suspend fun pairSite(baseUrl: String, code: String, deviceName: String): Result<CloudSite> = asResult {
        val normalized = HostUrl.normalize(baseUrl)
            ?: throw IllegalArgumentException("站点地址无效：请填写 http(s):// 开头的站点根地址")

        val existing = cloudSiteDao.findByBaseUrl(normalized)
        val data = client.bind(normalized, code.trim(), deviceName)

        val name = data.siteName?.takeIf { it.isNotBlank() } ?: displayHost(normalized)
        val tokenRef = "cloud-site-" + UUID.randomUUID()
        vault.put(tokenRef, data.jwt)

        val entity = if (existing != null) {
            existing.copy(
                name = name,
                tokenRef = tokenRef,
                status = CloudSiteStatus.ACTIVE.name,
                moduleHint = null,
                pairedAtEpochMs = System.currentTimeMillis(),
            )
        } else {
            CloudSiteEntity(
                name = name,
                baseUrl = normalized,
                tokenRef = tokenRef,
                status = CloudSiteStatus.ACTIVE.name,
                pairedAtEpochMs = System.currentTimeMillis(),
            )
        }

        val id = try {
            val rowId = cloudSiteDao.upsert(entity)
            if (existing != null) existing.id else rowId
        } catch (e: SQLiteConstraintException) {
            // findByBaseUrl 与 upsert 之间有并发窗口（两台设备同时扫同一个码）
            vault.remove(tokenRef)
            throw IllegalArgumentException("该站点已绑定，请先解绑后再试")
        }
        if (existing != null && existing.tokenRef != tokenRef) vault.remove(existing.tokenRef)

        entity.copy(id = id).toDomain()
    }

    /** 站点配置（operate_password_required 驱动电源操作的密码输入框）。401 走统一恢复策略。 */
    suspend fun siteConfig(siteId: Long): Result<CloudSiteConfig> = asResult {
        val data = auth.call(siteId) { cred -> client.config(cred.site.baseUrl, cred.token) }
        CloudSiteConfig(siteName = data.siteName, operatePasswordRequired = data.operatePasswordRequired)
    }

    /**
     * 先尽力通知服务端吊销（失败不阻塞本地清理 —— 用户可能正因为站点连不上才要解绑），
     * 再删 TokenVault 令牌与 Room 行。
     */
    suspend fun unbindSite(siteId: Long): Result<Unit> = asResult {
        val site = cloudSiteDao.getById(siteId) ?: throw IllegalArgumentException("站点不存在或已解绑")
        val token = vault.get(site.tokenRef)
        if (token != null) runCatching { client.unbind(site.baseUrl, token) }
        cloudSiteDao.delete(siteId)
        vault.remove(site.tokenRef)
        Unit
    }

    /** 重命名站点：只改 Room 的显示名（trim 后非空、≤50 字），令牌与绑定状态不动。 */
    suspend fun renameSite(siteId: Long, name: String): Result<Unit> = asResult {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "站点名称不能为空" }
        require(trimmed.length <= SITE_NAME_MAX_LENGTH) { "站点名称过长（最多 $SITE_NAME_MAX_LENGTH 个字）" }
        cloudSiteDao.updateName(siteId, trimmed)
        Unit
    }

    private fun CloudSiteEntity.toDomain(): CloudSite = CloudSite(
        id = id,
        name = name,
        baseUrl = baseUrl,
        status = if (status == CloudSiteStatus.ACTIVE.name) CloudSiteStatus.ACTIVE else CloudSiteStatus.NEEDS_REPAIR,
        pairedAtEpochMs = pairedAtEpochMs,
    )

    private fun displayHost(baseUrl: String): String {
        val url = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return url.host + port
    }
}
