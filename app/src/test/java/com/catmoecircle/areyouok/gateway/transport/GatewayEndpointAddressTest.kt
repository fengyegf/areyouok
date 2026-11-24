package com.catmoecircle.areyouok.gateway.transport

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GatewayEndpoint.virtualAddressHint] 的纯 JVM 单测。
 *
 * 覆盖线上事故里的 Clash/Mihomo TUN 地址（`198.18.0.0/15`），以及绝不能误伤的
 * 局域网、Tailscale 与公网地址。
 */
class GatewayEndpointAddressTest {

    @Test
    fun `flags clash tun and other unreachable virtual addresses`() {
        val hits = listOf(
            "198.18.0.1",
            "198.18.255.254",
            "198.19.0.1",
            "https://198.18.0.1:7789",
            "240.0.0.1",
            "0.0.0.0",
            "127.0.0.1",
            "https://[::1]:7789",
            "https://[fe80::1]:7789",
        )
        for (url in hits) {
            val hint = GatewayEndpoint.virtualAddressHint(url)
            assertNotNull("应判为虚拟地址：$url", hint)
            assertTrue("要说明手机连不上：$hint", hint!!.contains("手机连不上"))
            assertTrue("要给出可操作建议：$hint", hint.contains("局域网地址"))
        }
    }

    @Test
    fun `hint names the offending address`() {
        // IPv6 的方括号会被剥掉，提示里给的是不带括号的地址。
        val tun = requireNotNull(GatewayEndpoint.virtualAddressHint("https://198.18.0.1:7789"))
        assertTrue(tun, tun.contains("198.18.0.1"))
        val linkLocal = requireNotNull(GatewayEndpoint.virtualAddressHint("https://[fe80::1]:7789"))
        assertTrue(linkLocal, linkLocal.contains("fe80::1"))
    }

    @Test
    fun `keeps lan tailscale and public addresses`() {
        val misses = listOf(
            "192.168.1.104",
            "10.0.0.9",
            "172.29.80.1",
            "100.111.72.120",
            "https://[2409:8a62:5053:2e20:9060:3bf6:6ea0:b4a5]:7789",
            "8.8.8.8",
            "gw.example.com",
            "",
        )
        for (url in misses) {
            assertNull("不应判为虚拟地址：$url", GatewayEndpoint.virtualAddressHint(url))
        }
    }
}
