package com.catmoecircle.areyouok.ui.gateway

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备上的解码链路：用 zxing 生成二维码图片（仅测试夹具），再交给 ML Kit 识别，
 * 覆盖「相册选图 → 识别」这条真实路径。
 */
@RunWith(AndroidJUnit4::class)
class QrScanMlKitTest {

    @Test
    fun `decodes a qr image`() = runBlocking {
        assertEquals(PAYLOAD, decodeQrBitmap(renderQr(PAYLOAD)))
    }

    @Test
    fun `decodes a qr image rotated by 90 degrees`() = runBlocking {
        val bitmap = renderQr(PAYLOAD)
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            android.graphics.Matrix().apply { postRotate(90f) },
            true,
        )
        assertEquals(PAYLOAD, decodeQrBitmap(rotated))
    }

    @Test
    fun `blank image has no qr code`() = runBlocking {
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        blank.eraseColor(0xFFFFFFFF.toInt())
        assertNull(decodeQrBitmap(blank))
    }

    /** 生成一张二维码图片（黑码白底），作为解码输入。 */
    private fun renderQr(text: String, size: Int = 480): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val PAYLOAD = "dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788&code=ABC123&n=FY-PC"
    }
}
