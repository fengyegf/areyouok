package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.FakeTransport
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已作答提问卡片在 repository 层的行为：
 * 提交成功后进时间线、按 eventId 幂等、不把别的会话的提问串进来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerQuestionCardTest {

    private val endpoint = GatewayEndpoint(baseUrl = "http://127.0.0.1:7788", name = "PC")

    private fun scope(dispatcher: CoroutineDispatcher) =
        CoroutineScope(dispatcher + SupervisorJob())

    /** 推一条提问事件（真实形状：WS 帧 id 是 ev-…，答案要用 data.eventId）。 */
    private fun FakeTransport.emitQuestion(
        seq: Long,
        waterfallEventId: String,
        sessionId: String = "s1",
        question: String = "选一个方案",
        options: List<String> = listOf("A", "B"),
    ) {
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
                                put("question", question)
                                put(
                                    "options",
                                    buildJsonArray {
                                        options.forEach { label ->
                                            add(buildJsonObject { put("label", label) })
                                        }
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun cards(repository: GatewayRepository) =
        repository.messages.value.filter { it.role == ChatRole.QUESTION }

    @Test
    fun `answered question is appended once with its questions and answers`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitQuestion(seq = 20, waterfallEventId = "wf-q1")
        advanceUntilIdle()
        assertEquals(1, repository.pendingInterventions.value.size)

        val answers = listOf(QuestionAnswer(id = "q1", selected = listOf("B"), custom = "补充"))
        assertTrue(repository.answerQuestion("wf-q1", answers))
        advanceUntilIdle()

        val card = cards(repository).single()
        assertEquals("question-wf-q1", card.id)
        assertEquals(listOf("q1"), card.questions.map { it.id })
        assertEquals("测试问答", card.questions.single().header)
        assertEquals("选一个方案", card.questions.single().text)
        assertEquals(answers, card.answers)
        assertTrue(repository.pendingInterventions.value.isEmpty())
        testScope.cancel()
    }

    @Test
    fun `resubmitting the same event never inserts a second card`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitQuestion(seq = 20, waterfallEventId = "wf-q1")
        advanceUntilIdle()
        val answers = listOf(QuestionAnswer(id = "q1", selected = listOf("A")))
        repository.answerQuestion("wf-q1", answers)
        advanceUntilIdle()
        assertEquals(1, cards(repository).size)

        // 重连/补发把同一次提问又推了一遍：再提交一次也不能多出一张卡片。
        transport.emitQuestion(seq = 21, waterfallEventId = "wf-q1")
        advanceUntilIdle()
        assertEquals(1, repository.pendingInterventions.value.size)
        repository.answerQuestion("wf-q1", answers)
        advanceUntilIdle()

        assertEquals(1, cards(repository).size)
        assertEquals("question-wf-q1", cards(repository).single().id)
        testScope.cancel()
    }

    @Test
    fun `answering another session question does not touch the current timeline`() = runTest {
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
        assertEquals("s1", repository.selectedSessionId.value)

        transport.emitQuestion(seq = 20, waterfallEventId = "wf-q2", sessionId = "s2")
        advanceUntilIdle()
        assertEquals(1, repository.pendingInterventions.value.size)

        assertTrue(
            repository.answerQuestion("wf-q2", listOf(QuestionAnswer(id = "q1", selected = listOf("A")))),
        )
        advanceUntilIdle()

        // 别的会话的提问照样能回答，但不能出现在当前会话时间线里。
        assertTrue(cards(repository).isEmpty())
        assertTrue(repository.pendingInterventions.value.isEmpty())
        testScope.cancel()
    }
}
