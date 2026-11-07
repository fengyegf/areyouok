package com.catmoecircle.areyouok.gateway.transport

import com.catmoecircle.areyouok.gateway.protocol.AnswerRequest
import com.catmoecircle.areyouok.gateway.protocol.CreateSessionRequest
import com.catmoecircle.areyouok.gateway.protocol.CreateSessionResponse
import com.catmoecircle.areyouok.gateway.protocol.ErrorBody
import com.catmoecircle.areyouok.gateway.protocol.EventsPage
import com.catmoecircle.areyouok.gateway.protocol.GatewayJson
import com.catmoecircle.areyouok.gateway.protocol.HealthResponse
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessagesPage
import com.catmoecircle.areyouok.gateway.protocol.ModelCatalog
import com.catmoecircle.areyouok.gateway.protocol.ModelsResponse
import com.catmoecircle.areyouok.gateway.protocol.OkResponse
import com.catmoecircle.areyouok.gateway.protocol.PairRequest
import com.catmoecircle.areyouok.gateway.protocol.PairResponse
import com.catmoecircle.areyouok.gateway.protocol.PermissionCatalog
import com.catmoecircle.areyouok.gateway.protocol.PermissionCatalogResponse
import com.catmoecircle.areyouok.gateway.protocol.PermissionResult
import com.catmoecircle.areyouok.gateway.protocol.SelectModelRequest
import com.catmoecircle.areyouok.gateway.protocol.SelectPermissionRequest
import com.catmoecircle.areyouok.gateway.protocol.SendMessageRequest
import com.catmoecircle.areyouok.gateway.protocol.SessionState
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp 实现的 `/v1` REST 客户端。
 *
 * Bearer 令牌由 [endpointProvider] 每次请求时提供（配对后热更新，不重建客户端）。
 * 网络 IO 统一切到 [Dispatchers.IO]，失败时把服务端错误信封翻译成 [GatewayException]。
 */
class OkHttpGatewayApi(
    private val client: OkHttpClient,
    private val endpointProvider: () -> GatewayEndpoint?,
    private val json: Json = GatewayJson.json,
) : GatewayApi {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------ GatewayApi

    override suspend fun health(): HealthResponse = healthFor(endpoint().baseUrl)

    override suspend fun probeHealth(baseUrl: String): HealthResponse = healthFor(baseUrl)

    override suspend fun workspaces(): List<Workspace> =
        parseList<Workspace>(get("/workspaces"), LIST_KEYS)

    override suspend fun sessions(workspaceId: String?): List<SessionSummary> {
        val query = if (workspaceId.isNullOrBlank()) emptyMap() else mapOf("workspaceId" to workspaceId)
        return parseList<SessionSummary>(get("/sessions", query), LIST_KEYS)
    }

    override suspend fun createSession(
        workspaceId: String?,
        cwd: String?,
        title: String?,
    ): CreateSessionResponse {
        val body = CreateSessionRequest(workspaceId = workspaceId, cwd = cwd, title = title)
        val text = post(
            "/sessions",
            json.encodeToString(CreateSessionRequest.serializer(), body),
        )
        return parseCreatedSession(text)
    }

    override suspend fun sessionState(sessionId: String): SessionState {
        val text = get("/sessions/${pathSegment(sessionId)}/state")
        return decodeOrThrow<SessionState>(text)
    }

    override suspend fun messages(
        sessionId: String,
        beforeSeq: Long?,
        limit: Int?,
    ): MessagesPage {
        val query = buildMap {
            beforeSeq?.let { put("beforeSeq", it.toString()) }
            limit?.let { put("limit", it.toString()) }
        }
        val text = get("/sessions/${pathSegment(sessionId)}/messages", query)
        return runCatching { decodeOrThrow<MessagesPage>(text) }
            .getOrElse { MessagesPage(messages = parseList<Message>(text, LIST_KEYS)) }
    }

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        mode: String,
    ): OkResponse {
        val body = SendMessageRequest(text = text, mode = mode)
        val response = post(
            "/sessions/${pathSegment(sessionId)}/messages",
            json.encodeToString(SendMessageRequest.serializer(), body),
        )
        return runCatching { decodeOrThrow<OkResponse>(response) }.getOrElse { OkResponse() }
    }

    override suspend fun interrupt(sessionId: String): OkResponse {
        val response = post("/sessions/${pathSegment(sessionId)}/interrupt", "{}")
        return runCatching { decodeOrThrow<OkResponse>(response) }.getOrElse { OkResponse() }
    }

    override suspend fun models(): ModelCatalog {
        val text = get("/models")
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return ModelCatalog()
        val response = when (root) {
            // 更早的网关直接返回裸数组（`[{…}]`）或 `{models:[…]}`/`{providers:[…]}`。
            is JsonArray -> ModelsResponse(ok = true, catalog = root)
            is JsonObject -> runCatching {
                json.decodeFromJsonElement(ModelsResponse.serializer(), root)
            }.getOrNull() ?: return ModelCatalog()
            else -> return ModelCatalog()
        }
        if (!response.ok) return ModelCatalog()
        // 解析失败（DSH 升级导致形状变化等）时给空目录，UI 隐藏模型入口而不是崩溃。
        return runCatching { response.catalog() }.getOrElse { ModelCatalog() }
    }

    override suspend fun permissionCatalog(): PermissionCatalog {
        val text = get("/permissions")
        val response = decodeOrThrow<PermissionCatalogResponse>(text)
        throwIfNotOk(response.ok, text, "权限目录不可用")
        return response.catalog ?: PermissionCatalog()
    }

    override suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): OkResponse {
        val body = SelectModelRequest(
            provider = provider,
            model = model,
            reasoningEffort = reasoningEffort,
        )
        val text = post(
            "/sessions/${pathSegment(sessionId)}/model",
            json.encodeToString(SelectModelRequest.serializer(), body),
        )
        // 成功响应是 `{"ok":true}`；个别网关回空体，按成功处理（与 interrupt 一致）。
        val response = runCatching { decodeOrThrow<OkResponse>(text) }.getOrNull()
        throwIfNotOk(response?.ok ?: true, text, "模型切换失败")
        return response ?: OkResponse()
    }

    override suspend fun selectPermission(sessionId: String, preset: String): PermissionResult {
        val body = SelectPermissionRequest(preset = preset)
        val text = post(
            "/sessions/${pathSegment(sessionId)}/permission",
            json.encodeToString(SelectPermissionRequest.serializer(), body),
        )
        val result = decodeOrThrow<PermissionResult>(text)
        throwIfNotOk(result.ok, text, "权限切换失败")
        return result
    }

    override suspend fun interventions(status: String): List<Intervention> {
        val query = if (status.isBlank()) emptyMap() else mapOf("status" to status)
        return parseList<Intervention>(get("/interventions", query), LIST_KEYS)
    }

    override suspend fun answerIntervention(eventId: String, value: JsonElement): OkResponse {
        val body = AnswerRequest(value = value)
        val response = post(
            "/interventions/${pathSegment(eventId)}/answer",
            json.encodeToString(AnswerRequest.serializer(), body),
        )
        return runCatching { decodeOrThrow<OkResponse>(response) }.getOrElse { OkResponse() }
    }

    override suspend fun events(sinceSeq: Long?): EventsPage {
        val query = buildMap { sinceSeq?.let { put("sinceSeq", it.toString()) } }
        val text = get("/events", query)
        return runCatching { decodeOrThrow<EventsPage>(text) }
            .getOrElse { EventsPage(events = parseList(text, LIST_KEYS)) }
    }

    override suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        deviceId: String,
    ): PairResponse {
        val normalized = GatewayEndpoint.normalizeBaseUrl(baseUrl)
            ?: throw GatewayException("invalid_request", 0, "配对地址无效")
        val body = PairRequest(code = code, deviceName = deviceName, deviceId = deviceId)
        val text = execute(
            baseUrl = normalized,
            method = "POST",
            path = "/pair",
            body = json.encodeToString(PairRequest.serializer(), body),
            token = null,
        )
        return decodeOrThrow<PairResponse>(text)
    }

    // ------------------------------------------------------------ HTTP 细节

    private fun endpoint(): GatewayEndpoint =
        endpointProvider() ?: throw GatewayException(
            GatewayException.CODE_UNAUTHENTICATED,
            0,
            "尚未选择网关",
        )

    private suspend fun healthFor(baseUrl: String): HealthResponse {
        val normalized = GatewayEndpoint.normalizeBaseUrl(baseUrl)
            ?: throw GatewayException("invalid_request", 0, "网关地址无效")
        val token = endpointProvider()?.takeIf { it.baseUrl == normalized }?.token
        val text = execute(
            baseUrl = normalized,
            method = "GET",
            path = "/health",
            body = null,
            token = token,
        )
        return decodeOrThrow<HealthResponse>(text)
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): String {
        val endpoint = endpoint()
        return execute(
            baseUrl = endpoint.baseUrl,
            method = "GET",
            path = path,
            body = null,
            token = endpoint.token,
            query = query,
        )
    }

    private suspend fun post(path: String, body: String): String {
        val endpoint = endpoint()
        return execute(
            baseUrl = endpoint.baseUrl,
            method = "POST",
            path = path,
            body = body,
            token = endpoint.token,
        )
    }

    private suspend fun execute(
        baseUrl: String,
        method: String,
        path: String,
        body: String?,
        token: String?,
        query: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val url = (baseUrl.trimEnd('/') + "/v1" + path).toHttpUrlOrNull()
            ?: throw GatewayException("invalid_request", 0, "非法 URL: $baseUrl$path")
        val urlBuilder = url.newBuilder()
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMediaType))
            "DELETE" -> builder.delete()
            else -> throw GatewayException("invalid_request", 0, "不支持的 method: $method")
        }

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            text
        }
    }

    private fun parseError(httpCode: Int, body: String): GatewayException {
        val envelope = runCatching { json.decodeFromString(ErrorBody.serializer(), body) }.getOrNull()
        val code = envelope?.error?.code?.takeIf { it.isNotBlank() }
            ?: when (httpCode) {
                400 -> GatewayException.CODE_INVALID_REQUEST
                401 -> GatewayException.CODE_UNAUTHENTICATED
                403 -> GatewayException.CODE_FORBIDDEN_SOURCE
                404 -> GatewayException.CODE_NOT_FOUND
                409 -> GatewayException.CODE_RESYNC_REQUIRED
                429 -> GatewayException.CODE_RATE_LIMITED
                503 -> GatewayException.CODE_DSH_UNAVAILABLE
                else -> "http_$httpCode"
            }
        val message = envelope?.error?.message?.takeIf { it.isNotBlank() }
            ?: body.take(240)
        return GatewayException(code, httpCode, message)
    }

    /**
     * HTTP 200 + `{ok:false,error}` 的业务失败也按 [GatewayException] 抛出。
     *
     * 网关对大部分业务错误用非 2xx（已由 [parseError] 处理），但权限切换等
     * 端点沿用 DSH 的「HTTP 200 + ok:false」风格；这里统一翻成同一个错误体系。
     */
    private fun throwIfNotOk(ok: Boolean, body: String, fallbackMessage: String) {
        if (ok) return
        val envelope = runCatching { json.decodeFromString(ErrorBody.serializer(), body) }.getOrNull()
        throw GatewayException(
            envelope?.error?.code?.takeIf { it.isNotBlank() } ?: GatewayException.CODE_INVALID_REQUEST,
            0,
            envelope?.error?.message?.takeIf { it.isNotBlank() } ?: fallbackMessage,
        )
    }

    private inline fun <reified T> decodeOrThrow(text: String): T =
        runCatching { json.decodeFromString<T>(text) }.getOrElse { error ->
            throw GatewayException(
                "invalid_response",
                0,
                "响应解析失败: ${error.message}",
                error,
            )
        }

    private inline fun <reified T> parseList(text: String, keys: List<String>): List<T> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> keys.firstNotNullOfOrNull { key -> root[key] as? JsonArray }
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
        }
    }

    private fun parseCreatedSession(text: String): CreateSessionResponse {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (root is JsonArray) {
            val session = root.firstOrNull()?.let {
                runCatching { json.decodeFromJsonElement(SessionSummary.serializer(), it) }.getOrNull()
            }
            return CreateSessionResponse(sessionId = session?.id, session = session)
        }
        val direct = runCatching { decodeOrThrow<CreateSessionResponse>(text) }.getOrNull()
        if (direct?.resolvedSessionId != null) return direct

        // 兜底：`{"session": {...}}` 之外可能出现 `{"data": {...}}` / `{"result": {...}}`。
        if (root is JsonObject) {
            val nested = listOf("session", "data", "result").firstNotNullOfOrNull { key ->
                root[key] as? JsonObject
            }
            if (nested != null) {
                val session = runCatching {
                    json.decodeFromJsonElement(SessionSummary.serializer(), nested)
                }.getOrNull()
                val id = (nested["sessionId"] as? JsonPrimitive)?.content
                    ?: (nested["id"] as? JsonPrimitive)?.content
                    ?: session?.id
                if (!id.isNullOrBlank()) return CreateSessionResponse(sessionId = id, session = session)
            }
            val id = (root["id"] as? JsonPrimitive)?.content
            if (!id.isNullOrBlank()) return CreateSessionResponse(sessionId = id)
        }
        return direct ?: CreateSessionResponse()
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val USER_AGENT = "AreYouOK-Android/1.0 (dsh-gateway)"
        private val LIST_KEYS = listOf(
            "items", "data", "list", "results",
            "workspaces", "sessions", "messages", "interventions", "pending", "models",
        )
    }
}
