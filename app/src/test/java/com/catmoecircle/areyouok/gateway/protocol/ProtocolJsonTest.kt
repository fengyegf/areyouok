package com.catmoecircle.areyouok.gateway.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议帧 / REST DTO 的序列化与反序列化。 */
class ProtocolJsonTest {

    @Test
    fun `hello frame encodes discriminator t and decodes back`() {
        val frame: StreamFrame = HelloFrame(deviceId = "dev-1", resume = ResumeState(42))
        val text = GatewayJson.encodeFrame(frame)
        assertTrue("missing discriminator: $text", text.contains("\"t\":\"hello\""))
        assertTrue(text.contains("\"deviceId\":\"dev-1\""))

        val decoded = GatewayJson.decodeFrame(text)
        assertEquals(HelloFrame(deviceId = "dev-1", resume = ResumeState(42)), decoded)
    }

    @Test
    fun `ping and ping decode`() {
        val ping = GatewayJson.decodeFrame("""{"t":"ping","ts":1700000000}""")
        assertEquals(PingFrame(1700000000), ping)
        assertEquals("""{"t":"pong","ts":7}""", GatewayJson.encodeFrame(PongFrame(7)))
    }

    @Test
    fun `ready frame carries snapshot and ignores unknown fields`() {
        val text = """
            {"t":"ready","seq":1300,"serverTime":1700000000000,"extra":"ignored",
             "snapshot":{"workspaces":[{"workspaceId":"ws1","title":"demo","unknown":1}],
                         "sessions":[{"sessionId":"s1","running":true,
                                      "projections":{"kind":"sequenced","asOfSeq":3,"values":{"title":"会话"}}}],
                         "pending":[{"eventId":"ev1","sessionId":"s1","kind":"approval","data":{"toolName":"pwsh"}}]}}
        """.trimIndent()
        val frame = GatewayJson.decodeFrame(text) as ReadyFrame
        assertEquals(1300L, frame.seq)
        // DSH 工作区主键是 workspaceId（不是 id），展示名在 title。
        assertEquals("ws1", frame.snapshot?.workspaces?.single()?.key)
        assertEquals("demo", frame.snapshot?.workspaces?.single()?.displayName)
        // DSH 会话主键是 sessionId，标题在 projections.values.title。
        val session = frame.snapshot?.sessions?.single()
        assertEquals("s1", session?.id)
        assertEquals("会话", session?.displayTitle)
        assertEquals(true, session?.running)
        assertEquals("pwsh", frame.snapshot?.pending?.single()?.toolName)
    }

    @Test
    fun `session summary decodes dsh sessionId and projection title`() {
        val summary = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"session-9","updatedAt":12,"running":false,"blank":true,"agentAvailable":true,
                "projections":{"kind":"sequenced","asOfSeq":7,"values":{"title":"网关交接"}}}""",
        )
        assertEquals("session-9", summary.id)
        assertEquals("网关交接", summary.displayTitle)
        assertEquals(false, summary.running)
    }

    @Test
    fun `blank session honours sessionListMetadata and subagent flag`() {
        val blank = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"session-abcd1234","blank":true,
                "projections":{"kind":"sequenced","asOfSeq":1,
                "values":{"title":null,"sessionListMetadata":{"blank":true,"lastPromptAt":null}}}}""",
        )
        assertTrue(blank.isBlank)
        assertTrue(!blank.isSubagent)

        // 列表侧元数据明确 blank=false 时，即使顶层 blank 还是 true 也算有内容。
        val prompted = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"s1","blank":true,
                "projections":{"values":{"sessionListMetadata":{"blank":false,"lastPromptAt":99}}}}""",
        )
        assertTrue(!prompted.isBlank)

        val subagent = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"s2","parentSessionId":"s1","origin":"subagent"}""",
        )
        assertTrue(subagent.isSubagent)
    }

    @Test
    fun `real gateway session payload decodes with projection title`() {
        // 真实 `/v1/sessions` 的投影对象会带很多 UI 不关心的键（tokenUsage/goal/...），
        // 这里验证 ignoreUnknownKeys + sessionId 映射 + title 提取同时成立。
        val text = """
            {"ok":true,"sessions":[{"sessionId":"session-d75a","updatedAt":1793976056232,
              "agentAvailable":true,"formatStatus":"current","running":true,"blank":false,
              "cwd":"E:\\Android\\areyouok",
              "projections":{"kind":"sequenced","asOfSeq":2386,"values":{
                "title":"网关交接文档并发开发",
                "userQuestions":{"active":[],"settled":[]},
                "tokenUsage":{"uncachedInputTokens":1,"outputTokens":2,"cacheReadTokens":3,"cacheWriteTokens":0},
                "goal":{"goal":{"id":"g1","status":"active"}}}}}]}
        """.trimIndent()
        val body = GatewayJson.json.parseToJsonElement(text).jsonObject
        val sessions = body["sessions"] as JsonArray
        val summary = GatewayJson.json.decodeFromJsonElement(SessionSummary.serializer(), sessions.single())
        assertEquals("session-d75a", summary.id)
        assertEquals("网关交接文档并发开发", summary.displayTitle)
        assertEquals(true, summary.running)
    }

    @Test
    fun `event frame decodes kind and data`() {
        val text = """
            {"t":"event","seq":1301,"id":"ev-9","topic":"session:s1","kind":"message.delta",
             "data":{"sessionId":"s1","text":"你"}}
        """.trimIndent()
        val frame = GatewayJson.decodeFrame(text) as EventFrame
        assertEquals(1301L, frame.seq)
        assertEquals("ev-9", frame.id)
        assertEquals(EventKind.MESSAGE_DELTA, frame.eventKind())
        assertEquals("你", frame.data.string("text"))
    }

    @Test
    fun `unknown frame type and unknown kind are tolerated`() {
        assertNull(GatewayJson.decodeFrame("""{"t":"totally-new","x":1}"""))
        assertEquals(EventKind.UNKNOWN, EventKind.fromWire("some.future.kind"))
        assertEquals(EventKind.UNKNOWN, EventKind.fromWire(null))
        assertEquals(EventKind.APPROVAL_REQUEST, EventKind.fromWire("approval.request"))
    }

    @Test
    fun `health response decodes final envelope`() {
        val health = GatewayJson.json.decodeFromString<HealthResponse>(
            """{"ok":true,"name":"FY-PC","v":1,"dsh":"0.2.2-rc.1","lan":true,"paired":false,"uptimeMs":1234,"newField":"x"}""",
        )
        assertTrue(health.ok)
        assertEquals("FY-PC", health.name)
        assertEquals(1, health.v)
        assertTrue(health.lan)
        assertEquals(1234L, health.uptimeMs)
    }

    @Test
    fun `error envelope decodes`() {
        val body = GatewayJson.json.decodeFromString<ErrorBody>(
            """{"ok":false,"error":{"code":"unauthenticated","message":"need token"}}""",
        )
        assertEquals("unauthenticated", body.error?.code)
        assertEquals("need token", body.error?.message)
    }

    @Test
    fun `messages page decodes final envelope`() {
        val page = GatewayJson.json.decodeFromString<MessagesPage>(
            """{"ok":true,"messages":[{"seq":3,"role":"assistant","text":"hi","time":1,"kind":"assistant/message"}],"cursor":3,"hasMore":false}""",
        )
        assertEquals(1, page.messages.size)
        assertEquals(3L, page.messages.first().seq)
        assertEquals("assistant", page.messages.first().role)
    }

    @Test
    fun `intervention questions are parsed lazily from data`() {
        // REST `/v1/interventions` 的 kind 是 `question.request`（不是 `question`）。
        val intervention = GatewayJson.json.decodeFromString<Intervention>(
            """{"eventId":"ev-1","sessionId":"s1","kind":"question.request","createdAt":9,
                "data":{"questions":[{"id":"q1","prompt":"选一个","options":[{"label":"A"},{"label":"B"}]}]}}""",
        )
        assertEquals("ev-1", intervention.eventId)
        assertTrue(intervention.isQuestion)
        assertTrue(!intervention.isApproval)
        assertEquals(listOf("A", "B"), intervention.questions.single().options.map { it.label })
        assertEquals("选一个", intervention.questions.single().prompt)
    }

    @Test
    fun `rest approval request kind is recognized as approval`() {
        val intervention = GatewayJson.json.decodeFromString<Intervention>(
            """{"eventId":"ev-2","sessionId":"s1","kind":"approval.request",
                "data":{"toolName":"pwsh","callId":"c1","reason":"执行命令"}}""",
        )
        assertTrue(intervention.isApproval)
        assertTrue(!intervention.isQuestion)
        assertEquals("pwsh", intervention.toolName)
    }

    @Test
    fun `intervention from flat approval event`() {
        val data = buildJsonObject {
            put("sessionId", "s1")
            put("toolName", "pwsh")
            put("callId", "call-1")
            put("reason", "执行命令")
        }
        val intervention = Intervention.fromEvent(eventId = "ev-7", sessionId = "s1", kind = "approval", data = data)
        assertEquals("ev-7", intervention.eventId)
        assertEquals("pwsh", intervention.toolName)
        assertEquals("call-1", intervention.callId)
        assertEquals("执行命令", intervention.reason)
        assertTrue(intervention.isApproval)
    }

    @Test
    fun `approval display reason prefers localized zh text`() {
        // 真实 DSH 的 request.displayReason 是多语言对象；桌面端显示的是本地化文案。
        val intervention = GatewayJson.json.decodeFromString<Intervention>(
            """{"eventId":"wf-appr","kind":"approval.request","data":{"toolName":"write",
                "reason":"escalate sandbox to workspace-write: raw",
                "request":{"toolName":"write","reason":"raw","displayReason":{
                    "en":"Allow this operation with workspace-write permissions: raw",
                    "zh":"允许本次操作使用 workspace-write 权限：raw"}}}}""",
        )
        assertEquals("允许本次操作使用 workspace-write 权限：raw", intervention.displayReason)

        val plain = GatewayJson.json.decodeFromString<Intervention>(
            """{"eventId":"wf-appr2","kind":"approval.request","data":{"toolName":"write","reason":"执行命令"}}""",
        )
        assertEquals("执行命令", plain.displayReason)
    }

    @Test
    fun `ws intervention uses waterfall event id instead of frame id`() {
        // 网关给 WS 帧分配的 id 是 `ev-<seq>`，真正能回答的是 data.eventId；
        // 用错 id 会被网关 404，桌面端弹窗不会消失。
        val data = buildJsonObject {
            put("eventId", "6f1e-waterfall")
            put("sessionId", "s1")
            put(
                "questions",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "test_interaction")
                            put("question", "功能弹窗测试")
                        },
                    )
                },
            )
        }
        val intervention = Intervention.fromEvent(
            eventId = "ev-42",
            sessionId = null,
            kind = "question",
            data = data,
        )
        assertEquals("6f1e-waterfall", intervention.eventId)
        assertTrue(intervention.isQuestion)
        assertEquals("test_interaction", intervention.questions.single().id)
    }

    @Test
    fun `dsh question item text header detail and option description decode`() {
        val intervention = GatewayJson.json.decodeFromString<Intervention>(
            """{"eventId":"wf-1","sessionId":"s1","kind":"question.request","data":{"questions":[
                {"id":"q1","header":"选项测试","question":"请选择一个方案",
                 "detail":"补充说明","options":[
                    {"label":"方案 A (Recommended)","description":"确认第一个方案"},
                    {"label":"方案 B","description":"确认多个方案"}]}]}}""",
        )
        val question = intervention.questions.single()
        assertEquals("q1", question.id)
        assertEquals("请选择一个方案", question.text)
        assertEquals("选项测试", question.header)
        assertEquals("补充说明", question.detail)
        assertEquals(listOf("方案 A (Recommended)", "方案 B"), question.options.map { it.label })
        assertEquals("确认第一个方案", question.options.first().description)
    }

    @Test
    fun `pending interventions are scoped to their session`() {
        val all = listOf(
            Intervention(eventId = "ev-a", sessionId = "s1"),
            Intervention(eventId = "ev-b", sessionId = "s2"),
            Intervention(eventId = "ev-c", sessionId = null),
        )
        // 当前对话只弹自己的审批/提问；没选中对话时不弹。
        assertEquals(listOf("ev-a"), Intervention.pendingForSession(all, "s1").map { it.eventId })
        assertEquals(listOf("ev-b"), Intervention.pendingForSession(all, "s2").map { it.eventId })
        assertEquals(emptyList<Intervention>(), Intervention.pendingForSession(all, null))
        assertEquals(emptyList<Intervention>(), Intervention.pendingForSession(all, "s3"))
        // 侧边栏用这个集合给会话行打橙色「!」（未知 sessionId 的事件不标记任何行）。
        assertEquals(setOf("s1", "s2"), Intervention.pendingSessionIds(all))
    }

    @Test
    fun `question answers encode custom reply and omit blanks`() {
        val value = InterventionAnswer.question(
            listOf(
                QuestionAnswer(id = "q1", selected = listOf("A"), custom = "补充"),
                QuestionAnswer(id = "q2", selected = listOf("B"), custom = "  "),
            ),
        )
        assertEquals(
            """{"answers":[{"id":"q1","selected":["A"],"custom":"补充"},{"id":"q2","selected":["B"]}]}""",
            value.toString(),
        )
        // 旧的 map 版本仍然可用（只有选项 label）。
        assertEquals(
            """{"answers":[{"id":"q1","selected":["A"]}]}""",
            InterventionAnswer.question(mapOf("q1" to listOf("A"))).toString(),
        )
    }

    @Test
    fun `real model catalog decodes groups efforts and default`() {
        // 抓自运行中的 DSH（GET /v1/models）真实响应，压缩成最小可行样本。
        val response = GatewayJson.json.decodeFromString<ModelsResponse>(
            """{"ok":true,"catalog":{
                "default":{"provider":"opencode-go","model":"deepseek-v4.1-flash","reasoningEffort":"max"},
                "routableProviders":["deepseek-official","opencode-go"],
                "groups":[{"id":"opencode-go","name":"opencode-go","models":[
                    {"id":"deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","description":"通用模型",
                     "reasoning":{"efforts":[
                        {"id":"low","name":"Low"},
                        {"id":"high","name":"High","description":"默认均衡"},
                        {"id":"max","name":"Max"}],"defaultEffort":"high"}}]}],
                "failures":[]}}""",
        )
        val catalog = response.catalog()
        assertEquals("opencode-go", catalog.default?.provider)
        assertEquals("deepseek-v4.1-flash", catalog.default?.model)
        assertEquals("max", catalog.default?.reasoningEffort)
        assertEquals(listOf("deepseek-official", "opencode-go"), catalog.routableProviders)
        assertEquals(1, catalog.groups.size)

        val model = catalog.models.single()
        // group 里没有 provider 字段：由 group.id 补齐，UI 才能拼出 selectModel 的入参。
        assertEquals("opencode-go", model.provider)
        assertEquals("deepseek-v4.1-flash", model.modelId)
        assertEquals("DeepSeek V4.1 Flash", model.displayName)
        assertEquals("通用模型", model.displayDescription)
        assertEquals(listOf("low", "high", "max"), model.efforts.map { it.id })
        assertEquals("High", model.efforts[1].displayName)
        assertEquals("high", model.defaultEffort)

        assertTrue(!catalog.isEmpty)
        assertTrue(catalog.canRoute("opencode-go"))
        assertTrue(!catalog.canRoute("not-installed"))
        assertEquals("deepseek-v4.1-flash", catalog.model("opencode-go", "deepseek-v4.1-flash")?.modelId)
    }

    @Test
    fun `legacy model catalog shapes still parse into groups`() {
        val fromProviders = GatewayJson.json.decodeFromString<ModelsResponse>(
            """{"ok":true,"catalog":{"providers":[{"id":"anthropic","name":"Anthropic",
                "models":[{"id":"claude","model":"claude-x","label":"Fast"}]}]}}""",
        ).catalog()
        val legacyModel = fromProviders.models.single()
        assertEquals("anthropic", legacyModel.provider)
        assertEquals("claude-x", legacyModel.modelId)
        assertEquals("Fast", legacyModel.displayName)
        assertEquals("Anthropic", fromProviders.groups.single().displayName)

        val flat = GatewayJson.json.decodeFromString<ModelsResponse>(
            """{"ok":true,"catalog":{"models":[{"id":"m1","provider":"p1","label":"Fast"}]}}""",
        ).catalog()
        assertEquals("m1", flat.models.single().modelId)
        assertEquals("p1", flat.models.single().provider)

        val bareArray = GatewayJson.json.decodeFromString<ModelsResponse>(
            """{"ok":true,"catalog":[{"id":"m2","label":"Bare"}]}""",
        ).catalog()
        assertEquals("Bare", bareArray.models.single().displayName)

        // null / 缺字段 / unknown 形状都退化成空目录，不抛。
        assertTrue(
            GatewayJson.json.decodeFromString<ModelsResponse>("""{"ok":true,"catalog":null}""").catalog().isEmpty,
        )
        assertTrue(GatewayJson.json.decodeFromString<ModelsResponse>("""{"ok":true}""").catalog().isEmpty)
        assertTrue(
            GatewayJson.json.decodeFromString<ModelsResponse>("""{"ok":true,"catalog":"oops"}""").catalog().isEmpty,
        )
    }

    @Test
    fun `permission catalog decodes options defaults and error envelope`() {
        val response = GatewayJson.json.decodeFromString<PermissionCatalogResponse>(
            """{"ok":true,"catalog":{"options":[
                {"value":"read-only","name":"只读","description":"仅允许读取"},
                {"value":"workspace-write","name":"工作区写入"},
                {"value":"danger-full-access","name":"完全访问"}],
                "defaultOptions":[{"value":"workspace-write","name":"工作区写入"}],"defaultPreset":"workspace-write"}}""",
        )
        val catalog = response.catalog!!
        assertEquals(3, catalog.options.size)
        assertEquals("只读", catalog.displayName("read-only"))
        assertEquals("unknown-preset", catalog.displayName("unknown-preset"))
        assertEquals(listOf("workspace-write"), catalog.defaultOptionValues)
        assertEquals("workspace-write", catalog.defaultPreset)
        assertEquals("danger-full-access", catalog.options.last().value)

        val failure = GatewayJson.json.decodeFromString<PermissionCatalogResponse>(
            """{"ok":false,"error":{"code":"not_found","message":"no such endpoint"}}""",
        )
        assertTrue(!failure.ok)
        assertEquals("not_found", failure.error?.code)
        assertNull(failure.catalog)
    }

    @Test
    fun `session projections decode model selection and permissions`() {
        // 真实 `/v1/sessions` 的 projections.values（实测）：modelSelection.lastUsed/next + permissions.currentValue。
        val summary = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"session-e67","projections":{"kind":"sequenced","asOfSeq":10,"values":{
                "title":"网关交接",
                "modelSelection":{
                    "lastUsed":{"provider":"opencode-go","model":"deepseek-v4.1-flash","reasoningEffort":"high"},
                    "next":{"provider":"opencode-go","model":"deepseek-v4.1-flash","reasoningEffort":"max"}},
                "permissions":{"currentValue":"danger-full-access"}}}}""",
        )
        assertEquals("网关交接", summary.displayTitle)
        // 输入框展示 next（已选未用）。
        assertEquals(ModelSelection("opencode-go", "deepseek-v4.1-flash", "max"), summary.modelSelection)
        assertEquals("danger-full-access", summary.permissionPreset)

        // 只有 lastUsed（刚启动、还没切换过）时回退 lastUsed。
        val usedOnly = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"s2","projections":{"values":{"modelSelection":
                {"lastUsed":{"provider":"p","model":"m"},"next":null}}}}""",
        )
        assertEquals("m", usedOnly.modelSelection?.model)

        // 没有投影（旧网关）时为 null，UI 用目录 default 兜底。
        val none = GatewayJson.json.decodeFromString<SessionSummary>("""{"sessionId":"s3"}""")
        assertNull(none.modelSelection)
        assertNull(none.permissionPreset)
    }

    @Test
    fun `optimistic projection copy helpers keep other fields`() {
        val summary = GatewayJson.json.decodeFromString<SessionSummary>(
            """{"sessionId":"s1","projections":{"kind":"sequenced","asOfSeq":3,"values":{
                "title":"标题","sessionListMetadata":{"blank":false},
                "modelSelection":{"lastUsed":{"provider":"p","model":"old"},
                                  "next":{"provider":"p","model":"old"}},
                "permissions":{"currentValue":"read-only"}}}}""",
        )
        val modelUpdated = summary.withModelSelection(ModelSelection("p", "new", "high"))
        assertEquals("new", modelUpdated.modelSelection?.model)
        assertEquals("high", modelUpdated.modelSelection?.reasoningEffort)
        // lastUsed 与其它投影字段都不能被乐观更新丢掉。
        assertEquals("old", modelUpdated.projections?.values?.modelSelection?.lastUsed?.model)
        assertEquals("标题", modelUpdated.displayTitle)
        assertEquals("read-only", modelUpdated.permissionPreset)
        assertEquals(3L, modelUpdated.projections?.asOfSeq)
        assertEquals(false, modelUpdated.isBlank)

        val permissionUpdated = modelUpdated.withPermissionPreset("danger-full-access")
        assertEquals("danger-full-access", permissionUpdated.permissionPreset)
        assertEquals("new", permissionUpdated.modelSelection?.model)
        assertEquals("标题", permissionUpdated.displayTitle)
    }

    @Test
    fun `pair and create session responses`() {
        val pair = GatewayJson.json.decodeFromString<PairResponse>(
            """{"ok":true,"deviceToken":"t","deviceId":"d","expiresAt":null}""",
        )
        assertEquals("t", pair.deviceToken)
        assertEquals("d", pair.deviceId)

        val created = GatewayJson.json.decodeFromString<CreateSessionResponse>(
            """{"ok":true,"sessionId":"s9","agentPreset":"default"}""",
        )
        assertEquals("s9", created.resolvedSessionId)
        assertEquals("default", created.agentPreset)
    }

    @Test
    fun `workspace display name falls back to path`() {
        val workspace = Workspace(id = "w", path = "/home/fy/demo")
        assertEquals("demo", workspace.displayName)
        assertEquals("/home/fy/demo", workspace.displayPath)
    }

    @Test
    fun `default workspace is recognized by its automatic title`() {
        val default = GatewayJson.json.decodeFromString<Workspace>(
            """{"workspaceId":"w0","title":"default-workspace","path":"D:/Documents/deepseek-harness/default-workspace","sessionIds":[]}""",
        )
        assertEquals(Workspace.DEFAULT_WORKSPACE_TITLE, default.title)
        assertTrue(default.isDefaultWorkspace)

        val project = GatewayJson.json.decodeFromString<Workspace>(
            """{"workspaceId":"w1","title":"areyouok","path":"E:/Android/areyouok"}""",
        )
        assertTrue(!project.isDefaultWorkspace)
    }

    @Test
    fun `json object helpers tolerate string numbers`() {
        val data = JsonObject(mapOf("n" to kotlinx.serialization.json.JsonPrimitive("42")))
        assertEquals(42L, data.long("n"))
        assertNull(data.string("missing"))
        assertEquals(true, JsonObject(mapOf("b" to kotlinx.serialization.json.JsonPrimitive("true"))).bool("b"))
    }

    @Test
    fun `topics helper`() {
        assertEquals("session:abc", Topics.session("abc"))
        assertEquals("abc", Topics.sessionIdOf("session:abc"))
        assertNull(Topics.sessionIdOf("workspaces"))
        assertNotNull(Intervention.fromEvent("id", null, null, null))
    }
}
