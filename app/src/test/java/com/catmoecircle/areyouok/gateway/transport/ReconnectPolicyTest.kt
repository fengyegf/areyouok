package com.catmoecircle.areyouok.gateway.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重连退避：0.5→30s，±30% 抖动。 */
class ReconnectPolicyTest {

    @Test
    fun `backoff steps grow then cap at 30s`() {
        assertEquals(500L, ReconnectPolicy.baseDelayMs(1))
        assertEquals(1_000L, ReconnectPolicy.baseDelayMs(2))
        assertEquals(2_000L, ReconnectPolicy.baseDelayMs(3))
        assertEquals(4_000L, ReconnectPolicy.baseDelayMs(4))
        assertEquals(8_000L, ReconnectPolicy.baseDelayMs(5))
        assertEquals(15_000L, ReconnectPolicy.baseDelayMs(6))
        assertEquals(30_000L, ReconnectPolicy.baseDelayMs(7))
        assertEquals(30_000L, ReconnectPolicy.baseDelayMs(20))
    }

    @Test
    fun `jitter stays within 30 percent`() {
        val noJitter = ReconnectPolicy.delayMs(3, jitter = 0.0)
        assertEquals(2_000L, noJitter)
        assertEquals((2_000L * 1.3).toLong(), ReconnectPolicy.delayMs(3, jitter = 1.0))
        assertEquals((2_000L * 0.7).toLong(), ReconnectPolicy.delayMs(3, jitter = -1.0))
    }

    @Test
    fun `heartbeat constants match contract`() {
        assertEquals(15_000L, ReconnectPolicy.PING_INTERVAL_MS)
        assertEquals(45_000L, ReconnectPolicy.IDLE_TIMEOUT_MS)
        assertEquals(30_000L, ReconnectPolicy.STABLE_RESET_MS)
        assertTrue(ReconnectPolicy.IDLE_TIMEOUT_MS > ReconnectPolicy.PING_INTERVAL_MS)
    }

    @Test
    fun `first attempt is never zero`() {
        assertTrue(ReconnectPolicy.delayMs(1, jitter = -1.0) >= 250L)
    }
}
