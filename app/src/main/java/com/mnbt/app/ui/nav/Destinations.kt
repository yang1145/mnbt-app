package com.mnbt.app.ui.nav

import android.net.Uri
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * 全站路由表。屏与屏之间只允许通过这里定义的 route 互相跳转，
 * 任何一级页面都不要自己拼字符串——P5 之后还要给「能力协商」按入口灰显，
 * 路由集中一处是那个灰显能落地的前提。
 */
object Destinations {
    // 底部四 tab
    const val CLOUD = "cloud"
    const val HOSTS = "hosts"
    const val TERMINAL = "terminal"
    const val MINE = "mine"

    // 配对流程
    const val ARG_BASE = "base"
    const val ARG_CODE = "code"
    const val PAIR_SCAN = "pair/scan"
    const val PAIR_MANUAL = "pair/manual"
    const val PAIR_TRUST = "pair/trust?$ARG_BASE={$ARG_BASE}&$ARG_CODE={$ARG_CODE}"

    // 云服务器：绑定流程与详情（复用 ARG_BASE / ARG_CODE；siteId、hostId 均 LongType）
    const val CLOUD_PAIR_SCAN = "cloud/pair/scan"
    const val CLOUD_PAIR_TRUST = "cloud/pair/trust?$ARG_BASE={$ARG_BASE}&$ARG_CODE={$ARG_CODE}"
    const val ARG_SITE_ID = "siteId"
    // {hostId} 一律 LongType（主机详情与云服务器详情共用参数名）
    const val ARG_HOST_ID = "hostId"
    const val CLOUD_SERVER_DETAIL = "cloud/server/{$ARG_SITE_ID}/{$ARG_HOST_ID}"

    // 主机详情与功能页
    const val HOST_DETAIL = "host/{$ARG_HOST_ID}"

    const val F_PHP = "php"
    const val F_RUNDIR = "rundir"
    const val F_INDEXDOC = "indexdoc"
    const val F_REWRITE = "rewrite"
    const val F_GZIP = "gzip"
    const val F_HOTLINK = "hotlink"
    const val F_PASSDIR = "passdir"
    const val F_PASSWORD = "password"
    const val F_CACHE = "cache"
    const val F_FILES = "files"
    const val F_DATABASE = "database"
    const val F_SQL_IMPORT = "sqlimport"
    const val F_DOMAINS = "domains"
    const val F_SSL = "ssl"
    const val F_MONITOR = "monitor"
    const val F_NOTICE = "notice"
    const val F_DEPLOY = "deploy"

    const val ARG_PATH = "path"
    const val FILE_EDITOR = "editor?$ARG_HOST_ID={$ARG_HOST_ID}&$ARG_PATH={$ARG_PATH}"

    // 我的 tab 二级页
    const val MINE_HOSTS = "mine/hosts"
    const val MINE_CLOUD_SITES = "mine/cloud_sites"
    const val MINE_CERTS = "mine/certs"

    // 终端 tab 二级页
    const val ARG_SESSION_ID = "sessionId"
    const val SSH_EDIT = "ssh/edit?$ARG_SESSION_ID={$ARG_SESSION_ID}"
    const val SSH_TERMINAL = "ssh/terminal/{$ARG_SESSION_ID}"

    /** 新建会话传 -1 */
    fun sshEditRoute(sessionId: Long): String = "ssh/edit?sessionId=$sessionId"

    fun hostFeature(feature: String, hostId: Long): String = "host/$hostId/$feature"

    /** query 参数要 URL 编码：base 里带 ://、path 里带 /，不编码会撞 route 分隔符 */
    fun pairTrustRoute(base: String, code: String): String =
        "pair/trust?base=${Uri.encode(base)}&code=${Uri.encode(code)}"

    /** 云站点绑定信任页：同 pairTrustRoute，base/code 走 query 并 URL 编码 */
    fun cloudPairTrustRoute(base: String, code: String): String =
        "cloud/pair/trust?base=${Uri.encode(base)}&code=${Uri.encode(code)}"

    fun editorRoute(hostId: Long, path: String): String =
        "editor?hostId=$hostId&path=${Uri.encode(path)}"

    fun trustQueryBase(entry: NavBackStackEntry): String =
        entry.arguments?.getString(ARG_BASE).orEmpty()

    fun trustQueryCode(entry: NavBackStackEntry): String =
        entry.arguments?.getString(ARG_CODE).orEmpty()

    /** {hostId} 参数在每条 host 子路由上都要注册，集中写一次。 */
    fun hostIdArg() = navArgument(ARG_HOST_ID) { type = NavType.LongType }

    fun NavController.openHostDetail(hostId: Long) = navigate("host/$hostId")

    fun NavController.openCloudServerDetail(siteId: Long, hostId: Long) =
        navigate("cloud/server/$siteId/$hostId")
}

/** 从 back stack 里取 hostId，缺失即编程错误，直接抛而不是给默认值。 */
fun NavBackStackEntry.requireHostId(): Long =
    arguments?.getLong(Destinations.ARG_HOST_ID)
        ?: error("route ${destination.route} 缺少 hostId 参数")

/** 从 back stack 里取 siteId（云服务器站点），缺失即编程错误，直接抛而不是给默认值。 */
fun NavBackStackEntry.requireSiteId(): Long =
    arguments?.getLong(Destinations.ARG_SITE_ID)
        ?: error("route ${destination.route} 缺少 siteId 参数")
