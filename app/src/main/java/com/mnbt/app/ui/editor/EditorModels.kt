package com.mnbt.app.ui.editor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/**
 * 打开时判定的文件编码。宝塔站点历史文件大量 GBK 存储（PRD §4.5），
 * content_base64 是权威字节，`content` 字段只是 lossy 展示，不作判定依据。
 */
enum class FileCharset(val label: String, val javaCharset: Charset) {
    Utf8("UTF-8", Charset.forName("UTF-8")),
    Gbk("GBK", Charset.forName("GBK")),
}

/**
 * 严格 UTF-8 一次判定：REPORT 模式下任何畸形字节都会抛 CharacterCodingException，
 * 失败即回落 GBK。不做字节统计猜测——误判一次就写坏一次文件，宁可保守。
 * GBK 也解不出（真二进制）时最终回落 lossy UTF-8，由 UI 呈现乱码但字节仍可原样回写。
 */
fun decodeByDetection(bytes: ByteArray): Pair<String, FileCharset> {
    val strict = runCatching {
        Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
    if (strict.isSuccess) return strict.getOrThrow() to FileCharset.Utf8
    // String(bytes, Charset) 对畸形输入是替换而非抛异常，真二进制也会得到可显示文本；
    // 字节原样保留在 VM，保存仍按判定编码回写，不会再损坏。
    return String(bytes, FileCharset.Gbk.javaCharset) to FileCharset.Gbk
}

/** file.read 负载（tools/mock server.mjs file.read 分支；顶层扁平与 data 合并由 EnvelopeParser 处理）。 */
@Serializable
data class FileReadPayload(
    @SerialName("content_base64") val contentBase64: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("size") val size: Long? = null,
)

/** file.save 应答：mock 返回 saved/size/mtime，真实 bridge 同构；字段只用于成功判定，不做二次校验。 */
@Serializable
data class FileSaveAck(
    @SerialName("saved") val saved: Boolean = false,
    @SerialName("size") val size: Long? = null,
)
