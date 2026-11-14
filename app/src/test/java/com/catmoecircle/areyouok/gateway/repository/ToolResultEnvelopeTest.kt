package com.catmoecircle.areyouok.gateway.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果信封解析：<path> / <type> / <content> 标签属于这次工具调用，
 * 必须从正文里剥出来（真机上曾把标签原样当灰字正文显示）。
 */
class ToolResultEnvelopeTest {

    @Test
    fun `full envelope is split into path type and content`() {
        val text = """
            <path>E:\Android\areyouok\.tmp\s2.png</path>
            <type>image</type>
            <content>
            图片已保存
            </content>
        """.trimIndent()

        val envelope = parseToolResultEnvelope(text)

        assertTrue(envelope.hadEnvelope)
        assertEquals("""E:\Android\areyouok\.tmp\s2.png""", envelope.path)
        assertEquals("image", envelope.type)
        assertEquals("图片已保存", envelope.content)
    }

    @Test
    fun `text without envelope is returned unchanged`() {
        val text = "[exit code: 0]\n已写入 3 个文件"

        val envelope = parseToolResultEnvelope(text)

        assertFalse(envelope.hadEnvelope)
        assertNull(envelope.path)
        assertNull(envelope.type)
        assertEquals(text, envelope.content)
    }

    @Test
    fun `missing closing content tag keeps the remainder`() {
        val text = "<path>/tmp/a.log</path>\n<content>\nline1\nline2…"

        val envelope = parseToolResultEnvelope(text)

        assertTrue(envelope.hadEnvelope)
        assertNull(envelope.type)
        assertEquals("line1\nline2…", envelope.content)
    }

    @Test
    fun `type tag is optional`() {
        val envelope = parseToolResultEnvelope("<path>/tmp/a.log</path>\n<content>ok</content>")

        assertTrue(envelope.hadEnvelope)
        assertNull(envelope.type)
        assertEquals("ok", envelope.content)
    }

    @Test
    fun `path with chinese and backslashes keeps its exact value`() {
        val envelope = parseToolResultEnvelope(
            """<path>D:\工作目录\截图 1.png</path><type>image</type><content>已保存</content>""",
        )

        assertTrue(envelope.hadEnvelope)
        assertEquals("""D:\工作目录\截图 1.png""", envelope.path)
        assertEquals("image", envelope.type)
        assertEquals("已保存", envelope.content)
    }

    @Test
    fun `content may contain angle brackets`() {
        val envelope = parseToolResultEnvelope(
            """<path>/tmp/x.kt</path><content>if (a < b) println("<b>")</content>""",
        )

        assertTrue(envelope.hadEnvelope)
        assertEquals("""if (a < b) println("<b>")""", envelope.content)
    }

    @Test
    fun `leading whitespace still counts as envelope`() {
        val envelope = parseToolResultEnvelope("\n  <path>/tmp/x.txt</path><content>ok</content>")

        assertTrue(envelope.hadEnvelope)
        assertEquals("/tmp/x.txt", envelope.path)
        assertEquals("ok", envelope.content)
    }

    @Test
    fun `unterminated path tag is not an envelope`() {
        val text = "<path>/tmp/x.txt\n<content>ok</content>"

        val envelope = parseToolResultEnvelope(text)

        assertFalse(envelope.hadEnvelope)
        assertEquals(text, envelope.content)
    }

    @Test
    fun `envelope without content tag keeps the text after the tags`() {
        val envelope = parseToolResultEnvelope("<path>/tmp/x.txt</path><type>text</type>hello")

        assertTrue(envelope.hadEnvelope)
        assertEquals("text", envelope.type)
        assertEquals("hello", envelope.content)
    }

    @Test
    fun `file name helper takes the last path segment`() {
        assertEquals("s2.png", toolPathFileName("""E:\Android\areyouok\.tmp\s2.png"""))
        assertEquals("s2.png", toolPathFileName("/tmp/a/s2.png"))
        assertNull(toolPathFileName("   "))
        assertNull(toolPathFileName(null))
    }
}
