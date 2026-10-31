package com.mnbt.app.ui.filemanager

import com.mnbt.app.data.remote.api.MnbtJson
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数组参数按契约 §1.2 的 JSON 串写法提交（`paths=["/a","/b"]`），
 * 因为 urlencoded 的重复键 `paths[]` 用 Map 表达不了。转义交给 [MnbtJson]，不自己拼引号。
 */
fun List<String>.jsonArrayOf(): String = MnbtJson.encodeToString(this)

/** 文本类扩展名：点击直接进在线编辑器（PRD §12.1 A10 的 php 编辑路径）。大小写不敏感。 */
val TEXT_FILE_EXTENSIONS = setOf("php", "html", "js", "css", "txt", "ini", "conf", "md", "json", "xml", "log", "svg")

/** 可解压的压缩包扩展名（FILE_UNZIP 入口只对这类文件出现）。 */
val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")

fun fileExtension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun isTextFile(name: String): Boolean = fileExtension(name) in TEXT_FILE_EXTENSIONS

fun isArchiveFile(name: String): Boolean = fileExtension(name) in ARCHIVE_EXTENSIONS

/** 人类可读体积：B / KB / MB（GB、TB 顺带支持），三位以上取整、否则一位小数。 */
fun humanReadableSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.size - 1) {
        value /= 1024.0
        unitIndex++
    }
    val number = if (value >= 100.0) "%.0f".format(Locale.ROOT, value) else "%.1f".format(Locale.ROOT, value)
    return "$number ${units[unitIndex]}"
}

private val mtimeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)

/** mtime 是 Unix 秒。 */
fun formatMtime(seconds: Long): String =
    if (seconds <= 0) "—" else mtimeFormatter.format(Date(seconds * 1000L))

/** file.list 不带权限（恒空串），只有 file.access.get 的返回值才有 "0644" 这种四段形式。 */
fun prettyPerms(perms: String): String =
    if (perms.length == 4 && perms[0] == '0') perms.substring(1) else perms.ifBlank { "—" }

/** 站点相对路径拼接：join("/a", "b") = "/a/b"，join("/", "a") = "/a"。 */
fun joinPath(parent: String, child: String): String {
    val p = parent.trimEnd('/')
    return (if (p.isEmpty()) "/" else p) + "/" + child.trimStart('/')
}

/** "/index.html" → "/"，"/path/style.css" → "/path"。 */
fun parentPath(path: String): String =
    path.substringBeforeLast('/').ifEmpty { "/" }

/** 面包屑回跳时重建目录栈："/a/b/c" 的祖先是 ["/", "/a", "/a/b"]（不含自身）。 */
fun ancestorChain(path: String): List<String> {
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    if (normalized == "/") return emptyList()
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    return buildList {
        add("/")
        var prefix = ""
        for (i in 0 until segments.lastIndex) {
            prefix += "/" + segments[i]
            add(prefix)
        }
    }
}

/** 面包屑分段：("根目录", "/") 开头，其余每段一个可点目标。 */
fun breadcrumbSegments(path: String): List<Pair<String, String>> {
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    val out = mutableListOf<Pair<String, String>>()
    var prefix = ""
    segments.forEachIndexed { _, seg ->
        prefix += "/" + seg
        out += seg to prefix
    }
    return out
}

/**
 * 目录在前 + 名称/时间排序。file.list 的 sort/sortOrder 我们没有下传（服务端只按名称排），
 * 时间排序与目录置顶都在客户端做。
 */
fun List<FileEntry>.sortedFor(sort: FileSort): List<FileEntry> = sortedWith(
    compareBy<FileEntry> { !it.isDirectory }
        .then(
            if (sort == FileSort.NAME) {
                compareBy({ it.name.lowercase(Locale.ROOT) }, { it.path })
            } else {
                compareByDescending { it.mtime }
            },
        ),
)
