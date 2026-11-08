package com.catmoecircle.areyouok.ui.settings

import com.catmoecircle.areyouok.gateway.GatewaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormattingTest {

    @Test
    fun `device display name prefers saved name`() {
        val device = GatewaySettings.RecentGateway("http://192.168.1.104:7788", "书房 PC")
        assertEquals("书房 PC", deviceDisplayName(device))
    }

    @Test
    fun `device display name falls back to host`() {
        val device = GatewaySettings.RecentGateway("http://192.168.1.104:7788", null)
        assertEquals("192.168.1.104", deviceDisplayName(device))
    }

    @Test
    fun `device display name keeps raw url when host is unavailable`() {
        val device = GatewaySettings.RecentGateway("not a url", "  ")
        assertEquals("not a url", deviceDisplayName(device))
    }

    @Test
    fun `gateway address label strips scheme and trailing slash`() {
        assertEquals("192.168.1.104:7788", gatewayAddressLabel("http://192.168.1.104:7788/"))
        assertEquals("gateway.example.com:7789", gatewayAddressLabel("https://gateway.example.com:7789"))
    }

    @Test
    fun `work step display storage round trip and fallback`() {
        assertEquals(WorkStepDisplay.STANDARD, WorkStepDisplay.DEFAULT)
        assertEquals(WorkStepDisplay.STANDARD, WorkStepDisplay.fromStorage(null))
        assertEquals(WorkStepDisplay.STANDARD, WorkStepDisplay.fromStorage("unknown-value"))
        WorkStepDisplay.entries.forEach { level ->
            assertEquals(level, WorkStepDisplay.fromStorage(level.name))
        }
    }

    @Test
    fun `work step collapse timing storage round trip and fallback`() {
        assertEquals(WorkStepCollapseTiming.COMPLETION, WorkStepCollapseTiming.DEFAULT)
        assertEquals(WorkStepCollapseTiming.COMPLETION, WorkStepCollapseTiming.fromStorage(null))
        assertEquals(
            WorkStepCollapseTiming.COMPLETION,
            WorkStepCollapseTiming.fromStorage("unknown-value"),
        )
        WorkStepCollapseTiming.entries.forEach { timing ->
            assertEquals(timing, WorkStepCollapseTiming.fromStorage(timing.name))
        }
    }

    @Test
    fun `folded replies storage round trip and fallback`() {
        assertEquals(WorkStepFoldedReplies.ALL, WorkStepFoldedReplies.DEFAULT)
        assertEquals(WorkStepFoldedReplies.ALL, WorkStepFoldedReplies.fromStorage(null))
        assertEquals(WorkStepFoldedReplies.ALL, WorkStepFoldedReplies.fromStorage("unknown-value"))
        WorkStepFoldedReplies.entries.forEach { replies ->
            assertEquals(replies, WorkStepFoldedReplies.fromStorage(replies.name))
        }
    }

    @Test
    fun `work step display tiers follow the desktop client policies`() {
        // 简洁：运行中也收起，标题只有活动名，不带明细。
        assertTrue(WorkStepDisplay.CONCISE.groupsWorkSteps)
        assertTrue(WorkStepDisplay.CONCISE.groupsWhileRunning)
        assertFalse(WorkStepDisplay.CONCISE.showsLiveDetail)
        assertFalse(WorkStepDisplay.CONCISE.showsRowHint)

        // 标准：运行中也收起，标题带一行明细（工具名 / 命令 / 路径）。
        assertTrue(WorkStepDisplay.STANDARD.groupsWorkSteps)
        assertTrue(WorkStepDisplay.STANDARD.groupsWhileRunning)
        assertTrue(WorkStepDisplay.STANDARD.showsLiveDetail)
        assertTrue(WorkStepDisplay.STANDARD.showsRowHint)

        // 详细：运行中逐条实时展示，结束后再折叠。
        assertTrue(WorkStepDisplay.DETAILED.groupsWorkSteps)
        assertFalse(WorkStepDisplay.DETAILED.groupsWhileRunning)
        assertTrue(WorkStepDisplay.DETAILED.showsLiveDetail)

        // 完全展开：不分组，逐条展示；行内信息仍默认折叠。
        assertFalse(WorkStepDisplay.EXPANDED.groupsWorkSteps)
        assertFalse(WorkStepDisplay.EXPANDED.groupsWhileRunning)
        assertFalse(WorkStepDisplay.EXPANDED.showsLiveDetail)
        assertTrue(WorkStepDisplay.EXPANDED.showsRowHint)
    }
}
