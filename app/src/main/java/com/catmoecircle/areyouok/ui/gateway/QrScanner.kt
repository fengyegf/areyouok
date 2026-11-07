package com.catmoecircle.areyouok.ui.gateway

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * 清理扫码/粘贴得到的二维码文本：去首尾空白，并去掉扫码器偶尔带上的引号。
 * 纯 JVM 函数，单测直接覆盖；空内容返回 null 表示「没有可用内容」。
 */
fun sanitizeScannedText(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val quotes = charArrayOf('"', '\'', ' ', '\n', '\r', '\t')
    val cleaned = trimmed.trim(*quotes)
    return cleaned.ifEmpty { null }
}

/**
 * 扫码入口：调起 zxing-android-embedded 的相机扫码页，返回二维码里的文本。
 *
 * - 只识别二维码（配对深链 `dshgw://pair?…` 或纯 `http://<ip>:7788` 地址）；
 * - 相机权限由库内 `CaptureActivity` 自行申请，拒绝后会停留在扫码页，用户可返回手动输入；
 * - 扫到内容即关闭扫码页并回调，再交给
 *   [com.catmoecircle.areyouok.gateway.pairing.PairInviteParser] 解析。
 *
 * @param prompt 扫码页底部提示语（例如「对准电脑上的配对二维码」）。
 * @param onScanned 识别到二维码文本时回调（已清理，空内容不回调）。
 * @return 触发扫码的函数，可在按钮点击时调用。
 */
@Composable
fun rememberQrScannerLauncher(prompt: String, onScanned: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        sanitizeScannedText(result.contents)?.let(onScanned)
    }
    return remember(launcher, prompt) {
        {
            launcher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(prompt)
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                    setBarcodeImageEnabled(false)
                },
            )
        }
    }
}
