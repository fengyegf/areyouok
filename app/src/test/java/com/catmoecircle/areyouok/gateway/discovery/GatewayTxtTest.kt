package com.catmoecircle.areyouok.gateway.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** mDNS TXT 记录契约：`v=1,name=...,lan=7788,pair=7789,api=/v1`。 */
class GatewayTxtTest {

    private fun attributes(vararg pairs: Pair<String, String>): Map<String, ByteArray> =
        pairs.associate { (key, value) -> key to value.toByteArray(Charsets.UTF_8) }

    @Test
    fun `parses full txt record`() {
        val gateway = GatewayTxt.parse(
            serviceName = "_dshgw._tcp.local.",
            attributes = attributes(
                "v" to "1",
                "name" to "FY-PC",
                "lan" to "7788",
                "pair" to "7789",
                "api" to "/v1",
            ),
            host = "192.168.1.5",
        )
        requireNotNull(gateway)
        assertEquals("FY-PC", gateway.name)
        assertEquals("http://192.168.1.5:7788", gateway.baseUrl)
        assertEquals(7789, gateway.pairPort)
        assertEquals(1, gateway.version)
    }

    @Test
    fun `missing lan port is rejected`() {
        assertNull(
            GatewayTxt.parse(
                serviceName = "svc",
                attributes = attributes("v" to "1", "name" to "PC"),
                host = "10.0.0.2",
            ),
        )
    }

    @Test
    fun `future protocol version is ignored`() {
        assertNull(
            GatewayTxt.parse(
                serviceName = "svc",
                attributes = attributes("v" to "2", "lan" to "7788"),
                host = "10.0.0.2",
            ),
        )
    }

    @Test
    fun `ipv6 host is bracketed`() {
        val gateway = GatewayTxt.parse(
            serviceName = "svc",
            attributes = attributes("lan" to "7788"),
            host = "fd00::1",
        )
        requireNotNull(gateway)
        assertEquals("http://[fd00::1]:7788", gateway.baseUrl)
        // 没有 name TXT 时退回服务名
        assertEquals("svc", gateway.name)
    }

    @Test
    fun `endpoint carries token and name`() {
        val gateway = DiscoveredGateway(
            serviceName = "svc",
            name = "书房 PC",
            host = "192.168.1.5",
            lanPort = 7788,
        )
        val endpoint = gateway.toEndpoint(token = "tok")
        requireNotNull(endpoint)
        assertEquals("http://192.168.1.5:7788", endpoint.baseUrl)
        assertEquals("tok", endpoint.token)
        assertEquals("书房 PC", endpoint.name)
    }
}
