package com.mnbt.app.data.remote.api

/**
 * bridge 插件的 action 名。这里是唯一定义处，UI 层不要出现裸字符串。
 * 入参/负载口径以 MNBT 仓库的 `app_plugins/mobile_bridge/docs/api-contract.md` 为准
 * （PRD §5 只列能力，字段名以契约文档为准）。
 */
object MnbtActions {

    // 配对与设备（PRD §5.8）
    const val PAIR = "pair"
    const val DEVICE_PING = "device.ping"
    const val DEVICE_INFO = "device.info"
    const val DEVICE_REVOKE = "device.revoke"

    // 概览与用量（§5.1）
    const val HOST_OVERVIEW = "host.overview"
    const val HOST_PROFILE = "host.profile"

    // 站点设置（§5.2）
    /** 各设置分区的初始化数据，params: section=php|yxml|mrwd|wjt|gzip|pass|xgpass|cache|url|mysqlcz */
    const val SITE_INIT = "site.init"
    const val SITE_PHP_SET = "site.php.set"
    const val SITE_RUNDIR_SET = "site.rundir.set"
    const val SITE_INDEXDOC_SET = "site.indexdoc.set"
    const val SITE_REWRITE_GET = "site.rewrite.get"
    const val SITE_REWRITE_SET = "site.rewrite.set"
    const val SITE_GZIP_SET = "site.gzip.set"
    const val SITE_PASSDIR_LIST = "site.passdir.list"
    const val SITE_PASSDIR_ADD = "site.passdir.add"
    const val SITE_PASSDIR_DEL = "site.passdir.del"
    const val SITE_PASSWORD_SET = "site.password.set"
    const val SITE_HOTLINK_GET = "site.hotlink.get"
    const val SITE_HOTLINK_SET = "site.hotlink.set"
    const val SITE_CACHE_LIST = "site.cache.list"
    const val SITE_CACHE_ADD = "site.cache.add"
    const val SITE_CACHE_EDIT = "site.cache.edit"
    const val SITE_CACHE_DEL = "site.cache.del"

    // 文件管理（§5.3）
    const val FILE_LIST = "file.list"
    const val FILE_READ = "file.read"
    const val FILE_SAVE = "file.save"
    const val FILE_CREATE = "file.create"
    const val FILE_DELETE = "file.delete"
    const val FILE_DELETE_BATCH = "file.delete_batch"
    const val FILE_RENAME = "file.rename"
    const val FILE_COPY = "file.copy"
    const val FILE_COMPRESS = "file.compress"
    const val FILE_UNZIP = "file.unzip"
    const val FILE_SIZE = "file.size"
    const val FILE_UPLOAD_PREPARE = "file.upload_prepare"
    const val FILE_UPLOAD = "file.upload"
    const val FILE_DOWNLOAD = "file.download"
    const val FILE_ACCESS_GET = "file.access.get"
    const val FILE_ACCESS_SET = "file.access.set"

    // 数据库（§5.4）。面板是「一主机 = 一库 = 一账号」，没有建库/删库原语：
    // 契约 §11 里 db.create / db.delete 恒回 1010，新增/删除的语义落在备份上。
    const val DB_LIST = "db.list"
    const val DB_BACKUP_CREATE = "db.backup.create"
    const val DB_BACKUP_DELETE = "db.backup.delete"
    const val DB_DELETE_ALL = "db.delete_all"
    const val DB_BACKUP_DOWNLOAD = "db.backup.download"
    const val DB_BACKUP_RESTORE = "db.backup.restore"
    const val DB_ACCESS_SET = "db.access.set"
    const val DB_SQL_IMPORT = "db.sql.import"

    // 域名与 SSL（§5.5）
    const val DOMAIN_LIST = "domain.list"
    const val DOMAIN_SUBDIR_LIST = "domain.subdir.list"
    const val DOMAIN_ADD = "domain.add"
    const val DOMAIN_DEL = "domain.del"
    const val DOMAIN_PREFIX_SET = "domain.prefix.set"
    const val SSL_GET = "ssl.get"
    const val SSL_APPLY = "ssl.apply"
    const val SSL_RENEW = "ssl.renew"
    const val SSL_CUSTOM = "ssl.custom"
    const val SSL_CLOSE = "ssl.close"
    const val SSL_FORCE_HTTPS = "ssl.force_https"

    // 监控与通知（§5.6）
    const val MONITOR_LIST = "monitor.list"
    const val MONITOR_SAVE = "monitor.save"
    const val MONITOR_DELETE = "monitor.delete"
    const val MONITOR_TOGGLE = "monitor.toggle"
    const val MONITOR_LOG = "monitor.log"
    const val NOTICE_LIST = "notice.list"
    const val NOTICE_READ = "notice.read"

    // 一键部署（§5.7）
    const val DEPLOY_LIST = "deploy.list"
    const val DEPLOY_FORM = "deploy.form"
    const val DEPLOY_RUN = "deploy.run"
}
