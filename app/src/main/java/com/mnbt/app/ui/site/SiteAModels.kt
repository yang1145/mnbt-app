package com.mnbt.app.ui.site

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * P2-C 组 A 八个站点设置页的响应负载 DTO。字段名严格对齐 tools/mock/server.mjs
 * 的 dataFor()（APP 现全走 mock）。包络里的 meta 字段（action/gn/ts…）由
 * ignoreUnknownKeys 忽略，这里只声明业务字段。
 */

// ---- PHP 版本：SITE_INIT(section=php) / SITE_PHP_SET ----
@Serializable
data class SiteAPhpInit(
    val current: String = "",
    val options: List<String> = emptyList(),
)

@Serializable
data class SiteAPhpResult(
    val phpversion: String = "",
)

// ---- 运行目录：SITE_INIT(section=yxml) / SITE_RUNDIR_SET ----
@Serializable
data class SiteARundirInit(
    val current: String = "",
    val options: List<String> = emptyList(),
)

@Serializable
data class SiteARundirResult(
    val rundir: String = "",
)

// ---- 默认文档：SITE_INIT(section=mrwd) / SITE_INDEXDOC_SET ----
@Serializable
data class SiteAIndexdocInit(
    val current: String = "",
)

@Serializable
data class SiteAIndexdocResult(
    val indexdoc: String = "",
)

// ---- 伪静态：SITE_INIT(section=wjt) / SITE_REWRITE_GET / SITE_REWRITE_SET ----
// 初始模板列表 + 当前模板取自 section=wjt；规则内容与选中态以 rewrite.get 为准。
@Serializable
data class SiteARewriteInit(
    val current: String = "",
    val list: List<String> = emptyList(),
)

@Serializable
data class SiteARewriteDetail(
    val current: String = "",
    val rewrite: String = "",
    val list: List<String> = emptyList(),
)

@Serializable
data class SiteARewriteResult(
    val rewrite: String = "",
)

// ---- Gzip：SITE_INIT(section=gzip) / SITE_GZIP_SET ----
@Serializable
data class SiteAGzipInit(
    val current: String = "",
)

@Serializable
data class SiteAGzipResult(
    val gzip: String = "",
)

// ---- 防盗链：SITE_HOTLINK_GET / SITE_HOTLINK_SET ----
// open/lasts 在 mock 里是数字（1 / 30），解码为 Int，UI 再映射布尔/字符串。
@Serializable
data class SiteAHotlinkData(
    val open: Int = 0,
    val domains: String = "",
    val exts: String = "",
    val lasts: Int = 0,
)
