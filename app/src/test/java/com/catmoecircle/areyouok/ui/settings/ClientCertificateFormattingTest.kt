package com.catmoecircle.areyouok.ui.settings

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCertificateFormattingTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun epochMs(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `expiry uses yyyy-mm-dd in the requested zone`() {
        val notAfter = epochMs("2044-01-01T00:00:00Z")
        assertEquals("2044-01-01", formatCertificateExpiry(notAfter, utc))
        assertEquals("2044-01-01", formatCertificateExpiry(notAfter, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `expiry honours the zone across a day boundary`() {
        // UTC 20:00 已经是上海次日 04:00。
        val notAfter = epochMs("2044-01-01T20:00:00Z")
        assertEquals("2044-01-01", formatCertificateExpiry(notAfter, utc))
        assertEquals("2044-01-02", formatCertificateExpiry(notAfter, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `serial keeps the first eight characters and uppercase`() {
        assertEquals("1A2B3C4D", formatCertificateSerial("1a2b3c4d5e6f"))
        assertEquals("1A2B3C4D", formatCertificateSerial(" 0x1a2b3c4d5e6f "))
        assertEquals("ABC", formatCertificateSerial("abc"))
        assertEquals("", formatCertificateSerial("   "))
        assertEquals("", formatCertificateSerial(""))
    }

    @Test
    fun `certificate state classifies expired expiring and valid`() {
        val now = epochMs("2044-01-01T00:00:00Z")
        val day = 24L * 60L * 60L * 1000L
        assertEquals(ClientCertificateState.EXPIRED, certificateState(now - 1, now))
        assertEquals(ClientCertificateState.EXPIRED, certificateState(now, now))
        assertEquals(ClientCertificateState.EXPIRING_SOON, certificateState(now + day, now))
        assertEquals(
            ClientCertificateState.EXPIRING_SOON,
            certificateState(now + CERTIFICATE_EXPIRING_SOON_DAYS * day, now),
        )
        assertEquals(
            ClientCertificateState.VALID,
            certificateState(now + (CERTIFICATE_EXPIRING_SOON_DAYS + 1) * day, now),
        )
    }

    @Test
    fun `label prefers label then CN then null`() {
        assertEquals("Pixel 8", certificateDisplayLabel("Pixel 8", "CN=ignored"))
        assertEquals("Pixel 8", certificateDisplayLabel("  ", "CN=Pixel 8,OU=DSH"))
        assertEquals("Back,up", certificateDisplayLabel("", "CN=Back\\,up,OU=DSH"))
        assertNull(certificateDisplayLabel("", "OU=DSH"))
        assertNull(certificateDisplayLabel("", ""))
    }

    @Test
    fun `common name parsing handles escaped commas and empty values`() {
        assertEquals("Pixel 8", certificateCommonName("CN=Pixel 8"))
        assertEquals("Back,up", certificateCommonName("CN=Back\\,up"))
        assertEquals("A=B", certificateCommonName("CN=A=B"))
        assertNull(certificateCommonName("CN="))
        assertNull(certificateCommonName("O=DSH"))
        assertNull(certificateCommonName(""))
    }

    @Test
    fun `import password requires at least six characters`() {
        assertFalse(isValidCertificatePassword(""))
        assertFalse(isValidCertificatePassword("12345"))
        assertTrue(isValidCertificatePassword("123456"))
        assertTrue(isValidCertificatePassword("dshgw-fixture"))
    }
}
