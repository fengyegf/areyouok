package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessageImage
import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer

/**
 * 把 REST 历史与 WS 增量（`message.delta` / 完整消息 / 工具事件）拼成时间线。
 *
 * 三条规则：
 * 1. `seq` 相同的历史/完整消息只保留一条；没有 `seq` 时用消息 `id` 当稳定身份
 *    （重连补发 / 历史分页重叠都不会重复；绝不按正文文本去重，同文本的两条真实消息各占一行）；
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
        items[callIndex] = call.copy(
            text = result.text,
            toolPath = result.toolPath,
            toolType = result.toolType,
        )
        items.removeAt(resultIndex)
    }

    /**
     * 合并一条完整消息（REST 历史或 `message.user`/`message.assistant` 事件）。
     *
     * 身份以 `seq` 优先、其次消息 `id`：网关重连时会重放同一批持久消息
     * （帧 seq 是新的，但消息 seq/id 不变），命中已有行就原地更新，不再追加。
     */
    fun appendFull(message: Message, optimisticText: String? = null): Boolean {
        val role = message.role.toChatRole()
        val text = message.text
        val seq = message.seq
        val images = message.usableImages()

        // 稳定身份：seq 优先，没有 seq 时用消息 id（重连补发 / 历史分页重叠时据此合并）。
        val identity = message.id
        val existing = items.indexOfFirst { item ->
            !item.pending &&
                ((seq != null && item.seq == seq) || (identity != null && item.id == identity))
        }
        if (existing >= 0) {
            items[existing] = items[existing].copy(
                text = text.ifBlank { items[existing].text },
                seq = seq ?: items[existing].seq,
                streaming = false,
                images = if (images.isNotEmpty()) images else items[existing].images,
            )
            return false
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
                    images = images,
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
                    images = if (images.isNotEmpty()) images else current.images,
                )
                return true
            }
            // 只有思考链 + 工具调用的 step：正文为空，这条 assistant 记录不是一条
            // 可见回复。早前会照样追加一条空 assistant 行，时间线在它这里被截断，
            // 于是每个工具步都单独成组（「运行了代码」卡片之间隔着一条看不见的空行），
            // 空行本身也渲染成一段空白。历史重建会丢掉空正文（见 MessageHistory），
            // 所以只有实时流能看到这个现象。
            if (text.isBlank() && images.isEmpty()) return false
        }

        items += ChatMessage(
            id = message.id ?: seq?.let { "seq-$it" } ?: "msg-${++localCounter}",
            role = role,
            text = text,
            seq = seq,
            time = message.time,
            meta = message.kind,
            images = images,
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
        /** 工具结果里的图片（`read_image` 之类）：只存引用，显示时按需预览。 */
        images: List<MessageImage> = emptyList(),
    ): Boolean {
        // 工具结果正文可能带 <path>/<type>/<content> 信封（DSH 把输出落盘路径写在正文里）：
        // 先解析，正文只留 content，路径/类型单独存字段，卡片展开时展示。
        val parsed = summary?.let { parseToolResultEnvelope(it) }
        val resultText = parsed?.content
        val resultPath = parsed?.path
        val resultType = parsed?.type
        if (callId != null) {
            val existing = items.indexOfLast { it.role == ChatRole.TOOL && it.callId == callId }
            if (existing >= 0) {
                val current = items[existing]
                items[existing] = current.copy(
                    text = resultText?.takeIf { it.isNotBlank() } ?: current.text,
                    meta = toolName ?: current.meta,
                    seq = seq ?: current.seq,
                    arguments = arguments ?: current.arguments,
                    running = running,
                    images = if (images.isNotEmpty()) images else current.images,
                    toolPath = resultPath ?: current.toolPath,
                    toolType = resultType ?: current.toolType,
                )
                return false
            }
        }
        // 同一事件的重连补发：seq 是稳定身份，命中时并回原行（历史里的工具行没有 callId）。
        if (seq != null) {
            val existing = items.indexOfLast { it.role == ChatRole.TOOL && it.seq == seq }
            if (existing >= 0) {
                val current = items[existing]
                items[existing] = current.copy(
                    text = resultText?.takeIf { it.isNotBlank() } ?: current.text,
                    meta = toolName ?: current.meta,
                    callId = callId ?: current.callId,
                    arguments = arguments ?: current.arguments,
                    running = running,
                    images = if (images.isNotEmpty()) images else current.images,
                    toolPath = resultPath ?: current.toolPath,
                    toolType = resultType ?: current.toolType,
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
                    text = resultText?.takeIf { it.isNotBlank() } ?: current.text,
                    meta = toolName ?: current.meta,
                    seq = seq ?: current.seq,
                    arguments = arguments ?: current.arguments,
                    running = running,
                    toolPath = resultPath ?: current.toolPath,
                    toolType = resultType ?: current.toolType,
                )
                return false
            }
        }
        val text = resultText?.takeIf { it.isNotBlank() }
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
            images = images,
            toolPath = resultPath,
            toolType = resultType,
        )
        return true
    }

    /**
     * 把 PTC 模式注入的工具结果信封（`<path>` / `<type>`）并回最近一条工具行。
     *
     * 真机现象：工具返回图片/文件时，DSH 会把 `<path>…</path><type>image</type>`
     * 作为一条新的 user 消息注入给模型；旧实现把它当系统灰字原样显示，正文里
     * 漏出尖括号标签。它描述的其实是一次工具调用的产物，应当归到「工具」行。
     *
     * @return 是否找到并更新了工具行；没有工具行时返回 false（调用方丢弃这条消息）。
     */
    fun attachToolEnvelope(path: String?, type: String?): Boolean {
        if (path.isNullOrBlank() && type.isNullOrBlank()) return false
        val index = items.indexOfLast { it.role == ChatRole.TOOL }
        if (index < 0) return false
        val current = items[index]
        items[index] = current.copy(
            toolPath = path ?: current.toolPath,
            toolType = type ?: current.toolType,
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
        val identity = message.id
        if (items.any { item ->
                !item.pending &&
                    ((seq != null && item.seq == seq) || (identity != null && item.id == identity))
            }
        ) {
            return false
        }
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

    /**
     * 记录一次「已作答的提问」：把题目和用户选择追加成时间线里的一张卡片。
     *
     * 桌面端会把提问与答案留在对话里，App 提交后卡片消失、聊天记录里什么都没有，
     * 回看时不知道刚才选了什么。这里在提交成功后补一条 [ChatRole.QUESTION]，
     * 之后再来的流式正文自然排在它后面（顺序与真实对话一致）。
     *
     * 同一次提问只记一条（重试提交 / 补发不会重复）；[id] 用提问的 eventId 派生。
     */
    fun appendAnsweredQuestion(
        questions: List<Question>,
        answers: List<QuestionAnswer>,
        id: String,
        time: Long? = null,
    ): Boolean {
        if (questions.isEmpty()) return false
        if (items.any { it.id == id }) return false
        items += ChatMessage(
            id = id,
            role = ChatRole.QUESTION,
            text = "",
            time = time,
            questions = questions,
            answers = answers,
        )
        return true
    }

    /**
     * 历史重建后把缓存的「已作答提问」插回时间线（切会话再回来不丢卡片）。
     *
     * [afterSeq] 是作答时时间线里最大的消息 seq：插到第一条 seq 更大的消息之前，
     * 这样后续助手回复仍在卡片后面，顺序与作答当时一致。找不到锚点（历史分页没覆盖、
     * 或还没有新消息）时追加到末尾。同 id 已存在则跳过，避免重复。
     */
    fun insertAnsweredQuestion(card: ChatMessage, afterSeq: Long?): Boolean {
        if (items.any { it.id == card.id }) return false
        if (afterSeq == null) {
            items += card
            return true
        }
        val index = items.indexOfFirst { item -> item.seq != null && item.seq > afterSeq }
        if (index < 0) items += card else items.add(index, card)
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
