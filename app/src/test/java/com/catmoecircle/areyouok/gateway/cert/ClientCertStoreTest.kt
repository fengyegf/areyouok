package com.catmoecircle.areyouok.gateway.cert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 证书表 JSON 编解码与内存存储（真机实现用 Android Keystore，JVM 测纯函数）。 */
class ClientCertStoreTest {

    @Test
    fun `codec round trips all fields`() {
        val cert = cert("1A2B3C")
        val encoded = ClientCertCodec.encode(listOf(cert))
        val decoded = ClientCertCodec.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(cert, decoded.first())
    }

    @Test
    fun `codec self heals corrupt or empty data`() {
        assertTrue(ClientCertCodec.decode(null).isEmpty())
        assertTrue(ClientCertCodec.decode("").isEmpty())
        assertTrue(ClientCertCodec.decode("not json").isEmpty())
        assertTrue(ClientCertCodec.decode("[{\"serial\":\"\"}]").isEmpty())
        assertTrue(ClientCertCodec.decode("[{\"serial\":\"AA\",\"p12\":\"!!!\"}]").isEmpty())
    }

    @Test
    fun `codec skips broken entries but keeps good ones`() {
        val good = cert("AA")
        val text = ClientCertCodec.encode(listOf(good)).removeSuffix("]") + ",{\"serial\":\"BB\"}]"
        val decoded = ClientCertCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("AA", decoded.first().serial)
    }

    @Test
    fun `in memory store saves replaces removes and clears`() {
        val store = InMemoryClientCertStore()
        assertTrue(store.list().isEmpty())
        val first = cert("AA")
        store.save(first)
        store.save(cert("BB"))
        assertEquals(listOf("AA", "BB"), store.list().map { it.serial })
        store.save(first.copy(label = "updated"))
        assertEquals("updated", store.list().first { it.serial == "AA" }.label)
        store.remove("AA")
        assertEquals(listOf("BB"), store.list().map { it.serial })
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    private fun cert(serial: String) = ClientCertificate(
        serial = serial,
        subject = "CN=dev-" + serial,
        issuer = "CN=DSH Gateway Test CA",
        notAfter = 2_000_000_000_000L,
        label = "dev-" + serial,
        p12 = ByteArray(8) { it.toByte() },
        password = "p@ss",
    )
}
