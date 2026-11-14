package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.MessageImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 图片预览地址的纯函数（JVM 单测，不需要 Android）。 */
class ImageUrlsTest {

    private val image = MessageImage(
        attachmentId = "att-1",
        mediaType = "image/png",
        width = 800,
        height = 600,
        bytes = 12345,
        name = "截图 1.png",
    )

    @Test
    fun `attachment url carries the reference the host validates`() {
        val url = attachmentPreviewUrl(image, "s-1")
        assertEquals(
            "/v1/sessions/s-1/attachments/att-1?mediaType=image%2Fpng&width=800&height=600&bytes=12345&name=%E6%88%AA%E5%9B%BE%201.png",
            url,
        )
    }

    @Test
    fun `attachment url needs a usable reference`() {
        assertNull(attachmentPreviewUrl(image, null))
        assertNull(attachmentPreviewUrl(image, " "))
        assertNull(attachmentPreviewUrl(image.copy(attachmentId = ""), "s-1"))
        assertNull(attachmentPreviewUrl(image.copy(mediaType = null), "s-1"))
        assertNull(attachmentPreviewUrl(image.copy(width = null), "s-1"))
        assertNull(attachmentPreviewUrl(image.copy(bytes = null), "s-1"))
        assertNull(attachmentPreviewUrl(image.copy(mediaType = "text/plain"), "s-1"))
    }

    @Test
    fun `markdown local paths go through the session image preview`() {
        assertEquals(
            "/v1/sessions/s-1/image?path=docs%2Fshot%201.png",
            markdownImageUrl("docs/shot 1.png", "s-1"),
        )
        assertEquals(
            "/v1/sessions/s-1/image?path=E%3A%2Fwork%2Fshot.png",
            markdownImageUrl("E:/work/shot.png", "s-1"),
        )
    }

    @Test
    fun `markdown remote urls are used as-is`() {
        assertEquals("https://example.com/a.png", markdownImageUrl("https://example.com/a.png", "s-1"))
        assertEquals("http://example.com/a.png", markdownImageUrl("http://example.com/a.png", null))
        assertEquals("data:image/png;base64,AAA", markdownImageUrl("data:image/png;base64,AAA", null))
    }

    @Test
    fun `markdown local paths need a session`() {
        assertNull(markdownImageUrl("docs/shot.png", null))
        assertNull(markdownImageUrl("   ", "s-1"))
    }

    @Test
    fun `multi images use a compact grid instead of one per row`() {
        assertEquals(2, imageGridColumns(2))
        assertEquals(2, imageGridColumns(3))
        assertEquals(2, imageGridColumns(4))
        assertEquals(3, imageGridColumns(5))
        assertEquals(3, imageGridColumns(9))
    }

    @Test
    fun `the grid shows at most nine images`() {
        assertEquals(0, imageGridVisibleCount(0))
        assertEquals(1, imageGridVisibleCount(1))
        assertEquals(4, imageGridVisibleCount(4))
        assertEquals(9, imageGridVisibleCount(9))
        assertEquals(9, imageGridVisibleCount(12))
        assertEquals(0, imageGridVisibleCount(-3))
    }
}
