package com.catmoecircle.areyouok.gateway

import com.catmoecircle.areyouok.gateway.protocol.CreateSessionResponse
import com.catmoecircle.areyouok.gateway.protocol.EventsPage
import com.catmoecircle.areyouok.gateway.protocol.HealthResponse
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.MessagesPage
import com.catmoecircle.areyouok.gateway.protocol.ModelCatalog
import com.catmoecircle.areyouok.gateway.protocol.OkResponse
import com.catmoecircle.areyouok.gateway.protocol.PairResponse
import com.catmoecircle.areyouok.gateway.protocol.PermissionCatalog
import com.catmoecircle.areyouok.gateway.protocol.PermissionResult
import com.catmoecircle.areyouok.gateway.protocol.SetTeamEnabledResponse
import com.catmoecircle.areyouok.gateway.protocol.SessionState
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Topics
import com.catmoecircle.areyouok.gateway.protocol.TranslateResponse
import com.catmoecircle.areyouok.gateway.protocol.TranslationProviderInfo
import com.catmoecircle.areyouok.gateway.protocol.TranslationProvidersResponse
import com.catmoecircle.areyouok.gateway.protocol.EventFrame
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import com.catmoecircle.areyouok.gateway.transport.GatewayApi
import com.catmoecircle.areyouok.gateway.transport.GatewayTransport
import com.catmoecircle.areyouok.gateway.transport.StreamUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 可编程的假 REST 客户端，供 repository 状态机单测使用。 */
class FakeGatewayApi : GatewayApi {

    var health = HealthResponse(ok = true, name = "FY-PC", v = 1, dsh = "0.2.2-rc.1", lan = true)
    var workspaces = listOf(Workspace(id = "ws1", name = "demo"))
    var sessions = listOf(SessionSummary(id = "s1", title = "会话一"))
    var messagesPage = MessagesPage(messages = emptyList())
    /** 按 `beforeSeq` 区分的更早历史页（分页测试用）；没有命中时回落到 [messagesPage]。 */
    var olderMessagesPages = mutableMapOf<Long?, MessagesPage>()
    /** 每次 `GET /v1/sessions/{id}/messages` 的入参：(beforeSeq, limit)。 */
    val messageRequests = mutableListOf<Pair<Long?, Int?>>()
    var interventions = emptyList<Intervention>()
    var modelCatalog = ModelCatalog()
    var permissionCatalog = PermissionCatalog()
    var selectModelResponse = OkResponse(ok = true)
    var selectPermissionResult = PermissionResult(ok = true)
    var setTeamEnabledResponse = SetTeamEnabledResponse(ok = true, enabled = null)
    var eventsPage = EventsPage(events = emptyList())
    var createSessionResponse = CreateSessionResponse(ok = true, sessionId = "s-new")
    var pairResponse = PairResponse(ok = true, deviceToken = "tok-123")
    var error: Throwable? = null

    /** 只在 `POST /v1/translate` 时抛的失败注入（不影响 providers 探测）。 */
    var translationError: Throwable? = null

    /** 只在 `GET /v1/translate/providers` 时抛的失败注入。 */
    var translationProvidersError: Throwable? = null

    /**
     * 可编程的翻译应答：默认按「[目标语言] 原文」回；测试可替换成计数/挂起实现
     * （分段顺序、加载态观察都靠它）。
     */
    var translateHandler: suspend (
        text: String,
        targetLanguage: String,
        sourceLanguage: String?,
        provider: String?,
    ) -> TranslateResponse = { text, targetLanguage, sourceLanguage, provider ->
        TranslateResponse(
            text = "[$targetLanguage]$text",
            provider = provider ?: "bing",
            targetLanguage = targetLanguage,
            sourceLanguage = sourceLanguage ?: "auto",
            chars = text.length,
        )
    }

    /** `GET /v1/translate/providers` 的应答（默认与网关契约一致）。 */
    var translationProvidersResponse = TranslationProvidersResponse(
        providers = listOf(
            TranslationProviderInfo(id = "bing", label = "Bing", free = true),
            TranslationProviderInfo(id = "google", label = "Google", free = true),
        ),
        defaultProvider = "bing",
        defaultTargetLanguage = "zh-CN",
        maxTextChars = TranslationProvidersResponse.DEFAULT_MAX_TEXT_CHARS,
        targets = listOf("zh-CN", "zh-TW", "en", "ja"),
    )

    /** 每次 `POST /v1/translate` 的入参（保序）。 */
    val translationRequests = mutableListOf<TranslationRequestRecord>()

    val sentMessages = mutableListOf<Triple<String, String, String>>()
    /** 每次 `POST /v1/sessions/{id}/messages` 带上的文件回执 id。 */
    val sentFiles = mutableListOf<List<String>>()
    val answered = mutableListOf<Pair<String, JsonElement>>()
    val interruptedSessions = mutableListOf<String>()
    /** 每次 `POST /v1/sessions` 的入参：(workspaceId, cwd, title)。 */
    val createdSessions = mutableListOf<Triple<String?, String?, String?>>()
    /** 每次 `POST /v1/sessions/{id}/model` 的入参。 */
    val selectedModels = mutableListOf<SelectedModel>()
    /** 每次 `POST /v1/sessions/{id}/permission` 的入参：(sessionId, preset)。 */
    val selectedPermissions = mutableListOf<Pair<String, String>>()
    /** 每次 `POST /v1/sessions/{id}/team` 的入参：(sessionId, enabled)。 */
    val teamRequests = mutableListOf<Pair<String, Boolean>>()

    override suspend fun health(): HealthResponse {
        maybeThrow()
        return health
    }

    override suspend fun workspaces(): List<Workspace> {
        maybeThrow()
        return workspaces
    }

    override suspend fun sessions(workspaceId: String?): List<SessionSummary> {
        maybeThrow()
        return sessions
    }

    override suspend fun createSession(
        workspaceId: String?,
        cwd: String?,
        title: String?,
    ): CreateSessionResponse {
        maybeThrow()
        createdSessions += Triple(workspaceId, cwd, title)
        return createSessionResponse
    }

    override suspend fun sessionState(sessionId: String): SessionState {
        maybeThrow()
        return SessionState(sessionId = sessionId, running = false)
    }

    override suspend fun messages(
        sessionId: String,
        beforeSeq: Long?,
        limit: Int?,
    ): MessagesPage {
        maybeThrow()
        messageRequests += beforeSeq to limit
        return olderMessagesPages[beforeSeq] ?: messagesPage
    }

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        mode: String,
        files: List<String>,
    ): OkResponse {
        maybeThrow()
        sentMessages += Triple(sessionId, text, mode)
        sentFiles += files.toList()
        return OkResponse(ok = true, sessionId = sessionId, accepted = true)
    }

    override suspend fun interrupt(sessionId: String): OkResponse {
        maybeThrow()
        interruptedSessions += sessionId
        return OkResponse()
    }

    override suspend fun models(): ModelCatalog {
        maybeThrow()
        return modelCatalog
    }

    override suspend fun permissionCatalog(): PermissionCatalog {
        maybeThrow()
        return permissionCatalog
    }

    override suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): OkResponse {
        maybeThrow()
        selectedModels += SelectedModel(sessionId, provider, model, reasoningEffort)
        return selectModelResponse
    }

    override suspend fun selectPermission(sessionId: String, preset: String): PermissionResult {
        maybeThrow()
        selectedPermissions += sessionId to preset
        return selectPermissionResult
    }

    override suspend fun setTeamEnabled(
        sessionId: String,
        enabled: Boolean,
    ): SetTeamEnabledResponse {
        maybeThrow()
        teamRequests += sessionId to enabled
        // 默认回显请求值：契约要求 `enabled` 与请求一致才算成功；
        // 测试想模拟「回显不一致」时可以显式设置 setTeamEnabledResponse.enabled。
        val response = setTeamEnabledResponse
        return response.copy(
            sessionId = response.sessionId ?: sessionId,
            enabled = response.enabled ?: enabled,
        )
    }

    override suspend fun interventions(status: String): List<Intervention> {
        maybeThrow()
        return interventions
    }

    override suspend fun answerIntervention(eventId: String, value: JsonElement): OkResponse {
        maybeThrow()
        answered += eventId to value
        return OkResponse()
    }

    override suspend fun events(sinceSeq: Long?): EventsPage {
        maybeThrow()
        return eventsPage
    }

    override suspend fun probeHealth(baseUrl: String): HealthResponse {
        maybeThrow()
        return health
    }

    override suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        deviceId: String,
    ): PairResponse {
        maybeThrow()
        return pairResponse
    }

    override suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String?,
        provider: String?,
    ): TranslateResponse {
        maybeThrow()
        translationError?.let { throw it }
        translationRequests += TranslationRequestRecord(text, targetLanguage, sourceLanguage, provider)
        return translateHandler(text, targetLanguage, sourceLanguage, provider)
    }

    override suspend fun translationProviders(): TranslationProvidersResponse {
        maybeThrow()
        translationProvidersError?.let { throw it }
        return translationProvidersResponse
    }

    private fun maybeThrow() {
        error?.let { throw it }
    }
}

/** 一次 `POST /v1/translate` 的入参记录。 */
data class TranslationRequestRecord(
    val text: String,
    val targetLanguage: String,
    val sourceLanguage: String?,
    val provider: String?,
)

/** 一次 `POST /v1/sessions/{id}/model` 的入参记录。 */
data class SelectedModel(
    val sessionId: String,
    val provider: String,
    val model: String,
    val reasoningEffort: String?,
)

/** 可手动推帧的假传输层。 */
class FakeTransport : GatewayTransport {

    val fakeApi = FakeGatewayApi()
    override val api: GatewayApi get() = fakeApi

    private val updates = MutableSharedFlow<StreamUpdate>(extraBufferCapacity = 128)

    val subscriptions = mutableSetOf<String>()
    var shutdownCalls = 0
    var initialResumeSeq: Long? = null

    fun emit(update: StreamUpdate) {
        updates.tryEmit(update)
    }

    fun emitEvent(
        seq: Long,
        kind: String,
        data: JsonObject,
        id: String? = null,
        topic: String? = null,
        ts: Long? = null,
    ) {
        emit(
            StreamUpdate.Event(
                EventFrame(
                    seq = seq,
                    id = id ?: "ev-$seq",
                    topic = topic,
                    kind = kind,
                    ts = ts,
                    data = data,
                ),
            ),
        )
    }

    fun sessionDelta(seq: Long, sessionId: String, text: String) {
        emitEvent(
            seq = seq,
            kind = "message.delta",
            data = buildJsonObject {
                put("sessionId", sessionId)
                put("text", text)
            },
        )
    }

    override fun stream(initialResumeSeq: Long): Flow<StreamUpdate> {
        this.initialResumeSeq = initialResumeSeq
        return updates
    }

    override fun subscribe(topic: String) {
        subscriptions += topic
    }

    override fun unsubscribe(topic: String) {
        subscriptions -= topic
    }

    override fun shutdown() {
        shutdownCalls++
    }

    fun sessionTopic(sessionId: String): String = Topics.session(sessionId)
}
