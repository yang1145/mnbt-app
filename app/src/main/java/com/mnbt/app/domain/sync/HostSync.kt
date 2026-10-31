package com.mnbt.app.domain.sync

import com.mnbt.app.AppContainer
import com.mnbt.app.data.local.db.HostEntity
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.data.remote.api.model.HostProfile
import com.mnbt.app.data.remote.api.model.HostSnapshot
import com.mnbt.app.data.remote.api.model.HostOverview
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * 主机用量/档案的刷新编排（PRD §10.1「串行请求收敛」）。
 *
 * 单台主机 = host.overview 与 host.profile 并发；多台主机 = 外层再并发，
 * 任一台失败只影响它自己的行，不把整页拖成白屏。
 */
class HostSync(private val container: AppContainer) {

    suspend fun refresh(host: HostEntity): HostOutcome<HostSnapshot> = coroutineScope {
        val overview = async {
            hostCall(container, host.id, MnbtActions.HOST_OVERVIEW) {
                decodePayload<HostOverview>(it)
            }
        }
        val profile = async {
            hostCall(container, host.id, MnbtActions.HOST_PROFILE) {
                decodePayload<HostProfile>(it)
            }
        }
        val o = overview.await()
        val p = profile.await()
        // overview 与 profile 是两个独立请求：任一块成功就该显示，不能因为另一半失败
        // 把成功的那半一起丢掉（历史上 overview 被核心 exit() 截断时，会连带 PHP 版本也不显示）。
        // 只有鉴权/封禁这类整页级失败才短路；单块 Failed 则保留另一块的数据。
        if (o is HostOutcome.TokenRejected || p is HostOutcome.TokenRejected) {
            return@coroutineScope HostOutcome.TokenRejected
        }
        if (o is HostOutcome.CredentialMissing || p is HostOutcome.CredentialMissing) {
            return@coroutineScope HostOutcome.CredentialMissing
        }
        if (o is HostOutcome.HostBlocked) return@coroutineScope o
        if (p is HostOutcome.HostBlocked) return@coroutineScope p
        if (o is HostOutcome.Ready || p is HostOutcome.Ready) {
            val snapshot = HostSnapshot(
                overview = (o as? HostOutcome.Ready)?.data,
                profile = (p as? HostOutcome.Ready)?.data,
            )
            // 两块都拿到才算完整快照，可落缓存；半截快照只用于本次渲染，不覆盖好的缓存
            if (o is HostOutcome.Ready && p is HostOutcome.Ready) {
                container.hostDao.updateOverview(
                    id = host.id,
                    json = Json.encodeToString(HostSnapshot.serializer(), snapshot),
                    cachedAt = System.currentTimeMillis(),
                )
            }
            return@coroutineScope HostOutcome.Ready(snapshot)
        }
        // 上面已经处理过鉴权/封禁/任一成功，走到这里两块都只剩 Failed；
        // 以 overview 的失败原因为主（p 是 HostOutcome<HostProfile>，不能直接当返回值用）
        return@coroutineScope (o as? HostOutcome.Failed) ?: (p as? HostOutcome.Failed)
        ?: HostOutcome.Failed("主机信息获取失败")
    }

    /** 主机列表下拉刷新：全部并发，失败的主机单独标记。 */
    suspend fun refreshAll(hosts: List<HostEntity>): Map<Long, HostOutcome<HostSnapshot>> =
        coroutineScope {
            hosts.map { host -> host.id to async { refresh(host) } }
                .associate { (id, deferred) -> id to deferred.await() }
        }

    companion object {
        /** Room 里的快照坏掉时按空处理，不能让一条脏数据崩掉整个列表。 */
        fun decodeSnapshot(json: String?): HostSnapshot? = json?.let {
            runCatching {
                Json.decodeFromString(HostSnapshot.serializer(), it)
            }.getOrNull()
        }
    }
}
