package com.catmoecircle.areyouok.gateway.cert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关导出的 PKCS#12 fixture：JDK KeyStore 能打开、私钥与两条链都在（A7）。
 *
 * fixture：`resources/mtls/client-fixture.p12`，用与网关完全相同的 node-forge 参数生成
 * （PBE 3DES），密码 `dshgw-fixture`；叶子 CN=fixture-device、serial 2A1B3C，
 * 签发者 CN=DSH Gateway Test CA。改网关导出格式时要同步重生成这个文件。
 */
class Pkcs12ImportTest {

    @Test
    fun `opens fixture pkcs12 with password and reads key and chain`() {
        val opened = Pkcs12Tools.open(fixtureBytes(), FIXTURE_PASSWORD)
        assertNotNull(opened.key)
        assertEquals("叶子 + CA 两条链", 2, opened.chain.size)
        val leaf = opened.chain.first()
        assertEquals("fixture-device", Pkcs12Tools.cnOf(leaf.subjectX500Principal))
        assertEquals("2A1B3C", Pkcs12Tools.serialHex(leaf))
        assertTrue(Pkcs12Tools.cnOf(leaf.issuerX500Principal).contains("DSH Gateway Test CA"))
        assertTrue(leaf.notAfter.time > System.currentTimeMillis())
    }

    @Test
    fun `wrong password throws chinese error`() {
        val error = runCatching { Pkcs12Tools.open(fixtureBytes(), "wrong-password") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue((error as IllegalArgumentException).message.orEmpty().contains("密码错误"))
    }

    @Test
    fun `rebuilds pkcs12 with a fresh random password`() {
        val opened = Pkcs12Tools.open(fixtureBytes(), FIXTURE_PASSWORD)
        val password = Pkcs12Tools.randomPassword()
        assertTrue(password.length >= 32)
        val rebuilt = Pkcs12Tools.build(opened.key, opened.chain, password)
        val reopened = Pkcs12Tools.open(rebuilt, password)
        assertNotNull(reopened.key)
        assertEquals(opened.chain.size, reopened.chain.size)
        assertEquals("2A1B3C", Pkcs12Tools.serialHex(reopened.chain.first()))
        // 旧密码打不开新 p12（自动领证后的本机密码是随机的）。
        assertTrue(runCatching { Pkcs12Tools.open(rebuilt, FIXTURE_PASSWORD) }.isFailure)
    }

    @Test
    fun `builds client certificate for import`() {
        val cert = ClientCertEnrollment.fromPkcs12(fixtureBytes(), FIXTURE_PASSWORD)
        assertEquals("2A1B3C", cert.serial)
        assertEquals("fixture-device", cert.label)
        assertTrue(cert.issuer.contains("DSH Gateway Test CA"))
        assertTrue(cert.subject.contains("fixture-device"))
        assertTrue(cert.p12.contentEquals(fixtureBytes()))
        assertTrue(cert.notAfter > System.currentTimeMillis())
        assertTrue(ClientCerts.install(cert))
        ClientCerts.remove(cert.serial)
    }

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/mtls/client-fixture.p12")?.use { it.readBytes() }
            ?: error("缺少 fixture: mtls/client-fixture.p12")

    companion object {
        private const val FIXTURE_PASSWORD = "dshgw-fixture"
    }
}
