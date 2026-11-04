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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 云服务器扫码绑定入口页：相机走 zxing-embedded 的 [ScanContract]（与 PairScanScreen
 * 同一套取景交互，权限被拒 / 无相机 / 退出扫码器都有手动输入兜底）。
 *
 * 二维码内容是 JSON（协议 §2）：
 * `{"app":"mnbt","type":"服务商系统标识","proto":2,"url":"https://站点根","code":"绑定码"}`。
 * app=mnbt 且 proto>=2 才放行去绑定页（type 自由填写，透传给绑定页落库展示）；
 * 缺 proto 的旧版 v1 码给「插件需升级」引导，其余给非法码错误态（对齐 MNBT 扫到
 * 非法码的提示风格）。校验是纯本地逻辑，不走 VM。
 */
@Composable
fun CloudPairScanScreen(navController: NavController) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    var scanError by rememberSaveable { mutableStateOf<Int?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result: ScanIntentResult? ->
        val contents = result?.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        // result 为 null / contents 为空 = 用户按返回退出扫码器：留在本页走手动输入
        when (val qr = parseCloudQr(contents)) {
            is CloudQr.Valid -> {
                scanError = null
                navController.navigate(Destinations.cloudPairTrustRoute(qr.url, qr.code, qr.systemType))
            }
            is CloudQr.LegacyProtocol -> scanError = R.string.cloud_pair_legacy_qr
            is CloudQr.Invalid -> scanError = R.string.cloud_pair_invalid_qr
        }
    }

    val promptText = stringResource(R.string.cloud_pair_scan_prompt)
    val launchScanner = {
        scanError = null
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

        scanError?.let { errorRes ->
            CloudPairFailedCard(
                message = stringResource(errorRes),
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

/** 云站点配对二维码解析结果（协议 §2）。 */
internal sealed interface CloudQr {
    /** v2 合法码：放行绑定。[systemType] 为服务商系统标识（type 字段，透传落库展示）。 */
    data class Valid(val url: String, val code: String, val systemType: String) : CloudQr

    /** v1 旧码（无 proto）：绑定必失败，给升级引导而不是笼统的「非法二维码」。 */
    data object LegacyProtocol : CloudQr

    /** 不是云站点配对二维码。 */
    data object Invalid : CloudQr
}

/**
 * 校验并抽取云站点绑定二维码。`app=="mnbt"` 且 url/code 非空才认定是配对码：
 * 有 `proto>=2` → [CloudQr.Valid]；缺 `proto`（v1 旧版插件生成的码）→
 * [CloudQr.LegacyProtocol]；其余一律 [CloudQr.Invalid]。
 */
internal fun parseCloudQr(raw: String): CloudQr = try {
    val obj = MnbtJson.parseToJsonElement(raw).jsonObject
    val app = obj["app"]?.jsonPrimitive?.contentOrNull
    val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val proto = obj["proto"]?.jsonPrimitive?.intOrNull
    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val code = obj["code"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    when {
        app != "mnbt" || url.isEmpty() || code.isEmpty() -> CloudQr.Invalid
        proto == null || proto < 2 -> CloudQr.LegacyProtocol
        else -> CloudQr.Valid(url, code, type)
    }
} catch (e: Exception) {
    // 非法 JSON / 字段不是字符串：一律按「扫到的不是绑定二维码」处理
    CloudQr.Invalid
}
