package com.mnbt.app.ui.filemanager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 文件管理屏 DTO。字段名逐一对照 `app_plugins/mobile_bridge/docs/api-contract.md` §7。
 * 负载经 EnvelopeParser 取出后 `data` 与顶层平铺字段已合并，这里按 `data` 内字段声明即可。
 */

/**
 * file.list 单行。`type` 只有 "dir" 与 "file"；`path` 服务端已拼成完整路径，直接下钻即可。
 * `size` 字节、`mtime` Unix 秒。
 * `perms`/`owner`/`group`：面板的单层列表接口拿不到这三项，服务端恒回空串，
 * 要看权限得单独调 file.access.get。
 */
@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val type: String = "file",
    val size: Long = 0,
    val mtime: Long = 0,
    val perms: String = "",
    val owner: String = "",
    val group: String = "",
) {
    val isDirectory: Boolean get() = type.equals("dir", ignoreCase = true)
}

/** file.list 负载：{cwd, path, list, total}。**只含该层直接子项**，不做递归展平。 */
@Serializable
data class FileListPayload(
    val cwd: String = "/",
    val path: String = "",
    val list: List<FileEntry> = emptyList(),
    val total: Long = 0,
)

/** file.delete_batch 负载：{deleted:Int, failed:String[]}（failed 是失败项的路径）。 */
@Serializable
data class FileDeleteBatchPayload(
    val deleted: Int = 0,
    val failed: List<String> = emptyList(),
)

/** file.access.get 负载：{path, perm, owner, group}（perm 是 "0644" 形式的八进制串）。 */@Serializable
data class FileAccessPayload(
    val path: String = "",
    val perm: String = "",
    val owner: String = "",
    val group: String = "",
)

/** file.size 负载：{path, size, unit, files}。unit 固定 "B"，size 是字节，展示自行换算。 */
@Serializable
data class FileSizePayload(
    val size: Long = 0,
    val unit: String = "",
)

/**
 * file.upload_prepare 负载：分片计划。
 * [resumeOffset] 是服务端 tmp 文件已收到的字节数，非 0 表示上次中断留下的前缀可复用（契约 §7.1）。
 */
@Serializable
data class UploadPreparePayload(
    @SerialName("upload_id") val uploadId: String = "",
    val name: String = "",
    val size: Long = 0,
    @SerialName("max_size") val maxSize: Long = 0,
    @SerialName("chunk_size") val chunkSize: Long = 0,
    @SerialName("resume_offset") val resumeOffset: Long = 0,
    val path: String = "/",
)

/**
 * file.download 负载：`url` 是宝塔的 token 型外链，直接 GET 即可，**不要带 Authorization**。
 * `size` 面板拿不到，恒 0，不要展示。
 */
@Serializable
data class FileDownloadPayload(
    val filename: String = "",
    val file: String = "",
    val size: Long = 0,
    val url: String = "",
    @SerialName("expires_hint") val expiresHint: String = "",
)
