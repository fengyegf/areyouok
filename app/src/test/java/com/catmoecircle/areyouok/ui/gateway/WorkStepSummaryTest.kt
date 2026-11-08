package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkStepSummaryTest {

    private fun tool(
        id: String,
        name: String?,
        text: String = "",
        arguments: String? = null,
        running: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = ChatRole.TOOL,
        text = text,
        meta = name,
        arguments = arguments,
        running = running,
    )

    private fun thinking(id: String, text: String, streaming: Boolean = false) =
        ChatMessage(id = id, role = ChatRole.THINKING, text = text, streaming = streaming)

    @Test
    fun `tool names map to the same activity categories as the desktop client`() {
        assertEquals(WorkActivity.READ, workActivityOf("read"))
        assertEquals(WorkActivity.READ, workActivityOf("ops_read_files"))
        assertEquals(WorkActivity.READ_IMAGE, workActivityOf("read_image"))
        assertEquals(WorkActivity.SEARCH, workActivityOf("grep"))
        assertEquals(WorkActivity.SEARCH, workActivityOf("glob"))
        assertEquals(WorkActivity.WRITE, workActivityOf("write"))
        assertEquals(WorkActivity.EDIT, workActivityOf("edit"))
        assertEquals(WorkActivity.EDIT, workActivityOf("apply_patch"))
        assertEquals(WorkActivity.COMMANDS, workActivityOf("bash"))
        assertEquals(WorkActivity.COMMANDS, workActivityOf("ops_bash"))
        assertEquals(WorkActivity.COMMANDS, workActivityOf("terminal_start"))
        assertEquals(WorkActivity.CODE, workActivityOf("run_code"))
        assertEquals(WorkActivity.WEB_SEARCH, workActivityOf("web_search"))
        assertEquals(WorkActivity.WEB_FETCH, workActivityOf("web_fetch"))
        assertEquals(WorkActivity.SUBAGENTS, workActivityOf("subagent"))
        assertEquals(WorkActivity.SUBAGENTS, workActivityOf("subagent_fork"))
        assertEquals(WorkActivity.PLAN, workActivityOf("todo_write"))
        assertEquals(WorkActivity.QUESTIONS, workActivityOf("ask_user_question"))
        assertEquals(WorkActivity.TOOLS, workActivityOf("some_plugin_tool"))
        assertEquals(WorkActivity.TOOLS, workActivityOf(null))
    }

    @Test
    fun `live detail reads the first useful argument`() {
        assertEquals(
            "git status --short",
            liveToolDetail("ops_bash", """{"command":"git status --short","timeout":10}"""),
        )
        assertEquals(
            "app/src/Main.kt",
            liveToolDetail("read", """{"file_path":"app/src/Main.kt"}"""),
        )
        assertEquals(
            "kotlin compose",
            liveToolDetail("web_search", """{"query":"kotlin compose"}"""),
        )
        assertEquals(
            "a, b",
            liveToolDetail("web_search", """{"queries":["a","b"]}"""),
        )
    }

    @Test
    fun `live detail falls back to the tool name and ignores blank values`() {
        assertEquals("ops_bash", liveToolDetail("ops_bash", null))
        assertEquals("ops_bash", liveToolDetail("ops_bash", "not json"))
        // 数字参数不算明细，退回工具名。
        assertEquals("options", liveToolDetail("options", """{"timeout":10}"""))
    }

    @Test
    fun `live detail collapses whitespace and truncates long text`() {
        val detail = liveToolDetail("ops_bash", """{"command":"a\n\n  b   c"}""")
        assertEquals("a b c", detail)

        val long = "x".repeat(400)
        val truncated = liveToolDetail("ops_bash", """{"command":"$long"}""")
        assertEquals(160, truncated.length)
        assertTrue(truncated.endsWith("…"))
    }

    @Test
    fun `summarize ranks categories by count then first appearance`() {
        val summary = summarizeWork(
            listOf(
                tool("t1", "read"),
                tool("t2", "ops_bash"),
                tool("t3", "read"),
            ),
        )
        assertEquals(
            listOf(WorkActivity.READ to 2, WorkActivity.COMMANDS to 1),
            summary.counts.map { it.activity to it.count },
        )
        assertEquals(WorkActivity.READ, summary.leading)
        assertEquals(listOf(WorkActivity.READ, WorkActivity.COMMANDS), summary.titleActivities)
        assertTrue(!summary.titleHasMore)
        assertNull(summary.running)
    }

    @Test
    fun `summarize limits the title to three categories and flags more`() {
        val summary = summarizeWork(
            listOf(
                tool("t1", "read"),
                tool("t2", "ops_bash"),
                tool("t3", "grep"),
                tool("t4", "web_fetch"),
            ),
        )
        assertEquals(
            listOf(WorkActivity.READ, WorkActivity.COMMANDS, WorkActivity.SEARCH, WorkActivity.WEB_FETCH),
            summary.counts.map { it.activity },
        )
        assertEquals(3, summary.titleActivities.size)
        assertTrue(summary.titleHasMore)
    }

    @Test
    fun `running tool decides the live title and detail`() {
        val summary = summarizeWork(
            listOf(
                tool("t1", "read", text = "done", running = false),
                tool("t2", "ops_bash", text = "", arguments = """{"command":"gradlew assembleDebug"}""", running = true),
            ),
        )
        assertEquals(WorkActivity.COMMANDS, summary.running)
        assertEquals("gradlew assembleDebug", summary.runningDetail)
    }

    @Test
    fun `finished tools leave no live activity`() {
        val summary = summarizeWork(
            listOf(
                tool("t1", "read", text = "file contents", running = false),
            ),
        )
        assertNull(summary.running)
        assertEquals("", summary.runningDetail)
    }

    @Test
    fun `streaming reasoning is the live activity with its last line as detail`() {
        val summary = summarizeWork(
            listOf(
                thinking("th", "先拆一下问题\n\n再确认渲染层", streaming = true),
            ),
        )
        assertEquals(WorkActivity.THINKING, summary.running)
        assertEquals("再确认渲染层", summary.runningDetail)
        assertTrue(summary.counts.isEmpty())
    }
}
