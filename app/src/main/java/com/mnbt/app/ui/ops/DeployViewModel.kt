package com.mnbt.app.ui.ops

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.mnbt.app.R
import com.mnbt.app.AppContainer
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.domain.call.HostOutcome
import com.mnbt.app.domain.call.hostCall
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.toLoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * 一键部署状态机（PRD §5.7）。
 *
 * 图标加载的硬约束（§5.7 实现约束 + §8.3）：
 * - MN_bs.src 若是相对路径，按该主机 baseUrl 补全；
 * - ImageLoader 只在本 ViewModel 内按 hostId 构建（onCleared 即释放），
 *   不动 App 级全局 ImageLoader；okHttpClient 注入
 *   `container.client.rawClient(credential.pin)`，即该主机专属、带指纹信任的
 *   OkHttpClient——否则手工信任过自签证书的站点图片全部握手失败。
 *
 * deploy.run 是同步执行（契约 §10.3：核心逐步 sleep(3)，整轮几十秒），
 * `task_id` 面板拿不到、恒空串，所以反馈取 `status` 原话，APP 侧没有进度可轮询。
 * 读超时按 §1.3 放宽到 180s（见 `readTimeoutFor`），执行期间 UI 不可关弹层、不可连点。
 */
class DeployViewModel(
    private val application: Application,
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {

    private val _listState = MutableStateFlow<LoadState<OpsDeployListData>>(LoadState.Loading)
    val listState: StateFlow<LoadState<OpsDeployListData>> = _listState.asStateFlow()

    /** null = 表单弹层未打开 */
    private val _formState = MutableStateFlow<LoadState<OpsDeployFormData>?>(null)
    val formState: StateFlow<LoadState<OpsDeployFormData>?> = _formState.asStateFlow()

    /** 表单参数值：key → 用户输入/选中值，打开表单时按 default 预填 */
    private val _fieldValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val fieldValues: StateFlow<Map<String, String>> = _fieldValues.asStateFlow()

    private val _runPhase = MutableStateFlow<OpsWritePhase>(OpsWritePhase.Idle)
    val runPhase: StateFlow<OpsWritePhase> = _runPhase.asStateFlow()

    private val _iconLoader = MutableStateFlow<ImageLoader?>(null)
    val iconLoader: StateFlow<ImageLoader?> = _iconLoader.asStateFlow()

    private var baseUrl: String = ""

    init {
        load()
        viewModelScope.launch { prepareIconLoader() }
    }

    fun load() {
        viewModelScope.launch {
            if (_listState.value !is LoadState.Ready) _listState.value = LoadState.Loading
            _listState.value = hostCall(
                container, hostId, MnbtActions.DEPLOY_LIST
            ) { decodePayload<OpsDeployListData>(it) }.toLoadState()
        }
    }

    /**
     * 图标绝对地址：绝对 src 原样用，相对 src 按主机 base 补全（PRD §5.7）。
     * src 为空返回空串，页面据此走占位。
     */
    fun iconUrl(src: String): String = when {
        src.isBlank() -> ""
        src.startsWith("http://") || src.startsWith("https://") -> src
        else -> baseUrl.trimEnd('/') + "/" + src.trimStart('/')
    }

    fun openForm(appItem: OpsDeployApp) {
        if (_formState.value is LoadState.Loading) return
        _formState.value = LoadState.Loading
        _runPhase.value = OpsWritePhase.Idle
        viewModelScope.launch {
            // mock 用 str('bs_id', str('id'))：以 bs_id 为准，附 id 兜底
            val outcome = hostCall(
                container, hostId, MnbtActions.DEPLOY_FORM,
                mapOf("bs_id" to appItem.id.toString(), "id" to appItem.id.toString())
            ) { decodePayload<OpsDeployFormData>(it) }
            when (outcome) {
                is HostOutcome.Ready -> {
                    _formState.value = LoadState.Ready(outcome.data)
                    // 全部从空开始：契约 §10.3 的 default 是 placeholder 的载体，不是可提交的值
                    _fieldValues.value = outcome.data.fields.associate { field -> field.key to "" }
                }

                is HostOutcome.Failed -> _formState.value = outcome.toLoadState()

                else -> {
                    // 凭证/封禁类：关弹层，整页切到引导态
                    _formState.value = null
                    _listState.value = when (outcome) {
                        HostOutcome.TokenRejected -> LoadState.TokenRejected
                        HostOutcome.CredentialMissing -> LoadState.CredentialMissing
                        is HostOutcome.HostBlocked -> LoadState.HostBlocked(outcome.message)
                        else -> LoadState.Failed("请求失败")
                    }
                }
            }
        }
    }

    fun dismissForm() {
        _formState.value = null
        _runPhase.value = OpsWritePhase.Idle
    }

    fun setFieldValue(key: String, value: String) {
        _fieldValues.value = _fieldValues.value + (key to value)
    }

    /** 必填项没填就返回它们的展示名，供 UI 与 run() 共同拦截（服务端只回 1007，不如本地先提示）。 */
    fun missingRequiredFields(form: OpsDeployFormData): List<String> =
        form.fields.filter { it.required && _fieldValues.value[it.key].isNullOrBlank() }
            .map { it.label.ifBlank { it.key } }

    /**
     * deploy.run：`bs_id` + 表单参数逐项平铺进 form-urlencoded body。
     *
     * 破坏性动作（契约 §10.3：核心会**先删光站点根目录文件**再解压源码包，并逐步 sleep(3)），
     * 所以调用方必须先过 [missingRequiredFields] 并由 UI 做二次确认；读超时按 §1.3 放到 180s。
     * 执行期间 [run] 自身靠 `Saving` 挡住连点，UI 侧同时禁止关闭弹层。
     */
    fun run() {
        val form = _formState.value as? LoadState.Ready ?: return
        if (_runPhase.value is OpsWritePhase.Saving) return
        val missing = missingRequiredFields(form.data)
        if (missing.isNotEmpty()) {
            _runPhase.value = OpsWritePhase.Error(
                application.getString(R.string.ops_deploy_required_missing, missing.joinToString("、")),
            )
            return
        }
        viewModelScope.launch {
            _runPhase.value = OpsWritePhase.Saving
            val params = buildMap {
                put("bs_id", form.data.id.toString())
                form.data.fields.forEach { field ->
                    put(field.key, _fieldValues.value[field.key].orEmpty())
                }
            }
            val outcome = hostCall(
                container, hostId, MnbtActions.DEPLOY_RUN, params
            ) { decodePayload<OpsDeployRunData>(it) }
            when (outcome) {
                is HostOutcome.Ready -> {
                    val message = application.getString(
                        R.string.ops_deploy_queued,
                        outcome.data.taskId.ifBlank { outcome.data.status },
                    )
                    _runPhase.value = OpsWritePhase.Done(message)
                    load()
                }

                is HostOutcome.Failed ->
                    // 1008 限流的 retryAfter 留给弹层内展示，不关弹层
                    _runPhase.value = OpsWritePhase.Error(
                        outcome.message,
                        outcome.retryAfterSeconds,
                    )

                else -> {
                    _formState.value = null
                    _runPhase.value = OpsWritePhase.Idle
                    _listState.value = when (outcome) {
                        HostOutcome.TokenRejected -> LoadState.TokenRejected
                        HostOutcome.CredentialMissing -> LoadState.CredentialMissing
                        is HostOutcome.HostBlocked -> LoadState.HostBlocked(outcome.message)
                        else -> LoadState.Failed("请求失败")
                    }
                }
            }
        }
    }

    fun consumeRunPhase() {
        val phase = _runPhase.value
        if (phase is OpsWritePhase.Done || phase is OpsWritePhase.Error) {
            _runPhase.value = OpsWritePhase.Idle
        }
    }

    private suspend fun prepareIconLoader() {
        val host = container.hostDao.findById(hostId) ?: return
        baseUrl = host.baseUrl
        val credential = container.hostRepository.credentialFor(host) ?: return
        _iconLoader.value = ImageLoader.Builder(application)
            .okHttpClient { container.client.rawClient(credential.pin) }
            .build()
    }

    override fun onCleared() {
        // Coil 2 的 ImageLoader 没有 dispose()，丢引用即可，磁盘/内存缓存由 Coil 全局持有
        _iconLoader.value = null
    }
}
