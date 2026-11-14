package com.catmoecircle.areyouok.ui.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Photos

/**
 * 扫码取景页：CameraX 预览 + ML Kit 实时识别二维码。
 *
 * 取代原来的 zxing `CaptureActivity`（库默认锁横屏、预览方向还得自己兜），这里
 * 自己拼一套 Compose 界面：
 *
 * - Manifest 里固定竖屏，和 App 其余页面保持一致，不会再横过来；
 * - 取景框中间是清晰区域，四周压暗，右上角「相册」图标可从本地图片识别；
 * - 相机权限在页内申请，被拒绝时给出「去设置 / 从相册选择」两条退路；
 * - 识别成功即回传文本并关闭，结果与相册选图完全一致（都走
 *   [sanitizeScannedText] 清理）。
 */
class QrScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prompt = intent.getStringExtra(EXTRA_PROMPT)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.gateway_scan_prompt)
        setContent {
            AreYouOKTheme {
                QrScanScreen(
                    prompt = prompt,
                    onScanned = ::deliverResult,
                    onCancel = ::cancelScan,
                )
            }
        }
    }

    /** 把识别到的二维码文本回给调用方（见 [rememberQrScannerLauncher]）。 */
    private fun deliverResult(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
        finish()
    }

    private fun cancelScan() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val EXTRA_PROMPT = "com.catmoecircle.areyouok.extra.QR_SCAN_PROMPT"
        private const val EXTRA_RESULT = "com.catmoecircle.areyouok.extra.QR_SCAN_RESULT"

        /** 构造打开扫码页的 Intent。 */
        fun intent(context: Context, prompt: String): Intent =
            Intent(context, QrScanActivity::class.java).putExtra(EXTRA_PROMPT, prompt)

        /** 从扫码页返回的数据里取二维码文本；取消或空内容返回 null。 */
        fun resultText(data: Intent?): String? =
            sanitizeScannedText(data?.getStringExtra(EXTRA_RESULT))
    }
}

/** 扫码页主体：取景 + 遮罩 + 权限兜底。 */
@Composable
private fun QrScanScreen(
    prompt: String,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraFailed by remember { mutableStateOf(false) }
    var pickingImage by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(cameraGranted) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val unreadableText = stringResource(R.string.gateway_scan_image_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // uri 为 null 表示用户取消了选择。
        if (uri == null) return@rememberLauncherForActivityResult
        pickingImage = true
        scope.launch {
            val scanned = decodeQrFromImage(context, uri)
            pickingImage = false
            if (scanned.isNullOrEmpty()) {
                Toast.makeText(context, unreadableText, Toast.LENGTH_SHORT).show()
            } else {
                onScanned(scanned)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (cameraGranted && !cameraFailed) {
            QrCameraPreview(
                onScanned = onScanned,
                onFailure = { cameraFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        QrScanOverlay(
            prompt = prompt,
            showViewfinder = cameraGranted && !cameraFailed,
            noticeText = when {
                !cameraGranted -> stringResource(R.string.gateway_scan_camera_denied)
                cameraFailed -> stringResource(R.string.gateway_scan_camera_failed)
                else -> null
            },
            noticeHint = if (!cameraGranted) {
                stringResource(R.string.gateway_scan_camera_denied_hint)
            } else {
                null
            },
            onOpenSettings = { openAppSettings(context) },
            onCancel = onCancel,
            onPickImage = {
                if (!pickingImage) {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
        )

        if (pickingImage) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.gateway_scan_picking),
                    color = Color.White,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

/**
 * CameraX 预览 + ML Kit 逐帧识别。
 *
 * 分析链路：预览帧 → [InputImage]（带上 CameraX 算好的 `rotationDegrees`）→
 * [BarcodeScanner] → 首个二维码文本。识别期间不放行新帧（ML Kit 单帧处理更稳，
 * 也避免重复回调）。
 */
@Composable
private fun QrCameraPreview(
    onScanned: (String) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var scannedText by remember { mutableStateOf<String?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analyzer = remember {
        QrFrameAnalyzer(sharedQrScanner) { text ->
            // 分析线程回调：切回主线程再更新 Compose 状态，并且只认第一个结果。
            mainHandler.post { if (scannedText == null) scannedText = text }
        }
    }

    LaunchedEffect(scannedText) {
        scannedText?.let(onScanned)
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analysisExecutor, analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (error: Exception) {
                    onFailure()
                }
            },
            mainExecutor,
        )

        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** 逐帧识别器：同一时间只处理一帧，处理完再关掉 [ImageProxy]。 */
private class QrFrameAnalyzer(
    private val scanner: BarcodeScanner,
    private val onFound: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val media = image.image
        if (media == null) {
            busy.set(false)
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val text = sanitizeScannedText(barcode.rawValue)
                    if (text != null) {
                        onFound(text)
                        break
                    }
                }
            }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }
}

/** 取景层：四边压暗 + 中间取景框，底部提示语，顶部「关闭 / 相册」两个按钮。 */
@Composable
private fun QrScanOverlay(
    prompt: String,
    showViewfinder: Boolean,
    noticeText: String?,
    noticeHint: String?,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeLabel = stringResource(R.string.gateway_scan_close)
    val albumLabel = stringResource(R.string.gateway_scan_image)
    val openSettingsLabel = stringResource(R.string.gateway_scan_open_settings)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 取景框：短边的 66%，提示语放在框下方。
        val hole = (if (maxWidth < maxHeight) maxWidth else maxHeight) * 0.66f

        if (showViewfinder) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val holePx = size.minDimension * 0.66f
                val left = (size.width - holePx) / 2f
                val top = (size.height - holePx) / 2f
                val scrim = Color.Black.copy(alpha = 0.5f)
                drawRect(scrim, Offset.Zero, ComposeSize(size.width, top))
                drawRect(
                    scrim,
                    Offset(0f, top + holePx),
                    ComposeSize(size.width, size.height - top - holePx),
                )
                drawRect(scrim, Offset(0f, top), ComposeSize(left, holePx))
                drawRect(
                    scrim,
                    Offset(left + holePx, top),
                    ComposeSize(size.width - left - holePx, holePx),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.92f),
                    topLeft = Offset(left, top),
                    size = ComposeSize(holePx, holePx),
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            Text(
                text = prompt,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = hole / 2 + 28.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScanOverlayButton(MiuixIcons.Regular.Back, closeLabel, onCancel)
            Spacer(Modifier.weight(1f))
            ScanOverlayButton(MiuixIcons.Regular.Photos, albumLabel, onPickImage)
        }

        if (noticeText != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = noticeText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (noticeHint != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = noticeHint, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = onOpenSettings) {
                        Text(text = openSettingsLabel, color = Color.White, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onPickImage) {
                        Text(text = albumLabel, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/** 取景页顶部按钮：半透明圆形底 + 白色图标。 */
@Composable
private fun ScanOverlayButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** 相机权限被永久拒绝时，系统不再弹窗，只能引导去应用详情页手动打开。 */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
