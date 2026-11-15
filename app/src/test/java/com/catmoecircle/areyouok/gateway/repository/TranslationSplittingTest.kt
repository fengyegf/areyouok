package com.catmoecircle.areyouok.gateway.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 思考链分段纯函数：段落边界 / 上限 / 代理对 / 空段。 */
class TranslationSplittingTest {

    @Test
    fun `blank text yields no chunks`() {
        assertTrue(splitForTranslation("").isEmpty())
        assertTrue(splitForTranslation("   \n\n  \n ").isEmpty())
    }

    @Test
    fun `short text stays a single chunk`() {
        assertEquals(listOf("hello world"), splitForTranslation("hello world", 100))
        assertEquals(listOf("第一段"), splitForTranslation("  第一段 \n", 100))
    }

    @Test
    fun `paragraphs stay separate when the limit is tight`() {
        val text = "aaaa\n\nbbbb\n\ncccc"
        assertEquals(listOf("aaaa", "bbbb", "cccc"), splitForTranslation(text, 5))
    }

    @Test
    fun `short paragraphs merge while they fit`() {
        val text = "aaaa\n\nbbbb\n\ncccc"
        // "aaaa\n\nbbbb" 正好 10 个码元放得下；再拼 cccc 就 16 > 10。
        assertEquals(listOf("aaaa\n\nbbbb", "cccc"), splitForTranslation(text, 10))
    }

    @Test
    fun `overlong paragraph is split without losing content`() {
        val paragraph = (1..40).joinToString(" ") { "word$it" }
        val chunks = splitForTranslation(paragraph, 30)
        assertTrue("chunks=$chunks", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("chunk too long: ${chunk.length}", chunk.length <= 30)
        }
        // 分段只丢空白：把空白归一化后应与原文完全一致。
        assertEquals(
            paragraph.filterNot { it.isWhitespace() },
            chunks.joinToString("").filterNot { it.isWhitespace() },
        )
    }

    @Test
    fun `unbroken text is hard split at the limit`() {
        val chunks = splitForTranslation("a".repeat(25), 10)
        assertEquals(listOf("a".repeat(10), "a".repeat(10), "a".repeat(5)), chunks)
    }

    @Test
    fun `surrogate pairs are never split`() {
        // 每个 emoji 占 2 个 UTF-16 码元；上限取奇数，逼边界落在代理对中间。
        val text = "😀".repeat(10)
        val chunks = splitForTranslation(text, 3)
        assertTrue("chunks=$chunks", chunks.size > 1)
        for (chunk in chunks) {
            assertFalse("leading low surrogate: $chunk", chunk[0] in '\uDC00'..'\uDFFF')
            assertFalse("trailing high surrogate: $chunk", chunk.last() in '\uD800'..'\uDBFF')
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `limit below a surrogate pair keeps the pair together`() {
        assertEquals(listOf("😀"), splitForTranslation("😀", 1))
    }

    @Test
    fun `windows newlines are normalized before splitting`() {
        assertEquals(listOf("a", "b"), splitForTranslation("a\r\n\r\nb", 2))
    }

    @Test
    fun `every chunk is non blank`() {
        val chunks = splitForTranslation("\n\n  \n\n第一段\n\n\n\n第二段\n\n", 6)
        assertEquals(listOf("第一段", "第二段"), chunks)
        assertTrue(chunks.none { it.isBlank() })
    }
}
