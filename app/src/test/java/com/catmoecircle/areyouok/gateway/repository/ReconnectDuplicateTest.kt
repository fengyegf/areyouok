package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.FakeTransport
import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessagesPage
import com.catmoecircle.areyouok.gateway.protocol.ReadyFrame
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.StreamUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连后时间线不重复（真机问题：App 连上/重连后，会话第一条消息在时间线里出现两次）。
 *
 * 复现用帧形状与 dsh-gateway 实际下发的一致：`message.user` / `message.assistant`
 * 的 DSH seq 在**事件 data 顶层**，`data.message` 才是 DSH 原始消息（没有 seq）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectDuplicateTest {

    private val endpoint = GatewayEndpoint(baseUrl = "http://127.0.0.1:7788", name = "PC")

    private fun scope(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        CoroutineScope(dispatcher + SupervisorJob())

    /** 网关下发的 DSH 原始 user 消息（无 seq）。 */
    private fun userEventData(text: String, seq: Long) = buildJsonObject {
        put("sessionId", "s1")
        put("seq", seq)
        put("role", "user")
        put("text", text)
        put("message", buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("source", buildJsonObject { put("kind", "user") })
        })
    }

    private fun assistantEventData(text: String, seq: Long) = buildJsonObject {
        put("sessionId", "s1")
        put("seq", seq)
        put("role", "assistant")
        put("text", text)
        put("message", buildJsonObject {
            put("role", "assistant")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        })
    }

    @Test
    fun `reconnect replay does not duplicate the first user message already in history`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(Message(seq = 41, role = "user", text = "帮我看下日志")),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        assertEquals(1, repository.messages.value.count { it.role == ChatRole.USER })

        // 重连：网关把同一轮的首条消息再补发一次（事件 data.seq 仍是 41，网关帧 seq 是新的）。
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 120)))
        transport.emitEvent(seq = 101, kind = "message.user", data = userEventData("帮我看下日志", 41))
        advanceUntilIdle()

        assertEquals(1, repository.messages.value.count { it.role == ChatRole.USER })
        assertEquals("帮我看下日志", repository.messages.value.first { it.role == ChatRole.USER }.text)
        testScope.cancel()
    }

    @Test
    fun `reconnect replay does not duplicate the assistant reply already in history`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(Message(seq = 51, role = "assistant", text = "日志在这里")),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        assertEquals(1, repository.messages.value.count { it.role == ChatRole.ASSISTANT })

        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 120)))
        transport.emitEvent(seq = 101, kind = "message.assistant", data = assistantEventData("日志在这里", 51))
        advanceUntilIdle()

        assertEquals(1, repository.messages.value.count { it.role == ChatRole.ASSISTANT })
        testScope.cancel()
    }

    @Test
    fun `repeated reconnect replays keep the timeline stable`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(
                    Message(seq = 41, role = "user", text = "第一条"),
                    Message(seq = 51, role = "assistant", text = "回复"),
                ),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        // 模拟三次重连：每次网关都把同一批消息当成新事件补发（帧 seq 递增）。
        var frameSeq = 100L
        repeat(3) {
            transport.emit(StreamUpdate.Ready(ReadyFrame(seq = frameSeq + 2)))
            transport.emitEvent(seq = frameSeq + 1, kind = "message.user", data = userEventData("第一条", 41))
            transport.emitEvent(seq = frameSeq + 2, kind = "message.assistant", data = assistantEventData("回复", 51))
            frameSeq += 10
            advanceUntilIdle()
        }

        assertEquals(2, repository.messages.value.size)
        assertEquals(listOf("第一条", "回复"), repository.messages.value.map { it.text })
        testScope.cancel()
    }

    @Test
    fun `reconnect replay never merges two real messages with the same text`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(
                    Message(seq = 41, role = "user", text = "继续"),
                    Message(seq = 42, role = "user", text = "继续"),
                ),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        // 重连重放：两条同文本但 seq 不同的真实消息必须各自留在时间线里。
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 120)))
        transport.emitEvent(seq = 101, kind = "message.user", data = userEventData("继续", 41))
        transport.emitEvent(seq = 102, kind = "message.user", data = userEventData("继续", 42))
        advanceUntilIdle()

        val userMessages = repository.messages.value.filter { it.role == ChatRole.USER }
        assertEquals(2, userMessages.size)
        assertEquals(listOf(41L, 42L), userMessages.mapNotNull { it.seq })
        testScope.cancel()
    }

    @Test
    fun `reconnect replay dedupes a message without seq by its id`() = runTest {
        val assembler = MessageAssembler()
        val first = Message(id = "msg-user-1", role = "user", text = "没有 seq 的消息")
        val again = Message(id = "msg-user-1", role = "user", text = "没有 seq 的消息")
        val other = Message(id = "msg-user-2", role = "user", text = "没有 seq 的消息")

        assertTrue(assembler.appendFull(first))
        assertFalse(assembler.appendFull(again))
        assertTrue(assembler.appendFull(other))
        assertEquals(2, assembler.snapshot().size)
    }
}
