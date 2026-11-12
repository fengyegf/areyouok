package com.catmoecircle.areyouok.gateway.transport

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
import com.catmoecircle.areyouok.gateway.protocol.SessionState
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import kotlinx.serialization.json.JsonElement

/** `/v1` 返回的业务错误（对应 §6.5 的错误码）。 */
class GatewayException(
    val code: String,
    val httpStatus: Int = 0,
    private val detail: String = code,
    cause: Throwable? = null,
) : Exception(detail, cause) {

    val isUnauthenticated: Boolean get() = code == CODE_UNAUTHENTICATED || httpStatus == 401
    val isNotFound: Boolean get() = code == CODE_NOT_FOUND || httpStatus == 404
    val isForbiddenSource: Boolean get() = code == CODE_FORBIDDEN_SOURCE || httpStatus == 403
    val isRateLimited: Boolean get() = code == CODE_RATE_LIMITED || httpStatus == 429
    val isConflict: Boolean get() = code == CODE_RESYNC_REQUIRED || httpStatus == 409

    fun userMessage(): String = when {
        isUnauthenticated -> "设备未授权，请重新配对"
        isForbiddenSource -> "公网访问被拒绝（仅允许局域网）"
        isNotFound -> "资源不存在或已结束"
        isRateLimited -> "请求过于频繁，请稍后重试"
        isConflict -> "状态已变化，正在重新同步"
        code == CODE_DSH_UNAVAILABLE -> "桌面 DSH 不可用"
        else -> detail.ifBlank { code }
    }

    companion object {
        const val CODE_UNAUTHENTICATED = "unauthenticated"
        const val CODE_FORBIDDEN_SOURCE = "forbidden_source"
        const val CODE_NOT_FOUND = "not_found"
        const val CODE_INVALID_REQUEST = "invalid_request"
        const val CODE_RATE_LIMITED = "rate_limited"
        const val CODE_DSH_UNAVAILABLE = "dsh_unavailable"
        const val CODE_RESYNC_REQUIRED = "resync_required"
    }
}

/**
 * `/v1` REST 客户端接口。
 *
 * 抽象出来是为了让 repository 的状态机可以在 JVM 单测里替换成 Fake 实现。
 */
interface GatewayApi {
    suspend fun health(): HealthResponse

    suspend fun workspaces(): List<Workspace>

    suspend fun sessions(workspaceId: String? = null): List<SessionSummary>

    suspend fun createSession(
        workspaceId: String? = null,
        cwd: String? = null,
        title: String? = null,
    ): CreateSessionResponse

    suspend fun sessionState(sessionId: String): SessionState

    suspend fun messages(
        sessionId: String,
        beforeSeq: Long? = null,
        limit: Int? = null,
    ): MessagesPage

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        mode: String = "queue",
    ): OkResponse

    suspend fun interrupt(sessionId: String): OkResponse

    /** GET /v1/models：DSH 模型目录（解析失败返回空目录，不抛）。 */
    suspend fun models(): ModelCatalog

    /** GET /v1/permissions：本机沙箱权限预设目录。 */
    suspend fun permissionCatalog(): PermissionCatalog

    /**
     * POST /v1/sessions/{id}/model：切换会话**下一次 prompt** 使用的模型/思考强度。
     *
     * 对应 DSH `session/selectModel`；`reasoningEffort` 省略时由 DSH 用模型默认强度。
     */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): OkResponse

    /** POST /v1/sessions/{id}/permission：切换会话的沙箱权限预设。 */
    suspend fun selectPermission(sessionId: String, preset: String): PermissionResult

    suspend fun interventions(status: String = "pending"): List<Intervention>

    suspend fun answerIntervention(eventId: String, value: JsonElement): OkResponse

    /** GET /v1/events?sinceSeq=：WS 不可用时的补发兜底。 */
    suspend fun events(sinceSeq: Long? = null): EventsPage

    /** 局域网 health 探测：判断某个地址是不是 dsh-gateway。 */
    suspend fun probeHealth(baseUrl: String): HealthResponse

    /** 公网/局域网配对。 */
    suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        deviceId: String,
    ): PairResponse

    /**
     * 取一张图的预览字节。
     *
     * 只做「按链接预览」：网关按请求去 PC 上读文件/附件返回，客户端不落盘；
     * PC 上文件没了就返回 null（桌面端同样显示不出来）。默认不做任何事，
     * 历史/测试替身不实现图片预览也不影响其他功能。
     *
     * @param url 网关相对路径（`/v1/sessions/…`）或图片的绝对 http(s) 地址。
     */
    suspend fun fetchImage(url: String): ByteArray? = null
}

/** REST 请求体错误时的小工具：把异常转换成可展示文案。 */
fun Throwable.toUserMessage(): String = when (this) {
    is GatewayException -> userMessage()
    is java.net.UnknownHostException -> "无法解析网关地址"
    is java.net.ConnectException -> "无法连接网关，请确认同一局域网"
    is java.net.SocketTimeoutException -> "网关响应超时"
    is javax.net.ssl.SSLException -> "TLS 握手失败（证书指纹不匹配？）"
    else -> message ?: javaClass.simpleName
}
