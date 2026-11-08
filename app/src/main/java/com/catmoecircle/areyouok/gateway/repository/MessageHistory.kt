package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message

/**
 * 历史消息 → 时间线条目（纯 Kotlin，无 Android 依赖，JVM 单测覆盖）。
 *
 * DSH 的历史里**一次工具调用是两条记录**：`tool.call`（正文 = 工具名）和紧随其后的
 * `tool.result` / `tool.error`（正文 = 输出）。旧实现把两条都当独立行，于是时间线里
 * 一次调用出现「edit」+「工具」两行，「工作步骤 · N 项」也把 73 次调用显示成 146 项。
 * 这里按出现顺序把结果并回最近一次还没配对的调用，一行 = 一次工具调用。
 *
 * 同一 step 里若连续出现多个调用、之后才出现结果，按 FIFO 依次配对；
 * 只有结果没有调用（分页切开了、或老网关数据不完整）时保留为单独一行。
 */
internal fun List<Message>.toHistoryChatMessages(): List<ChatMessage> {
    val out = ArrayList<ChatMessage>(size)
    // 等待结果的历史工具调用：存它在 out 里的下标，按出现顺序配对。
    val pendingCalls = ArrayDeque<Int>()
    for (message in this) {
        if (message.role.equals("tool", ignoreCase = true)) {
            if (message.kind == KIND_TOOL_CALL) {
                pendingCalls.addLast(out.size)
                out += ChatMessage(
                    id = toolRowId(message, "call"),
                    role = ChatRole.TOOL,
                    text = "",
                    seq = message.seq,
                    time = message.time,
                    meta = message.text.takeIf { it.isNotBlank() },
                    running = true,
                )
            } else {
                val callIndex = pendingCalls.removeFirstOrNull()
                val call = callIndex?.let { out[it] }
                if (call != null) {
                    // 原地替换成「调用 + 结果」一行：工具名留在 meta，输出进正文。
                    out[callIndex] = call.copy(text = message.text, running = false)
                } else {
                    out += ChatMessage(
                        id = toolRowId(message, "result"),
                        role = ChatRole.TOOL,
                        text = message.text,
                        seq = message.seq,
                        time = message.time,
                    )
                }
            }
            continue
        }
        out += message.toTimelineMessages()
    }
    return out
}

/**
 * 单条历史消息 → 时间线条目（0～2 条）。
 *
 * - assistant 的思考链（[Message.reasoning]）拆成单独的「思考过程」行，避免混进正文；
 * - user 消息按 `source.kind` 分类：真人 → 用户气泡，系统通知 → 系统行，纯上下文丢弃；
 * - tool 消息只处理「不带调用配对」的兜底（正常配对在 [toHistoryChatMessages] 里完成）。
 */
internal fun Message.toTimelineMessages(): List<ChatMessage> {
    if (text.isBlank() && seq == null && reasoning.isNullOrBlank()) return emptyList()
    val role = when (this.role.lowercase()) {
        "user" -> MessageMapping.userRole(this) ?: return emptyList()
        "tool" -> ChatRole.TOOL
        "system" -> ChatRole.SYSTEM
        else -> ChatRole.ASSISTANT
    }
    // 历史消息没有 toolName/arguments：`kind` 是内部事件名（tool.call/tool.result），
    // 不能当工具名展示；tool.call 的正文就是工具名，tool.result 的正文是输出。
    val meta = when {
        role == ChatRole.SYSTEM -> MessageMapping.noticeMeta(this)
        role != ChatRole.TOOL -> kind
        kind == KIND_TOOL_CALL -> text.takeIf { it.isNotBlank() }
        else -> null
    }
    val baseId = id ?: seq?.let { "seq-$it" } ?: "history-${text.hashCode()}"

    val messages = ArrayList<ChatMessage>(2)
    if (role == ChatRole.ASSISTANT && !reasoning.isNullOrBlank()) {
        messages += ChatMessage(
            id = "$baseId-thinking",
            role = ChatRole.THINKING,
            text = reasoning,
            // 思考行是从同一条 assistant 消息拆出来的派生行：不能沿用消息的 seq，
            // 否则分页按 seq 去重时会把紧随其后的正文行当成重复丢掉。
            seq = null,
            time = time,
        )
    }
    if (text.isNotBlank() || role != ChatRole.ASSISTANT) {
        messages += ChatMessage(
            id = baseId,
            role = role,
            text = text,
            seq = seq,
            time = time,
            meta = meta,
        )
    }
    return messages
}

/** 历史工具行的稳定 id：优先用 seq（历史消息都有），否则退回文本哈希。 */
private fun toolRowId(message: Message, suffix: String): String {
    val base = message.seq?.let { "seq-$it" } ?: "history-${message.text.hashCode()}"
    return "tool-$suffix-$base"
}

/** 网关历史里工具调用记录的 kind。 */
private const val KIND_TOOL_CALL = "tool.call"
