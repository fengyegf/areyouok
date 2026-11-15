package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.FakeTransport
import com.catmoecircle.areyouok.gateway.protocol.TranslateResponse
import com.catmoecircle.areyouok.gateway.protocol.TranslationProvidersResponse
import com.catmoecircle.areyouok.gateway.transport.GatewayException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 思考链翻译状态机：加载态 → 完成/失败、缓存去重、分段串行、失败不抛。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThinkingTranslationTest {

    private fun repo(transport: FakeTransport, scope: CoroutineScope): GatewayRepository =
        GatewayRepository(transport, scope)

    @Test
    fun `successful translation publishes done and caches the result`() = runTest {
        val transport = FakeTransport()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        val result = repository.translateThinking("m1", "hello", "bing", "zh-CN")

        assertTrue(result.isDone)
        assertEquals("[zh-CN]hello", result.text)
        assertEquals("bing", result.provider)
        assertEquals("zh-CN", result.targetLanguage)
        assertNull(result.message)
        val cached = repository.translations.value.getValue("m1")
        assertTrue(cached.isDone)
        assertEquals("[zh-CN]hello", cached.displayText)

        // 同一 messageId + provider + 目标语言直接复用，不打第二次。
        repository.translateThinking("m1", "hello", "bing", "zh-CN")
        assertEquals(1, transport.fakeApi.translationRequests.size)
        repoScope.cancel()
    }

    @Test
    fun `loading state is published before the gateway answers`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            fakeApi.translateHandler = { text, target, _, provider ->
                gate.await()
                TranslateResponse(text = "译:$text", provider = provider ?: "bing", targetLanguage = target)
            }
        }
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        val job = launch { repository.translateThinking("m2", "hello", "bing", "zh-CN") }
        runCurrent()
        val loading = repository.translations.value.getValue("m2")
        assertTrue(loading.isLoading)
        assertNull(loading.displayText)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(repository.translations.value.getValue("m2").isDone)
        job.join()
        repoScope.cancel()
    }

    @Test
    fun `changing provider or target triggers a new request`() = runTest {
        val transport = FakeTransport()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        repository.translateThinking("m3", "hello", "bing", "zh-CN")
        repository.translateThinking("m3", "hello", "google", "zh-CN")
        repository.translateThinking("m3", "hello", "google", "en")

        assertEquals(3, transport.fakeApi.translationRequests.size)
        assertEquals("google", transport.fakeApi.translationRequests[1].provider)
        assertEquals("en", transport.fakeApi.translationRequests[2].targetLanguage)
        repoScope.cancel()
    }

    @Test
    fun `failure marks failed without throwing and can be retried`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.translationError =
                GatewayException(GatewayException.CODE_TRANSLATION_TIMEOUT, 504, "翻译超时")
        }
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        val failed = repository.translateThinking("m4", "hello", "bing", "zh-CN")
        assertTrue(failed.isFailed)
        assertEquals("翻译超时，请重试", failed.message)
        assertTrue(failed.retryable)
        assertFalse(repository.translations.value.getValue("m4").isDone)

        // 用户点「重试」：错误去掉后应重新请求并成功。
        transport.fakeApi.translationError = null
        val retried = repository.translateThinking("m4", "hello", "bing", "zh-CN")
        assertTrue(retried.isDone)
        assertEquals(1, transport.fakeApi.translationRequests.size)
        repoScope.cancel()
    }

    @Test
    fun `long text is split into serial requests and joined in order`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.translationProvidersResponse = TranslationProvidersResponse(maxTextChars = 10)
            var index = 0
            fakeApi.translateHandler = { _, target, _, provider ->
                index += 1
                TranslateResponse(text = "T$index", provider = provider ?: "bing", targetLanguage = target)
            }
        }
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        val result = repository.translateThinking("m5", "aaaa\n\nbbbb\n\ncccc", "bing", "zh-CN")

        assertEquals(
            listOf("aaaa\n\nbbbb", "cccc"),
            transport.fakeApi.translationRequests.map { it.text },
        )
        assertEquals("T1\n\nT2", result.text)
        repoScope.cancel()
    }

    @Test
    fun `providers failure still translates with the contract fallback limit`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.translationProvidersError =
                GatewayException(GatewayException.CODE_TRANSLATION_UNAVAILABLE, 503, "no")
        }
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        val result = repository.translateThinking("m6", "hello", "bing", "zh-CN")

        assertTrue(result.isDone)
        repoScope.cancel()
    }

    @Test
    fun `blank text or blank message id is a no-op`() = runTest {
        val transport = FakeTransport()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        assertTrue(repository.translateThinking("", "hello", "bing", "zh-CN").isIdle)
        assertTrue(repository.translateThinking("m7", "   ", "bing", "zh-CN").isIdle)
        assertEquals(0, transport.fakeApi.translationRequests.size)
        assertTrue(repository.translations.value.isEmpty())
        repoScope.cancel()
    }

    @Test
    fun `clearing the cache re-translates with the new settings`() = runTest {
        val transport = FakeTransport()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val repository = repo(transport, repoScope)

        repository.translateThinking("m8", "hello", "bing", "zh-CN")
        repository.clearTranslations()
        assertTrue(repository.translations.value.isEmpty())

        // 设置页换了服务/语言后清缓存：下次展开按新参数重新请求，而不是复用旧译文。
        repository.translateThinking("m8", "hello", "google", "zh-CN")
        assertEquals(2, transport.fakeApi.translationRequests.size)
        repoScope.cancel()
    }
}
