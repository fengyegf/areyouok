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
        meta: String? = null,
        running: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = role,
        text = text,
        streaming = streaming,
        meta = meta,
        running = running,
    )

    private fun user(id: String = "u") = message(id, ChatRole.USER, "帮我改一下")
    private fun tool(id: String = "t", name: String = "ops_bash", running: Boolean = true) =
        message(id, ChatRole.TOOL, name, meta = name, running = running)
    private fun thinking(id: String = "th", streaming: Boolean = false) =
        message(id, ChatRole.THINKING, "先看看代码", streaming = streaming)
    private fun assistant(id: String = "a", text: String = "已完成", streaming: Boolean = false) =
        message(id, ChatRole.ASSISTANT, text, streaming)

    @Test
    fun `running turn folds steps into one live group while the reply streams`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "正在处理", streaming = true),
        )
        val entries = buildTimelineEntries(messages, running = true)

        assertEquals(3, entries.size)
        assertEquals("u", (entries[0] as TimelineEntry.Single).message.id)
        val group = entries[1] as TimelineEntry.WorkGroup
        assertEquals(listOf("th", "t1"), group.items.map { it.id })
        // 回复已经开始输出，这一段工作步骤随之结束（标题改为完成摘要）。
        assertTrue(group.closed)
        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)
    }

    @Test
    fun `reply splits the turn into segments so later tools form a new group`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "先看这里"),
            tool("t2"),
            thinking("th2"),
            assistant("a2", "最终汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false)

        assertEquals(5, entries.size)
        assertEquals("u", (entries[0] as TimelineEntry.Single).message.id)

        val first = entries[1] as TimelineEntry.WorkGroup
        assertEquals(listOf("th", "t1"), first.items.map { it.id })
        assertTrue(first.closed)

        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)

        val second = entries[3] as TimelineEntry.WorkGroup
        assertEquals(listOf("t2", "th2"), second.items.map { it.id })
        assertTrue(second.closed)

        assertEquals("最终汇报", (entries[4] as TimelineEntry.Single).message.text)
    }

    @Test
    fun `final replies folds intermediate replies into the preceding group`() {
        val messages = listOf(
            user(),
            thinking(),
            tool("t1"),
            assistant("a1", "中间说明"),
            tool("t2"),
            assistant("a2", "最终汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false, keepAllReplies = false)

        assertEquals(4, entries.size)
        val first = entries[1] as TimelineEntry.WorkGroup
        // 中间回复收进它前面的工作步骤，最终回复留在分组外；后续工具另起一段。
        assertEquals(listOf("th", "t1", "a1"), first.items.map { it.id })
        val second = entries[2] as TimelineEntry.WorkGroup
        assertEquals(listOf("t2"), second.items.map { it.id })
        assertEquals("a2", (entries[3] as TimelineEntry.Single).message.id)
        assertEquals("最终汇报", (entries[3] as TimelineEntry.Single).message.text)
    }

    @Test
    fun `final replies keeps intermediate replies visible while running`() {
        val messages = listOf(
            user(),
            tool("t1"),
            assistant("a1", "中间说明"),
            tool("t2"),
        )
        val entries = buildTimelineEntries(
            messages,
            running = true,
            keepAllReplies = false,
        )

        // 运行中不隐藏中间回复：分组 + 回复 + 末尾实时分组。
        assertEquals(4, entries.size)
        assertTrue(entries[1] is TimelineEntry.WorkGroup)
        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)
        val live = entries[3] as TimelineEntry.WorkGroup
        assertEquals(false, live.closed)
    }

    @Test
    fun `detailed mode keeps every step visible until the turn completes`() {
        val runningMessages = listOf(user(), thinking(), tool("t1"), assistant("a1", "写一半", streaming = true))
        val runningEntries = buildTimelineEntries(
            runningMessages,
            running = true,
            groupWhileRunning = false,
        )
        assertEquals(4, runningEntries.size)
        assertTrue(runningEntries.all { it is TimelineEntry.Single })

        val finished = listOf(user(), thinking(), tool("t1"), assistant("a1", "完成"))
        val finishedEntries = buildTimelineEntries(finished, running = false, groupWhileRunning = false)
        assertEquals(3, finishedEntries.size)
        assertTrue(finishedEntries[1] is TimelineEntry.WorkGroup)
    }

    @Test
    fun `next input keeps the latest completed turn expanded until a new user message`() {
        val messages = listOf(
            user("u1"),
            tool("t1"),
            assistant("a1", "第一轮汇报"),
            user("u2"),
            tool("t2"),
            assistant("a2", "第二轮汇报"),
        )
        val entries = buildTimelineEntries(
            messages,
            running = false,
            deferCollapse = true,
            keepAllReplies = false,
        )

        // 第一轮已经收起；最新一轮保持逐条展开。
        assertEquals(6, entries.size)
        assertTrue(entries[1] is TimelineEntry.WorkGroup)
        assertEquals("t2", (entries[4] as TimelineEntry.Single).message.id)
        assertEquals("a2", (entries[5] as TimelineEntry.Single).message.id)

        // 下一次输入到来后，上一轮也收起（两段工作步骤各成一个分组）。
        val withNextInput = messages + user("u3")
        val folded = buildTimelineEntries(
            withNextInput,
            running = false,
            deferCollapse = true,
            keepAllReplies = false,
        )
        assertEquals(7, folded.size)
        assertTrue(folded[1] is TimelineEntry.WorkGroup)
        assertTrue(folded[4] is TimelineEntry.WorkGroup)
        assertEquals("a2", (folded[5] as TimelineEntry.Single).message.id)
    }

    @Test
    fun `verbose level keeps every work step as a normal entry`() {
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
    }

    @Test
    fun `turn that ends with tools still folds into a closed group`() {
        val messages = listOf(user(), tool("t1"), tool("t2"))
        val entries = buildTimelineEntries(messages, running = false)

        assertEquals(2, entries.size)
        val group = entries[1] as TimelineEntry.WorkGroup
        assertEquals(listOf("t1", "t2"), group.items.map { it.id })
        assertTrue(group.closed)
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
    fun `system messages are never folded`() {
        val messages = listOf(
            tool("t1"),
            message("s1", ChatRole.SYSTEM, "会话已重命名"),
            assistant("a1", "汇报"),
        )
        val entries = buildTimelineEntries(messages, running = false)

        assertEquals(3, entries.size)
        // 孤立的工具行（前面没有 user）也收进一个已完成分组；system 独立。
        val group = entries[0] as TimelineEntry.WorkGroup
        assertEquals(listOf("t1"), group.items.map { it.id })
        assertEquals(ChatRole.SYSTEM, (entries[1] as TimelineEntry.Single).message.role)
        assertEquals("a1", (entries[2] as TimelineEntry.Single).message.id)
    }
}
