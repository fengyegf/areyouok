package com.catmoecircle.areyouok.ui.gateway

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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

/** 只识别二维码：配对深链 `dshgw://pair?…` 或纯 `http://<ip>:7788` 地址。 */
private val qrScannerOptions: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    .build()

/**
 * 进程内共用的 ML Kit 识别器。
 *
 * 相机预览每秒会喂好几帧进来，每次新建 [BarcodeScanner] 都要重新加载模型，既慢又
 * 占内存；ML Kit 的 client 本身是线程安全的，所以整进程只建一个，随进程存活。
 */
internal val sharedQrScanner: BarcodeScanner by lazy { BarcodeScanning.getClient(qrScannerOptions) }

/**
 * 扫码入口：打开 [QrScanActivity]（CameraX 取景 + ML Kit 实时识别），返回二维码文本。
 *
 * - 只识别二维码（配对深链 `dshgw://pair?…` 或纯 `http://<ip>:7788` 地址）；
 * - 相机权限由扫码页自己申请，拒绝后可以返回手动输入，也能从相册选图；
 * - 扫到内容即关闭扫码页并回调，再交给
 *   [com.catmoecircle.areyouok.gateway.pairing.PairInviteParser] 解析。
 *
 * @param prompt 扫码页底部的提示语（例如「对准电脑上的配对二维码」）。
 * @param onScanned 识别到二维码文本时回调（已清理，空内容不回调）。
 * @return 触发扫码的函数，可在按钮点击时调用。
 */
@Composable
fun rememberQrScannerLauncher(prompt: String, onScanned: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    // 回调随重组变化，用 rememberUpdatedState 保证扫码页返回时调用的是最新的 lambda。
    val currentOnScanned by rememberUpdatedState(onScanned)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        QrScanActivity.resultText(result.data)?.let(currentOnScanned)
    }
    return remember(launcher, prompt) {
        { launcher.launch(QrScanActivity.intent(context, prompt)) }
    }
}

/** 相册图片的长边上限：超过就先缩小再识别，既省内存也不影响二维码识别。 */
private const val QR_IMAGE_MAX_EDGE = 1600

/** 相册图片的方向不确定，四个方向都试一遍（ML Kit 通过 [InputImage] 的旋转参数支持）。 */
private val qrRotations = intArrayOf(0, 90, 180, 270)

/**
 * 把 [width]×[height] 的图片缩到长边不超过 [maxEdge] 所需的采样比例（1、2、4…）。
 *
 * 相册照片动辄四五千像素，整张喂给 ML Kit 既慢又容易 OOM；二维码信息量小，
 * 缩到一两千像素仍能稳定识别。纯函数，单测直接覆盖。
 */
internal fun qrImageSampleSize(width: Int, height: Int, maxEdge: Int = QR_IMAGE_MAX_EDGE): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    val longest = max(width, height)
    var sample = 1
    while (longest / sample > maxEdge) {
        sample *= 2
    }
    return sample
}

/** ML Kit 的识别结果是异步 [Task]，这里把它包成挂起函数，方便顺序试四个方向。 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
}

/**
 * 在一张位图里找二维码：找到返回清理后的文本，没有返回 null。
 *
 * 依次试 0°/90°/180°/270°（相册里的截图/照片很少正好摆正）。ML Kit 认识
 * [InputImage] 上带的旋转角度，所以不用自己搬像素。
 */
internal suspend fun decodeQrBitmap(
    bitmap: Bitmap,
    scanner: BarcodeScanner = sharedQrScanner,
): String? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    for (rotation in qrRotations) {
        val barcodes = runCatching {
            scanner.process(InputImage.fromBitmap(bitmap, rotation)).awaitResult()
        }.getOrNull() ?: continue
        for (barcode in barcodes) {
            sanitizeScannedText(barcode.rawValue)?.let { return it }
        }
    }
    return null
}

/**
 * 本地图片扫码的公共接口：从 [uri] 指向的图片里识别二维码，返回清理后的文本；
 * 读不到图或图片里没有二维码都返回 null。
 *
 * 只依赖 [Context] 和图片 [Uri]，以后从分享 Intent、剪贴板图片等入口调用也走同
 * 一条路径。读图与解码都在 IO 线程，大图会先缩到 [QR_IMAGE_MAX_EDGE]。
 */
suspend fun decodeQrFromImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val bitmap = runCatching { loadScaledBitmap(context, uri) }.getOrNull()
        ?: return@withContext null
    try {
        decodeQrBitmap(bitmap)
    } finally {
        bitmap.recycle()
    }
}

/**
 * 读图并按 [QR_IMAGE_MAX_EDGE] 缩小。
 *
 * 用 [ImageDecoder] 而不是 BitmapFactory：它会按 EXIF 自动把照片摆正，并输出软件
 * 位图，保证 [Bitmap.getPixels] 可用。
 */
private fun loadScaledBitmap(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val sample = qrImageSampleSize(info.size.width, info.size.height)
        if (sample > 1) decoder.setTargetSampleSize(sample)
    }
}

/**
 * 本地图片扫码入口：调起系统照片选择器，把选中的图片交给 [decodeQrFromImage] 识别。
 *
 * - 不需要存储权限（API 33+ 走系统照片选择器，低版本由系统降级为文档选择器）；
 * - 识别在后台线程完成，选到没有二维码的图回调 [onUnreadable]，用户取消选择则什么都不做；
 * - 结果与相机扫码完全一致：已清理的二维码文本，可直接交给
 *   [com.catmoecircle.areyouok.gateway.pairing.PairInviteParser] 解析。
 *
 * @param onScanned 识别到二维码文本时回调（空内容不会触发）。
 * @param onUnreadable 图片里找不到二维码时回调（拍糊了、图不对等），用来提示用户。
 * @return 触发选图的函数，可在按钮长按或菜单项点击时调用。
 */
@Composable
fun rememberQrImageScannerLauncher(
    onScanned: (String) -> Unit,
    onUnreadable: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 回调随重组变化，用 rememberUpdatedState 保证相册返回时调用的是最新的 lambda。
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentOnUnreadable by rememberUpdatedState(onUnreadable)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // uri 为 null 表示用户取消了选择。
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val scanned = decodeQrFromImage(context, uri)
            if (scanned.isNullOrEmpty()) currentOnUnreadable() else currentOnScanned(scanned)
        }
    }
    return remember(picker) {
        {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}
