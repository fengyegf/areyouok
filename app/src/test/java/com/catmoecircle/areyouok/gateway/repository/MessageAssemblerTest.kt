package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** delta 合并 / 完整消息落定 / 乐观消息替换。 */
class MessageAssemblerTest {

    @Test
    fun `delta chunks merge into one streaming message`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("你", seq = 1)
        assembler.appendDelta("好", seq = 2)
        assembler.appendDelta("呀", seq = 3)

        val messages = assembler.snapshot()
        assertEquals(1, messages.size)
        assertEquals(ChatRole.ASSISTANT, messages.single().role)
        assertEquals("你好呀", messages.single().text)
        assertTrue(messages.single().streaming)
    }

    @Test
    fun `full assistant message finalizes streaming bubble`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("你", seq = 1)
        assembler.appendDelta("好", seq = 2)
        assembler.appendFull(Message(seq = 3, role = "assistant", text = "你好呀！"))

        val messages = assembler.snapshot()
        assertEquals(1, messages.size)
        assertEquals("你好呀！", messages.single().text)
        assertFalse(messages.single().streaming)
    }

    @Test
    fun `second turn opens a new bubble after finish`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("第一轮", seq = 1)
        assembler.finishStreaming()
        assembler.appendDelta("第二轮", seq = 2)

        assertEquals(
            listOf("第一轮", "第二轮"),
            assembler.snapshot().map { it.text },
        )
        assertFalse(assembler.snapshot().first().streaming)
        assertTrue(assembler.snapshot().last().streaming)
    }

    @Test
    fun `history message with same seq is not duplicated`() {
        val assembler = MessageAssembler()
        assembler.reset(listOf(ChatMessage(id = "m1", role = ChatRole.USER, text = "hi", seq = 5)))
        assertFalse(assembler.appendFull(Message(seq = 5, role = "user", text = "hi")))
        assertEquals(1, assembler.snapshot().size)
    }

    @Test
    fun `optimistic user message is replaced by server echo`() {
        val assembler = MessageAssembler()
        assembler.appendLocalUser("跑一下测试", time = 1)
        assertTrue(assembler.snapshot().single().pending)

        assembler.appendFull(Message(seq = 12, role = "user", text = "跑一下测试"))
        val messages = assembler.snapshot()
        assertEquals(1, messages.size)
        assertFalse(messages.single().pending)
        assertEquals(12L, messages.single().seq)
    }

    @Test
    fun `tool-only assistant step does not create an empty message`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("先跑一下测试", kind = "reasoning")
        assembler.appendTool(toolName = "run_code", callId = "call-1", summary = null, seq = 2, running = true)
        assembler.appendTool(toolName = null, callId = "call-1", summary = "ok", seq = 3)

        // DSH 的一个 step 只有 reasoning + tool-call：落盘的 assistant 正文为空。
        val added = assembler.appendFull(Message(seq = 4, role = "assistant", text = ""))

        assertFalse(added)
        assertEquals(
            listOf(ChatRole.THINKING, ChatRole.TOOL),
            assembler.snapshot().map { it.role },
        )
    }

    @Test
    fun `tool call and result share one row`() {
        val assembler = MessageAssembler()
        assembler.appendTool(toolName = "pwsh", callId = "call-1", summary = null, seq = 1)
        assembler.appendTool(toolName = null, callId = "call-1", summary = "exit 0", seq = 2)

        val messages = assembler.snapshot()
        assertEquals(1, messages.size)
        assertEquals(ChatRole.TOOL, messages.single().role)
        assertEquals("pwsh", messages.single().meta)
        assertEquals("exit 0", messages.single().text)
    }

    @Test
    fun `tool call keeps arguments for detailed display`() {
        val assembler = MessageAssembler()
        assembler.appendTool(
            toolName = "ops_bash",
            callId = "call-2",
            summary = null,
            seq = 3,
            arguments = "{\"command\":\"ls\"}",
        )
        assembler.appendTool(toolName = "ops_bash", callId = "call-2", summary = "a.txt", seq = 4)

        val message = assembler.snapshot().single()
        assertEquals("{\"command\":\"ls\"}", message.arguments)
        assertEquals("a.txt", message.text)
    }

    @Test
    fun `reasoning deltas become a separate thinking message`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("先看看文件", kind = "reasoning")
        assembler.appendDelta("，再改代码", kind = "reasoning")
        assembler.appendDelta("好的，已完成。", kind = "text")

        val messages = assembler.snapshot()
        assertEquals(2, messages.size)
        assertEquals(ChatRole.THINKING, messages[0].role)
        assertEquals("先看看文件，再改代码", messages[0].text)
        assertFalse(messages[0].streaming)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
        assertEquals("好的，已完成。", messages[1].text)
    }

    @Test
    fun `full assistant message finishes streaming thinking`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("思考中", kind = "reasoning")
        assembler.appendFull(Message(seq = 9, role = "assistant", text = "最终汇报"))

        val messages = assembler.snapshot()
        assertEquals(ChatRole.THINKING, messages[0].role)
        assertFalse(messages[0].streaming)
        assertEquals("最终汇报", messages[1].text)
    }

    @Test
    fun `finishStreaming also settles thinking rows`() {
        val assembler = MessageAssembler()
        assembler.appendDelta("还在想", kind = "reasoning")
        assembler.finishStreaming()
        assertFalse(assembler.snapshot().single().streaming)
    }

    @Test
    fun `removeLocalUser drops failed optimistic message`() {
        val assembler = MessageAssembler()
        assembler.appendLocalUser("失败消息", time = 1)
        assembler.removeLocalUser("失败消息")
        assertTrue(assembler.snapshot().isEmpty())
    }

    @Test
    fun `tool and system rows keep order`() {
        val assembler = MessageAssembler()
        assembler.appendFull(Message(seq = 1, role = "user", text = "hi"))
        assembler.appendTool(toolName = "pwsh", callId = "c1", summary = null)
        assembler.appendFull(Message(seq = 2, role = "assistant", text = "done"))
        assembler.appendSystem("会话已重命名")

        assertEquals(
            listOf(ChatRole.USER, ChatRole.TOOL, ChatRole.ASSISTANT, ChatRole.SYSTEM),
            assembler.snapshot().map { it.role },
        )
    }

    @Test
    fun `prepend inserts older history at the head and dedupes by seq`() {
        val assembler = MessageAssembler()
        assembler.reset(
            listOf(
                ChatMessage(id = "seq-300", role = ChatRole.USER, text = "新的", seq = 300),
                ChatMessage(id = "seq-301", role = ChatRole.ASSISTANT, text = "回复", seq = 301),
            ),
        )

        val added = assembler.prepend(
            listOf(
                ChatMessage(id = "seq-100", role = ChatRole.USER, text = "旧的", seq = 100),
                ChatMessage(id = "seq-101", role = ChatRole.TOOL, text = "tool", seq = 101),
                ChatMessage(id = "seq-300", role = ChatRole.USER, text = "新的", seq = 300),
            ),
        )

        assertEquals(2, added)
        assertEquals(
            listOf("旧的", "tool", "新的", "回复"),
            assembler.snapshot().map { it.text },
        )
    }

    @Test
    fun `background job notice is appended as system row with source kind`() {
        val assembler = MessageAssembler()
        val first = assembler.appendContext(
            Message(seq = 3377, role = "user", text = "background job pwsh-1 finished"),
            meta = "tool-jobs",
        )
        val duplicate = assembler.appendContext(
            Message(seq = 3377, role = "user", text = "background job pwsh-1 finished"),
            meta = "tool-jobs",
        )

        assertTrue(first)
        assertFalse(duplicate)
        val row = assembler.snapshot().single()
        assertEquals(ChatRole.SYSTEM, row.role)
        assertEquals("tool-jobs", row.meta)
    }

    @Test
    fun `live tool result merges into a pending history call without callId`() {
        val assembler = MessageAssembler()
        // 历史里的 tool.call：没有 callId、正文为空，只有工具名。
        assembler.reset(
            listOf(
                ChatMessage(id = "tool-call-seq-1", role = ChatRole.TOOL, text = "", seq = 1, meta = "edit"),
            ),
        )
        assembler.appendTool(
            toolName = null,
            callId = "call-live",
            summary = "The file was edited",
            seq = 2,
            mergeWithPending = true,
        )

        val row = assembler.snapshot().single()
        assertEquals(ChatRole.TOOL, row.role)
        assertEquals("edit", row.meta)
        assertEquals("The file was edited", row.text)
    }

    @Test
    fun `prepend merges a tool pair split across history pages`() {
        val assembler = MessageAssembler()
        assembler.reset(
            listOf(
                // 已加载页的第一条：上一次调用的结果行（历史结果没有 callId）。
                ChatMessage(id = "tool-result-seq-101", role = ChatRole.TOOL, text = "输出", seq = 101),
            ),
        )
        assembler.prepend(
            listOf(
                ChatMessage(id = "tool-call-seq-100", role = ChatRole.TOOL, text = "", seq = 100, meta = "edit"),
            ),
        )

        val row = assembler.snapshot().single()
        assertEquals(ChatRole.TOOL, row.role)
        assertEquals("edit", row.meta)
        assertEquals("输出", row.text)
    }
}
