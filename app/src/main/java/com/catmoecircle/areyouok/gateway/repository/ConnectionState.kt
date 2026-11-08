package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint

/** 连接状态机（交接文档 §13D：发现 → 连接 → 在线 → 错误/重连）。 */
enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
    ;

    val isOnline: Boolean get() = this == CONNECTED
    val isBusy: Boolean get() = this == CONNECTING || this == RECONNECTING
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val endpoint: GatewayEndpoint? = null,
    /** 网关自报名字（health.name），用于状态栏展示。 */
    val name: String? = null,
    val lan: Boolean = false,
    val serverVersion: String? = null,
    val serverSeq: Long = 0L,
    val attempt: Int = 0,
    val error: String? = null,
    val lastErrorAt: Long? = null,
) {
    val isOnline: Boolean get() = phase.isOnline
    val isBusy: Boolean get() = phase.isBusy

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: endpoint?.name?.takeIf { it.isNotBlank() } ?: "未连接"
}

/** 消息展示角色。 */
enum class ChatRole {
    USER,
    ASSISTANT,
    /** 模型思考链（reasoning delta），与正文分开存储、默认折叠展示。 */
    THINKING,
    TOOL,
    SYSTEM,
}

/** UI 直接消费的消息模型：流式 delta 会合并进最后一条 assistant 消息。 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val pending: Boolean = false,
    val seq: Long? = null,
    val time: Long? = null,
    /** 工具名等附加说明。 */
    val meta: String? = null,
    /** 工具消息的 callId，用于把 call/result 合并成一条。 */
    val callId: String? = null,
    /** 工具调用参数（JSON 文本），展开工具行时展示。 */
    val arguments: String? = null,
    /** 工具已派发、结果尚未返回：工作步骤标题据此显示「正在运行命令」等实时状态。 */
    val running: Boolean = false,
)

enum class NoticeLevel { INFO, WARN, ERROR }

data class GatewayNotice(
    val level: NoticeLevel,
    val text: String,
    val at: Long = System.currentTimeMillis(),
)
