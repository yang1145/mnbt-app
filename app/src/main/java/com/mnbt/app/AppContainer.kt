package com.mnbt.app

import android.content.Context
import com.mnbt.app.data.local.AppSettings
import com.mnbt.app.data.local.db.AppDatabase
import com.mnbt.app.data.local.db.CloudSiteDao
import com.mnbt.app.data.local.db.HostDao
import com.mnbt.app.data.local.db.SshSessionDao
import com.mnbt.app.data.local.secure.TokenVault
import com.mnbt.app.data.remote.api.MnbtClient
import com.mnbt.app.data.remote.api.zjmf.ZjmfClient
import com.mnbt.app.data.remote.http.DeviceInfo
import com.mnbt.app.data.remote.http.HostHttpClientFactory
import com.mnbt.app.domain.repo.CloudServerRepository
import com.mnbt.app.domain.repo.CloudSiteRepository
import com.mnbt.app.domain.repo.HostRepository
import com.mnbt.app.domain.sync.HostSync
import com.mnbt.app.ssh.SshRepository
import com.mnbt.app.ssh.SshSessionRegistry

/**
 * 手工依赖注入的根。全部用 lazy：Application.onCreate 里只做一次对象分配，
 * Room 建库、Keystore 取主密钥、OkHttp 建连接池都推迟到首次真正使用时，
 * 冷启动预算（PRD §10.1）靠这个守住。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: AppSettings by lazy { AppSettings(appContext) }

    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    val hostDao: HostDao by lazy { database.hostDao() }

    val sshSessionDao: SshSessionDao by lazy { database.sshSessionDao() }

    val cloudSiteDao: CloudSiteDao by lazy { database.cloudSiteDao() }

    private val vaultResult: TokenVault.OpenResult by lazy { TokenVault.open(appContext) }

    val vault: TokenVault by lazy { vaultResult.vault }

    /**
     * true 表示 Keystore 主密钥失效、密文库被重建过，所有令牌与 SSH 凭据已丢失。
     * 必须提示用户重新扫码绑定，而不是让每台主机各自报"凭据已失效"。
     */
    val vaultRecovered: Boolean get() = vaultResult.recovered

    val device: DeviceInfo by lazy { DeviceInfo(vault) }

    val clientFactory: HostHttpClientFactory by lazy {
        HostHttpClientFactory(HostHttpClientFactory.buildRoot(device, BuildConfig.DEBUG))
    }

    val client: MnbtClient by lazy { MnbtClient(clientFactory, device) }

    /** ZJMF-CBAP（云站点）通信入口，与 [client] 共用连接池，系统 CA 信任、无 pinning。 */
    val zjmfClient: ZjmfClient by lazy { ZjmfClient(clientFactory, device) }

    val hostRepository: HostRepository by lazy { HostRepository(hostDao, vault, client) }

    val cloudSiteRepository: CloudSiteRepository by lazy { CloudSiteRepository(cloudSiteDao, vault, zjmfClient) }

    val cloudServerRepository: CloudServerRepository by lazy {
        CloudServerRepository(cloudSiteDao, vault, zjmfClient)
    }

    val hostSync: HostSync by lazy { HostSync(this) }

    val sshRegistry: SshSessionRegistry by lazy { SshSessionRegistry(appContext) }

    val sshRepository: SshRepository by lazy { SshRepository(sshSessionDao, vault, sshRegistry) }
}

class MnbtApplication : android.app.Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 从任意 Context 取容器，省掉层层传递。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as MnbtApplication).container
