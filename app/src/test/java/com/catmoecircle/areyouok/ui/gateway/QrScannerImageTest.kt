package com.catmoecircle.areyouok.ui.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地图片扫码里的纯 JVM 部分：缩图采样比例。
 *
 * 真正的解码交给 ML Kit（Android 上才有实现），由 `androidTest` 里的
 * [QrScanMlKitTest] 在设备上覆盖。
 */
class QrScannerImageTest {

    @Test
    fun `sample size keeps small images untouched`() {
        assertEquals(1, qrImageSampleSize(1600, 900))
        assertEquals(2, qrImageSampleSize(1080, 1920))
        assertEquals(1, qrImageSampleSize(0, 100))
    }

    @Test
    fun `sample size halves until the long edge fits`() {
        assertEquals(2, qrImageSampleSize(3000, 1000))
        assertEquals(4, qrImageSampleSize(6000, 4000))
        assertEquals(8, qrImageSampleSize(12000, 1000))
    }
}
