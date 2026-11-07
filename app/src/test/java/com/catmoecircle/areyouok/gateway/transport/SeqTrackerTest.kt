package com.catmoecircle.areyouok.gateway.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** seq/id 去重水位。 */
class SeqTrackerTest {

    @Test
    fun `accepts increasing seq once`() {
        val tracker = SeqTracker()
        assertTrue(tracker.shouldApply(1, "a"))
        assertTrue(tracker.shouldApply(2, "b"))
        assertEquals(2L, tracker.lastAppliedSeq())
    }

    @Test
    fun `drops duplicate id`() {
        val tracker = SeqTracker()
        assertTrue(tracker.shouldApply(10, "ev-1"))
        // 同一 id、更高 seq：重连补发场景，仍应丢弃
        assertFalse(tracker.shouldApply(11, "ev-1"))
        assertFalse(tracker.shouldApply(10, "ev-1"))
    }

    @Test
    fun `drops stale or equal seq`() {
        val tracker = SeqTracker(initialSeq = 100)
        assertFalse(tracker.shouldApply(100, "ev-100"))
        assertFalse(tracker.shouldApply(99, "ev-99"))
        assertTrue(tracker.shouldApply(101, "ev-101"))
    }

    @Test
    fun `accepts id-less events by seq and seq-less by id`() {
        val tracker = SeqTracker()
        assertTrue(tracker.shouldApply(5, null))
        assertFalse(tracker.shouldApply(5, null))
        assertTrue(tracker.shouldApply(null, "no-seq"))
        assertFalse(tracker.shouldApply(null, "no-seq"))
    }

    @Test
    fun `reset clears watermark`() {
        val tracker = SeqTracker(initialSeq = 50)
        tracker.reset()
        assertEquals(0L, tracker.lastAppliedSeq())
        assertTrue(tracker.shouldApply(1, "ev-1"))
    }

    @Test
    fun `reconnect resumes from last applied seq`() {
        val tracker = SeqTracker()
        tracker.shouldApply(7, "e7")
        assertEquals(7L, tracker.lastAppliedSeq())
        // 新连接用同一水位重建
        val resumed = SeqTracker(tracker.lastAppliedSeq())
        assertFalse(resumed.shouldApply(7, "e7"))
        assertTrue(resumed.shouldApply(8, "e8"))
    }
}
