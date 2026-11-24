package com.catmoecircle.areyouok.gateway.cert

import javax.security.auth.x500.X500Principal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 证书表 install/remove/snapshot、KeyManager 选择规则、hasIssuer（纯 JVM）。 */
class ClientCertsTest {

    @Before
    fun setUp() {
        ClientCerts.load(InMemoryClientCertStore())
    }

    @After
    fun tearDown() {
        ClientCerts.load(InMemoryClientCertStore())
    }

    @Test
    fun `install remove and snapshot`() {
        val cert = fixtureCertificate()
        assertTrue(ClientCerts.install(cert))
        assertEquals(1, ClientCerts.snapshot().size)
        assertEquals(cert.serial, ClientCerts.snapshot().first().serial)
        assertNotNull(ClientCerts.subjectDn(cert.serial))
        assertTrue(ClientCerts.hasIssuer(cert.issuer))

        // 同一 serial 只保留一张（覆盖导入/续期）。
        ClientCerts.install(cert)
        assertEquals(1, ClientCerts.snapshot().size)

        ClientCerts.remove(cert.serial)
        assertTrue(ClientCerts.snapshot().isEmpty())
        assertNull(ClientCerts.subjectDn(cert.serial))
    }

    @Test
    fun `load restores usable certificates from store`() {
        val cert = fixtureCertificate()
        val store = InMemoryClientCertStore(listOf(cert, cert.copy(serial = "BROKEN", p12 = ByteArray(4))))
        ClientCerts.load(store)
        assertEquals("损坏条目不进内存表", 1, ClientCerts.snapshot().size)
        assertEquals(cert.serial, ClientCerts.snapshot().first().serial)
        assertNotNull(ClientCerts.keyManager().getPrivateKey(cert.serial))
    }

    @Test
    fun `install rejects broken pkcs12`() {
        val broken = ClientCertificate(
            serial = "FF",
            subject = "CN=broken",
            issuer = "CN=broken",
            notAfter = 0L,
            label = "broken",
            p12 = ByteArray(16) { 0x30 },
            password = "wrong",
        )
        assertFalse(ClientCerts.install(broken))
        assertTrue(ClientCerts.snapshot().isEmpty())
    }

    @Test
    fun `hasIssuer matches dn and rejects others`() {
        val cert = fixtureCertificate()
        ClientCerts.install(cert)
        assertTrue(ClientCerts.hasIssuer(cert.issuer))
        assertTrue(ClientCerts.hasIssuer(X500Principal(cert.issuer).name))
        assertFalse(ClientCerts.hasIssuer("CN=Another CA"))
        assertFalse(ClientCerts.hasIssuer(""))
    }

    @Test
    fun `key manager prefers cert issued by server accepted ca`() {
        val caA = X500Principal("CN=CA-A")
        val caB = X500Principal("CN=CA-B")
        val entries = listOf(
            ClientCerts.Entry(alias = "A1", issuerDn = caA.name, subjectDn = "CN=a1"),
            ClientCerts.Entry(alias = "B1", issuerDn = caB.name, subjectDn = "CN=b1"),
        )
        assertEquals("B1", ClientCerts.selectAlias(entries, "RSA", arrayOf(caB)))
        assertEquals("A1", ClientCerts.selectAlias(entries, "RSA", arrayOf(caA)))
        // 两个都被接受时命中第一张（同为 CA 时再按到期时间挑最新）。
        assertEquals("A1", ClientCerts.selectAlias(entries, "RSA", arrayOf(caA, caB)))
    }

    @Test
    fun `key manager prefers freshest when same ca issued several`() {
        val ca = X500Principal("CN=CA-A")
        val entries = listOf(
            ClientCerts.Entry(alias = "OLD", issuerDn = ca.name, subjectDn = "CN=old", notAfter = 1_000L),
            ClientCerts.Entry(alias = "NEW", issuerDn = ca.name, subjectDn = "CN=new", notAfter = 9_000L),
        )
        assertEquals("NEW", ClientCerts.selectAlias(entries, "RSA", arrayOf(ca)))
    }

    @Test
    fun `key manager falls back to the only entry and else returns null`() {
        val single = listOf(ClientCerts.Entry(alias = "ONLY", issuerDn = "CN=Private CA", subjectDn = "CN=only"))
        assertEquals("ONLY", ClientCerts.selectAlias(single, "RSA", arrayOf(X500Principal("CN=Unknown"))))
        assertEquals("ONLY", ClientCerts.selectAlias(single, "RSA", null))

        val two = listOf(
            ClientCerts.Entry(alias = "A", issuerDn = "CN=CA-A", subjectDn = "CN=a"),
            ClientCerts.Entry(alias = "B", issuerDn = "CN=CA-B", subjectDn = "CN=b"),
        )
        assertNull(ClientCerts.selectAlias(two, "RSA", null))
        assertNull(ClientCerts.selectAlias(two, "RSA", arrayOf(X500Principal("CN=CA-C"))))
        assertNull(ClientCerts.selectAlias(emptyList(), "RSA", null))
        // 本机证书恒为 RSA：服务端要 EC 时不要乱发证书。
        assertNull(ClientCerts.selectAlias(two, "EC", arrayOf(X500Principal("CN=CA-A"))))
    }

    @Test
    fun `freshness decides renewal`() {
        val cert = fixtureCertificate()
        ClientCerts.install(cert)
        val renew = 30L * 24 * 60 * 60 * 1000
        assertTrue(ClientCerts.hasFreshCertificate(cert.issuer, cert.notAfter - renew - 1, renew))
        assertFalse("距过期不足 30 天要续期", ClientCerts.hasFreshCertificate(cert.issuer, cert.notAfter - renew + 1, renew))
        assertFalse("已过期要续期", ClientCerts.hasFreshCertificate(cert.issuer, cert.notAfter + 1, renew))
        assertEquals(listOf(cert.serial), ClientCerts.serialsForIssuer(cert.issuer))
        assertTrue(ClientCerts.serialsForIssuer("CN=Another CA").isEmpty())
    }

    private fun fixtureCertificate(): ClientCertificate {
        val bytes = javaClass.getResourceAsStream("/mtls/client-fixture.p12")!!.use { it.readBytes() }
        return ClientCertEnrollment.fromPkcs12(bytes, "dshgw-fixture")
    }
}
