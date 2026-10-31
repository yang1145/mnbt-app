package com.mnbt.app.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mnbt.app.AppContainer
import com.mnbt.app.MnbtApplication
import com.mnbt.app.domain.call.HostOutcome

/**
 * 功能页统一状态。Blocked/NeedRepair 与 Failed 分开，因为 UI 反应不同：
 * 前两者是整页形态（只读页/引导重扫），后者是Snackbar级。
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
    data class Failed(val message: String, val raw: String? = null) : LoadState<Nothing>
    data object TokenRejected : LoadState<Nothing>
    data object CredentialMissing : LoadState<Nothing>
    data class HostBlocked(val message: String) : LoadState<Nothing>
}

fun <T> HostOutcome<T>.toLoadState(): LoadState<T> = when (this) {
    is HostOutcome.Ready -> LoadState.Ready(data)
    HostOutcome.TokenRejected -> LoadState.TokenRejected
    HostOutcome.CredentialMissing -> LoadState.CredentialMissing
    is HostOutcome.HostBlocked -> LoadState.HostBlocked(message)
    is HostOutcome.Failed -> LoadState.Failed(message, raw)
}

/** 从 Activity 的 Context 直接构造带 AppContainer 的 ViewModel。 */
@Composable
inline fun <reified VM : ViewModel> mnbtViewModel(
    crossinline create: (AppContainer, CreationExtras) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication
            create(app.container, this)
        }
    },
)

/** CreationExtras 里取容器，供 create  lambda 用。 */
fun CreationExtras.appContainer(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication).container
