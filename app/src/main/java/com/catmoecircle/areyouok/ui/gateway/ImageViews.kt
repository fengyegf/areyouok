package com.catmoecircle.areyouok.ui.gateway

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.catmoecircle.areyouok.gateway.protocol.MessageImage

/**
 * 按需取图：URL → 图片字节，取不到返回 null。
 *
 * 走网关的图片预览端点（PC 按请求现场读文件/附件），App 不下载、不落盘：
 * PC 上文件被删了，这里也就显示不出来，和桌面端一致。
 */
fun interface ImageFetcher {
    suspend fun fetch(url: String): ByteArray?
}

/**
 * 当前聊天里的取图入口与所属会话。
 *
 * 用 CompositionLocal 传递，避免给时间线里每一层折叠行都加参数；
 * 默认 null（预览/测试环境）时不加载任何图片。
 */
val LocalImageFetcher = staticCompositionLocalOf<ImageFetcher?> { null }

val LocalImageSessionId = staticCompositionLocalOf<String?> { null }

/** 内存里的预览缓存：只活在本进程，退出即丢，不算「下载」。 */
private object PreviewBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/** 缓存 key 带上解码尺寸：缩略图和大图分开存，互不顶掉。 */
private fun previewCacheKey(url: String, maxEdge: Int): String = url + "@" + maxEdge

/**
 * 一张「按链接预览」的图片：进入可见区域时才向网关要字节，加载完按比例缩放显示。
 *
 * 加载中留白，失败显示一行灰字——图片是锦上添花，不能拖垮整条消息。
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 260.dp,
    maxHeight: Dp = 320.dp,
    contentDescription: String? = null,
    /** 非空时按正方形格子裁切（多图网格）；为空时按原比例缩放。 */
    squareSize: Dp? = null,
) {
    val fetcher = LocalImageFetcher.current
    val key = url?.takeIf { it.isNotBlank() }
    if (key == null || fetcher == null) {
        // 网格里没有地址也要占住格子，否则多图会让气泡忽宽忽窄。
        if (squareSize != null) ImageCellPlaceholder(size = squareSize)
        return
    }

    // 网格缩略图按格子尺寸解码：别拿 1600px 的大图去塞 100dp 的方格。
    val maxEdge = if (squareSize == null) PREVIEW_MAX_EDGE else GRID_THUMB_MAX_EDGE
    val cacheKey = previewCacheKey(key, maxEdge)
    var bitmap by remember(cacheKey) { mutableStateOf(PreviewBitmapCache.get(cacheKey)) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var preview by remember(cacheKey) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(cacheKey, fetcher) {
        if (bitmap != null) return@LaunchedEffect
        val bytes = runCatching { fetcher.fetch(key) }.getOrNull()
        val decoded = bytes?.let { decodePreview(it, maxEdge) }
        if (decoded == null) {
            failed = true
        } else {
            PreviewBitmapCache.put(cacheKey, decoded)
            bitmap = decoded
        }
    }

    val loaded = bitmap
    when {
        loaded != null -> Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = if (squareSize == null) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier
                .then(
                    if (squareSize == null) {
                        Modifier.sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
                    } else {
                        Modifier.size(squareSize)
                    },
                )
                .clip(RoundedCornerShape(if (squareSize == null) 12.dp else 10.dp))
                .clickable { preview = loaded },
        )

        failed -> if (squareSize == null) {
            Text(
                text = "图片无法预览",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = modifier,
            )
        } else {
            ImageCellPlaceholder(size = squareSize, label = "无法预览")
        }

        // 网格里先占位（多图逐张加载时格子不会忽有忽无）；单图仍按原样留白。
        else -> if (squareSize == null) Unit else ImageCellPlaceholder(size = squareSize)
    }

    preview?.let { shown ->
        ImagePreviewDialog(bitmap = shown, url = key, onDismiss = { preview = null })
    }
}

/**
 * 图片大图预览：全屏黑底、双指缩放（1x–6x）、双击放大/复位、放大后可拖动平移。
 *
 * 只为「看一下」服务，不做保存/分享。未放大时单击或返回键关闭；放大后单击不关，
 * 避免和拖动/双击抢事件而误关。
 */
@Composable
fun ImagePreviewDialog(bitmap: Bitmap, onDismiss: () -> Unit, url: String? = null) {
    // 网格里点开的是缩略图：先顶上缩略图，再去取同一张的全尺寸版本换掉。
    var shown by remember(bitmap) { mutableStateOf(bitmap) }
    val fetcher = LocalImageFetcher.current
    LaunchedEffect(url, fetcher) {
        val key = url?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (fetcher == null) return@LaunchedEffect
        val fullKey = previewCacheKey(key, PREVIEW_MAX_EDGE)
        val full = PreviewBitmapCache.get(fullKey)
            ?: runCatching { fetcher.fetch(key) }.getOrNull()
                ?.let { decodePreview(it, PREVIEW_MAX_EDGE) }
                ?.also { PreviewBitmapCache.put(fullKey, it) }
        if (full != null) shown = full
    }
    // 每次打开都是全新的预览状态（Dialog 退出组合后自动丢弃）。
    var scale by remember { mutableStateOf(PREVIEW_MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val image = remember(shown) { shown.asImageBitmap() }

    // usePlatformDefaultWidth = false：去掉对话框默认的窗口宽度上限，宽图不再被塞进小窗；
    // decorFitsSystemWindows = false：黑色背景铺到状态栏/导航栏下方，四周不留系统栏缝隙。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds()
                // 双指缩放 / 拖动：先以焦点缩放，再叠加平移，最后统一 clamp。
                .pointerInput(shown) {
                    val imageWidth = shown.width.toFloat()
                    val imageHeight = shown.height.toFloat()
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldScale = scale
                        val newScale = clampPreviewScale(oldScale * zoom)
                        // 焦点换算成「相对视口中心」的坐标，和 graphicsLayer 绕中心缩放一致。
                        val focusX = centroid.x - size.width / 2f
                        val focusY = centroid.y - size.height / 2f
                        val moved = Offset(
                            zoomOffsetForFocus(offset.x, focusX, oldScale, newScale) + pan.x,
                            zoomOffsetForFocus(offset.y, focusY, oldScale, newScale) + pan.y,
                        )
                        val geometry = previewGeometry(
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            viewportWidth = size.width.toFloat(),
                            viewportHeight = size.height.toFloat(),
                        )
                        scale = newScale
                        offset = clampPreviewOffset(moved, newScale, geometry)
                    }
                }
                .pointerInput(shown) {
                    val imageWidth = shown.width.toFloat()
                    val imageHeight = shown.height.toFloat()
                    fun geometry(): PreviewGeometry = previewGeometry(
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                    )
                    detectTapGestures(
                        onTap = {
                            // 放大状态下的单击不关闭：留给人看细节，也避免误触。
                            if (!isPreviewZoomed(scale)) onDismiss()
                        },
                        onDoubleTap = { position ->
                            val target = doubleTapTargetScale(scale)
                            if (isPreviewZoomed(target)) {
                                val focusX = position.x - size.width / 2f
                                val focusY = position.y - size.height / 2f
                                val moved = Offset(
                                    zoomOffsetForFocus(offset.x, focusX, scale, target),
                                    zoomOffsetForFocus(offset.y, focusY, scale, target),
                                )
                                scale = target
                                offset = clampPreviewOffset(moved, target, geometry())
                            } else {
                                scale = PREVIEW_MIN_SCALE
                                offset = Offset.Zero
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

// ------------------------------------------------------------------ 多图网格

/** 格子之间的间隙。 */
private val GRID_GAP = 4.dp

/** 两列时的格子边长：2–4 张走 2×2，比一张一行省掉一半以上高度。 */
private val GRID_TWO_COLUMN_CELL = 112.dp

/** 三列时的格子边长：5 张以上再缩一档。 */
private val GRID_THREE_COLUMN_CELL = 92.dp

/**
 * 用户消息里的多张图片：按网格紧凑排开，一屏能看全，不再一张占一行。
 *
 * - 2–4 张两列（2×2），5 张以上三列；
 * - 每格正方形裁切，缩略图按格子尺寸解码，不拿全尺寸大图塞小格；
 * - 点按任意一格仍然打开全屏预览（预览会再取一次全尺寸）；
 * - 超过 IMAGE_GRID_MAX_CELLS 张时最后一格盖一层「+N」，别让一条消息把对话顶走。
 */
@Composable
fun UserImageGrid(
    images: List<MessageImage>,
    sessionId: String?,
    modifier: Modifier = Modifier,
) {
    val visible = images.take(imageGridVisibleCount(images.size))
    if (visible.isEmpty()) return
    val columns = imageGridColumns(visible.size)
    val cell = if (columns == 2) GRID_TWO_COLUMN_CELL else GRID_THREE_COLUMN_CELL
    val overflow = images.size - visible.size

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        for (rowStart in visible.indices step columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP, Alignment.End)) {
                for (index in rowStart until minOf(rowStart + columns, visible.size)) {
                    val image = visible[index]
                    Box {
                        RemoteImage(
                            url = attachmentPreviewUrl(image, sessionId),
                            contentDescription = image.name,
                            squareSize = cell,
                        )
                        if (overflow > 0 && index == visible.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+" + overflow,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 网格里的空格占位：加载中/取不到时也占住同样尺寸，避免多图忽大忽小。 */
@Composable
private fun ImageCellPlaceholder(size: Dp, label: String? = null) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 9.sp,
            )
        }
    }
}

/** 预览缩放范围：1x 为等比 Fit 的原始大小，6x 足够看清细节。 */
internal const val PREVIEW_MIN_SCALE = 1f
internal const val PREVIEW_MAX_SCALE = 6f
/** 双击放大到的倍率；再双击回到 1x。 */
internal const val PREVIEW_DOUBLE_TAP_SCALE = 2.5f
/** 判定「已放大」的阈值，避开浮点误差。 */
private const val PREVIEW_ZOOM_EPSILON = 0.01f

/** 预览视图的几何信息（像素）：视口大小 + 图片按 Fit 居中后的基准尺寸。 */
internal data class PreviewGeometry(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
)

/** 把缩放钳制在 1x–6x。 */
internal fun clampPreviewScale(scale: Float): Float =
    if (scale.isNaN()) PREVIEW_MIN_SCALE else scale.coerceIn(PREVIEW_MIN_SCALE, PREVIEW_MAX_SCALE)

/** 是否处于放大状态；放大后单击不关闭、才允许拖动。 */
internal fun isPreviewZoomed(scale: Float): Boolean = scale > PREVIEW_MIN_SCALE + PREVIEW_ZOOM_EPSILON

/** 双击的目标缩放：未放大 → 2.5x，已放大 → 复位到 1x。 */
internal fun doubleTapTargetScale(currentScale: Float): Float =
    if (isPreviewZoomed(currentScale)) PREVIEW_MIN_SCALE else PREVIEW_DOUBLE_TAP_SCALE

/**
 * 图片按 Fit 居中后的基准尺寸：等比缩放到刚好放下，长边贴住屏幕，不留额外黑边。
 * 参数非法（图片/视口尺寸为 0）时基准尺寸为 0，调用方按「无可移动余量」处理。
 */
internal fun previewGeometry(
    imageWidth: Float,
    imageHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): PreviewGeometry {
    if (imageWidth <= 0f || imageHeight <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) {
        return PreviewGeometry(viewportWidth, viewportHeight, 0f, 0f)
    }
    val fitScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    return PreviewGeometry(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        fittedWidth = imageWidth * fitScale,
        fittedHeight = imageHeight * fitScale,
    )
}

/**
 * 以手势焦点为中心缩放后，偏移应该变成多少（单轴）。
 *
 * 设偏移前偏移为 o、焦点为 f（相对视口中心），焦点下的图片点在缩放前后保持不动：
 * f = p * oldScale + o，故 newOffset = f - (f - o) * newScale / oldScale。
 */
internal fun zoomOffsetForFocus(currentOffset: Float, focus: Float, oldScale: Float, newScale: Float): Float {
    if (oldScale <= 0f || newScale <= 0f) return currentOffset
    return focus - (focus - currentOffset) * (newScale / oldScale)
}

/**
 * 平移偏移的边界钳制：图片比视口小时强制居中（偏移 0），
 * 放大后最多拖到图片边缘贴住视口边缘，不露出黑边、也不拖出可视区。
 */
internal fun clampPreviewOffset(offset: Offset, scale: Float, geometry: PreviewGeometry): Offset {
    val maxX = ((geometry.fittedWidth * scale - geometry.viewportWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((geometry.fittedHeight * scale - geometry.viewportHeight) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

/** 全屏预览的解码上限（最长边 px）。 */
private const val PREVIEW_MAX_EDGE = 1600

/**
 * 网格缩略图的解码上限（最长边 px）：百来 dp 的格子按 3x 密度约 300px，
 * 再大就是白吃内存——缩略图不该和全屏预览抢同一份缓存。
 */
private const val GRID_THUMB_MAX_EDGE = 420

/** 解码预览图：按最长边 [maxEdge] 采样，避免大图把内存吃满。 */
private fun decodePreview(bytes: ByteArray, maxEdge: Int = PREVIEW_MAX_EDGE): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

