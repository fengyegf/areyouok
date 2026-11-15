package com.catmoecircle.areyouok.gateway.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻译 DTO 的反序列化：缺字段 / 未知字段 / 错误信封。 */
class TranslationJsonTest {

    @Test
    fun `providers response decodes and ignores unknown fields`() {
        val text = """
            {"ok":true,"providers":[{"id":"bing","label":"Bing","free":true,"extra":"x"},
                                    {"id":"google","label":"Google","free":true}],
             "defaultProvider":"bing","defaultTargetLanguage":"zh-CN","maxTextChars":4000,
             "targets":["zh-CN","en"],"serverTime":1}
        """.trimIndent()
        val response = GatewayJson.decodeOrNull<TranslationProvidersResponse>(text)
        assertNotNull(response)
        assertEquals(listOf("bing", "google"), response!!.providers.map { it.id })
        assertEquals("Bing", response.providers.first().displayLabel)
        assertTrue(response.providers.first().free)
        assertEquals("bing", response.defaultProvider)
        assertEquals(4000, response.effectiveMaxTextChars)
        assertEquals(listOf("zh-CN", "en"), response.targets)
    }

    @Test
    fun `missing maxTextChars falls back to contract default`() {
        val response = GatewayJson.decodeOrNull<TranslationProvidersResponse>("""{"ok":true}""")
        assertNotNull(response)
        assertEquals(
            TranslationProvidersResponse.DEFAULT_MAX_TEXT_CHARS,
            response!!.effectiveMaxTextChars,
        )
        assertTrue(response.providers.isEmpty())
        assertNull(response.defaultProvider)
        assertNull(response.defaultTargetLanguage)
    }

    @Test
    fun `translate response decodes full payload`() {
        val text = """{"text":"你好","provider":"bing","targetLanguage":"zh-CN",
            "sourceLanguage":"en","cached":true,"chars":42}""".replace("\n", "")
        val response = GatewayJson.decodeOrNull<TranslateResponse>(text)
        assertNotNull(response)
        assertEquals("你好", response!!.text)
        assertEquals("bing", response.provider)
        assertEquals("zh-CN", response.targetLanguage)
        assertEquals("en", response.sourceLanguage)
        assertTrue(response.cached)
        assertEquals(42, response.chars)
        assertTrue(response.ok)
    }

    @Test
    fun `translate response tolerates minimal payload`() {
        val response = GatewayJson.decodeOrNull<TranslateResponse>("""{"text":"译文"}""")
        assertNotNull(response)
        assertEquals("译文", response!!.text)
        assertEquals("", response.provider)
        assertEquals("", response.targetLanguage)
        assertFalse(response.cached)
        assertEquals(0, response.chars)
    }

    @Test
    fun `failure envelope keeps error code and message`() {
        val response = GatewayJson.decodeOrNull<TranslateResponse>(
            """{"ok":false,"error":{"code":"translation_timeout","message":"翻译超时"}}""",
        )
        assertNotNull(response)
        assertFalse(response!!.ok)
        assertEquals("translation_timeout", response.error?.code)
        assertEquals("翻译超时", response.error?.message)
    }

    @Test
    fun `provider label falls back to id`() {
        val info = GatewayJson.decodeOrNull<TranslationProviderInfo>("""{"id":"google"}""")
        assertEquals("google", info!!.displayLabel)
        assertFalse(info.free)
    }
}
