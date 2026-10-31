package com.mnbt.app.ssh

import android.content.Context
import java.security.Security
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * MINA SSHD 在 Android 上的一次性环境准备。
 *
 * 官方 `docs/android.md` 明确说明：SSHD 不主动支持 Android，但留了钩子。它内部会去读
 * `user.home` / `user.dir` / `user.name` 三个系统属性来推导默认路径，这三个在 Android 上都不存在，
 * 不显式喂值会在加载私钥、写 known_hosts 时炸掉。这里统一指向 app 的 filesDir。
 */
object SshPlatform {

    @Volatile
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return

        val root = context.filesDir.toPath()
        System.setProperty("user.home", root.toString())
        System.setProperty("user.dir", root.toString())
        if (System.getProperty("user.name").isNullOrBlank()) {
            System.setProperty("user.name", "mnbt")
        }

        OsUtils.setAndroid(true)
        OsUtils.setCurrentUser("mnbt")
        PathUtils.setUserHomeFolderResolver { root }
        OsUtils.setCurrentWorkingDirectoryResolver { root }

        // Android 平台自带一个被裁剪过的 BC，缺 ed25519 与 chacha20-poly1305@openssh.com。
        // SecurityUtils 只按 provider 名字探测，先摘掉残缺的那个再装完整的，
        // 否则它会认为 BC 可用然后在真正取算法时失败。
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())

        ready = true
    }
}
