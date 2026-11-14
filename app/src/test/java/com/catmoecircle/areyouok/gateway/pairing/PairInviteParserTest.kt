package com.catmoecircle.areyouok.gateway.pairing

import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `dshgw://pair` 深链与手动输入解析。 */
class PairInviteParserTest {

    @Test
    fun `parses deep link with encoded url code fp and name`() {
        val invite = PairInviteParser.parse(
            "dshgw://pair?url=https%3A%2F%2Fgw.example.com%3A7789&code=ABC123&fp=sha256%2Fabc&n=FY-PC",
        )
        requireNotNull(invite)
        assertEquals("https://gw.example.com:7789", invite.baseUrl)
        assertEquals("ABC123", invite.code)
        assertEquals("sha256/abc", invite.fingerprint)
        assertEquals("FY-PC", invite.name)
        assertTrue(!invite.isLan)
    }

    @Test
    fun `parses lan deep link without code`() {
        val invite = PairInviteParser.parse("dshgw://pair?url=http://192.168.1.5:7788&n=书房 PC")
        requireNotNull(invite)
        assertEquals("http://192.168.1.5:7788", invite.baseUrl)
        assertTrue(invite.isLan)
        assertTrue(invite.needsCode)
    }

    @Test
    fun `prefers lan plain http over public https`() {
        val invite = PairInviteParser.parse(
            "dshgw://pair?url=https%3A%2F%2F%5B2408%3A1%5D%3A7789&lan=http%3A%2F%2F192.168.1.5%3A7788&pub=https%3A%2F%2F%5B2408%3A1%5D%3A7789&code=ABC123&fp=abc&n=FY-PC",
        )
        requireNotNull(invite)
        assertEquals("http://192.168.1.5:7788", invite.baseUrl)
        assertEquals("http://192.168.1.5:7788", invite.lanUrl)
        assertEquals("https://[2408:1]:7789", invite.publicUrl)
        assertEquals("http://192.168.1.5:7788", invite.httpUrl)
        assertEquals("https://[2408:1]:7789", invite.httpsUrl)
        assertTrue(invite.isLan)
        assertEquals("ABC123", invite.code)
    }

    @Test
    fun `lan only deep link has no certificate requirement`() {
        val invite = PairInviteParser.parse("dshgw://pair?lan=http%3A%2F%2F10.0.0.9%3A7788&n=PC")
        requireNotNull(invite)
        assertEquals("http://10.0.0.9:7788", invite.baseUrl)
        assertTrue(invite.isLan)
        assertTrue(invite.needsCode)
        assertNull(invite.publicUrl)
        assertNull(invite.httpsUrl)
    }

    @Test
    fun `build emits lan and pub parameters`() {
        val link = PairInviteParser.build(
            baseUrl = "http://192.168.1.5:7788",
            code = "XY-9",
            name = "FY-PC",
            lanUrl = "http://192.168.1.5:7788",
            publicUrl = "https://[2408:1]:7789",
        )
        assertTrue(link.contains("lan=http%3A%2F%2F192.168.1.5%3A7788"))
        assertTrue(link.contains("pub=https%3A%2F%2F%5B2408%3A1%5D%3A7789"))
        val parsed = PairInviteParser.parse(link)
        requireNotNull(parsed)
        assertEquals("http://192.168.1.5:7788", parsed.baseUrl)
        assertEquals("https://[2408:1]:7789", parsed.publicUrl)
    }

    @Test
    fun `rejects wrong scheme or host`() {
        assertNull(PairInviteParser.parseDeepLink("https://pair?url=http://x:1"))
        assertNull(PairInviteParser.parseDeepLink("dshgw://other?url=http://x:1"))
        assertNull(PairInviteParser.parseDeepLink("dshgw://pair?code=ABC"))
    }

    @Test
    fun `manual url normalizes missing scheme and v1 suffix`() {
        assertEquals("http://192.168.1.7:7788", GatewayEndpoint.normalizeBaseUrl("192.168.1.7:7788"))
        assertEquals("http://192.168.1.7:7788", GatewayEndpoint.normalizeBaseUrl("http://192.168.1.7:7788/"))
        assertEquals("http://192.168.1.7:7788", GatewayEndpoint.normalizeBaseUrl("http://192.168.1.7:7788/v1"))
        assertEquals("https://gw.example.com", GatewayEndpoint.normalizeBaseUrl("https://gw.example.com/v1/stream"))
        assertNull(GatewayEndpoint.normalizeBaseUrl("   "))
    }

    @Test
    fun `manual text accepts url and code separated by whitespace`() {
        val invite = PairInviteParser.parseManual("http://192.168.1.7:7788 123456")
        requireNotNull(invite)
        assertEquals("http://192.168.1.7:7788", invite.baseUrl)
        assertEquals("123456", invite.code)
    }

    @Test
    fun `manual text with only url has empty code`() {
        val invite = PairInviteParser.parseManual("http://192.168.1.7:7788")
        requireNotNull(invite)
        assertTrue(invite.needsCode)
    }

    @Test
    fun `build then parse round trip`() {
        val link = PairInviteParser.build(
            baseUrl = "https://gw.example.com:7789",
            code = "XY-9",
            fingerprint = "sha256/pin",
            name = "FY-PC",
        )
        val parsed = PairInviteParser.parse(link)
        requireNotNull(parsed)
        assertEquals("https://gw.example.com:7789", parsed.baseUrl)
        assertEquals("XY-9", parsed.code)
        assertEquals("sha256/pin", parsed.fingerprint)
        assertEquals("FY-PC", parsed.name)
    }

    @Test
    fun `toEndpoint keeps base url and token`() {
        val invite = PairInvite(baseUrl = "http://10.0.0.2:7788", name = "PC")
        val endpoint = invite.toEndpoint(token = "tok")
        assertEquals("http://10.0.0.2:7788/v1", endpoint.baseUrl + "/v1")
        assertEquals("ws://10.0.0.2:7788/v1/stream", endpoint.wsUrl)
        assertEquals("tok", endpoint.token)
    }

    @Test
    fun `https endpoint uses wss`() {
        val endpoint = GatewayEndpoint(baseUrl = "https://gw.example.com:7789")
        assertEquals("wss://gw.example.com:7789/v1/stream", endpoint.wsUrl)
    }

    @Test
    fun `public mode link prefers https and carries the certificate`() {
        val invite = PairInviteParser.parse(
            "dshgw://pair?mode=pub&lan=http%3A%2F%2F192.168.1.5%3A7788" +
                "&pub=https%3A%2F%2F%5B2408%3A1%5D%3A7789&code=ABC123" +
                "&fp=sha256%2Fabc&cert=AQIDBAU&n=FY-PC",
        )
        requireNotNull(invite)
        assertEquals("https://[2408:1]:7789", invite.baseUrl)
        assertTrue(invite.preferPublic)
        assertEquals("http://192.168.1.5:7788", invite.lanUrl)
        assertEquals("https://[2408:1]:7789", invite.publicUrl)
        assertEquals("AQIDBAU", invite.certificateBase64)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), invite.certificateDer())
        assertTrue(!invite.isLan)
    }

    @Test
    fun `legacy link without mode still prefers the lan edge`() {
        val invite = PairInviteParser.parse(
            "dshgw://pair?lan=http%3A%2F%2F192.168.1.5%3A7788&pub=https%3A%2F%2F%5B2408%3A1%5D%3A7789",
        )
        requireNotNull(invite)
        assertEquals("http://192.168.1.5:7788", invite.baseUrl)
        assertTrue(!invite.preferPublic)
        assertNull(invite.certificateBase64)
        assertNull(invite.certificateDer())
    }

    @Test
    fun `build emits public mode and certificate then round trips`() {
        val link = PairInviteParser.build(
            baseUrl = "https://[2408:1]:7789",
            code = "XY-9",
            fingerprint = "abc",
            name = "FY-PC",
            lanUrl = "http://192.168.1.5:7788",
            publicUrl = "https://[2408:1]:7789",
            preferPublic = true,
            certificate = "AQID",
        )
        assertTrue(link.contains("mode=pub"))
        assertTrue(link.contains("cert=AQID"))
        val parsed = PairInviteParser.parse(link)
        requireNotNull(parsed)
        assertEquals("https://[2408:1]:7789", parsed.baseUrl)
        assertTrue(parsed.preferPublic)
        assertEquals("AQID", parsed.certificateBase64)
    }

    @Test
    fun `malformed certificate is ignored instead of breaking the invite`() {
        val invite = PairInviteParser.parse("dshgw://pair?pub=https%3A%2F%2Fgw%3A7789&cert=!!!!")
        requireNotNull(invite)
        assertEquals("!!!!", invite.certificateBase64)
        assertNull(invite.certificateDer())
    }
}
