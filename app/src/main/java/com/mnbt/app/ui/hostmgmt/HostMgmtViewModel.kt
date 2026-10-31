package com.mnbt.app.ui.hostmgmt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.data.local.db.HostEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主机管理 / 证书信任管理两页共用的本地条目 VM。
 *
 * 这一层没有网络调用：重命名、解绑、撤销信任全部走 HostRepository
 * （解绑内部会尽力调 device.revoke 吊销远端令牌，失败不阻断本地删除）。
 * 因此状态直接是 Room Flow，不套 LoadState——LoadState 留给 hostCall 类页面。
 */
class HostMgmtViewModel(private val container: AppContainer) : ViewModel() {

    val hosts: StateFlow<List<HostEntity>> = container.hostRepository.observeHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 只保留已手工 pin 过指纹的条目（PRD §9.2 第 4 步存入 Room 的那批） */
    val pinnedHosts: StateFlow<List<HostEntity>> = hosts
        .map { list -> list.filter { it.pinnedFingerprint != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun rename(id: Long, label: String) = viewModelScope.launch {
        try {
            container.hostRepository.rename(id, label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(e.message ?: "重命名失败")
        }
    }

    /** 远端吊销 + 本地删除 + 令牌 ref 清理，见 HostRepository.remove 注释 */
    fun unbind(id: Long) = viewModelScope.launch {
        try {
            container.hostRepository.remove(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(e.message ?: "解绑失败")
        }
    }

    /** 撤销信任：pin 置 null，下次连接回到系统 CA 校验，自签站点会重新走信任页 */
    fun revokeTrust(id: Long) = viewModelScope.launch {
        try {
            container.hostRepository.setPin(id, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(e.message ?: "撤销失败")
        }
    }
}
