package com.catmoecircle.areyouok.gateway.pairing

import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TokenStore 内存实现 + PairingManager 的配对流程（不触达 Android Keystore）。 */
class InMemoryTokenStoreTest {

    @Test
    fun `saves reads and clears token per server`() {
        val store = InMemoryTokenStore(deviceIdValue = "dev-1")
        assertNull(store.tokenFor("http://a"))
        store.saveToken("http://a", "tok-a")
        store.saveToken("http://b", "tok-b")
        assertEquals("tok-a", store.tokenFor("http://a"))
        assertEquals("tok-b", store.tokenFor("http://b"))
        store.clearToken("http://a")
        assertNull(store.tokenFor("http://a"))
        assertEquals("tok-b", store.tokenFor("http://b"))
        assertEquals("dev-1", store.deviceId())
    }

    @Test
    fun `pairing manager stores token and returns endpoint`() = runBlocking {
        val api = com.catmoecircle.areyouok.gateway.FakeGatewayApi()
        api.pairResponse = com.catmoecircle.areyouok.gateway.protocol.PairResponse(
            ok = true,
            deviceToken = "device-token",
            expiresAt = null,
        )
        val store = InMemoryTokenStore(deviceIdValue = "dev-42")
        val manager = PairingManager(api, store)

        val result = manager.pair(
            PairInvite(baseUrl = "https://gw.example.com:7789", code = "ABC", name = "PC"),
            deviceName = "Pixel",
        )

        assertEquals("device-token", result.endpoint.token)
        assertEquals("device-token", store.tokenFor("https://gw.example.com:7789"))
        assertEquals("dev-42", result.deviceId)
        assertEquals("device-token", manager.endpointFor("https://gw.example.com:7789").token)
    }

    @Test(expected = com.catmoecircle.areyouok.gateway.transport.GatewayException::class)
    fun `pairing rejects missing code`() {
        runBlocking {
            val manager = PairingManager(
                com.catmoecircle.areyouok.gateway.FakeGatewayApi(),
                InMemoryTokenStore(),
            )
            manager.pair(PairInvite(baseUrl = "http://10.0.0.2:7788"), deviceName = "Pixel")
        }
    }

    @Test
    fun `forget clears stored token`() {
        val store = InMemoryTokenStore()
        store.saveToken("http://10.0.0.2:7788", "tok")
        val manager = PairingManager(com.catmoecircle.areyouok.gateway.FakeGatewayApi(), store)
        manager.forget("http://10.0.0.2:7788/")
        assertNull(store.tokenFor("http://10.0.0.2:7788"))
    }

    @Test
    fun `endpointFor adds no token for lan gateway`() {
        val manager = PairingManager(
            com.catmoecircle.areyouok.gateway.FakeGatewayApi(),
            InMemoryTokenStore(),
        )
        val endpoint = manager.endpointFor("192.168.1.9:7788")
        assertEquals("http://192.168.1.9:7788", endpoint.baseUrl)
        assertTrue(endpoint.token == null)
        assertEquals(GatewayEndpoint("http://192.168.1.9:7788").wsUrl, endpoint.wsUrl)
    }
}
