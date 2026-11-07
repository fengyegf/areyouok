package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史消息 → 时间线条目：一次工具调用是 `tool.call` + `tool.result` 两条记录，
 * 必须并成一行（真机上曾把 73 次调用显示成 146 项）。
 */
class MessageHistoryTest {

    private fun call(seq: Long, name: String) =
        Message(seq = seq, role = "tool", text = name, kind = "tool.call")

    private fun result(seq: Long, text: String, kind: String = "tool.result") =
        Message(seq = seq, role = "tool", text = text, kind = kind)

    @Test
    fun `tool call and result merge into one row`() {
        val rows = listOf(call(1, "edit"), result(2, "The file was edited")).toHistoryChatMessages()

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(ChatRole.TOOL, row.role)
        assertEquals("edit", row.meta)
        assertEquals("The file was edited", row.text)
        // 合并行沿用调用记录的 seq，分页去重仍然有效。
        assertEquals(1L, row.seq)
    }

    @Test
    fun `tool call and error merge into one row`() {
        val rows = listOf(
            call(1, "edit"),
            result(2, "Error: old_string matched 3 times", kind = "tool.error"),
        ).toHistoryChatMessages()

        assertEquals(1, rows.size)
        assertEquals("edit", rows.single().meta)
        assertTrue(rows.single().text.startsWith("Error:"))
    }

    @Test
    fun `parallel calls pair with results in order`() {
        val rows = listOf(
            call(1, "read_image"),
            call(2, "pwsh"),
            result(3, "image/png"),
            result(4, "[exit code: 0]"),
        ).toHistoryChatMessages()

        assertEquals(2, rows.size)
        assertEquals(listOf("read_image", "pwsh"), rows.map { it.meta })
        assertEquals(listOf("image/png", "[exit code: 0]"), rows.map { it.text })
    }

    @Test
    fun `result without call stays standalone`() {
        val rows = listOf(result(7, "旧输出")).toHistoryChatMessages()

        assertEquals(1, rows.size)
        assertEquals(ChatRole.TOOL, rows.single().role)
        assertEquals(null, rows.single().meta)
        assertEquals("旧输出", rows.single().text)
    }

    @Test
    fun `pending call without result stays as one row`() {
        val rows = listOf(call(9, "ops_bash")).toHistoryChatMessages()

        assertEquals(1, rows.size)
        assertEquals("ops_bash", rows.single().meta)
        assertEquals("", rows.single().text)
    }

    @Test
    fun `seventy three calls and results become seventy three rows`() {
        // 真机回归：会话有 73 次调用 + 71 条结果 + 2 条错误，旧实现显示「146 项」。
        val messages = ArrayList<Message>(146)
        var seq = 1L
        repeat(73) { index ->
            messages += call(seq++, "ops_bash")
            messages += if (index % 37 == 0) {
                result(seq++, "error", kind = "tool.error")
            } else {
                result(seq++, "output-$index")
            }
        }

        val rows = messages.toHistoryChatMessages()

        assertEquals(73, rows.size)
        assertEquals(73, rows.count { it.role == ChatRole.TOOL })
        assertEquals(73, rows.count { it.meta == "ops_bash" })
        assertEquals("output-72", rows.last().text)
    }

    @Test
    fun `assistant reasoning splits into thinking and text rows`() {
        val messages = listOf(
            Message(seq = 1, role = "user", text = "改一下", source = MessageSource(kind = "user")),
            Message(seq = 2, role = "assistant", text = "改好了", reasoning = "先看代码"),
        )

        val rows = messages.toHistoryChatMessages()

        assertEquals(3, rows.size)
        assertEquals(ChatRole.USER, rows[0].role)
        assertEquals(ChatRole.THINKING, rows[1].role)
        assertEquals("先看代码", rows[1].text)
        assertEquals(ChatRole.ASSISTANT, rows[2].role)
        assertEquals("改好了", rows[2].text)
    }
}
