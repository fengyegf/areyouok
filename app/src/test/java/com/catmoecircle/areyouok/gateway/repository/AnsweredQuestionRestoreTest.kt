package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.FakeTransport
import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessagesPage
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Test

/**
 * 已作答提问卡片的留存：切会话（历史整体重建）再切回来，卡片按作答时的 seq 锚点
 * 插回原来的位置，而不是消失或跑到末尾。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnsweredQuestionRestoreTest {

    private val endpoint = GatewayEndpoint(baseUrl = "http://127.0.0.1:7788", name = "PC")

    private fun scope(dispatcher: CoroutineDispatcher) =
        CoroutineScope(dispatcher + SupervisorJob())

    private fun FakeTransport.emitQuestion(seq: Long, waterfallEventId: String, sessionId: String = "s1") {
        emitEvent(
            seq = seq,
            kind = "question.request",
            data = buildJsonObject {
                put("eventId", waterfallEventId)
                put("sessionId", sessionId)
                put(
                    "questions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "q1")
                                put("header", "测试问答")
                                put("question", "选一个方案")
                                put(
                                    "options",
                                    buildJsonArray {
                                        add(buildJsonObject { put("label", "A") })
                                        add(buildJsonObject { put("label", "B") })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun GatewayRepository.timeline(): List<String> = messages.value.map { message ->
        when (message.role) {
            ChatRole.QUESTION -> "question:${message.id}"
            else -> "${message.role.name.lowercase()}:${message.text}"
        }
    }

    @Test
    fun `card is restored between the messages it was answered between`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(
                SessionSummary(id = "s1", title = "一"),
                SessionSummary(id = "s2", title = "二"),
            )
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(Message(seq = 10, role = "assistant", text = "before")),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        assertEquals("s1", repository.selectedSessionId.value)

        transport.emitQuestion(seq = 20, waterfallEventId = "wf-q1")
        advanceUntilIdle()
        repository.answerQuestion("wf-q1", listOf(QuestionAnswer(id = "q1", selected = listOf("B"))))
        advanceUntilIdle()
        assertEquals(listOf("assistant:before", "question:question-wf-q1"), repository.timeline())

        // 切走：时间线清空；期间会话在 PC 上又产生了新的助手消息。
        repository.selectSession("s2")
        advanceUntilIdle()
        transport.fakeApi.messagesPage = MessagesPage(
            messages = listOf(
                Message(seq = 10, role = "assistant", text = "before"),
                Message(seq = 30, role = "assistant", text = "after"),
            ),
        )

        // 切回来：历史整体重建，卡片按锚点回到「before」之后、「after」之前。
        repository.selectSession("s1")
        advanceUntilIdle()
        assertEquals(
            listOf("assistant:before", "question:question-wf-q1", "assistant:after"),
            repository.timeline(),
        )
        testScope.cancel()
    }

    @Test
    fun `restored card keeps its chosen answers`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(
                SessionSummary(id = "s1", title = "一"),
                SessionSummary(id = "s2", title = "二"),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitQuestion(seq = 20, waterfallEventId = "wf-q9")
        advanceUntilIdle()
        val answers = listOf(QuestionAnswer(id = "q1", selected = listOf("A"), custom = "再补充一点"))
        repository.answerQuestion("wf-q9", answers)
        advanceUntilIdle()

        repository.selectSession("s2")
        advanceUntilIdle()
        repository.selectSession("s1")
        advanceUntilIdle()

        val card = repository.messages.value.single { it.role == ChatRole.QUESTION }
        assertEquals("question-wf-q9", card.id)
        assertEquals(answers, card.answers)
        assertEquals(listOf("q1"), card.questions.map { it.id })
        assertEquals(1, repository.messages.value.count { it.role == ChatRole.QUESTION })
        testScope.cancel()
    }
}
