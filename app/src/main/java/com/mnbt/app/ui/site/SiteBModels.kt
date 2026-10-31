package com.mnbt.app.ui.site

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * P2-D 三组站点设置页的 DTO。字段名以 tools/mock/server.mjs 的实际响应为准
 * （APP 现全走 mock）：
 * - site.init section=pass  -> { section, list: [{dir, user}] }
 * - site.init section=xgpass -> { section, user }
 * - site.cache.list -> { list: [{id, url, type, seconds}] }
 * 包络里的 meta 字段（action/gn/bridge_version/ts）由 MnbtJson 的 ignoreUnknownKeys 丢弃。
 */

/** site.passdir.list / site.init(pass) 的一行：被密码保护的目录 + 账号。 */
@Serializable
data class SiteBPassdirEntry(
    val dir: String = "",
    val user: String? = null,
    /** mock 的 site.passdir.list 直接调用时才回显 pass（打码值），site.init(pass) 没有该字段。 */
    val pass: String? = null,
)

@Serializable
data class SiteBPassdirInit(
    val section: String? = null,
    val list: List<SiteBPassdirEntry> = emptyList(),
)

/**
 * site.init section=xgpass（契约 §6.2）：只回填用户名 + 一句面板原话的提示，
 * 口令任何形式都不回显（`actions_site.php:195-202`）。
 */
@Serializable
data class SiteBPasswordInit(
    val section: String? = null,
    val user: String = "",
    @SerialName("ftp_hint") val ftpHint: String = "",
)

/**
 * site.cache.list 的一行规则（契约 §6.2 cache 分区：{id:Int,url:String,seconds:Int,type,time_out}）。
 * 真实主键是 url —— 它就是缓存**后缀**（如 jpg / css,js），不是路径；
 * id 只是插件按列表顺序现编的序号，顺序一变就指到别的规则，所以每次改删都重新取列表、绝不落库。
 * type 是插件从后缀推导出来的展示值（page/image/file），APP 只读不写。
 */
@Serializable
data class SiteBCacheRule(
    val id: Long = 0,
    val url: String = "",
    val type: String = SiteBCacheType.Page,
    val seconds: Long = 0,
)

@Serializable
data class SiteBCacheList(
    val list: List<SiteBCacheRule> = emptyList(),
)

/**
 * 写 action（add/del/edit/set）的统一回执：字段全可缺省。
 * 解码它不为取数据，只为让「响应结构整体跑偏」以协议错误呈现而不是被吞掉。
 */
@Serializable
data class SiteBAck(
    val action: String? = null,
    val added: Boolean? = null,
    val deleted: Boolean? = null,
    val id: Long? = null,
    val dir: String? = null,
    val user: String? = null,
    val url: String? = null,
)

/**
 * 缓存规则的 type 枚举 —— 契约口径：由插件从后缀推导（html/htm/php/`/`→page，
 * 图片扩展名→image，其余→file），APP 只读展示，不作为 `site.cache.add/edit` 入参。
 */
object SiteBCacheType {
    const val Page = "page"
    const val Image = "image"
    const val File = "file"
}
