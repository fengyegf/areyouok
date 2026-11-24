package com.catmoecircle.areyouok.gateway.transport

import com.catmoecircle.areyouok.gateway.protocol.ClientCertCaResponse
import com.catmoecircle.areyouok.gateway.protocol.ClientCertEnrollResponse
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
import com.catmoecircle.areyouok.gateway.protocol.TeamActivity
import com.catmoecircle.areyouok.gateway.protocol.TranslateResponse
import com.catmoecircle.areyouok.gateway.protocol.TranslationProvidersResponse
import com.catmoecircle.areyouok.gateway.protocol.UploadFileResponse
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

    /** 翻译请求的文本超过网关上限（HTTP 413）：分段重发前不该出现。 */
    val isTextTooLong: Boolean get() = code == CODE_TEXT_TOO_LONG || httpStatus == 413
    val isConflict: Boolean get() = code == CODE_RESYNC_REQUIRED || httpStatus == 409

    fun userMessage(): String = when {
        isUnauthenticated -> "设备未授权，请重新配对"
        isForbiddenSource -> "公网访问被拒绝（仅允许局域网）"
        isNotFound -> "资源不存在或已结束"
        isRateLimited -> "请求过于频繁，请稍后重试"
        isConflict -> "状态已变化，正在重新同步"
        code == CODE_DSH_UNAVAILABLE -> "桌面 DSH 不可用"
        isTextTooLong -> "思考内容过长，无法翻译"
        code == CODE_TRANSLATION_FAILED -> "翻译服务返回失败"
        code == CODE_TRANSLATION_UNAVAILABLE -> "翻译服务暂不可用"
        code == CODE_TRANSLATION_TIMEOUT -> "翻译超时，请重试"
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

        /** 翻译请求文本超过网关上限（HTTP 413）。 */
        const val CODE_TEXT_TOO_LONG = "text_too_long"

        /** 翻译服务自行报错（HTTP 502）：文本没问题，但目标语言/服务端失败。 */
        const val CODE_TRANSLATION_FAILED = "translation_failed"

        /** 翻译服务不可用（HTTP 503）：网关没配翻译实现或暂时关掉。 */
        const val CODE_TRANSLATION_UNAVAILABLE = "translation_unavailable"

        /** 翻译超时（HTTP 504）：重试有意义。 */
        const val CODE_TRANSLATION_TIMEOUT = "translation_timeout"

        /**
         * 网关没有这个端点（旧版本 dsh-gateway）。
         *
         * 客户端把它当成「降级」而不是错误：团队活动面板显示空态/不支持提示，
         * 不弹 toast、不影响聊天主流程。
         */
        const val CODE_UNSUPPORTED = "unsupported"
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
        /**
         * 子智能体预览：该子代理的**直接父会话** id（与 [mode] 同时给出时才生效）。
         *
         * DSH 只接受 `{kind:'subagent',parentSessionId,childSessionId,mode}` 地址读取子代理历史；
         * 不带这两个参数时按普通会话地址走（子代理会话会被 DSH 拒绝，这是预期行为）。
         */
        parentSessionId: String? = null,
        /** 子智能体模式：`one-shot` / `continuable` / `unknown`。 */
        mode: String? = null,
    ): MessagesPage

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        mode: String = "queue",
        /** 先上传好的文件回执 id（见 [uploadFile]）；空列表时请求体里不带该字段。 */
        files: List<String> = emptyList(),
    ): OkResponse

    /**
     * POST /v1/sessions/{id}/files：把一个本地文件交给网关，换回回执。
     *
     * 回执只能被**同一个会话**的下一条消息引用。默认实现直接抛错：旧网关或测试
     * 替身没有这个端点时，纯文本发送不受影响，只有带附件时才会报错。
     */
    suspend fun uploadFile(
        sessionId: String,
        name: String,
        mimeType: String? = null,
        bytes: ByteArray,
    ): UploadFileResponse = throw GatewayException("unsupported", 0, "当前网关不支持文件上传")

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

    /**
     * POST /v1/sessions/{id}/team：开关根会话的「智能体团队协作」（DSH `agentTeam`）。
     *
     * 开关是每根会话的属性；关闭后 DSH 保留已有任务与子代理。
     * 实现必须校验响应里的 `enabled` 与请求一致，否则抛 [GatewayException]。
     */
    suspend fun setTeamEnabled(sessionId: String, enabled: Boolean): SetTeamEnabledResponse

    /**
     * GET /v1/sessions/{id}/activity：当前会话树的团队活动（子智能体 + 后台任务 + 任务板）。
     *
     * 默认实现直接抛 [GatewayException.CODE_UNSUPPORTED]：旧网关没有该端点时，
     * 右侧团队协作面板显示空态/不支持提示，聊天主流程完全不受影响。
     */
    suspend fun sessionActivity(sessionId: String): TeamActivity = throw GatewayException(
        GatewayException.CODE_UNSUPPORTED,
        0,
        "当前网关不支持团队活动",
    )

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
     * GET /v1/pki/ca：本网关的客户端证书 CA（契约 §5.1，只有局域网边可达）。
     *
     * 自动领证前用它判断「本机是否已有该 CA 签发的证书」；旧网关没有这个
     * 端点时抛 [GatewayException.CODE_UNSUPPORTED]，自动领证静默跳过。
     */
    suspend fun caCertificate(): ClientCertCaResponse = throw GatewayException(
        GatewayException.CODE_UNSUPPORTED,
        0,
        "当前网关不支持客户端证书",
    )

    /**
     * POST /v1/pki/enroll：用本机生成的 CSR 换一张客户端证书（契约 §5.1，仅局域网边）。
     *
     * 私钥不出手机，只把 CSR 交给网关。旧网关/公网边返回 404/403 时抛
     * [GatewayException]，自动领证据此静默跳过，不阻塞连接。
     *
     * `deviceId` 是**追加的可选参数**（默认 null，两参调用保持不变）：带上它
     * 网关才会把同 deviceId 的旧证书标成撤销（续期语义），并把证书绑到设备上
     * 以便面板"按设备撤销"。自动领证会传本机 deviceId（同时作为 CSR 的 CN）。
     */
    suspend fun enrollClientCertificate(
        csrPem: String,
        deviceName: String?,
        deviceId: String? = null,
    ): ClientCertEnrollResponse = throw GatewayException(
        GatewayException.CODE_UNSUPPORTED,
        0,
        "当前网关不支持客户端证书",
    )

    /**
     * POST /v1/translate：把一段文本交给网关内置的翻译服务（思考链专用）。
     *
     * 长文本由调用方先分段（见 `splitForTranslation`），这里只发一段；
     * 超时 / 限流 / 服务不可用都以 [GatewayException] 抛出，repository 会折成
     * 「翻译失败」状态，UI 不会因此中断。
     *
     * @param sourceLanguage 源语言，默认由网关自动识别。
     * @param provider `bing` / `google`；缺省时用网关的默认实现。
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String? = null,
        provider: String? = null,
    ): TranslateResponse = throw GatewayException(
        GatewayException.CODE_TRANSLATION_UNAVAILABLE,
        0,
        "当前网关不支持翻译",
    )

    /** GET /v1/translate/providers：可用翻译服务与单次字符上限。 */
    suspend fun translationProviders(): TranslationProvidersResponse = throw GatewayException(
        GatewayException.CODE_TRANSLATION_UNAVAILABLE,
        0,
        "当前网关不支持翻译",
    )

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
    is javax.net.ssl.SSLException -> sslUserMessage(this)
    else -> message ?: javaClass.simpleName
}

/** alert 42/44/45/46/48：bad_certificate / certificate_revoked / … / unknown_ca。 */
private val ALERT_CERT_PATTERN = Regex("alert[: ]+(42|44|45|46|48)\\b")

/**
 * TLS 握手失败的中文提示（公网边强制双向证书后的两种典型失败）。
 *
 * - 服务端要客户端证书但本机没有：alert 116 `certificate_required`；
 * - 本机证书被撤销/过期/不被该 CA 接受：alert 42/44/45/46/48 或 bad_certificate。
 *
 * JSSE 有时把细节藏在 cause 里，所以把整条 cause 链上的 message 拼起来匹配；
 * 匹配不到时保持原来的「证书指纹不匹配？」文案（服务端证书固定失败的语义不变）。
 */
internal fun sslUserMessage(error: javax.net.ssl.SSLException): String {
    val messages = mutableListOf<String>()
    var current: Throwable? = error
    while (current != null) {
        current.message?.let { messages += it }
        current = current.cause
    }
    val text = messages.joinToString(" ").lowercase()
    return when {
        text.contains("certificate required") ||
            text.contains("certificate_required") ||
            text.contains("alert 116") ||
            text.contains("alert: 116") ->
            "该网关要求客户端证书：请回到局域网自动领取，或从网关导出 .p12 后在此导入"

        text.contains("bad certificate") ||
            text.contains("bad_certificate") ||
            text.contains("certificate revoked") ||
            text.contains("certificate_revoked") ||
            text.contains("certificate expired") ||
            text.contains("certificate_expired") ||
            text.contains("certificate unknown") ||
            text.contains("certificate_unknown") ||
            text.contains("unknown_ca") ||
            ALERT_CERT_PATTERN.containsMatchIn(text) ->
            "客户端证书已失效或被撤销，请重新领证"

        else -> "TLS 握手失败（证书指纹不匹配？）"
    }
}
