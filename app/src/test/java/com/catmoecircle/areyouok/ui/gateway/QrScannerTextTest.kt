package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.pairing.PairInviteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 扫码结果的清理与解析（相机部分不参与 JVM 单测）。 */
class QrScannerTextTest {

    @Test
    fun `blank content is rejected`() {
        assertNull(sanitizeScannedText(null))
        assertNull(sanitizeScannedText(""))
        assertNull(sanitizeScannedText("   \n\t  "))
    }

    @Test
    fun `trims whitespace and wrapping quotes`() {
        assertEquals(
            "https://example.test:7789",
            sanitizeScannedText("  \"https://example.test:7789\"\n"),
        )
        assertEquals(
            "dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788",
            sanitizeScannedText("'dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788'"),
        )
    }

    @Test
    fun `scanned lan deep link becomes a connectable invite`() {
        val payload = "dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788&lan=http%3A%2F%2F192.168.1.5%3A7788&code=ABC123&n=FY-PC"
        val invite = PairInviteParser.parse(sanitizeScannedText("  $payload  ").orEmpty())
        requireNotNull(invite)
        assertEquals("http://192.168.1.5:7788", invite.baseUrl)
        assertEquals("http://192.168.1.5:7788", invite.httpUrl)
        assertNull(invite.httpsUrl)
        assertEquals("ABC123", invite.code)
        assertEquals("FY-PC", invite.name)
    }

    @Test
    fun `scanned manual address is accepted`() {
        val invite = PairInviteParser.parse(sanitizeScannedText("192.168.1.7:7788 123456").orEmpty())
        requireNotNull(invite)
        assertEquals("http://192.168.1.7:7788", invite.baseUrl)
        assertEquals("123456", invite.code)
    }
}
