package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message

/**
 * 把 REST 历史与 WS 增量（`message.delta` / 完整消息 / 工具事件）拼成时间线。
 *
 * 三条规则：
 * 1. `seq` 相同的历史/完整消息只保留一条（重连补发不会重复）；
 * 2. 流式 delta 合并进最后一条 streaming assistant 消息，完整消息到达后落定；
 * 3. 本机发送的乐观 user 消息在服务端回显时原地替换，避免闪两条。
 *
 * 纯 Kotlin，无 Android 依赖，JVM 单测直接覆盖。
 */
class MessageAssembler {

    private val items = mutableListOf<ChatMessage>()
    private var localCounter = 0
    private var streamCounter = 0

    fun snapshot(): List<ChatMessage> = items.toList()

    fun reset(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
    }

    /**
     * 头部拼接更早的历史（分页加载）。
     *
     * - 已存在的 `seq` / `id`（历史消息与实时消息可能重叠）不重复插入；
     * - 返回真正插入的条数，UI 用它判断「还要不要继续向上翻页」。
     */
    fun prepend(messages: List<ChatMessage>): Int {
        if (messages.isEmpty()) return 0
        val knownSeq = items.mapNotNullTo(HashSet()) { it.seq }
        val knownIds = items.mapTo(HashSet()) { it.id }
        val fresh = ArrayList<ChatMessage>(messages.size)
        for (message in messages) {
            val seq = message.seq
            if (seq != null && seq in knownSeq) continue
            if (message.id in knownIds) continue
            fresh += message
            if (seq != null) knownSeq += seq
            knownIds += message.id
        }
        if (fresh.isEmpty()) return 0
        items.addAll(0, fresh)
        mergeToolBoundary(fresh.size)
        return fresh.size
    }

    /**
     * 分页边界上的一次工具调用：更早一页的最后一条是 `tool.call`（结果还没来），
     * 已加载页的第一条是它的结果。插到头部后把这两行并成一行，避免一次调用显示两次。
     */
    private fun mergeToolBoundary(insertedCount: Int) {
        if (insertedCount <= 0 || insertedCount >= items.size) return
        val callIndex = insertedCount - 1
        val resultIndex = insertedCount
        val call = items[callIndex]
        val result = items[resultIndex]
        val awaitingResult = call.role == ChatRole.TOOL &&
            call.callId == null &&
            call.meta != null &&
            call.text.isBlank()
        val resultRow = result.role == ChatRole.TOOL && result.meta == null
        if (!awaitingResult || !resultRow) return
        items[callIndex] = call.copy(text = result.text)
        items.removeAt(resultIndex)
    }

    /** 合并一条完整消息（REST 历史或 `message.user`/`message.assistant` 事件）。 */
    fun appendFull(message: Message, optimisticText: String? = null): Boolean {
        val role = message.role.toChatRole()
        val text = message.text
        val seq = message.seq

        if (seq != null) {
            val existing = items.indexOfFirst { it.seq == seq && !it.pending }
            if (existing >= 0) {
                items[existing] = items[existing].copy(
                    text = text.ifBlank { items[existing].text },
                    streaming = false,
                )
                return false
            }
        }

        if (role == ChatRole.USER) {
            val optimisticIndex = items.indexOfLast { item ->
                item.pending && item.role == ChatRole.USER &&
                    (item.text == text || (optimisticText != null && item.text == optimisticText))
            }
            if (optimisticIndex >= 0) {
                items[optimisticIndex] = ChatMessage(
                    id = message.id ?: items[optimisticIndex].id,
                    role = role,
                    text = text,
                    seq = seq,
                    time = message.time ?: items[optimisticIndex].time,
                    meta = message.kind,
                )
                return true
            }
        }

        if (role == ChatRole.ASSISTANT) {
            finishThinking()
            val streamIndex = items.indexOfLast { it.streaming && it.role == ChatRole.ASSISTANT }
            if (streamIndex >= 0) {
                val current = items[streamIndex]
                items[streamIndex] = ChatMessage(
                    id = message.id ?: current.id,
                    role = role,
                    text = text.ifBlank { current.text },
                    streaming = false,
                    seq = seq ?: current.seq,
                    time = message.time ?: current.time,
                    meta = message.kind ?: current.meta,
                )
                return true
            }
        }

        items += ChatMessage(
            id = message.id ?: seq?.let { "seq-$it" } ?: "msg-${++localCounter}",
            role = role,
            text = text,
            seq = seq,
            time = message.time,
            meta = message.kind,
        )
        return true
    }

    /**
     * 流式增量：`kind` 为 `reasoning` 时并入「思考过程」消息，否则并入最后一条 streaming assistant。
     *
     * DSH 的 `message.delta` 带 `kind: "text" | "reasoning"`，之前不分流导致思考链
     * 被当成正文显示。这里把 reasoning 单独存一条 [ChatRole.THINKING] 消息，
     * 正文增量到达时把思考消息落定，UI 可分别折叠展示。
     */
    fun appendDelta(
        delta: String,
        messageId: String? = null,
        seq: Long? = null,
        kind: String? = null,
    ): Boolean {
        if (delta.isEmpty()) return false

        if (kind == KIND_REASONING) {
            val thinkingIndex = items.indexOfLast { it.streaming && it.role == ChatRole.THINKING }
            if (thinkingIndex >= 0) {
                val current = items[thinkingIndex]
                items[thinkingIndex] = current.copy(text = current.text + delta)
                return false
            }
            items += ChatMessage(
                id = messageId ?: "thinking-${++streamCounter}",
                role = ChatRole.THINKING,
                text = delta,
                streaming = true,
            )
            return true
        }

        // 正文开始输出：把仍在流式的思考链落定，避免它继续吞后续正文。
        finishThinking()

        val streamIndex = items.indexOfLast { it.streaming && it.role == ChatRole.ASSISTANT }
        if (streamIndex >= 0) {
            val current = items[streamIndex]
            items[streamIndex] = current.copy(
                text = current.text + delta,
                seq = seq ?: current.seq,
            )
            return false
        }
        items += ChatMessage(
            id = messageId ?: "stream-${++streamCounter}",
            role = ChatRole.ASSISTANT,
            text = delta,
            streaming = true,
            seq = seq,
        )
        return true
    }

    /** 工具调用/结果：同一个 callId 复用一条工具摘要。 */
    fun appendTool(
        toolName: String?,
        callId: String?,
        summary: String?,
        seq: Long? = null,
        arguments: String? = null,
        /** 结果事件：历史里的工具调用没有 callId，结果到达时并回最近一条等待中的调用行。 */
        mergeWithPending: Boolean = false,
        /** 调用事件为 true、结果事件为 false；工作步骤标题据此显示实时活动。 */
        running: Boolean = false,
    ): Boolean {
        if (callId != null) {
            val existing = items.indexOfLast { it.role == ChatRole.TOOL && it.callId == callId }
            if (existing >= 0) {
                val current = items[existing]
                items[existing] = current.copy(
                    text = summary?.takeIf { it.isNotBlank() } ?: current.text,
                    meta = toolName ?: current.meta,
                    seq = seq ?: current.seq,
                    arguments = arguments ?: current.arguments,
                    running = running,
                )
                return false
            }
        }
        if (mergeWithPending) {
            // 历史记录里的 tool.call 没有 callId（只剩「工具名 + 空正文」），
            // 实时结果到达时按顺序并回最近一条，不要新起一行。
            val pending = items.indexOfLast { item ->
                item.role == ChatRole.TOOL && item.callId == null &&
                    item.meta != null && item.text.isBlank()
            }
            if (pending >= 0) {
                val current = items[pending]
                items[pending] = current.copy(
                    text = summary?.takeIf { it.isNotBlank() } ?: current.text,
                    meta = toolName ?: current.meta,
                    seq = seq ?: current.seq,
                    arguments = arguments ?: current.arguments,
                    running = running,
                )
                return false
            }
        }
        val text = summary?.takeIf { it.isNotBlank() }
            ?: toolName?.takeIf { it.isNotBlank() }
            ?: "工具调用"
        items += ChatMessage(
            id = callId ?: "tool-${++localCounter}",
            role = ChatRole.TOOL,
            text = text,
            seq = seq,
            meta = toolName,
            callId = callId,
            arguments = arguments,
            running = running,
        )
        return true
    }

    /** turn 结束：所有流式消息（正文与思考链）落定。 */
    fun finishStreaming() {
        for (index in items.indices) {
            val item = items[index]
            if (item.streaming) items[index] = item.copy(streaming = false)
        }
    }

    /** 思考链落定：正文开始输出或完整消息到达时调用。 */
    private fun finishThinking() {
        for (index in items.indices) {
            val item = items[index]
            if (item.streaming && item.role == ChatRole.THINKING) {
                items[index] = item.copy(streaming = false)
            }
        }
    }

    fun appendLocalUser(text: String, time: Long): ChatMessage {
        val message = ChatMessage(
            id = "local-${++localCounter}",
            role = ChatRole.USER,
            text = text,
            pending = true,
            time = time,
        )
        items += message
        return message
    }

    fun removeLocalUser(text: String) {
        items.removeAll { it.pending && it.role == ChatRole.USER && it.text == text }
    }

    fun appendSystem(text: String): ChatMessage {
        val message = ChatMessage(id = "sys-${++localCounter}", role = ChatRole.SYSTEM, text = text)
        items += message
        return message
    }

    /**
     * 系统通知（DSH 的 `user/message` 但 `source.kind` 不是 `user`：后台任务完成、
     * 子代理结算、团队消息…）。按 seq 去重，作为 [ChatRole.SYSTEM] 追加，绝不进用户气泡。
     */
    fun appendContext(message: Message, meta: String?): Boolean {
        val seq = message.seq
        if (seq != null && items.any { it.seq == seq && !it.pending }) return false
        items += ChatMessage(
            id = message.id ?: seq?.let { "seq-$it" } ?: "context-${++localCounter}",
            role = ChatRole.SYSTEM,
            text = message.text,
            seq = seq,
            time = message.time,
            meta = meta,
        )
        return true
    }

    private fun String.toChatRole(): ChatRole = when (lowercase()) {
        "user" -> ChatRole.USER
        "tool" -> ChatRole.TOOL
        "system" -> ChatRole.SYSTEM
        else -> ChatRole.ASSISTANT
    }

    companion object {
        /** DSH `message.delta` 的 kind：思考链增量（不是正文）。 */
        const val KIND_REASONING = "reasoning"
    }
}
