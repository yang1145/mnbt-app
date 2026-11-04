package com.mnbt.app.domain.repo

import com.mnbt.app.data.local.db.CloudSiteDao
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.cloud.CloudSystemClient
import com.mnbt.app.data.remote.api.cloud.CloudSystemException
import com.mnbt.app.data.remote.api.cloud.CloudServerDto
import com.mnbt.app.data.remote.api.cloud.CloudTime
import com.mnbt.app.domain.model.CloudFlowItem
import com.mnbt.app.domain.model.CloudPowerAction
import com.mnbt.app.domain.model.CloudServerDetail
import com.mnbt.app.domain.model.CloudServerSummary
import com.mnbt.app.domain.model.CloudSiteStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 云服务器的读取与操作入口。列表数据是「Room 站点 × 内存快照」的组合：
 * 快照只在 [refresh] 成功后更新，冷启动/断网时 UI 拿到的是最近一次的稳定数据。
 *
 * 协议 v2：电源/VNC/流量全部走 `mnbt/cloud/server/{id}/...` 统一路由，无模块路由与探测；
 * 状态轮询复用详情端点。统一端点 404 = 服务商插件未升级到 v2，映射为升级引导文案。
 */
class CloudServerRepository(
    private val cloudSiteDao: CloudSiteDao,
    private val vault: TokenVault,
    private val client: CloudSystemClient,
) {

    private val auth = CloudAuth(cloudSiteDao, vault, client)

    private val serversState = MutableStateFlow<List<CloudServerSummary>>(emptyList())

    private val refreshMutex = Mutex()

    fun servers(): Flow<List<CloudServerSummary>> =
        combine(cloudSiteDao.observeAll(), serversState) { sites, cached ->
            val alive = sites.mapTo(HashSet()) { it.id }
            cached.filter { it.siteId in alive }
                .sortedWith(compareBy({ it.siteId }, { it.hostId }))
        }

    /**
     * 对每个站点并发拉取服务器列表（含 NEEDS_REPAIR 站点：令牌可能已被上一轮修好，
     * 拉成功即恢复 ACTIVE，避免误标站点永远出不了数据）。单站点失败不拖垮整体；
     * 只有 401（令牌确实失效，CloudAuth 已先做过 single-flight refresh-重试）才保持
     * NEEDS_REPAIR，其余错误（网络抖动等）保留上一份快照与站点状态，等下次刷新自愈。
     */
    suspend fun refresh(): Result<Unit> = asResult {
        refreshMutex.withLock {
            val sites = cloudSiteDao.observeAll().first()
            coroutineScope {
                sites.map { site ->
                    async {
                        try {
                            val data = auth.call(site.id) { cred ->
                                client.cloudServers(cred.site.baseUrl, cred.token)
                            }
                            val summaries = data.list.map { it.toSummary(site.id) }
                            serversState.update { current ->
                                current.filterNot { it.siteId == site.id } + summaries
                            }
                            if (site.status != CloudSiteStatus.ACTIVE.name) {
                                cloudSiteDao.updateStatus(site.id, CloudSiteStatus.ACTIVE.name)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is CloudSystemException && e.status == 401) {
                                serversState.update { current ->
                                    current.filterNot { it.siteId == site.id }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        Unit
    }

    suspend fun detail(siteId: Long, hostId: Long): Result<CloudServerDetail> = asResult {
        val data = auth.call(siteId) { cred ->
            client.cloudServerDetail(cred.site.baseUrl, cred.token, hostId)
        }
        val server = data.server
        CloudServerDetail(
            summary = server.toSummary(siteId),
            assignIp = server.assignIp,
            os = server.os,
            username = server.addition?.username,
            password = server.addition?.password,
            port = server.addition?.port,
            clientNotes = server.clientNotes,
            configText = server.configText,
        )
    }

    /**
     * 电源操作。operatePassword 由 UI 按站点 config.operate_password_required 决定传不传。
     * 失败原样包进 failure（含 CloudSystemException，UI 展示 msg 并允许用户改密码重试）。
     * 统一端点 404 = 服务商插件还是 v1（无该路由），映射为升级引导。
     */
    suspend fun power(
        siteId: Long,
        hostId: Long,
        action: CloudPowerAction,
        operatePassword: String? = null,
    ): Result<Unit> = asResult {
        val func = when (action) {
            CloudPowerAction.ON -> "on"
            CloudPowerAction.OFF -> "off"
            CloudPowerAction.REBOOT -> "reboot"
            CloudPowerAction.HARD_OFF -> "hard_off"
            CloudPowerAction.HARD_REBOOT -> "hard_reboot"
        }
        auth.call(siteId) { cred ->
            try {
                client.power(cred.site.baseUrl, cred.token, hostId, func, operatePassword)
            } catch (e: CloudSystemException) {
                throw if (isLegacy404(e)) legacyException(e) else e
            }
        }
        Unit
    }

    /**
     * 状态轮询：协议 v2 无独立 status 端点，复用详情端点取实时电源状态。
     * 返回 (powerStatus, powerDesc)。
     */
    suspend fun status(siteId: Long, hostId: Long): Result<Pair<String, String>> = asResult {
        val data = auth.call(siteId) { cred ->
            client.cloudServerDetail(cred.site.baseUrl, cred.token, hostId)
        }
        (data.server.powerStatus.orEmpty()) to (data.server.powerDesc.orEmpty())
    }

    /** 返回绝对 URL：data.url 不以 http 开头时拼站点根。统一端点 404 映射为升级引导。 */
    suspend fun vnc(siteId: Long, hostId: Long): Result<String> = asResult {
        auth.call(siteId) { cred ->
            val data = try {
                client.vnc(cred.site.baseUrl, cred.token, hostId)
            } catch (e: CloudSystemException) {
                throw if (isLegacy404(e)) legacyException(e) else e
            }
            normalizeVncUrl(cred.site.baseUrl, data.url)
        }
    }

    /** 归一化流量（§5.7，能力 `flow`）。v1 的模块回退端点已废除。 */
    suspend fun flow(siteId: Long, hostId: Long): Result<List<CloudFlowItem>> = asResult {
        auth.call(siteId) { cred ->
            client.flow(cred.site.baseUrl, cred.token, hostId).list.map { it.toDomain() }
        }
    }

    // ---- 内部工具 ----

    /**
     * 电源/VNC 是 v2 新增统一端点：v1 插件站点（未升级）会得到 404，
     * 此时不该报「接口不存在」这种没头绪的错，而是给出明确的升级引导。
     */
    private fun isLegacy404(e: CloudSystemException): Boolean =
        e.status == 404 || e.errorCode == "legacy_protocol"

    private fun legacyException(e: CloudSystemException): CloudSystemException =
        CloudSystemException(0, LEGACY_PROTOCOL_MESSAGE, "legacy_protocol")

    private fun normalizeVncUrl(baseUrl: String, url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) base + trimmed else "$base/$trimmed"
    }

    private fun CloudServerDto.toSummary(siteId: Long): CloudServerSummary = CloudServerSummary(
        siteId = siteId,
        hostId = id.toLong(),
        name = name.orEmpty(),
        productName = productName.orEmpty(),
        hostStatus = status.orEmpty(),
        module = module.orEmpty(),
        powerStatus = powerStatus.orEmpty(),
        powerDesc = powerDesc.orEmpty(),
        dedicateIp = dedicateIp,
        dueTimeEpochMs = CloudTime.parseEpochMs(dueTime),
        billingCycleName = billingCycleName,
        activeTimeEpochMs = CloudTime.parseEpochMs(activeTime),
    )

    private fun com.mnbt.app.data.remote.api.cloud.CloudFlowEntry.toDomain(): CloudFlowItem =
        CloudFlowItem(
            name = name ?: "流量",
            used = used,
            total = total,
            unit = unit.orEmpty(),
        )
}
