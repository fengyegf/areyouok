package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole

/**
 * 聊天时间线的展示分组（纯 Kotlin，无 Compose 依赖，JVM 单测覆盖）。
 *
 * 与 DSH 客户端一致：一轮（user → 下一个 user 之间）里，**连续的思考 / 工具调用**
 * 收成一个工作步骤分组，助手回复把它截断——回答、调工具、再回答、再调工具时
 * 会出现多个分组，回答本身始终作为普通消息展示（分段折叠）。
 *
 * 参数：
 * - [groupWorkSteps]：false（「完全展开」档）时完全不分组，工作步骤按普通消息逐条展示；
 * - [groupWhileRunning]：true（「简洁 / 标准」）时运行中也分组（标题显示实时活动），
 *   false（「详细」）时运行中逐条实时展示，回答结束后再折叠；
 * - [deferCollapse]：true（「下次有新消息时」）时最新一轮完成后保持逐条展开，
 *   直到后面出现新的用户输入；
 * - [keepAllReplies]：true（「全部回复」）时所有 assistant 回复都留在分组外；
 *   false（「仅最终回答」）时整轮结束后，中间回复并入它前面的工作步骤分组。
 */
internal sealed interface TimelineEntry {
    val key: String

    data class Single(val message: ChatMessage) : TimelineEntry {
        override val key: String get() = "m:${message.id}"
    }

    data class WorkGroup(
        val id: String,
        val items: List<ChatMessage>,
        /** 分组已结束：标题显示「已读取文件、执行了命令」，否则显示实时活动。 */
        val closed: Boolean,
    ) : TimelineEntry {
        override val key: String get() = "w:$id"
    }
}

internal fun buildTimelineEntries(
    messages: List<ChatMessage>,
    running: Boolean,
    groupWorkSteps: Boolean = true,
    groupWhileRunning: Boolean = true,
    deferCollapse: Boolean = false,
    keepAllReplies: Boolean = true,
): List<TimelineEntry> {
    if (messages.isEmpty()) return emptyList()
    // 「下次有新消息时」只推迟最新一轮；末尾只有 system 通知时仍算最新。
    val latestRunStart = latestRunStart(messages)

    val entries = ArrayList<TimelineEntry>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message.role == ChatRole.USER || message.role == ChatRole.SYSTEM) {
            entries += TimelineEntry.Single(message)
            index++
            continue
        }
        val start = index
        while (index < messages.size &&
            messages[index].role != ChatRole.USER &&
            messages[index].role != ChatRole.SYSTEM
        ) {
            index++
        }
        val run = messages.subList(start, index).toList()
        val live = running && start == latestRunStart
        val streaming = run.any { it.streaming }
        val complete = !live && !streaming
        val deferred = deferCollapse && start == latestRunStart && complete
        if (!groupWorkSteps || deferred || (!complete && !groupWhileRunning)) {
            run.forEach { entries += TimelineEntry.Single(it) }
        } else {
            appendTurn(entries, run, live = live && !complete, keepAllReplies = keepAllReplies, complete = complete)
        }
    }
    return entries
}

/** 最新一轮的起始下标；最后一条可见消息是 user 时返回 -1（下一次输入已经到来）。 */
private fun latestRunStart(messages: List<ChatMessage>): Int {
    val last = messages.indexOfLast { it.role != ChatRole.SYSTEM }
    if (last < 0 || messages[last].role == ChatRole.USER) return -1
    var start = last
    while (start > 0 &&
        messages[start - 1].role != ChatRole.USER &&
        messages[start - 1].role != ChatRole.SYSTEM
    ) {
        start--
    }
    return start
}

/** 一轮里的片段：一段连续的工作步骤，或一条助手回复。 */
private sealed interface TurnSegment {
    class Process(val items: List<ChatMessage>) : TurnSegment
    class Reply(val message: ChatMessage) : TurnSegment
}

/**
 * 一轮工作的分组规则：助手回复处收束上一段工作步骤，末尾那段在整轮结束后才结束。
 *
 * 「仅最终回答」只在整轮结束后生效：中间回复并入它前面的工作步骤分组（展开分组可见），
 * 最后一条回复始终留在分组外。
 */
private fun appendTurn(
    entries: MutableList<TimelineEntry>,
    run: List<ChatMessage>,
    live: Boolean,
    keepAllReplies: Boolean,
    complete: Boolean,
) {
    val segments = ArrayList<TurnSegment>()
    var pending = ArrayList<ChatMessage>()
    for (message in run) {
        if (message.role == ChatRole.ASSISTANT) {
            if (pending.isNotEmpty()) {
                segments += TurnSegment.Process(pending)
                pending = ArrayList()
            }
            segments += TurnSegment.Reply(message)
        } else {
            pending += message
        }
    }
    if (pending.isNotEmpty()) segments += TurnSegment.Process(pending)
    if (segments.isEmpty()) return

    val finalReplyIndex = segments.indexOfLast { it is TurnSegment.Reply }
    val foldReplies = complete && !keepAllReplies

    var groupIndex = -1
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is TurnSegment.Process -> {
                val id = segment.items.first().id.ifBlank { "turn-${segment.items.first().hashCode()}" }
                entries += TimelineEntry.WorkGroup(
                    id = id,
                    items = segment.items,
                    // 后面还有回复（分组被截断）或整轮已经结束：标题改为完成摘要。
                    closed = index != segments.lastIndex || !live,
                )
                groupIndex = entries.lastIndex
            }
            is TurnSegment.Reply -> {
                val hide = foldReplies && index != finalReplyIndex
                val group = entries.getOrNull(groupIndex) as? TimelineEntry.WorkGroup
                if (hide && group != null) {
                    entries[groupIndex] = group.copy(items = group.items + segment.message)
                } else {
                    entries += TimelineEntry.Single(segment.message)
                }
            }
        }
    }
}
