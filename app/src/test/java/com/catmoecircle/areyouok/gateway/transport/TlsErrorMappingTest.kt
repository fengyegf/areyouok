package com.catmoecircle.areyouok.gateway.transport

import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公网 mTLS 的 SSLException → 中文提示（契约 §7.1）。 */
class TlsErrorMappingTest {

    @Test
    fun `missing client certificate asks to enroll on lan`() {
        val message = SSLException("Received fatal alert: certificate_required").toUserMessage()
        assertTrue(message.contains("要求客户端证书"))
        assertTrue(message.contains("局域网"))
    }

    @Test
    fun `alert 116 is treated as certificate required`() {
        assertTrue(
            SSLException("javax.net.ssl.SSLHandshakeException: alert: 116")
                .toUserMessage()
                .contains("要求客户端证书"),
        )
    }

    @Test
    fun `bad certificate suggests re enroll`() {
        val texts = listOf(
            "Received fatal alert: bad_certificate",
            "Received fatal alert: certificate_revoked",
            "Received fatal alert: certificate_expired",
            "Received fatal alert: unknown_ca",
            "alert: 46",
        )
        for (text in texts) {
            assertTrue(text, SSLException(text).toUserMessage().contains("失效或被撤销"))
        }
    }

    @Test
    fun `cause chain details are honored`() {
        val wrapped = SSLHandshakeException("handshake failed").apply {
            initCause(SSLException("Received fatal alert: certificate_required"))
        }
        assertTrue(wrapped.toUserMessage().contains("要求客户端证书"))
    }

    @Test
    fun `pinning failure keeps the old message`() {
        assertEquals("TLS 握手失败（证书指纹不匹配？）", SSLException("handshake failure").toUserMessage())
    }
}
