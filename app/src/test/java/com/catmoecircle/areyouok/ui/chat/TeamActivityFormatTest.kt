package com.catmoecircle.areyouok.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 团队活动面板的时长文案（纯函数，不依赖 Compose）。 */
class TeamActivityFormatTest {

    @Test
    fun `short durations use seconds`() {
        assertEquals("0s", TeamActivityFormat.formatDuration(0))
        assertEquals("12s", TeamActivityFormat.formatDuration(12_400))
        assertEquals("59s", TeamActivityFormat.formatDuration(59_999))
    }

    @Test
    fun `durations under an hour use mm ss`() {
        assertEquals("01:00", TeamActivityFormat.formatDuration(60_000))
        assertEquals("01:23", TeamActivityFormat.formatDuration(83_000))
        assertEquals("59:59", TeamActivityFormat.formatDuration(3_599_000))
    }

    @Test
    fun `durations over an hour use h mm ss`() {
        assertEquals("1:00:00", TeamActivityFormat.formatDuration(3_600_000))
        assertEquals("1:02:03", TeamActivityFormat.formatDuration(3_723_000))
        assertEquals("27:46:39", TeamActivityFormat.formatDuration(99_999_000))
    }

    @Test
    fun `negative durations are clamped`() {
        assertEquals("0s", TeamActivityFormat.formatDuration(-5_000))
    }

    @Test
    fun `running elapsed grows with now`() {
        assertEquals("12s", TeamActivityFormat.elapsed(now = 1_012_000, startedAt = 1_000_000))
        assertEquals("01:23", TeamActivityFormat.elapsed(now = 1_083_000, startedAt = 1_000_000))
    }

    @Test
    fun `settled elapsed freezes at finishedAt`() {
        // 结算后时长不再增长：now 往前走，结果不变。
        assertEquals(
            "01:23",
            TeamActivityFormat.elapsed(now = 9_999_999, startedAt = 1_000_000, finishedAt = 1_083_000),
        )
    }

    @Test
    fun `missing start falls back to placeholder`() {
        assertEquals(TeamActivityFormat.PLACEHOLDER, TeamActivityFormat.elapsed(now = 5, startedAt = null))
        assertEquals("--", TeamActivityFormat.PLACEHOLDER)
    }
}
