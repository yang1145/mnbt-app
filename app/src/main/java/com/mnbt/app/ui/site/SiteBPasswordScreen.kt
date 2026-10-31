package com.mnbt.app.ui.site

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mnbt.app.AppContainer
import com.mnbt.app.MnbtApplication
import com.mnbt.app.R
import com.mnbt.app.data.remote.api.MnbtActions
import com.mnbt.app.data.remote.api.decodePayload
import com.mnbt.app.ui.common.LoadState
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 主机密码长度下限：本地拦截 §4.6 的 1007（参数错误）场景，校验失败不发请求。 */
private const val SITE_B_MIN_PASSWORD_LENGTH = 8

/**
 * 修改主机控制面板密码（PRD §5.2 site.password.set，gn=xgpass）。
 * 初始化 site.init section=xgpass 只回填用户名，密码不回显、不预填。
 *
 * 入口签名：SitePasswordScreen(hostId, onBack, onRepair)
 */
@Composable
fun SitePasswordScreen(
    hostId: Long,
    onBack: () -> Unit = {},
    onRepair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm = mnbtViewModel { container, extras ->
        SiteBSitePasswordViewModel(
            container = container,
            hostId = hostId,
            context = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MnbtApplication,
        )
    }

    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val banner by vm.writeBanner.collectAsState()
    val repair by vm.repair.collectAsState()
    val palette = LocalAppPalette.current
    val snackbarHostState = remember { SnackbarHostState() }
    val readOnly = repair || banner != null

    LaunchedEffect(Unit) { vm.toast.collect { snackbarHostState.showSnackbar(it) } }

    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    val minLength = SITE_B_MIN_PASSWORD_LENGTH
    val tooShort = newPass.isNotEmpty() && newPass.length < minLength
    val mismatch = confirmPass.isNotEmpty() && newPass != confirmPass
    val newError = when {
        submitted && newPass.isEmpty() -> stringResource(R.string.site_b_err_password_empty)
        submitted && tooShort -> stringResource(R.string.site_b_err_password_short, minLength)
        else -> null
    }
    val confirmError = when {
        submitted && confirmPass.isEmpty() -> stringResource(R.string.site_b_err_password_confirm_empty)
        submitted && mismatch -> stringResource(R.string.site_b_err_password_mismatch)
        else -> null
    }
    val canSubmit = newPass.length >= minLength && newPass == confirmPass

    Scaffold(
        modifier = modifier,
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(AppDimens.ScreenPadding),
        ) {
            SiteBTopBar(title = stringResource(R.string.site_b_password_title), onBack = onBack)
            banner?.let {
                Spacer(Modifier.height(AppDimens.CardGap))
                SiteBBlockedBanner(it)
            }
            Spacer(Modifier.height(AppDimens.CardGap))

            SiteBStateBody(
                state = state,
                repair = repair,
                onRetry = { vm.reload() },
                onRepair = onRepair,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { init ->
                Text(
                    text = init.ftpHint.ifBlank { stringResource(R.string.site_b_password_hint) },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                // 账号只读回填行：56dp、与下方输入框等宽等高规整
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimens.RowHeight)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.site_b_password_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = init.user,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SiteBRowDivider()
                Spacer(Modifier.height(16.dp))

                SiteBPasswordField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = stringResource(R.string.site_b_password_new),
                    error = newError,
                    enabled = !readOnly,
                )
                Spacer(Modifier.height(12.dp))
                SiteBPasswordField(
                    value = confirmPass,
                    onValueChange = { confirmPass = it },
                    label = stringResource(R.string.site_b_password_confirm),
                    error = confirmError,
                    enabled = !readOnly,
                )
                Spacer(Modifier.height(24.dp))
                SiteBSubmitButton(
                    label = stringResource(R.string.site_b_password_submit),
                    enabled = !readOnly,
                    busy = busy,
                    onClick = {
                        submitted = true
                        if (canSubmit) {
                            vm.submit(newPass) {
                                newPass = ""
                                confirmPass = ""
                                submitted = false
                            }
                        }
                    },
                )
            }
        }
    }
}

internal class SiteBSitePasswordViewModel(
    container: AppContainer,
    hostId: Long,
    context: Context,
) : SiteBFeatureViewModel(container, hostId, context) {

    private val _state = MutableStateFlow<LoadState<SiteBPasswordInit>>(LoadState.Loading)
    val state: StateFlow<LoadState<SiteBPasswordInit>> = _state.asStateFlow()

    init {
        reload()
    }

    /** 初始化 = site.init section=xgpass，回 { section, user, ftp_hint }（口令不回显）。 */
    override fun reload() {
        viewModelScope.launch {
            load(_state, MnbtActions.SITE_INIT, mapOf("section" to "xgpass")) {
                decodePayload<SiteBPasswordInit>(it)
            }
        }
    }

    /**
     * 契约 §6.1：`site.password.set` 只读 `pass`（或 `newpass`），
     * 并把 FTP 与数据库口令同时改成这一个值（`ftp`/`sql` 缺省跟随 `pass`）；
     * 两者都空 → 1007。`user` 由服务端上下文决定，APP 传了也不消费，所以不发。
     * 口令绝不进日志、绝不回显 —— 提交后不要把 newPass 回填到任何状态。
     */
    fun submit(newPass: String, onDone: suspend () -> Unit = {}) {
        write(
            MnbtActions.SITE_PASSWORD_SET,
            mapOf("pass" to newPass),
            R.string.site_b_password_changed,
        ) { onDone() }
    }
}
