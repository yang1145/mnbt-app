package com.mnbt.app.ui.database

import com.mnbt.app.data.remote.api.MnbtException
import com.mnbt.app.domain.call.HostOutcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 数据库管理 / SQL 导入两页的响应负载 DTO。字段名与入参口径以
 * app_plugins/mobile_bridge/docs/api-contract.md §8（数据库 11 个）为准，
 * 并逐条核对了 lib/actions_db.php 的实际取值键。
 *
 * 面板的真实能力边界（决定了本页不能按「多库」设计）：
 * 一台主机 = 一个库 + 一个账号，库随主机开通产生。核心没有建库/删库原语，
 * db.create 与 db.delete 恒回 1010，「新建/删除」按钮的语义只能是备份的增删。
 *
 * 各 action 的入参（发送侧）：
 *  - db.list            : 无参。回 list[]（恒 0 或 1 条）+ quota(次) + sql_space(MB) + backup_count。
 *  - db.backup.create   : 无参（插件自己取 hxd）。回的 name/username 是库账号名，不是备份文件名。
 *  - db.backup.delete   : name=备份文件名（插件按文件名反查宝塔数字 id；传 id 也兼容）。
 *  - db.delete_all      : 无参。deleted=清空前的表数。
 *  - db.backup.download : file=备份文件名。回 url=节点直连的 token 型外链，rawClient GET 且不带 Authorization。
 *  - db.backup.restore  : name=备份文件名；data_account 只作校验，目标库一律是 sqluser。
 *  - db.access.set      : host=localhost|%（插件双向映射面板的 127.0.0.1/%）。port 只回显不生效——面板没有改端口能力。
 *  - db.sql.import      : multipart。文件字段 file；库名校验键是 data_account（兼容 db），不是 name。
 * 包络里的 meta 字段与本页不渲染的键（含 db_id）由 MnbtJson 的 ignoreUnknownKeys 忽略——
 * db_id 的类型契约里没钉死（数字或字符串都可能），声明进 DTO 反而有整包解码失败的风险。
 */

@Serializable
data class DbListData(
    val list: List<DbItem> = emptyList(),
    /** 备份次数配额（`unit` 恒为「次」），不是空间。 */
    val quota: DbQuota? = null,
    /** 数据库空间配额，单位 MB。契约 §3 红线：读 `max` 不读 `total`。 */
    @SerialName("sql_space") val sqlSpace: DbQuota? = null,
    @SerialName("backup_count") val backupCount: Int = 0,
)

/** 面板回的状态只有 "RUNNING" 与空串两种。 */
@Serializable
data class DbItem(
    val name: String = "",
    val username: String = "",
    val charset: String = "",
    val state: String = "",
    val addtime: String = "",
    val backups: List<DbBackup> = emptyList(),
)

@Serializable
data class DbBackup(
    val name: String = "",
    /** 字节数。插件保证是整数（收到小数 kotlinx 的 Long 会直接解码失败）。 */
    val size: Long = 0,
    val time: String = "",
)

/** used/max 服务端恒为浮点；`max <= 0` 表示不限。`unit` 直接用，别自己换算。 */
@Serializable
data class DbQuota(
    val used: Double = 0.0,
    val max: Double = 0.0,
    val unit: String = "",
)

/** db.backup.create 回显的 name/username 都是库账号名（备份文件名只出现在 db.list 里）。 */
@Serializable
data class DbBackupCreateResult(
    val name: String = "",
    val username: String = "",
    val quota: DbQuota? = null,
)

@Serializable
data class DbBackupDeleteResult(
    val name: String = "",
    val deleted: Boolean = false,
    val quota: DbQuota? = null,
)

// deleted 是「清空前的表数」（计数），与 db.backup.delete 的布尔 deleted 同名异形。
@Serializable
data class DbDeleteAllResult(
    val deleted: Int = 0,
    val remaining: Int = 0,
)

@Serializable
data class DbBackupDownloadResult(
    val file: String = "",
    val url: String = "",
    /** 面板拿不到大小，插件固定回 0，不要显示。 */
    val size: Long = 0,
)

@Serializable
data class DbRestoreResult(
    val name: String = "",
    val db: String = "",
    val restored: Boolean = false,
)

@Serializable
data class DbAccessResult(
    val db: String = "",
    val access: String = "",
    /** 面板无改端口能力，插件只回显 APP 传进来的值。 */
    val port: Int = 0,
    val applied: Boolean = false,
)

@Serializable
data class DbSqlImportResult(
    val file: String = "",
    val db: String = "",
    val imported: Boolean = false,
)

/**
 * 单次写操作（备份增删/清空/授权/恢复/下载/导入）的统一阶段，与页面主体的 [com.mnbt.app.ui.common.LoadState] 分开：
 * 页面主体决定整页形态，本类型只驱动 Snackbar 与提交禁用。对齐 PRD §8.3：错误走 Snackbar，不铺色块。
 */
sealed interface DbOpPhase {
    data object Idle : DbOpPhase
    data object Running : DbOpPhase
    data class Success(val message: String) : DbOpPhase
    data class Error(val message: String, val raw: String? = null) : DbOpPhase

    /** 令牌失效 / 本地凭据缺失：引导回主机列表重扫。 */
    data object NeedRepair : DbOpPhase

    /** 主机到期/封禁/面板关闭：整页只读，写操作持续禁用。 */
    data class Blocked(val message: String) : DbOpPhase
}

/** hostCall 的 HostOutcome → 写操作阶段。全分支覆盖。 */
fun <T> HostOutcome<T>.toOpPhase(onReady: (T) -> String): DbOpPhase = when (this) {
    is HostOutcome.Ready -> DbOpPhase.Success(onReady(data))
    HostOutcome.TokenRejected, HostOutcome.CredentialMissing -> DbOpPhase.NeedRepair
    is HostOutcome.HostBlocked -> DbOpPhase.Blocked(message)
    is HostOutcome.Failed -> DbOpPhase.Error(message, raw)
}

/**
 * 绕过 hostCall（multipart 直传）时手工分类。与 domain.call 内私有 classify 同规则，
 * 但不含 needsRepair 落库（调用方自行处理），因为 MnbtClient.upload 不经过那条路径。
 */
fun MnbtException.toOpPhase(): DbOpPhase = when (this) {
    is MnbtException.TokenRejected -> DbOpPhase.NeedRepair
    is MnbtException.HostExpired, is MnbtException.AccountBanned, is MnbtException.PanelClosed ->
        DbOpPhase.Blocked(message)
    is MnbtException.Protocol -> DbOpPhase.Error(message, raw)
    else -> DbOpPhase.Error(message)
}

/** 备份文件大小友好显示（这一项服务端给的是字节，与配额的 MB/GB 口径不同，别混用）。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return if (idx == 0) "${bytes} B" else String.format("%.1f %s", value, units[idx])
}
