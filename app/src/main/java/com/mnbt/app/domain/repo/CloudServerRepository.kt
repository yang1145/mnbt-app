package com.mnbt.app.domain.repo

import com.mnbt.app.data.local.db.CloudSiteDao
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.zjmf.ZjmfClient
import com.mnbt.app.data.remote.api.zjmf.ZjmfCloudServer
import com.mnbt.app.data.remote.api.zjmf.ZjmfException
import com.mnbt.app.data.remote.api.zjmf.ZjmfTime
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 云服务器的读取与操作入口。列表数据是「Room 站点 × 内存快照」的组合：
 * 快照只在 [refresh] 成功后更新，冷启动/断网时 UI 拿到的是最近一次的稳定数据。
 *
 * 模块名（mf_cloud / remf_cloud）决定了电源/状态/VNC 的直连路由前缀，
 * 三级缓存：内存 per-host → 站点级 moduleHint（Room）→ 现场探测。
 */
class CloudServerRepository(
    private val cloudSiteDao: CloudSiteDao,
    private val vault: TokenVault,
    private val client: ZjmfClient,
) {

    private val auth = CloudAuth(cloudSiteDao, vault, client)

    private val serversState = MutableStateFlow<List<CloudServerSummary>>(emptyList())

    /** hostId → module 的内存缓存，refresh/detail/探测命中后回填 */
    private val moduleCache = ConcurrentHashMap<Long, String>()

    private val refreshMutex = Mutex()

    fun servers(): Flow<List<CloudServerSummary>> =
        combine(cloudSiteDao.observeAll(), serversState) { sites, cached ->
            val alive = sites.mapTo(HashSet()) { it.id }
            cached.filter { it.siteId in alive }
                .sortedWith(compareBy({ it.siteId }, { it.hostId }))
        }

    /**
     * 对每个 ACTIVE 站点并发拉取服务器列表。单站点失败不拖垮整体：
     * 该站点置 NEEDS_REPAIR 并继续（401 时 CloudAuth 已先做过 refresh-重试一次）。
     */
    suspend fun refresh(): Result<Unit> = asResult {
        refreshMutex.withLock {
            val sites = cloudSiteDao.observeAll().first()
                .filter { it.status == CloudSiteStatus.ACTIVE.name }
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
                            summaries.forEach { rememberModule(site.id, it.hostId, it.module) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            auth.markNeedsRepair(site.id)
                            serversState.update { current ->
                                current.filterNot { it.siteId == site.id }
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
        rememberModule(siteId, hostId, server.module)
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
     * 电源操作。module 从缓存取（找不到现场探测）；operatePassword 由 UI 按站点
     * config.operate_password_required 决定传不传。失败原样包进 failure（含
     * ZjmfException，UI 展示 msg 并允许用户改密码重试）。
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
            val module = resolveModule(cred, hostId)
            client.modulePower(cred.site.baseUrl, cred.token, module, hostId, func, operatePassword)
        }
        Unit
    }

    /** 返回 (status, desc)，与模块直连 status 端点的 data 对应。 */
    suspend fun status(siteId: Long, hostId: Long): Result<Pair<String, String>> = asResult {
        auth.call(siteId) { cred ->
            val module = resolveModule(cred, hostId)
            val data = client.moduleStatus(cred.site.baseUrl, cred.token, module, hostId)
            (data.status.orEmpty()) to (data.desc.orEmpty())
        }
    }

    /** 返回绝对 URL：data.url 不以 http 开头时拼站点根。 */
    suspend fun vnc(siteId: Long, hostId: Long): Result<String> = asResult {
        auth.call(siteId) { cred ->
            val module = resolveModule(cred, hostId)
            val data = client.moduleVnc(cred.site.baseUrl, cred.token, module, hostId)
            normalizeVncUrl(cred.site.baseUrl, data.url)
        }
    }

    /**
     * 流量：先走 addon 归一化端点（契约 6）；端点 404（未部署该版本 addon）时
     * 回退到模块直连 flow 端点的尽力解析（契约 10）。
     */
    suspend fun flow(siteId: Long, hostId: Long): Result<List<CloudFlowItem>> = asResult {
        auth.call(siteId) { cred ->
            try {
                client.addonFlow(cred.site.baseUrl, cred.token, hostId).list.map { it.toDomain() }
            } catch (e: ZjmfException) {
                if (e.status != 404) throw e
                val module = resolveModule(cred, hostId)
                client.moduleFlow(cred.site.baseUrl, cred.token, module, hostId).map { it.toDomain() }
            }
        }
    }

    // ---- 模块探测 ----

    /**
     * 对候选 [moduleHint, "mf_cloud", "remf_cloud"] 依次试
     * `GET {module}/{id}/status`，包络 200 且 data 有 status 字段即命中；
     * 胜者写内存 per-host 缓存 + 站点级 moduleHint。令牌 401 直接上抛，
     * 让外层 CloudAuth 走 refresh-重试，而不是误判成「模块未找到」。
     */
    private suspend fun resolveModule(cred: CloudAuth.Credential, hostId: Long): String {
        moduleCache[hostId]?.let { return it }

        val candidates = listOfNotNull(
            cred.site.moduleHint?.takeIf { it.isNotBlank() },
            "mf_cloud",
            "remf_cloud",
        ).distinct()

        for (module in candidates) {
            val probe = runCatching {
                client.moduleStatus(cred.site.baseUrl, cred.token, module, hostId)
            }
            val status = probe.getOrNull()?.status
            if (!status.isNullOrBlank()) {
                moduleCache[hostId] = module
                if (cred.site.moduleHint != module) cloudSiteDao.updateModuleHint(cred.site.id, module)
                return module
            }
            val err = probe.exceptionOrNull()
            if (err is ZjmfException && err.status == 401) throw err
        }
        throw ZjmfException(
            0,
            "无法确定云服务器的模块类型（已尝试 ${candidates.joinToString("/")}），请刷新列表后重试",
        )
    }

    private suspend fun rememberModule(siteId: Long, hostId: Long, module: String?) {
        val m = module?.takeIf { it.isNotBlank() } ?: return
        moduleCache[hostId] = m
        val site = cloudSiteDao.getById(siteId) ?: return
        if (site.moduleHint != m) cloudSiteDao.updateModuleHint(siteId, m)
    }

    private fun normalizeVncUrl(baseUrl: String, url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) base + trimmed else "$base/$trimmed"
    }

    private fun ZjmfCloudServer.toSummary(siteId: Long): CloudServerSummary = CloudServerSummary(
        siteId = siteId,
        hostId = id.toLong(),
        name = name.orEmpty(),
        productName = productName.orEmpty(),
        hostStatus = status.orEmpty(),
        module = module.orEmpty(),
        powerStatus = powerStatus.orEmpty(),
        powerDesc = powerDesc.orEmpty(),
        dedicateIp = dedicateIp,
        dueTimeEpochMs = ZjmfTime.parseEpochMs(dueTime),
        billingCycleName = billingCycleName,
        activeTimeEpochMs = ZjmfTime.parseEpochMs(activeTime),
    )

    private fun com.mnbt.app.data.remote.api.zjmf.ZjmfFlowItem.toDomain(): CloudFlowItem =
        CloudFlowItem(
            name = name ?: "流量",
            used = used,
            total = total,
            unit = unit.orEmpty(),
        )
}
