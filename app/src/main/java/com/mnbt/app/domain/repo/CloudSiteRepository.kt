package com.mnbt.app.domain.repo

import android.database.sqlite.SQLiteConstraintException
import com.mnbt.app.data.local.db.CloudSiteDao
import com.mnbt.app.data.local.db.CloudSiteEntity
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.MnbtJson
import com.mnbt.app.data.remote.api.cloud.CloudSystemClient
import com.mnbt.app.data.remote.api.cloud.CloudSystemException
import com.mnbt.app.data.remote.http.HostUrl
import com.mnbt.app.domain.model.CloudSite
import com.mnbt.app.domain.model.CloudSiteCapabilities
import com.mnbt.app.domain.model.CloudSiteConfig
import com.mnbt.app.domain.model.CloudSiteStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** 站点显示名长度上限（云站点管理页重命名同口径）。 */
private const val SITE_NAME_MAX_LENGTH = 50

/** 绑定/手输时发现服务端仍是 v1 协议（未升级插件）的统一对外文案。 */
internal const val LEGACY_PROTOCOL_MESSAGE =
    "该站点的对接插件版本过旧（开放协议 v1），请通知服务商升级到开放协议 v2 后再绑定"

/**
 * 云站点（MNBT 云开放协议 v2）条目的唯一写入口。不变量与 [HostRepository] 一致：
 * 令牌只进 [TokenVault]，Room 里只留 ref；删条目必须连带删 ref。
 */
class CloudSiteRepository(
    private val cloudSiteDao: CloudSiteDao,
    private val vault: TokenVault,
    private val client: CloudSystemClient,
) {

    private val auth = CloudAuth(cloudSiteDao, vault, client)

    fun sites(): Flow<List<CloudSite>> =
        cloudSiteDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 扫码/手输配对：规范化 baseUrl → mnbt/bind 换令牌 → 校验协议版本 →
     * TokenVault 存 JWT → Room 落行（含 systemType 与能力缓存）。
     * 同地址重扫 = 原地换绑（换 tokenRef、回填站点名、刷新能力缓存、清 NEEDS_REPAIR），
     * 对齐 HostRepository.pair 的语义。
     *
     * [systemType] 为二维码 type 字段（服务商自报系统标识）；手输绑定为空串。
     */
    suspend fun pairSite(
        baseUrl: String,
        code: String,
        deviceName: String,
        systemType: String = "",
    ): Result<CloudSite> = asResult {
        val normalized = HostUrl.normalize(baseUrl)
            ?: throw IllegalArgumentException("站点地址无效：请填写 http(s):// 开头的站点根地址")

        val existing = cloudSiteDao.findByBaseUrl(normalized)
        val data = client.bind(normalized, code.trim(), deviceName)

        // 协议版本门槛（协议 §2/§5.1）：扫码路径已由二维码 proto 挡住 v1 旧码，
        // 手输路径没有 proto 信息，以 bind 响应兜底校验。
        if (data.proto != null && data.proto < 2) {
            rollbackBind(normalized, data.jwt, tokenRef = null, existingTokenRef = null)
            throw CloudSystemException(0, LEGACY_PROTOCOL_MESSAGE)
        }

        val name = data.siteName?.takeIf { it.isNotBlank() } ?: displayHost(normalized)
        val tokenRef = "cloud-site-" + UUID.randomUUID()
        vault.put(tokenRef, data.jwt)

        val entity = if (existing != null) {
            existing.copy(
                name = name,
                tokenRef = tokenRef,
                status = CloudSiteStatus.ACTIVE.name,
                systemType = systemType.ifBlank { existing.systemType },
                capabilitiesJson = data.capabilities?.let { encodeCapabilities(it) },
                pairedAtEpochMs = System.currentTimeMillis(),
            )
        } else {
            CloudSiteEntity(
                name = name,
                baseUrl = normalized,
                tokenRef = tokenRef,
                status = CloudSiteStatus.ACTIVE.name,
                systemType = systemType,
                capabilitiesJson = data.capabilities?.let { encodeCapabilities(it) },
                pairedAtEpochMs = System.currentTimeMillis(),
            )
        }

        val id = try {
            val rowId = cloudSiteDao.upsert(entity)
            if (existing != null) existing.id else rowId
        } catch (e: SQLiteConstraintException) {
            // findByBaseUrl 与 upsert 之间有并发窗口（两台设备同时扫同一个码）
            rollbackBind(normalized, data.jwt, tokenRef, existing?.tokenRef)
            throw IllegalArgumentException("该站点已绑定，请先解绑后再试")
        }
        if (existing != null && existing.tokenRef != tokenRef) vault.remove(existing.tokenRef)

        entity.copy(id = id).toDomain()
    }

    /**
     * 站点配置（operatePasswordRequired + proto + capabilities 驱动电源密码框与功能显隐）。
     * 网络成功即刷新能力缓存；失败降级用上次缓存的能力（null 视为全能力），不挡 UI。
     */
    suspend fun siteConfig(siteId: Long): Result<CloudSiteConfig> = asResult {
        val entity = cloudSiteDao.getById(siteId)
        try {
            val data = auth.call(siteId) { cred -> client.config(cred.site.baseUrl, cred.token) }
            entity?.let {
                val cached = data.capabilities?.let { caps -> encodeCapabilities(caps) }
                if (it.capabilitiesJson != cached) cloudSiteDao.updateCapabilities(siteId, cached)
            }
            CloudSiteConfig(
                siteName = data.siteName,
                operatePasswordRequired = data.operatePasswordRequired,
                proto = data.proto ?: 2,
                capabilities = CloudSiteCapabilities.fromList(data.capabilities),
            )
        } catch (e: CloudSystemException) {
            if (entity == null) throw e
            // 网络抖动等非 401 失败：降级读缓存，401 由 auth.call 已标记 NEEDS_REPAIR 后上抛
            if (e.status == 401) throw e
            CloudSiteConfig(
                siteName = entity.name,
                operatePasswordRequired = false,
                proto = 2,
                capabilities = parseCapabilities(entity.capabilitiesJson),
            )
        }
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

    /** 绑定中途失败的自清理：吊销刚发的令牌 + 清掉密文库新 ref（若有）。 */
    private suspend fun rollbackBind(baseUrl: String, jwt: String, tokenRef: String?, existingTokenRef: String?) {
        runCatching { client.unbind(baseUrl, jwt) }
        tokenRef?.let { vault.remove(it) }
        existingTokenRef?.let { vault.remove(it) }
    }

    private fun encodeCapabilities(caps: List<String>): String = MnbtJson.encodeToString(caps)

    /** 缓存文本 → 能力对象；空/损坏一律按全能力处理（宽容策略）。 */
    internal fun parseCapabilities(json: String?): CloudSiteCapabilities = try {
        if (json.isNullOrBlank()) {
            CloudSiteCapabilities.FULL
        } else {
            CloudSiteCapabilities.fromList(MnbtJson.decodeFromString<List<String>>(json))
        }
    } catch (e: Exception) {
        CloudSiteCapabilities.FULL
    }

    private fun CloudSiteEntity.toDomain(): CloudSite = CloudSite(
        id = id,
        name = name,
        baseUrl = baseUrl,
        systemType = systemType,
        status = if (status == CloudSiteStatus.ACTIVE.name) CloudSiteStatus.ACTIVE else CloudSiteStatus.NEEDS_REPAIR,
        pairedAtEpochMs = pairedAtEpochMs,
    )

    private fun displayHost(baseUrl: String): String {
        val url = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return url.host + port
    }
}
