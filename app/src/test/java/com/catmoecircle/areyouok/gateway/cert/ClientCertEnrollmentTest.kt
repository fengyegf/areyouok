package com.catmoecircle.areyouok.gateway.cert

import com.catmoecircle.areyouok.gateway.FakeGatewayApi
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动领证的纯 JVM 链路（fake 网关返回仓库 fixture 的叶子/CA）：
 * CSR 生成 → enroll 调用 → 组 PKCS#12 → [ClientCerts.install]，以及旧网关静默跳过。
 */
class ClientCertEnrollmentTest {

    @Before
    fun setUp() {
        ClientCerts.load(InMemoryClientCertStore())
    }

    @After
    fun tearDown() {
        ClientCerts.load(InMemoryClientCertStore())
    }

    @Test
    fun `enroll installs certificate returned by gateway`() = runBlocking {
        val opened = Pkcs12Tools.open(fixtureBytes(), FIXTURE_PASSWORD)
        val leaf = opened.chain.first()
        val api = FakeGatewayApi().apply {
            clientEnrollResponse = com.catmoecircle.areyouok.gateway.protocol.ClientCertEnrollResponse(
                certificate = pem(leaf),
                caCertificate = pem(opened.chain[1]),
                caFingerprint = "00",
                serial = Pkcs12Tools.serialHex(leaf),
                notAfter = leaf.notAfter.time,
                deviceId = "device-x",
            )
        }

        val certificate = ClientCertEnrollment.enroll(api, deviceId = "device-x", deviceName = "Pixel 8")

        assertNotNull(certificate)
        assertEquals("2A1B3C", certificate!!.serial)
        assertEquals("fixture-device", certificate.label)
        assertTrue(certificate.issuer.contains("DSH Gateway Test CA"))
        assertEquals(listOf(certificate.serial), ClientCerts.snapshot().map { it.serial })
        assertNotNull(ClientCerts.keyManager().getPrivateKey(certificate.serial))

        val request = api.enrollRequests.single()
        assertTrue("CSR 必须是 PEM 证书请求", request.first.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        assertEquals("Pixel 8", request.second)
        assertEquals("续期要带 deviceId", "device-x", request.third)
    }

    @Test
    fun `enroll returns null when gateway has no pki endpoint`() = runBlocking {
        val api = FakeGatewayApi()
        assertNull(ClientCertEnrollment.enroll(api, deviceId = "device-x", deviceName = null))
        assertTrue(ClientCerts.snapshot().isEmpty())
    }

    private fun pem(cert: X509Certificate): String {
        val body = Base64.getEncoder().encodeToString(cert.encoded).chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n"
    }

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/mtls/client-fixture.p12")?.use { it.readBytes() }
            ?: error("缺少 fixture: mtls/client-fixture.p12")

    companion object {
        private const val FIXTURE_PASSWORD = "dshgw-fixture"
    }
}
