package com.mnbt.app.ui.ops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 监控 / 通知 / 一键部署三页的响应负载 DTO。字段名严格对齐 tools/mock/server.mjs 的
 * dataFor()（APP 现全走 mock）：monitor.list/save/delete/toggle/log、
 * notice.list/read、deploy.list/form/run。包络里的 meta 字段由
 * MnbtJson.ignoreUnknownKeys 忽略，这里只声明业务字段。
 */

// ---- 监控（§5.6，monitor.list / save / delete / toggle / log） ----

/**
 * 一条监控任务。mock 里 resource 类型没有 target/last_code/last_time 字段，
 * 全部给空默认值；last_code 缺省（未检测过）用 0 表示无结果。
 */
@Serializable
data class OpsMonitorTask(
    val id: Int = 0,
    val name: String = "",
    val type: String = "url",
    val target: String = "",
    val cycle: Int = 0,
    val status: String = "on",
    @SerialName("last_code") val lastCode: Int? = null,
    @SerialName("last_time") val lastTime: String = "",
)

/** 核心限制照搬（PRD §5.6）：每用户最多 5 任务、URL 最小 60s、资源固定 180s。 */
@Serializable
data class OpsMonitorLimit(
    @SerialName("max_tasks") val maxTasks: Int = 5,
    @SerialName("url_min_cycle") val urlMinCycle: Int = 60,
    @SerialName("resource_cycle") val resourceCycle: Int = 180,
)

@Serializable
data class OpsMonitorListData(
    val list: List<OpsMonitorTask> = emptyList(),
    val limit: OpsMonitorLimit = OpsMonitorLimit(),
)

@Serializable
data class OpsMonitorSaveData(
    val id: Int = 0,
    val name: String = "",
    val type: String = "",
    val saved: Boolean = false,
)

@Serializable
data class OpsMonitorDeleteData(
    val id: Int = 0,
    val deleted: Boolean = false,
)

@Serializable
data class OpsMonitorToggleData(
    val id: Int = 0,
    val status: String = "",
)

@Serializable
data class OpsMonitorLogEntry(
    val time: String = "",
    val target: String = "",
    val code: Int = 0,
    @SerialName("used_ms") val usedMs: Int = 0,
    val status: String = "",
)

@Serializable
data class OpsMonitorLogData(
    val list: List<OpsMonitorLogEntry> = emptyList(),
    val total: Int = 0,
)

// ---- 通知（§5.6，notice.list / notice.read；mock 映射 panel.php） ----

/** mock 的通知项没有「类型」字段（id/title/content/time/read），差异已记入交付报告。 */
@Serializable
data class OpsNoticeItem(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val time: String = "",
    val read: Int = 0,
)

@Serializable
data class OpsNoticeListData(
    val list: List<OpsNoticeItem> = emptyList(),
    val unread: Int = 0,
    val total: Int = 0,
)

@Serializable
data class OpsNoticeReadData(
    val id: Int = 0,
    val read: Int = 0,
)

// ---- 一键部署（§5.7，deploy.list / form / run） ----

@Serializable
data class OpsDeployApp(
    val id: Int = 0,
    val name: String = "",
    val ver: String = "",
    /** MN_bs.src：可能绝对（mock 给 baseUrl 全链）也可能相对，需按主机 base 补全 */
    val src: String = "",
    val dir: String = "/",
)

@Serializable
data class OpsDeployListData(
    val list: List<OpsDeployApp> = emptyList(),
)

/**
 * deploy.form 的单个参数项（契约 §10.3）。
 *
 * 面板的部署表单只有文本/数字 input —— `type` 恒 `"input"`，`options` 恒空，
 * 所以这里不声明 options（不渲染的字段一律不进 DTO）。
 * `default` 也**不声明**：插件那边它是 placeholder 的载体（`isnr`），
 * 恒回空串，把它当值提交等于把提示文字填进表单。
 */
@Serializable
data class OpsDeployField(
    val key: String = "",
    val label: String = "",
    val type: String = "input",
    val required: Boolean = false,
    val placeholder: String = "",
    @SerialName("max_length") val maxLength: Int = 0,
)

@Serializable
data class OpsDeployFormData(
    val id: Int = 0,
    val name: String = "",
    @SerialName("need_db") val needDb: Boolean = false,
    @SerialName("create_dir") val createDir: String = "/",
    val fields: List<OpsDeployField> = emptyList(),
)

@Serializable
data class OpsDeployRunData(
    @SerialName("task_id") val taskId: String = "",
    val status: String = "",
)
