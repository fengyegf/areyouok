package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.FakeTransport
import com.catmoecircle.areyouok.gateway.protocol.EventsPage
import com.catmoecircle.areyouok.gateway.protocol.HealthResponse
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessagesPage
import com.catmoecircle.areyouok.gateway.protocol.ModelCatalog
import com.catmoecircle.areyouok.gateway.protocol.ModelGroup
import com.catmoecircle.areyouok.gateway.protocol.ModelInfo
import com.catmoecircle.areyouok.gateway.protocol.ModelReasoning
import com.catmoecircle.areyouok.gateway.protocol.ModelSelection
import com.catmoecircle.areyouok.gateway.protocol.PermissionCatalog
import com.catmoecircle.areyouok.gateway.protocol.PermissionOption
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.ReasoningEffort
import com.catmoecircle.areyouok.gateway.protocol.SessionModelSelection
import com.catmoecircle.areyouok.gateway.protocol.SessionPermissions
import com.catmoecircle.areyouok.gateway.protocol.SessionProjectionValues
import com.catmoecircle.areyouok.gateway.protocol.SessionProjections
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Snapshot
import com.catmoecircle.areyouok.gateway.protocol.ReadyFrame
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.GatewayException
import com.catmoecircle.areyouok.gateway.transport.StreamUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** repository 状态机：REST 自举 → WS 事件 → 本机操作 → 断线。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRepositoryTest {

    private val endpoint = GatewayEndpoint(baseUrl = "http://127.0.0.1:7788", name = "PC")

    private fun scope(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        CoroutineScope(dispatcher + SupervisorJob())

    @Test
    fun `connect bootstraps state and selects first session`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.workspaces = listOf(Workspace(id = "ws1", name = "demo"))
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "会话一"))
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(Message(seq = 1, role = "user", text = "hi")),
            )
            fakeApi.interventions = listOf(Intervention(eventId = "ev-1", sessionId = "s1"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)

        repository.connect(endpoint)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.CONNECTED, repository.connection.value.phase)
        assertEquals("FY-PC", repository.connection.value.displayName)
        assertEquals(listOf("ws1"), repository.workspaces.value.map { it.id })
        assertEquals("s1", repository.selectedSessionId.value)
        assertEquals(listOf("hi"), repository.messages.value.map { it.text })
        assertEquals(listOf("ev-1"), repository.pendingInterventions.value.map { it.eventId })
        // global 订阅：别的会话里的审批/提问也要能收到，侧边栏才能标记「!」。
        assertTrue(transport.subscriptions.contains("global"))
        assertTrue(transport.subscriptions.contains("session:s1"))
        assertEquals(0L, transport.initialResumeSeq)
        testScope.cancel()
    }

    @Test
    fun `duplicate or blank session ids are dropped before the sidebar renders`() = runTest {
        // 真实 DSH 的会话主键是 sessionId；一旦解不出来（空 id），侧边栏
        // LazyColumn 的 key 会重复成 ""，直接抛 Key "" was already used 闪退。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(
                SessionSummary(id = "s1", title = "一"),
                SessionSummary(id = ""),
                SessionSummary(id = "s1", title = "重复"),
                SessionSummary(id = "s2", title = "二"),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), repository.sessions.value.map { it.id })
        assertEquals("s1", repository.selectedSessionId.value)
        testScope.cancel()
    }

    @Test
    fun `ready snapshot overrides lists`() = runTest {
        val transport = FakeTransport()
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emit(
            StreamUpdate.Ready(
                ReadyFrame(
                    seq = 100,
                    snapshot = Snapshot(
                        workspaces = listOf(Workspace(id = "ws2")),
                        sessions = listOf(SessionSummary(id = "s2", title = "来自快照")),
                        pending = listOf(Intervention(eventId = "ev-9", kind = "question")),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("ws2"), repository.workspaces.value.map { it.id })
        assertEquals(listOf("s2"), repository.sessions.value.map { it.id })
        assertEquals(listOf("ev-9"), repository.pendingInterventions.value.map { it.eventId })
        assertEquals(100L, repository.connection.value.serverSeq)
        testScope.cancel()
    }

    @Test
    fun `message delta merges into streaming assistant message`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.sessionDelta(seq = 10, sessionId = "s1", text = "你")
        transport.sessionDelta(seq = 11, sessionId = "s1", text = "好")
        advanceUntilIdle()

        val assistant = repository.messages.value.filter { it.role == ChatRole.ASSISTANT }
        assertEquals(1, assistant.size)
        assertEquals("你好", assistant.single().text)
        assertTrue(assistant.single().streaming)
        testScope.cancel()
    }

    @Test
    fun `duplicate event seq is ignored`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(seq = 5, kind = "message.delta", data = buildJsonObject {
            put("sessionId", "s1")
            put("text", "A")
        })
        transport.emitEvent(seq = 5, kind = "message.delta", data = buildJsonObject {
            put("sessionId", "s1")
            put("text", "A")
        })
        advanceUntilIdle()

        val assistant = repository.messages.value.filter { it.role == ChatRole.ASSISTANT }
        assertEquals(1, assistant.size)
        assertEquals("A", assistant.single().text)
        testScope.cancel()
    }

    @Test
    fun `turn start and end toggle running state`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(seq = 1, kind = "turn.start", data = buildJsonObject { put("sessionId", "s1") })
        advanceUntilIdle()
        assertTrue(repository.sessionRunning.value)

        transport.emitEvent(seq = 2, kind = "turn.end", data = buildJsonObject { put("sessionId", "s1") })
        advanceUntilIdle()
        assertFalse(repository.sessionRunning.value)
        testScope.cancel()
    }

    @Test
    fun `turn completed notifies once per turn and ignores duplicates`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "会话一"))
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()

        // ready 的 serverTime 用来校正网关/本机时钟差（这里两者相同）。
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 100, serverTime = now)))
        advanceUntilIdle()

        val completed = buildJsonObject {
            put("sessionId", "s1")
            put("turn", 3)
            put("reason", "completed")
        }
        transport.emitEvent(seq = 101, kind = "turn.completed", data = completed, ts = now)
        // 同一轮补发/重复到达：只提醒一次。
        transport.emitEvent(seq = 102, kind = "turn.completed", data = completed, ts = now)
        advanceUntilIdle()

        assertEquals(listOf("s1"), completions.map { it.sessionId })
        assertEquals("会话一", completions.single().title)
        assertEquals(now, completions.single().time)
        testScope.cancel()
    }

    @Test
    fun `turn completed for a session that is not open still notifies`() = runTest {
        // 在 A 里干活、B 跑完了：仍然要提醒（点通知回到 B）。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(
                SessionSummary(id = "s1", title = "正在看的"),
                SessionSummary(id = "s2", title = "后台跑的"),
            )
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 100, serverTime = now)))
        advanceUntilIdle()

        transport.emitEvent(
            seq = 101,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "s2")
                put("turn", 1)
                put("reason", "completed")
            },
            ts = now,
        )
        advanceUntilIdle()

        assertEquals(listOf("s2"), completions.map { it.sessionId })
        assertEquals("后台跑的", completions.single().title)
        testScope.cancel()
    }

    @Test
    fun `session status flips never emit completions`() = runTest {
        // 旧实现靠 running true→false 推断完成，这正是「早已结束的对话又响一次」
        // 的来源：状态是快照，会被重启/补齐/过期状态表重新翻一遍。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "会话一", running = false))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(
            seq = 1,
            kind = "session.status",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("running", true)
            },
        )
        transport.emitEvent(
            seq = 2,
            kind = "session.status",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("running", false)
            },
        )
        // 缺 running 字段的畸形帧同样不能当成「跑完了」。
        transport.emitEvent(seq = 3, kind = "session.status", data = buildJsonObject { put("sessionId", "s1") })
        advanceUntilIdle()

        assertTrue(completions.isEmpty())
        testScope.cancel()
    }

    @Test
    fun `replayed turn completed is ignored until a live one arrives`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "会话一"))
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()

        // 连上时 ready.seq = 100：之后补发的 90/91 是历史，不应弹「任务完成」。
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 100, serverTime = now)))
        advanceUntilIdle()
        transport.emitEvent(
            seq = 90,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("turn", 4)
            },
            ts = now,
        )
        advanceUntilIdle()
        assertTrue(completions.isEmpty())

        // 实时事件（seq > 100）照常提醒。
        transport.emitEvent(
            seq = 101,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("turn", 5)
            },
            ts = now,
        )
        advanceUntilIdle()

        assertEquals(listOf("s1"), completions.map { it.sessionId })
        testScope.cancel()
    }

    @Test
    fun `stale turn completed beyond the freshness window is ignored`() = runTest {
        // 断线很久后重连，网关会补发这段时间结束的轮次：早就不该再打扰。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "会话一"))
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 100, serverTime = now)))
        advanceUntilIdle()

        val completed = buildJsonObject {
            put("sessionId", "s1")
            put("turn", 1)
        }
        // 10 分钟前结束的那一轮（seq 是新的，但时间戳很旧）。
        transport.emitEvent(
            seq = 101,
            kind = "turn.completed",
            data = completed,
            ts = now - 10 * 60_000L,
        )
        advanceUntilIdle()
        assertTrue(completions.isEmpty())

        // 2 分钟内结束的仍然提醒（补发窗口内的「刚跑完」）。
        transport.emitEvent(
            seq = 102,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("turn", 2)
            },
            ts = now - 30_000L,
        )
        advanceUntilIdle()
        assertEquals(1, completions.size)
        testScope.cancel()
    }

    @Test
    fun `turn completed while waiting for an answer is not notified`() = runTest {
        // 等审批/回答时由「提问/授权」通知负责，不再叠一条「任务完成」。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "会话一"))
            fakeApi.interventions = listOf(Intervention(eventId = "ev-approval", sessionId = "s1"))
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 100, serverTime = now)))
        advanceUntilIdle()
        assertEquals(1, repository.pendingInterventions.value.size)

        transport.emitEvent(
            seq = 101,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("turn", 1)
            },
            ts = now,
        )
        advanceUntilIdle()

        assertTrue(completions.isEmpty())
        testScope.cancel()
    }

    @Test
    fun `subagent and interrupted turns are not notified`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(
                SessionSummary(id = "s1", title = "会话一"),
                SessionSummary(id = "sub-1", title = "子代理", parentSessionId = "s1"),
            )
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        val completions = mutableListOf<SessionCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.sessionCompletions.collect { completions += it }
        }
        repository.connect(endpoint)
        advanceUntilIdle()
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 100, serverTime = now)))
        advanceUntilIdle()

        // 子会话：载荷标记 + 会话摘要本身都要挡住。
        transport.emitEvent(
            seq = 101,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "sub-1")
                put("turn", 1)
                put("subagent", true)
            },
            ts = now,
        )
        // 用户按了停止：不是「跑完了」。
        transport.emitEvent(
            seq = 102,
            kind = "turn.completed",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("turn", 1)
                put("reason", "aborted")
            },
            ts = now,
        )
        advanceUntilIdle()

        assertTrue(completions.isEmpty())
        testScope.cancel()
    }

    @Test
    fun `a gateway without the turn capability warns once, not on every reconnect`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val now = 1_700_000_000_000L
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope, clock = { now })
        repository.connect(endpoint)
        advanceUntilIdle()
        val notices = watchNotices(repository)

        // 旧网关：snapshot 没有 capabilities ⇒ 提示一次升级。
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 10, serverTime = now)))
        transport.emit(StreamUpdate.Ready(ReadyFrame(seq = 11, serverTime = now)))
        advanceUntilIdle()
        assertEquals(1, notices.count { it.text.contains("版本较旧") })

        // 新网关（带能力）：不再提示。
        transport.emit(
            StreamUpdate.Ready(
                ReadyFrame(
                    seq = 12,
                    serverTime = now,
                    snapshot = Snapshot(capabilities = listOf(GatewayRepository.CAPABILITY_TURN_COMPLETED)),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, notices.count { it.text.contains("版本较旧") })
        testScope.cancel()
    }

    @Test
    fun `approval event adds pending and answer removes it`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(
            seq = 20,
            kind = "approval.request",
            id = "ev-approval",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("toolName", "pwsh")
                put("reason", "需要执行命令")
            },
        )
        advanceUntilIdle()
        assertEquals(1, repository.pendingInterventions.value.size)
        assertEquals("pwsh", repository.pendingInterventions.value.single().toolName)

        val ok = repository.allowOnce("ev-approval")
        advanceUntilIdle()

        assertTrue(ok)
        assertTrue(repository.pendingInterventions.value.isEmpty())
        assertEquals("ev-approval", transport.fakeApi.answered.single().first)
        assertEquals(JsonPrimitive("allowed-once"), transport.fakeApi.answered.single().second)
        testScope.cancel()
    }

    @Test
    fun `question event uses waterfall event id and submits answers with custom`() = runTest {
        // WS 帧 id 是 `ev-<seq>`，不是 waterfall 的 eventId；回答必须用 data.eventId，
        // 否则网关 404、桌面端弹窗不消失（真实事故：点选项后 DSH 仍显示弹窗）。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(
            seq = 21,
            kind = "question.request",
            id = "ev-21",
            data = buildJsonObject {
                put("eventId", "wf-question-1")
                put("sessionId", "s1")
                put(
                    "questions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "q1")
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
        advanceUntilIdle()

        val pending = repository.pendingInterventions.value.single()
        assertEquals("wf-question-1", pending.eventId)
        assertEquals("选一个方案", pending.questions.single().text)

        val ok = repository.answerQuestion(
            pending.eventId,
            listOf(QuestionAnswer(id = "q1", selected = listOf("B"), custom = "补充")),
        )
        advanceUntilIdle()

        assertTrue(ok)
        assertTrue(repository.pendingInterventions.value.isEmpty())
        assertEquals("wf-question-1", transport.fakeApi.answered.single().first)
        val value = transport.fakeApi.answered.single().second.toString()
        assertTrue(value.contains("\"id\":\"q1\""))
        assertTrue(value.contains("\"selected\":[\"B\"]"))
        assertTrue(value.contains("\"custom\":\"补充\""))
        testScope.cancel()
    }

    @Test
    fun `question event keeps one pending entry keyed by waterfall event id`() = runTest {
        // 同一事件用 REST 快照（正确 eventId）+ WS 帧（ev-…）各来一次时只保留一条，避免重复卡片。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(
            seq = 22,
            kind = "question.request",
            id = "ev-22",
            data = buildJsonObject {
                put("eventId", "wf-question-2")
                put("sessionId", "s1")
                put(
                    "questions",
                    buildJsonArray {
                        add(buildJsonObject { put("id", "q1"); put("question", "继续吗") })
                    },
                )
            },
        )
        advanceUntilIdle()

        assertEquals(listOf("wf-question-2"), repository.pendingInterventions.value.map { it.eventId })
        testScope.cancel()
    }

    @Test
    fun `intervention resolved by other device removes pending`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.interventions = listOf(Intervention(eventId = "ev-2", sessionId = "s1"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        assertEquals(1, repository.pendingInterventions.value.size)

        transport.emitEvent(
            seq = 30,
            kind = "intervention.resolved",
            data = buildJsonObject {
                put("eventId", "ev-2")
                put("by", "other")
                put("outcome", "allowed-once")
            },
        )
        advanceUntilIdle()

        assertTrue(repository.pendingInterventions.value.isEmpty())
        testScope.cancel()
    }

    @Test
    fun `send message appends optimistic user and server echo replaces it`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val sent = repository.sendMessage("跑一下测试")
        advanceUntilIdle()
        assertTrue(sent)
        assertEquals(Triple("s1", "跑一下测试", "queue"), transport.fakeApi.sentMessages.single())
        assertTrue(repository.messages.value.last().pending)

        transport.emitEvent(
            seq = 40,
            kind = "message.user",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("seq", 41)
                put("role", "user")
                put("text", "跑一下测试")
            },
        )
        advanceUntilIdle()

        val userMessages = repository.messages.value.filter { it.role == ChatRole.USER }
        assertEquals(1, userMessages.size)
        assertFalse(userMessages.single().pending)
        testScope.cancel()
    }

    @Test
    fun `failed send removes optimistic message and returns false`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.fakeApi.error = GatewayException("dsh_unavailable", 503, "down")
        val sent = repository.sendMessage("会失败")
        advanceUntilIdle()

        assertFalse(sent)
        assertTrue(repository.messages.value.none { it.text == "会失败" })
        testScope.cancel()
    }

    @Test
    fun `create session inserts and selects new session`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = emptyList()
            fakeApi.createSessionResponse = com.catmoecircle.areyouok.gateway.protocol.CreateSessionResponse(
                ok = true,
                sessionId = "s-new",
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val sessionId = repository.createSession()
        advanceUntilIdle()

        assertEquals("s-new", sessionId)
        assertEquals("s-new", repository.selectedSessionId.value)
        assertTrue(repository.sessions.value.any { it.id == "s-new" })
        testScope.cancel()
    }

    @Test
    fun `new chat without workspace lands in the default workspace`() = runTest {
        // 侧边栏底部「新对话」没有显式项目：应落到 DSH 默认工作区，
        // 而不是先在「聊天」里冒出来再跳走。
        val transport = FakeTransport().apply {
            fakeApi.workspaces = listOf(
                Workspace(
                    workspaceId = "w-default",
                    title = Workspace.DEFAULT_WORKSPACE_TITLE,
                    path = "D:/Documents/deepseek-harness/default-workspace",
                ),
            )
            fakeApi.sessions = emptyList()
            fakeApi.createSessionResponse = com.catmoecircle.areyouok.gateway.protocol.CreateSessionResponse(
                ok = true,
                sessionId = "s-new",
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val sessionId = repository.createSession()
        advanceUntilIdle()

        assertEquals("s-new", sessionId)
        assertEquals("w-default", transport.fakeApi.createdSessions.single().first)
        assertEquals("w-default", repository.sessions.value.single().workspaceId)
        testScope.cancel()
    }

    @Test
    fun `blank sessions are hidden unless they are the current one`() = runTest {
        // DSH 每次「新建会话但不发消息」都会留下一条 blank 会话；桌面端只显示当前那一个。
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(
                SessionSummary(id = "session-ghost-1", blank = true),
                SessionSummary(id = "s1", title = "真实会话"),
                SessionSummary(id = "session-ghost-2", blank = true),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        assertEquals(listOf("s1"), repository.sessions.value.map { it.id })
        assertEquals("s1", repository.selectedSessionId.value)
        testScope.cancel()
    }

    @Test
    fun `new blank chat is visible while selected and hidden after switching away`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.createSessionResponse = com.catmoecircle.areyouok.gateway.protocol.CreateSessionResponse(
                ok = true,
                sessionId = "s-new",
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val sessionId = repository.createSession()
        advanceUntilIdle()

        // 刚建好的空会话是当前会话，所以能看到。
        assertEquals("s-new", sessionId)
        assertEquals("s-new", repository.selectedSessionId.value)
        assertTrue(repository.sessions.value.any { it.id == "s-new" })

        // 切走以后它又变回「空白会话」，从侧边栏隐藏。
        repository.selectSession("s1")
        advanceUntilIdle()
        assertEquals(listOf("s1"), repository.sessions.value.map { it.id })
        testScope.cancel()
    }

    @Test
    fun `create session inside a project sends that workspace id`() = runTest {
        // 侧边栏项目行右侧的「新对话」：新会话必须直接落在该项目里。
        val transport = FakeTransport().apply {
            fakeApi.sessions = emptyList()
            fakeApi.createSessionResponse = com.catmoecircle.areyouok.gateway.protocol.CreateSessionResponse(
                ok = true,
                sessionId = "s-proj",
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val sessionId = repository.createSession(workspaceId = "w1")
        advanceUntilIdle()

        assertEquals("s-proj", sessionId)
        assertEquals("w1", transport.fakeApi.createdSessions.single().first)
        assertEquals("w1", repository.sessions.value.single().workspaceId)
        // 网关的 workspace 成员表还没追上时，也要先归到该项目（而不是「聊天」）。
        val projects = SessionProjectGrouping.group(
            listOf(Workspace(workspaceId = "w1", title = "proj", path = "E:/proj")),
            repository.sessions.value,
        )
        assertEquals(listOf("s-proj"), projects.single().sessions.map { it.id })
        testScope.cancel()
    }

    @Test
    fun `bootstrap failure goes to error phase`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.error = GatewayException("unauthenticated", 401, "no token")
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ERROR, repository.connection.value.phase)
        assertNotNull(repository.connection.value.error)
        testScope.cancel()
    }

    @Test
    fun `disconnect returns to idle and shuts transport down`() = runTest {
        val transport = FakeTransport()
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        repository.disconnect()

        assertEquals(ConnectionPhase.IDLE, repository.connection.value.phase)
        assertTrue(transport.shutdownCalls >= 1)
        testScope.cancel()
    }

    @Test
    fun `resync without gap replays events from rest`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.eventsPage = EventsPage(
                ok = true,
                seq = 55,
                gap = false,
                events = listOf(
                    com.catmoecircle.areyouok.gateway.protocol.ServerEvent(
                        id = "ev-50",
                        seq = 50,
                        kind = "message.delta",
                        data = buildJsonObject {
                            put("sessionId", "s1")
                            put("text", "补发")
                        },
                    ),
                ),
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emit(StreamUpdate.Resynced("gap"))
        advanceUntilIdle()

        assertEquals("补发", repository.messages.value.last().text)
        testScope.cancel()
    }

    @Test
    fun `health forbidden on public network still connects`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.health = HealthResponse(ok = false, name = null, lan = false)
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        // 让 health 抛 403
        val failing = object : com.catmoecircle.areyouok.gateway.transport.GatewayApi by transport.fakeApi {
            override suspend fun health(): HealthResponse =
                throw GatewayException("forbidden_source", 403, "public")
        }
        val transport403 = object : com.catmoecircle.areyouok.gateway.transport.GatewayTransport by transport {
            override val api: com.catmoecircle.areyouok.gateway.transport.GatewayApi get() = failing
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport403, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.CONNECTED, repository.connection.value.phase)
        assertEquals("s1", repository.selectedSessionId.value)
        testScope.cancel()
    }

    // ---------------------------------------------------- 模型目录 / 权限目录

    /** 实测形状的最小模型目录：opencode-go / deepseek-v4.1-flash + 三档思考强度。 */
    private fun catalog(): ModelCatalog = ModelCatalog(
        default = ModelSelection("opencode-go", "deepseek-v4.1-flash", "max"),
        routableProviders = listOf("opencode-go"),
        groups = listOf(
            ModelGroup(
                id = "opencode-go",
                name = "opencode-go",
                models = listOf(
                    ModelInfo(
                        id = "deepseek-v4.1-flash",
                        name = "DeepSeek V4.1 Flash",
                        reasoning = ModelReasoning(
                            efforts = listOf(
                                ReasoningEffort("low", "Low"),
                                ReasoningEffort("high", "High"),
                                ReasoningEffort("max", "Max"),
                            ),
                            defaultEffort = "high",
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun permissionCatalog(): PermissionCatalog = PermissionCatalog(
        options = listOf(
            PermissionOption("read-only", "只读"),
            PermissionOption("workspace-write", "工作区写入"),
            PermissionOption("danger-full-access", "完全访问"),
        ),
        defaultOptions = listOf(PermissionOption("workspace-write", "工作区写入")),
        defaultPreset = "workspace-write",
    )

    /** 带完整投影的会话：title / modelSelection(lastUsed+next) / permissions。 */
    private fun sessionWithProjection(): SessionSummary = SessionSummary(
        id = "s1",
        projections = SessionProjections(
            kind = "sequenced",
            asOfSeq = 10,
            values = SessionProjectionValues(
                title = "会话一",
                modelSelection = SessionModelSelection(
                    lastUsed = ModelSelection("opencode-go", "deepseek-v4.1-flash", "high"),
                    next = ModelSelection("opencode-go", "deepseek-v4.1-flash", "high"),
                ),
                permissions = SessionPermissions(currentValue = "workspace-write"),
            ),
        ),
    )

    /** 在后台收集 notices（SharedFlow 没有 replay，必须在动作前开始收集）。 */
    private fun TestScope.watchNotices(repository: GatewayRepository): List<GatewayNotice> {
        val notices = mutableListOf<GatewayNotice>()
        // 必须用 UnconfinedTestDispatcher 让它立刻订阅上：普通 backgroundScope 任务
        // 在本版本 coroutines-test 里要等测试体结束才跑，tryEmit 会直接丢事件。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.notices.collect { notices += it }
        }
        return notices
    }

    @Test
    fun `connect loads model and permission catalogs`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
            fakeApi.modelCatalog = catalog()
            fakeApi.permissionCatalog = permissionCatalog()
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        assertEquals("opencode-go", repository.modelCatalog.value.default?.provider)
        assertEquals(listOf("low", "high", "max"), repository.modelCatalog.value.models.single().efforts.map { it.id })
        assertEquals(3, repository.permissionCatalog.value.options.size)
        assertEquals("workspace-write", repository.permissionCatalog.value.defaultPreset)
        // 目录失败不能拖垮连接：catalog 为空时仍然是 CONNECTED。
        assertEquals(ConnectionPhase.CONNECTED, repository.connection.value.phase)
        testScope.cancel()
    }

    @Test
    fun `select model optimistically updates next projection and keeps other fields`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(sessionWithProjection())
            fakeApi.modelCatalog = catalog()
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val ok = repository.selectModel("opencode-go", "deepseek-v4.1-flash", "max")
        advanceUntilIdle()

        assertTrue(ok)
        val call = transport.fakeApi.selectedModels.single()
        assertEquals("s1", call.sessionId)
        assertEquals("opencode-go", call.provider)
        assertEquals("deepseek-v4.1-flash", call.model)
        assertEquals("max", call.reasoningEffort)

        val session = repository.sessions.value.single()
        // 乐观更新：next 立即变成新选择，lastUsed / permissions / title 原样保留。
        assertEquals("max", session.modelSelection?.reasoningEffort)
        assertEquals("high", session.projections?.values?.modelSelection?.lastUsed?.reasoningEffort)
        assertEquals("workspace-write", session.permissionPreset)
        assertEquals("会话一", session.displayTitle)
        testScope.cancel()
    }

    @Test
    fun `select model without session returns false with warning`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = emptyList()
            fakeApi.modelCatalog = catalog()
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        val notices = watchNotices(repository)

        val ok = repository.selectModel("opencode-go", "deepseek-v4.1-flash", "max")
        advanceUntilIdle()

        assertFalse(ok)
        assertTrue(transport.fakeApi.selectedModels.isEmpty())
        assertTrue(notices.any { it.level == NoticeLevel.WARN && it.text.contains("请先选择会话") })
        testScope.cancel()
    }

    @Test
    fun `select model rejects empty or unroutable catalogs`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(sessionWithProjection())
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        val notices = watchNotices(repository)

        // 目录为空（网关未实现 / 拉取失败）：不盲发请求。
        assertFalse(repository.selectModel("opencode-go", "deepseek-v4.1-flash", "max"))
        advanceUntilIdle()
        assertTrue(transport.fakeApi.selectedModels.isEmpty())

        // 目录加载成功但 provider 不可路由：同样拒绝。
        transport.fakeApi.modelCatalog = catalog()
        repository.refresh()
        advanceUntilIdle()
        assertFalse(repository.selectModel("not-installed", "x", null))
        advanceUntilIdle()
        assertTrue(transport.fakeApi.selectedModels.isEmpty())
        assertTrue(notices.any { it.text.contains("暂无可路由") })
        assertTrue(notices.any { it.text.contains("不支持该模型提供方") })
        testScope.cancel()
    }

    @Test
    fun `failed select model keeps projection and emits warning`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(sessionWithProjection())
            fakeApi.modelCatalog = catalog()
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        val notices = watchNotices(repository)

        transport.fakeApi.error = GatewayException("dsh_unavailable", 503, "down")
        val ok = repository.selectModel("opencode-go", "deepseek-v4.1-flash", "max")
        advanceUntilIdle()

        assertFalse(ok)
        assertEquals("high", repository.sessions.value.single().modelSelection?.reasoningEffort)
        assertTrue(notices.any { it.level == NoticeLevel.WARN && it.text.contains("切换模型失败") })
        testScope.cancel()
    }

    @Test
    fun `select permission optimistically updates current value`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(sessionWithProjection())
            fakeApi.modelCatalog = catalog()
            fakeApi.permissionCatalog = permissionCatalog()
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        val ok = repository.selectPermission("danger-full-access")
        advanceUntilIdle()

        assertTrue(ok)
        assertEquals("s1" to "danger-full-access", transport.fakeApi.selectedPermissions.single())
        val session = repository.sessions.value.single()
        assertEquals("danger-full-access", session.permissionPreset)
        // 模型选择与标题不受权限切换影响。
        assertEquals("high", session.modelSelection?.reasoningEffort)
        assertEquals("会话一", session.displayTitle)
        testScope.cancel()
    }

    @Test
    fun `select permission without session or on failure keeps state`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = emptyList()
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()
        val notices = watchNotices(repository)

        assertFalse(repository.selectPermission("danger-full-access"))
        advanceUntilIdle()
        assertTrue(transport.fakeApi.selectedPermissions.isEmpty())
        assertTrue(notices.any { it.text.contains("请先选择会话") })

        repository.selectSession(null)
        transport.fakeApi.sessions = listOf(sessionWithProjection())
        repository.refresh()
        advanceUntilIdle()
        // 选中会话后再模拟网关失败：本地投影保持原值。
        transport.fakeApi.error = GatewayException("dsh_unavailable", 503, "down")
        assertFalse(repository.selectPermission("read-only"))
        advanceUntilIdle()
        assertEquals("workspace-write", repository.sessions.value.single().permissionPreset)
        assertTrue(notices.any { it.level == NoticeLevel.WARN && it.text.contains("切换权限失败") })
        testScope.cancel()
    }

    @Test
    fun `loaded history keeps a paging cursor and prepends older pages`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "长会话"))
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(
                    Message(seq = 300, role = "user", text = "最近一条"),
                    Message(seq = 301, role = "assistant", text = "回复"),
                ),
                hasMore = true,
            )
            fakeApi.olderMessagesPages[300] = MessagesPage(
                messages = listOf(
                    Message(seq = 100, role = "user", text = "我很久以前发的话"),
                    Message(seq = 101, role = "assistant", text = "早先的回复"),
                ),
                hasMore = false,
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        assertTrue(repository.hasOlderMessages.value)
        assertEquals(listOf("最近一条", "回复"), repository.messages.value.map { it.text })

        repository.loadOlderMessages()
        advanceUntilIdle()

        // 更早的一页插到头部，用户自己更早的消息因此可见。
        assertEquals(
            listOf("我很久以前发的话", "早先的回复", "最近一条", "回复"),
            repository.messages.value.map { it.text },
        )
        assertFalse(repository.hasOlderMessages.value)
        assertEquals(300L to 100, transport.fakeApi.messageRequests.last())
        testScope.cancel()
    }

    @Test
    fun `opening a session prefetches the whole last turn until a user boundary`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "长回合"))
            // 首屏只拿到回合末尾（助手汇报），前面的过程行（含中间正文）靠静默翻页补齐。
            fakeApi.messagesPage = MessagesPage(
                messages = listOf(Message(seq = 300, role = "assistant", text = "最终汇报")),
                hasMore = true,
            )
            fakeApi.olderMessagesPages[300] = MessagesPage(
                messages = listOf(
                    Message(seq = 200, role = "assistant", text = "过程中的正文"),
                    Message(seq = 201, role = "tool", text = "edit", kind = "tool.call"),
                ),
                hasMore = true,
            )
            fakeApi.olderMessagesPages[200] = MessagesPage(
                messages = listOf(Message(seq = 100, role = "user", text = "开始")),
                hasMore = false,
            )
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        // 打开会话就补到 user 边界：过程中的正文不会因为分页而缺失。
        assertEquals(
            listOf("开始", "过程中的正文", "最终汇报"),
            repository.messages.value.filter { it.text.isNotBlank() }.map { it.text },
        )
        assertEquals(3, transport.fakeApi.messageRequests.size)
        assertEquals(200L to 100, transport.fakeApi.messageRequests.last())
        assertFalse(repository.hasOlderMessages.value)
        testScope.cancel()
    }

    @Test
    fun `background job notice stays out of the timeline like the desktop client`() = runTest {
        val transport = FakeTransport().apply {
            fakeApi.sessions = listOf(SessionSummary(id = "s1", title = "T"))
        }
        val testScope = scope(StandardTestDispatcher(testScheduler))
        val repository = GatewayRepository(transport, testScope)
        repository.connect(endpoint)
        advanceUntilIdle()

        transport.emitEvent(
            seq = 90,
            kind = "message.user",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("seq", 91)
                put("role", "user")
                put("text", "background job pwsh-1 (pwsh: adb shell screenrecord …) finished [status: completed, exit code: 0]. Output:\ndone")
                put(
                    "source",
                    buildJsonObject {
                        put("kind", "tool-jobs")
                        put("form", "notice")
                    },
                )
            },
        )
        advanceUntilIdle()

        // 后台任务完成通知是上下文注入：不画成系统行，也不冒充用户消息。
        assertEquals(0, repository.messages.value.size)

        // 真人消息（source.kind=user）仍然进用户气泡。
        transport.emitEvent(
            seq = 92,
            kind = "message.user",
            data = buildJsonObject {
                put("sessionId", "s1")
                put("seq", 93)
                put("role", "user")
                put("text", "不是有实机吗？你用真机调试啊")
                put("source", buildJsonObject { put("kind", "user") })
            },
        )
        advanceUntilIdle()

        assertEquals(ChatRole.USER, repository.messages.value.last().role)
        assertEquals("不是有实机吗？你用真机调试啊", repository.messages.value.last().text)
        testScope.cancel()
    }
}
