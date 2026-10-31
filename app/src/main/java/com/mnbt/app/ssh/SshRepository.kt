package com.mnbt.app.ssh

import com.mnbt.app.data.local.db.SshSessionDao
import com.mnbt.app.data.local.db.SshSessionEntity
import com.mnbt.app.data.local.secure.TokenVault
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * SSH 连接配置的唯一写入口，与 [SshSessionRegistry] 的分工：
 * 这里管持久化与凭据解密，registry 只管活着的连接。
 *
 * 不变量与 [com.mnbt.app.domain.repo.HostRepository] 一致：
 * 口令、私钥、私钥口令只进 [TokenVault]，Room 里只留 ref；删配置时必须连带删 ref。
 */
class SshRepository(
    private val dao: SshSessionDao,
    private val vault: TokenVault,
    private val registry: SshSessionRegistry,
) {

    fun observeConfigs(): Flow<List<SshSessionEntity>> = dao.observeAll()

    suspend fun findById(id: Long): SshSessionEntity? = dao.findById(id)

    /** 已经连着的话直接拿现成的，UI 切回来时不会看到重连。 */
    fun liveConnection(id: Long): SshConnection? = registry[id]

    /**
     * 新建（[id] 为 null）或更新一条连接配置。
     *
     * 三个机密参数都是"null = 保持原值"：编辑界面不会把已存的口令回填进输入框，
     * 用户没动那个框时不应该把它清空。传空串才是显式清除。
     */
    suspend fun save(
        id: Long?,
        label: String,
        host: String,
        port: Int,
        username: String,
        authType: String,
        password: String? = null,
        privateKeyPem: String? = null,
        passphrase: String? = null,
        termType: String = "xterm-256color",
    ): Long {
        val existing = id?.let { dao.findById(it) }
        val now = System.currentTimeMillis()

        val passwordRef = storeSecret(existing?.passwordRef, "ssh-pw", password)
        val keyRef = storeSecret(existing?.privateKeyRef, "ssh-key", privateKeyPem)
        val passphraseRef = storeSecret(existing?.passphraseRef, "ssh-pp", passphrase)

        val entity = SshSessionEntity(
            id = existing?.id ?: 0,
            label = label.trim(),
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            passwordRef = passwordRef,
            privateKeyRef = keyRef,
            passphraseRef = passphraseRef,
            // 换了地址就等于换了服务器，原来记的主机公钥必须作废，重新走一次 TOFU。
            knownHostFingerprint = if (existing != null && existing.host == host.trim() && existing.port == port) {
                existing.knownHostFingerprint
            } else {
                null
            },
            termType = termType,
            createdAt = existing?.createdAt ?: now,
            lastUsedAt = existing?.lastUsedAt,
        )

        return if (existing == null) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            entity.id
        }
    }

    /** @return 新的/沿用的 ref，或 null 表示这项机密已被清除。 */
    private fun storeSecret(existingRef: String?, prefix: String, value: String?): String? {
        if (value == null) return existingRef
        if (value.isEmpty()) {
            existingRef?.let(vault::remove)
            return null
        }
        val ref = existingRef ?: "$prefix-" + UUID.randomUUID()
        vault.put(ref, value)
        return ref
    }

    /**
     * 连接已有配置。已经有活连接时直接复用，不重新握手。
     */
    suspend fun connect(id: Long): ConnectOutcome {
        registry[id]?.let { return ConnectOutcome.Success(it) }

        val entity = dao.findById(id)
            ?: return ConnectOutcome.Failure(SshError.Unknown("连接配置不存在，可能已被删除"))

        val target = buildTarget(entity)
            ?: return ConnectOutcome.Failure(
                SshError.CredentialMissing(
                    if (entity.authType == SshSessionEntity.AUTH_KEY) "私钥" else "口令"
                )
            )

        val outcome = registry.connect(target)
        if (outcome is ConnectOutcome.Success) dao.touchLastUsed(id, System.currentTimeMillis())
        return outcome
    }

    /**
     * 用户确认信任服务器公钥后落库。落完再调一次 [connect] 才会真正连上——
     * 第一次握手是被验证器故意掐断的，必须重连。
     */
    suspend fun trustHostKey(id: Long, fingerprint: String) = dao.updateKnownHost(id, fingerprint)

    fun disconnect(id: Long) = registry.disconnect(id)

    suspend fun delete(id: Long) {
        registry.disconnect(id)
        val entity = dao.findById(id) ?: return
        dao.deleteById(id)
        entity.passwordRef?.let(vault::remove)
        entity.privateKeyRef?.let(vault::remove)
        entity.passphraseRef?.let(vault::remove)
    }

    /**
     * @return null 表示该有的机密在 [TokenVault] 里找不到了（Keystore 主密钥失效后库被重建），
     *   调用方应提示用户重新录入，而不是当成一次认证失败。
     */
    private fun buildTarget(entity: SshSessionEntity): SshTarget? {
        var password: String? = null
        var privateKeyPem: String? = null
        var passphrase: String? = null

        if (entity.authType == SshSessionEntity.AUTH_KEY) {
            val ref = entity.privateKeyRef ?: return null
            privateKeyPem = vault.get(ref) ?: return null
            entity.passphraseRef?.let { passphrase = vault.get(it) }
        } else {
            val ref = entity.passwordRef ?: return null
            password = vault.get(ref) ?: return null
        }

        return SshTarget(
            configId = entity.id,
            label = entity.label,
            host = entity.host,
            port = entity.port,
            username = entity.username,
            password = password,
            privateKeyPem = privateKeyPem,
            passphrase = passphrase,
            termType = entity.termType,
            knownHostFingerprint = entity.knownHostFingerprint,
        )
    }
}
