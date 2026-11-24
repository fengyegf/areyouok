package com.catmoecircle.areyouok.gateway.transport

import com.catmoecircle.areyouok.gateway.protocol.ClientCertAuthority
import com.catmoecircle.areyouok.gateway.protocol.ClientCertCaResponse
import com.catmoecircle.areyouok.gateway.protocol.ClientCertEnrollRequest
import com.catmoecircle.areyouok.gateway.protocol.ClientCertEnrollResponse
import com.catmoecircle.areyouok.gateway.protocol.GatewayJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/v1/pki/enroll`、`/v1/pki/ca` 的 DTO JSON 往返（契约 §5.1）。 */
class ClientCertDtoTest {

    private val json = GatewayJson.json

    @Test
    fun `enroll request round trips and omits null fields`() {
        val request = ClientCertEnrollRequest(
            csr = "-----BEGIN CERTIFICATE REQUEST-----X",
            deviceName = "Pixel 8",
        )
        val text = json.encodeToString(ClientCertEnrollRequest.serializer(), request)
        assertTrue(text.contains("\"csr\""))
        assertTrue(text.contains("\"deviceName\":\"Pixel 8\""))
        assertFalse("空 deviceId 不应写进请求体", text.contains("\"deviceId\""))
        assertEquals(request, json.decodeFromString(ClientCertEnrollRequest.serializer(), text))

        // 续期需要 gateway 按 deviceId 撤销旧证书：带 deviceId 时请求体必须包含它。
        val withDevice = request.copy(deviceId = "dev-uuid")
        val withDeviceText = json.encodeToString(ClientCertEnrollRequest.serializer(), withDevice)
        assertTrue(withDeviceText.contains("\"deviceId\":\"dev-uuid\""))
        assertEquals(withDevice, json.decodeFromString(ClientCertEnrollRequest.serializer(), withDeviceText))
    }

    @Test
    fun `enroll response decodes contract fields and ignores unknown`() {
        val text = """
            {"ok":true,"certificate":"PEM-LEAF","caCertificate":"PEM-CA",
             "caFingerprint":"abcd","caFingerprintPretty":"AB:CD",
             "serial":"1A2B3C","notAfter":2044764400000,"deviceId":"dev-1","name":"Pixel 8",
             "futureField":{"nested":1}}
        """.trimIndent()
        val response = json.decodeFromString(ClientCertEnrollResponse.serializer(), text)
        assertEquals("PEM-LEAF", response.certificate)
        assertEquals("PEM-CA", response.caCertificate)
        assertEquals("abcd", response.caFingerprint)
        assertEquals("1A2B3C", response.serial)
        assertEquals(2044764400000L, response.notAfter)
        assertEquals("dev-1", response.deviceId)
        assertEquals("Pixel 8", response.name)
        assertTrue(response.ok)
    }

    @Test
    fun `enroll response round trips`() {
        val response = ClientCertEnrollResponse(
            certificate = "leaf",
            caCertificate = "ca",
            caFingerprint = "ff",
            serial = "AA",
            notAfter = 1_700_000_000_000L,
        )
        val text = json.encodeToString(ClientCertEnrollResponse.serializer(), response)
        assertEquals(response, json.decodeFromString(ClientCertEnrollResponse.serializer(), text))
        assertNull(json.decodeFromString(ClientCertEnrollResponse.serializer(), text).deviceId)
    }

    @Test
    fun `ca response round trips`() {
        val response = ClientCertCaResponse(
            ok = true,
            ca = ClientCertAuthority(certificate = "ca-pem", fingerprint = "ab", fingerprintPretty = "AB"),
        )
        val text = json.encodeToString(ClientCertCaResponse.serializer(), response)
        assertEquals(response, json.decodeFromString(ClientCertCaResponse.serializer(), text))
    }
}
