package com.catmoecircle.areyouok.gateway.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `/v1/stream` 的 WebSocket 帧（交接文档 §6.4）。
 *
 * 序列化时 `t` 由 [GatewayJson.json] 的 `classDiscriminator` 自动写入，
 * 因此各数据类里不要重复声明 `t` 字段。
 */
@Serializable
sealed interface StreamFrame

@Serializable
@SerialName("hello")
data class HelloFrame(
    val v: Int = 1,
    val deviceId: String,
    val resume: ResumeState? = null,
) : StreamFrame

@Serializable
data class ResumeState(
    val seq: Long = 0,
)

/** 服务端就绪帧：携带当前 ring 的 seq 与快照。 */
@Serializable
@SerialName("ready")
data class ReadyFrame(
    val seq: Long = 0,
    val serverTime: Long? = null,
    val snapshot: Snapshot? = null,
) : StreamFrame

@Serializable
data class Snapshot(
    val workspaces: List<Workspace> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val pending: List<Intervention> = emptyList(),
    /**
     * 网关能力清单（如 `turn.completed`）。
     *
     * 旧网关没有这个字段（= 空列表）：APK 据此提示「升级网关」，避免
     * 每轮结束提醒静默失效。
     */
    val capabilities: List<String> = emptyList(),
)

@Serializable
@SerialName("sub")
data class SubFrame(
    val id: String,
    val topic: String,
) : StreamFrame

@Serializable
@SerialName("unsub")
data class UnsubFrame(
    val id: String,
) : StreamFrame

@Serializable
@SerialName("ack")
data class AckFrame(
    val seq: Long,
) : StreamFrame

@Serializable
@SerialName("ping")
data class PingFrame(
    val ts: Long,
) : StreamFrame

@Serializable
@SerialName("pong")
data class PongFrame(
    val ts: Long = 0,
) : StreamFrame

/** 缺口提示：客户端应清空 seq 重新 hello（不带 resume）。 */
@Serializable
@SerialName("resync")
data class ResyncFrame(
    val reason: String? = null,
) : StreamFrame

@Serializable
@SerialName("error")
data class ErrorFrame(
    val code: String = "unknown",
    val message: String = "",
) : StreamFrame

/** 事件帧。`data` 保持为 [JsonObject]，按 kind 在 repository 里再解释。 */
@Serializable
@SerialName("event")
data class EventFrame(
    val seq: Long? = null,
    val id: String? = null,
    val topic: String? = null,
    val kind: String = "",
    /**
     * 事件在**网关**侧的发布时刻（epoch ms），补发时保持原值。
     *
     * 用来判断「这件事刚刚发生」还是「断线补齐的历史」：网关重启后 seq 会从头
     * 计，只靠 seq 分不清新旧，这时用时间戳兜底（见 `GatewayRepository` 的
     * 新鲜度窗口，配合 `ready.serverTime` 校正时钟）。
     */
    val ts: Long? = null,
    val data: JsonObject = JsonObject(emptyMap()),
) : StreamFrame

/** 事件 kind 枚举（未知值落到 [UNKNOWN]，保证前向兼容）。 */
enum class EventKind(val wire: String) {
    SESSION_STATUS("session.status"),
    SESSION_UPDATED("session.updated"),
    MESSAGE_USER("message.user"),
    MESSAGE_ASSISTANT("message.assistant"),
    MESSAGE_DELTA("message.delta"),
    TURN_START("turn.start"),
    TURN_END("turn.end"),
    /**
     * 一轮对话结束（`lib/turns.js` 订阅宿主 `turn/end` 后广播）。
     *
     * 与 [TURN_END] 的区别：[TURN_END] 是当前订阅会话的日志事件（用于收尾流式
     * 消息），只有设备跟随了该会话才收得到；[TURN_COMPLETED] 是**全局**事实，
     * 所有会话每轮一条，专门给「每轮结束提醒」用。
     */
    TURN_COMPLETED("turn.completed"),
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),
    APPROVAL_REQUEST("approval.request"),
    QUESTION_REQUEST("question.request"),
    INTERVENTION_RESOLVED("intervention.resolved"),
    WORKSPACE_UPDATED("workspace.updated"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(wire: String?): EventKind =
            entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

fun EventFrame.eventKind(): EventKind = EventKind.fromWire(kind)

/** WS 订阅 topic 约定。 */
object Topics {
    fun session(sessionId: String): String = "session:$sessionId"

    /**
     * 通配订阅：网关把 `global` 订阅视为「所有 topic」，因此能收到**其他会话**的
     * 审批/提问事件（`session/<id>` 上的 intervention 广播）。侧边栏的「待处理」标记靠它。
     */
    const val GLOBAL: String = "global"

    fun sessionIdOf(topic: String?): String? =
        topic?.takeIf { it.startsWith("session:") }?.removePrefix("session:")?.takeIf { it.isNotEmpty() }
}
