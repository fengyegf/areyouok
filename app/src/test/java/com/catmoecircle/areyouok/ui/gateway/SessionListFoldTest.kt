package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 侧边栏分组折叠：默认先露 4 条，点「展开其余 N 个对话」后全部展开。 */
class SessionListFoldTest {

    private fun sessions(count: Int): List<SessionSummary> =
        (1..count).map { SessionSummary(id = "s$it", title = "chat-$it") }

    @Test
    fun `preview limit is four`() {
        assertEquals(4, SESSION_PREVIEW_LIMIT)
    }

    @Test
    fun `empty group has nothing to fold`() {
        val empty = sessions(0)

        assertTrue(visibleSessions(empty, expanded = false).isEmpty())
        assertTrue(visibleSessions(empty, expanded = true).isEmpty())
        assertEquals(0, hiddenSessionCount(empty.size))
    }

    @Test
    fun `three sessions are all visible and nothing hidden`() {
        val list = sessions(3)

        assertEquals(list.map { it.id }, visibleSessions(list, expanded = false).map { it.id })
        assertEquals(3, visibleSessions(list, expanded = true).size)
        assertEquals(0, hiddenSessionCount(list.size))
    }

    @Test
    fun `four sessions exactly fit the preview`() {
        val list = sessions(4)

        assertEquals(4, visibleSessions(list, expanded = false).size)
        assertEquals(4, visibleSessions(list, expanded = true).size)
        assertEquals(0, hiddenSessionCount(list.size))
    }

    @Test
    fun `five sessions hide the fifth until expanded`() {
        val list = sessions(5)

        val folded = visibleSessions(list, expanded = false)
        assertEquals(listOf("s1", "s2", "s3", "s4"), folded.map { it.id })
        assertEquals(list.map { it.id }, visibleSessions(list, expanded = true).map { it.id })
        assertEquals(1, hiddenSessionCount(list.size))
    }

    @Test
    fun `ten sessions fold to four and hide six`() {
        val list = sessions(10)

        val folded = visibleSessions(list, expanded = false)
        assertEquals(4, folded.size)
        // 折叠保留的是列表顺序里的前 4 条（分组内已按最近活跃排序）。
        assertEquals(list.take(4), folded)
        assertEquals(10, visibleSessions(list, expanded = true).size)
        assertEquals(6, hiddenSessionCount(list.size))
    }

    @Test
    fun `custom limit drives preview and hidden count`() {
        val list = sessions(10)

        assertEquals(2, visibleSessions(list, expanded = false, limit = 2).size)
        assertEquals(8, hiddenSessionCount(list.size, limit = 2))
        // limit 大于条数时没有可折叠的。
        assertEquals(0, hiddenSessionCount(list.size, limit = 50))
        assertEquals(10, visibleSessions(list, expanded = false, limit = 50).size)
        // 小于等于 0 的 limit 当 0 处理，不会越界。
        assertTrue(visibleSessions(list, expanded = false, limit = 0).isEmpty())
        assertEquals(10, hiddenSessionCount(list.size, limit = 0))
    }

    @Test
    fun `hidden count never goes negative`() {
        assertEquals(0, hiddenSessionCount(0))
        assertEquals(0, hiddenSessionCount(1))
        assertEquals(0, hiddenSessionCount(4))
        assertEquals(1, hiddenSessionCount(5))
    }
}
