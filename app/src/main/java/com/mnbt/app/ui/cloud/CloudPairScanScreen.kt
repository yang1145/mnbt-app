package com.mnbt.app.ui.cloud

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.mnbt.app.data.remote.api.MnbtJson
import com.mnbt.app.ui.nav.Destinations
import com.mnbt.app.ui.theme.AppCorner
import com.mnbt.app.ui.theme.AppDimens
import com.mnbt.app.ui.theme.LocalAppPalette
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 云服务器扫码绑定入口页：相机走 zxing-embedded 的 [ScanContract]（与 PairScanScreen
 * 同一套取景交互，权限被拒 / 无相机 / 退出扫码器都有手动输入兜底）。
 *
 * 二维码内容是 JSON：`{"app":"mnbt","type":"zjmf","url":"https://站点根","code":"绑定码"}`。
 * app/type 校验通过且 url/code 非空才放行去绑定页；否则页面给明确错误态（对齐
 * MNBT 扫到非法码的提示风格）。校验是纯本地逻辑，不走 VM。
 */
@Composable
fun CloudPairScanScreen(navController: NavController) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    var invalidScan by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result: ScanIntentResult? ->
        val contents = result?.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        // result 为 null / contents 为空 = 用户按返回退出扫码器：留在本页走手动输入
        val qr = parseCloudQr(contents)
        if (qr == null) {
            invalidScan = true
        } else {
            navController.navigate(Destinations.cloudPairTrustRoute(qr.first, qr.second))
        }
    }

    val promptText = stringResource(R.string.cloud_pair_scan_prompt)
    val launchScanner = {
        invalidScan = false
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppDimens.ScreenPadding),
    ) {
        CloudPairTopBar(stringResource(R.string.cloud_pair_title_scan), navController)

        // 取景框示意：细边方框 + 扫码图标，不做蓝底色块（§8.3）；占余下高度防小屏溢出
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
                        text = stringResource(R.string.cloud_pair_scan_viewfinder_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                    if (cameraDenied) {
                        Text(
                            text = stringResource(R.string.cloud_pair_camera_denied),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }

        if (invalidScan) {
            CloudPairFailedCard(
                message = stringResource(R.string.cloud_pair_invalid_qr),
                onRetry = { launchScanner() },
                modifier = Modifier.padding(bottom = AppDimens.CardGap),
            )
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
                text = stringResource(R.string.cloud_pair_scan_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // 取景框下方常驻手动输入入口（兜底口）
        TextButton(
            onClick = { navController.navigate(Destinations.CLOUD_PAIR_TRUST) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = AppDimens.ScreenPadding),
        ) {
            Text(
                text = stringResource(R.string.cloud_pair_manual_entry),
                color = palette.primary,
            )
        }
    }
}

/** 校验并抽取 ZJMF 绑定二维码的 (站点根 url, 绑定码)；不是合规 JSON 或 app/type 不对返回 null。 */
internal fun parseCloudQr(raw: String): Pair<String, String>? = try {
    val obj = MnbtJson.parseToJsonElement(raw).jsonObject
    val app = obj["app"]?.jsonPrimitive?.contentOrNull
    val type = obj["type"]?.jsonPrimitive?.contentOrNull
    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val code = obj["code"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (app == "mnbt" && type == "zjmf" && url.isNotEmpty() && code.isNotEmpty()) url to code else null
} catch (e: Exception) {
    // 非法 JSON / 字段不是字符串：一律按「扫到的不是绑定二维码」处理
    null
}
