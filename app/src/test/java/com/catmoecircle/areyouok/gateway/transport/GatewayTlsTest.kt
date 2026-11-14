package com.catmoecircle.areyouok.gateway.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 证书固定表的纯 JVM 单测（不依赖 Android Keystore/网络）。 */
class GatewayTlsTest {

    @Test
    fun `normalizes sha256 fingerprints in every shape the QR may use`() {
        val hex = (1..32).joinToString("") { "%02x".format(it) }
        val grouped = hex.uppercase().chunked(2).joinToString(":")
        assertEquals(hex, GatewayTls.normalizeFingerprint(hex))
        assertEquals(hex, GatewayTls.normalizeFingerprint(grouped))
        assertEquals(hex, GatewayTls.normalizeFingerprint("sha256/" + hex))
        assertNull(GatewayTls.normalizeFingerprint("sha256/abc"))
        assertNull(GatewayTls.normalizeFingerprint(""))
    }

    @Test
    fun `fingerprint is the sha256 of the certificate bytes`() {
        val der = ByteArray(32) { it.toByte() }
        val fingerprint = GatewayTls.fingerprintOf(der)
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(fingerprint, GatewayTls.fingerprintOf(der.copyOf()))
    }

    @Test
    fun `decodes base64url der with or without padding`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), GatewayTls.decodeCertificate("AQID"))
        // 2 bytes -> 3 base64 chars, no padding; the decoder must tolerate it.
        assertArrayEquals(byteArrayOf(1, 2), GatewayTls.decodeCertificate("AQI"))
        assertNull(GatewayTls.decodeCertificate(""))
        assertNull(GatewayTls.decodeCertificate("!!!!"))
    }

    @Test
    fun `pins are per host, persistable and clearable`() {
        GatewayPins.clearAll()
        val der = ByteArray(48) { (it * 3).toByte() }
        val fingerprint = GatewayTls.fingerprintOf(der)
        val pin = GatewayPins.install("GW.Example.com", der, null)
        requireNotNull(pin)
        assertEquals(fingerprint, pin.fingerprint)
        assertEquals(fingerprint, GatewayPins.fingerprintOf("gw.example.com"))

        val restored = GatewayPins.decode(GatewayPins.encode(pin))
        requireNotNull(restored)
        assertEquals(fingerprint, restored.fingerprint)
        assertArrayEquals(der, restored.der)

        assertNull(GatewayPins.lookup("other.example.com"))
        GatewayPins.clear("gw.example.com")
        assertNull(GatewayPins.lookup("gw.example.com"))
    }
}
