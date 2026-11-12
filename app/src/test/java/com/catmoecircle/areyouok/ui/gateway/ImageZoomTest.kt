package com.catmoecircle.areyouok.ui.gateway

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片预览缩放的纯数学（JVM 单测，不需要 Android 运行时）。 */
class ImageZoomTest {

    private val eps = 0.0001f

    private fun assertOffset(expectedX: Float, expectedY: Float, actual: Offset) {
        assertEquals(expectedX, actual.x, eps)
        assertEquals(expectedY, actual.y, eps)
    }

    @Test
    fun `scale is clamped into the preview range`() {
        assertEquals(1f, clampPreviewScale(0.2f), eps)
        assertEquals(1f, clampPreviewScale(-3f), eps)
        assertEquals(3f, clampPreviewScale(3f), eps)
        assertEquals(PREVIEW_MAX_SCALE, clampPreviewScale(12f), eps)
        assertEquals(PREVIEW_MIN_SCALE, clampPreviewScale(Float.NaN), eps)
    }

    @Test
    fun `zoomed flag ignores floating point noise`() {
        assertFalse(isPreviewZoomed(1f))
        assertFalse(isPreviewZoomed(1.005f))
        assertTrue(isPreviewZoomed(1.5f))
        assertTrue(isPreviewZoomed(PREVIEW_MAX_SCALE))
    }

    @Test
    fun `double tap zooms in then resets`() {
        assertEquals(PREVIEW_DOUBLE_TAP_SCALE, doubleTapTargetScale(1f), eps)
        assertEquals(PREVIEW_MIN_SCALE, doubleTapTargetScale(PREVIEW_MAX_SCALE), eps)
        // 已经放大时再双击回到 1x，偏移由调用方清空
        assertEquals(PREVIEW_MIN_SCALE, doubleTapTargetScale(PREVIEW_DOUBLE_TAP_SCALE), eps)
    }

    @Test
    fun `wide image fits the screen width instead of leaving top and bottom gaps`() {
        // 用户截图里的 1570x837 宽图放进 1080x2400 竖屏：按宽度 Fit，高度只占中间一条
        val geometry = previewGeometry(
            imageWidth = 1570f,
            imageHeight = 837f,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
        )
        assertEquals(1080f, geometry.fittedWidth, eps)
        assertEquals(1080f * 837f / 1570f, geometry.fittedHeight, eps)
        assertTrue(geometry.fittedHeight < geometry.viewportHeight)
        assertTrue(geometry.fittedWidth <= geometry.viewportWidth)
    }

    @Test
    fun `tall image fits the screen height`() {
        val geometry = previewGeometry(
            imageWidth = 800f,
            imageHeight = 2000f,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
        )
        assertEquals(2400f, geometry.fittedHeight, eps)
        assertEquals(2400f * 800f / 2000f, geometry.fittedWidth, eps)
    }

    @Test
    fun `invalid sizes produce empty fitted box`() {
        val geometry = previewGeometry(0f, 837f, 1080f, 2400f)
        assertEquals(0f, geometry.fittedWidth, eps)
        assertEquals(0f, geometry.fittedHeight, eps)
        // 空几何下不允许任何平移
        assertOffset(0f, 0f, clampPreviewOffset(Offset(50f, 50f), 3f, geometry))
    }

    @Test
    fun `focus zoom keeps the point under the fingers`() {
        val oldScale = 1f
        val newScale = 2f
        val focus = 200f
        val oldOffset = 0f
        val newOffset = zoomOffsetForFocus(oldOffset, focus, oldScale, newScale)

        // 缩放前落在焦点下的图片点
        val point = (focus - oldOffset) / oldScale
        // 缩放后它仍在焦点下
        assertEquals(focus, point * newScale + newOffset, eps)
    }

    @Test
    fun `focus zoom at the viewport center scales the offset proportionally`() {
        // 焦点在中心时没有「围绕某点」的平移，偏移按倍率放大
        assertEquals(120f, zoomOffsetForFocus(30f, 0f, 1f, 4f), eps)
    }

    @Test
    fun `pan is forced to center at fit scale`() {
        val geometry = previewGeometry(1570f, 837f, 1080f, 2400f)
        assertOffset(0f, 0f, clampPreviewOffset(Offset(120f, -80f), 1f, geometry))
    }

    @Test
    fun `zoomed wide image clamps horizontally and stays centered vertically`() {
        val geometry = previewGeometry(1570f, 837f, 1080f, 2400f)
        val maxX = (geometry.fittedWidth * 2f - geometry.viewportWidth) / 2f
        // 横向最多拖到边缘贴边
        assertOffset(maxX, 0f, clampPreviewOffset(Offset(9999f, 9999f), 2f, geometry))
        assertOffset(-maxX, 0f, clampPreviewOffset(Offset(-9999f, -9999f), 2f, geometry))
        // 未超出边界时保持原样
        assertOffset(40f, 0f, clampPreviewOffset(Offset(40f, 0f), 2f, geometry))
        // 纵向图片仍比视口矮 → 不允许纵向偏移
        assertOffset(0f, 0f, clampPreviewOffset(Offset(0f, 300f), 2f, geometry))
    }

    @Test
    fun `zoomed tall image clamps vertically`() {
        val geometry = previewGeometry(800f, 2000f, 1080f, 2400f)
        val maxY = (geometry.fittedHeight * 3f - geometry.viewportHeight) / 2f
        assertOffset(0f, maxY, clampPreviewOffset(Offset(0f, 99999f), 3f, geometry))
        assertOffset(0f, -maxY, clampPreviewOffset(Offset(0f, -99999f), 3f, geometry))
    }

    @Test
    fun `scale beyond bounds also keeps the image inside the viewport`() {
        val geometry = previewGeometry(1570f, 837f, 1080f, 2400f)
        // 手势可能把偏移调得很大，再按最大倍率钳制也必须落在合法范围内
        val clamped = clampPreviewOffset(Offset(5000f, 5000f), PREVIEW_MAX_SCALE, geometry)
        val maxX = (geometry.fittedWidth * PREVIEW_MAX_SCALE - geometry.viewportWidth) / 2f
        val maxY = (geometry.fittedHeight * PREVIEW_MAX_SCALE - geometry.viewportHeight) / 2f
        assertEquals(maxX, clamped.x, eps)
        assertEquals(maxY, clamped.y, eps)
    }
}
