package com.catmoecircle.areyouok.gateway.cert

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pkcs10 的最小 DER 编码 + CSR（纯 JVM）：
 * 长度短/长格式、OID 编码、PEM 头尾、SHA256withRSA 签名校验。
 */
class Pkcs10Test {

    @Test
    fun `der length uses short form below 128`() {
        assertArrayEquals(byteArrayOf(0x00), Pkcs10.derLength(0))
        assertArrayEquals(byteArrayOf(0x7F), Pkcs10.derLength(127))
    }

    @Test
    fun `der length uses long form from 128`() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x80.toByte()), Pkcs10.derLength(128))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x01, 0x00), Pkcs10.derLength(256))
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            Pkcs10.derLength(65535),
        )
        assertArrayEquals(
            byteArrayOf(0x83.toByte(), 0x01, 0x00, 0x00),
            Pkcs10.derLength(65536),
        )
    }

    @Test
    fun `oid encoding matches rsa sha256 and common name`() {
        assertArrayEquals(
            byteArrayOf(
                0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
                0x0D, 0x01, 0x01, 0x0B,
            ),
            Pkcs10.derOid(Pkcs10.OID_SHA256_WITH_RSA),
        )
        assertArrayEquals(
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
            Pkcs10.derOid(Pkcs10.OID_COMMON_NAME),
        )
    }

    @Test
    fun `csr has pem header footer and decodable body`() {
        val keyPair = rsaKeyPair()
        val pem = Pkcs10.createCsr(keyPair, "device-1")
        val lines = pem.trim().lines()
        assertEquals("-----BEGIN CERTIFICATE REQUEST-----", lines.first())
        assertEquals("-----END CERTIFICATE REQUEST-----", lines.last())
        assertTrue(lines.size > 3)
        val body = lines.subList(1, lines.size - 1).joinToString("")
        val der = Base64.getDecoder().decode(body)
        assertTrue(der.isNotEmpty())
        // DER 顶层必须是 SEQUENCE。
        assertEquals(0x30, der[0].toInt() and 0xff)
    }

    @Test
    fun `csr signature verifies with sha256withrsa`() {
        val keyPair = rsaKeyPair()
        val commonName = "device-abc"
        val der = Pkcs10.createCsrDer(keyPair, commonName)
        val outer = readTlv(der, 0)
        assertEquals(0x30, outer.tag)

        val info = readTlv(der, outer.contentStart)
        val algorithm = readTlv(der, info.end)
        val signature = readTlv(der, algorithm.end)
        assertEquals(0x30, info.tag)
        assertEquals(0x30, algorithm.tag)
        assertEquals(0x03, signature.tag)
        assertEquals(outer.end, signature.end)

        // 签名覆盖的字节必须与 internal 的 certificationRequestInfo 完全一致（可测性契约）。
        val infoBytes = der.copyOfRange(info.start, info.end)
        assertArrayEquals(Pkcs10.certificationRequestInfo(keyPair.public, commonName), infoBytes)

        val signatureContent = signature.content(der)
        val signatureBytes = signatureContent.copyOfRange(1, signatureContent.size)
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(infoBytes)
        }
        assertTrue("CSR 签名应能被公钥验证", verifier.verify(signatureBytes))
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private class Tlv(val tag: Int, val start: Int, val contentStart: Int, val end: Int) {
        fun content(bytes: ByteArray): ByteArray = bytes.copyOfRange(contentStart, end)
    }

    private fun readTlv(bytes: ByteArray, offset: Int): Tlv {
        val tag = bytes[offset].toInt() and 0xff
        val first = bytes[offset + 1].toInt() and 0xff
        var header = 2
        var length = first
        if (first and 0x80 != 0) {
            val count = first and 0x7f
            length = 0
            for (index in 0 until count) {
                length = (length shl 8) or (bytes[offset + 2 + index].toInt() and 0xff)
            }
            header += count
        }
        val contentStart = offset + header
        return Tlv(tag, offset, contentStart, contentStart + length)
    }
}
