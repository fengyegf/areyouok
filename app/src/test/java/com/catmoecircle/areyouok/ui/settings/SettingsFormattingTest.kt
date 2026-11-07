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
    fun `work step display tiers expose increasing detail`() {
        // 简洁：只露「思考过程 / 工具调用」标签，不露出任何摘要。
        assertFalse(WorkStepDisplay.CONCISE.showsRowHint)
        assertFalse(WorkStepDisplay.CONCISE.showsOutputPreview)
        assertTrue(WorkStepDisplay.CONCISE.groupsWorkSteps)

        // 标准：在简洁基础上露出思考开头与工具名，仍折叠成工作步骤分组。
        assertTrue(WorkStepDisplay.STANDARD.showsRowHint)
        assertFalse(WorkStepDisplay.STANDARD.showsOutputPreview)
        assertTrue(WorkStepDisplay.STANDARD.groupsWorkSteps)

        // 详细：再露出工具输出首行（对齐上一版标准），仍折叠成工作步骤分组。
        assertTrue(WorkStepDisplay.DETAILED.showsRowHint)
        assertTrue(WorkStepDisplay.DETAILED.showsOutputPreview)
        assertTrue(WorkStepDisplay.DETAILED.groupsWorkSteps)

        // 完全展开：工作步骤不再是单个分组，而是逐条展示；行内信息仍默认折叠。
        assertTrue(WorkStepDisplay.EXPANDED.showsRowHint)
        assertTrue(WorkStepDisplay.EXPANDED.showsOutputPreview)
        assertFalse(WorkStepDisplay.EXPANDED.groupsWorkSteps)
    }
}
