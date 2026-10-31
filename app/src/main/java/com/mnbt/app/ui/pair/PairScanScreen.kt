package com.mnbt.app.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.mnbt.app.R
import com.mnbt.app.ui.common.mnbtViewModel
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.delay

/**
 * 扫码配对入口页（PRD §4.2 / §7.2）。
 *
 * 相机走 zxing-android-embedded 的 [ScanContract]：授权后自动拉起全屏取景，
 * 本页保留取景框示意 + 常驻「手动输入」入口 —— 权限被拒、用户退出扫码器
 * 或设备无相机时，都能从这一层继续（§4.2 手输入口）。
 *
 * 探测到 明文/自签 分支时把流程交给信任页（route 带 base+code），本页 VM 复位。
 */
@Composable
fun PairScanScreen(navController: NavController) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    val vm: PairViewModel = mnbtViewModel { c, _ -> PairViewModel(c) }
    val state by vm.state.collectAsState()
    var cameraDenied by remember { mutableStateOf(false) }

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result: ScanIntentResult? ->
        val contents = result?.contents
        if (!contents.isNullOrBlank()) vm.onScanned(contents)
        // result 为 null / contents 为空 = 用户按返回退出扫码器：留在本页走手动输入
    }

    val promptText = stringResource(R.string.pair_scan_prompt)
    val launchScanner = {
        scanLauncher.launch(
            ScanOptions()
                .setBeepEnabled(false)
                // 不是「允许横屏」：库里的 true 是按当前传感器方向现场锁定，
                // 竖屏由 AndroidManifest 覆盖 CaptureActivity 的 screenOrientation 保证
                .setOrientationLocked(false)
                .setPrompt(promptText),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchScanner() else cameraDenied = true
    }

    LaunchedEffect(Unit) {
        if (hasCameraPermission()) launchScanner() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is PairState.PlaintextWarn, is PairState.Untrusted -> {
                val qr = s.trustHandoffQr ?: return@LaunchedEffect
                vm.reset()
                navController.navigate(Destinations.pairTrustRoute(qr.baseUrl, qr.code))
            }
            is PairState.Bound -> {
                delay(1_200)
                if (navController.currentBackStackEntry?.destination?.route == Destinations.PAIR_SCAN) {
                    navController.finishPairingAndOpenHost(s.hostId)
                }
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
    ) {
        PairTopBar(stringResource(R.string.pair_title_scan)) { navController.popBackStack() }

        // 取景框示意：细边方框 + 扫码图标，不做蓝底色块（PRD §8.3）；占余下高度防小屏溢出
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(AppDimens.Hairline, palette.outline, AppCorner.Card)
                    .background(palette.surface, AppCorner.Card),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(palette.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_scan),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = palette.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.pair_scan_viewfinder_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                    if (cameraDenied) {
                        Text(
                            text = stringResource(R.string.pair_camera_denied),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(AppDimens.CardGap),
        ) {
            PairStateViews(state = state, onRetry = vm::retry)
        }

        Button(
            onClick = {
                if (hasCameraPermission()) launchScanner() else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            shape = AppCorner.Control,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_scan),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.pair_scan_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // 取景框下方常驻手动输入入口（§4.2 兜底口）
        TextButton(
            onClick = { navController.navigate(Destinations.PAIR_MANUAL) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = AppDimens.ScreenPadding),
        ) {
            Text(
                text = stringResource(R.string.pair_manual_entry),
                color = palette.primary,
            )
        }
    }
}
