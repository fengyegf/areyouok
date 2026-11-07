package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGroupingTest {

    private fun message(
        id: String,
        role: ChatRole,
        text: String = "",
        streaming: Boolean = false,
    ) = ChatMessage(id = id, role = role, text = text, streaming = streaming)

    private fun user(id: String = "u") = message(id, ChatRole.USER, "帮我改一下")
    private fun tool(id: String = "t") = message(id, ChatRole.TOOL, "ops_bash")
    private fun thinking(id: String = "th") = message(id, ChatRole.THINKING, "先看看代码")
    private fun assistant(id: String = "a", text: String = "已完成", streaming: Boolean = false) =
        message(id, ChatRole.ASSISTANT, text, streaming)

    @Test
    fun `final replies folds intermediate assistant replies into the work group`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "中间说明"),
            tool("t2"),
            assistant("a2", "最终汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false, keepAllReplies = false)

        assertEquals(3, entries.size)
        assertTrue(entries[0] is TimelineEntry.Single)
        assertEquals(ChatRole.USER, (entries[0] as TimelineEntry.Single).message.role)

        val group = entries[1] as TimelineEntry.WorkGroup
        assertEquals(listOf("th", "t1", "a1", "t2"), group.items.map { it.id })

        val report = (entries[2] as TimelineEntry.Single).message
        assertEquals("a2", report.id)
        assertEquals("最终汇报", report.text)
    }

    @Test
    fun `all replies keeps every assistant reply outside the work group`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "中间说明"),
            tool("t2"),
            assistant("a2", "最终汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false, keepAllReplies = true)

        // user + 工作步骤分组 + 两条 assistant 回复。
        assertEquals(4, entries.size)
        val group = entries[1] as TimelineEntry.WorkGroup
        // 只有思考 / 工具进入分组；两条回复都保持可见。
        assertEquals(listOf("th", "t1", "t2"), group.items.map { it.id })
        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)
        assertEquals("a2", (entries[3] as TimelineEntry.Single).message.id)
    }

    @Test
    fun `next input keeps the latest completed turn expanded until a new user message`() {
        val firstTurn = listOf(
            user("u1"),
            tool("t1"),
            assistant("a1", "第一轮汇报"),
            user("u2"),
            tool("t2"),
            assistant("a2", "第二轮汇报"),
        )
        val entries = buildTimelineEntries(
            firstTurn,
            running = false,
            deferCollapse = true,
            keepAllReplies = false,
        )

        // 第一轮已经收起；最新一轮保持逐条展开。
        assertEquals(6, entries.size)
        assertTrue(entries[1] is TimelineEntry.WorkGroup)
        assertEquals("t2", (entries[4] as TimelineEntry.Single).message.id)
        assertEquals("a2", (entries[5] as TimelineEntry.Single).message.id)

        // 下一次输入到来后，上一轮也收起。
        val withNextInput = firstTurn + user("u3")
        val folded = buildTimelineEntries(
            withNextInput,
            running = false,
            deferCollapse = true,
            keepAllReplies = false,
        )
        assertEquals(7, folded.size)
        assertTrue(folded[4] is TimelineEntry.WorkGroup)
        assertEquals("a2", (folded[5] as TimelineEntry.Single).message.id)
    }

    @Test
    fun `running turn keeps every item visible`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "正在处理", streaming = true),
        )
        val entries = buildTimelineEntries(messages, running = true)
        assertEquals(4, entries.size)
        assertTrue(entries.all { it is TimelineEntry.Single })
    }

    @Test
    fun `streaming report keeps turn expanded even if running flag is stale`() {
        val messages = listOf(user(), tool("t1"), assistant("a1", "写一半", streaming = true))
        val entries = buildTimelineEntries(messages, running = false)
        assertEquals(3, entries.size)
        assertTrue(entries.all { it is TimelineEntry.Single })
    }

    @Test
    fun `concise also folds tools and thinking into the work group`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "中间说明"),
            assistant("a2", "最终汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false, keepAllReplies = false)
        assertEquals(3, entries.size)

        val group = entries[1] as TimelineEntry.WorkGroup
        assertEquals(listOf("th", "t1", "a1"), group.items.map { it.id })
        assertEquals("a2", (entries[2] as TimelineEntry.Single).message.id)
    }

    @Test
    fun `turn without assistant report is not folded`() {
        val messages = listOf(user(), tool("t1"), tool("t2"))
        val entries = buildTimelineEntries(messages, running = false)
        assertEquals(3, entries.size)
        assertTrue(entries.all { it is TimelineEntry.Single })
    }

    @Test
    fun `multiple turns are grouped independently`() {
        val messages = listOf(
            user("u1"),
            tool("t1"),
            assistant("a1", "第一轮汇报"),
            user("u2"),
            tool("t2"),
            assistant("a2", "第二轮汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false)
        assertEquals(6, entries.size)
        assertTrue(entries[1] is TimelineEntry.WorkGroup)
        assertTrue(entries[4] is TimelineEntry.WorkGroup)
        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)
        assertEquals("a2", (entries[5] as TimelineEntry.Single).message.id)
    }

    @Test
    fun `expanded level keeps every work step as a normal entry`() {
        // 「完全展开」不把工作步骤收进分组：思考、工具、中间说明按普通消息逐条展示。
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "中间说明"),
            tool("t2"),
            assistant("a2", "最终汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false, groupWorkSteps = false)

        assertEquals(6, entries.size)
        assertTrue(entries.all { it is TimelineEntry.Single })
        assertEquals(
            listOf("u", "th", "t1", "a1", "t2", "a2"),
            entries.map { (it as TimelineEntry.Single).message.id },
        )
        assertEquals(ChatRole.ASSISTANT, (entries[5] as TimelineEntry.Single).message.role)
    }

    @Test
    fun `system messages are never folded`() {
        val messages = listOf(
            tool("t1"),
            message("s1", ChatRole.SYSTEM, "会话已重命名"),
            assistant("a1", "汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false)
        // 没有 user 的孤立工具行不折叠；system 独立；assistant 汇报独立。
        assertEquals(3, entries.size)
        assertEquals(ChatRole.TOOL, (entries[0] as TimelineEntry.Single).message.role)
        assertEquals(ChatRole.SYSTEM, (entries[1] as TimelineEntry.Single).message.role)
        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)
    }
}
