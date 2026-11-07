package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole

/**
 * 聊天时间线的展示分组（纯 Kotlin，无 Compose 依赖，JVM 单测覆盖）。
 *
 * 一轮（user → 下一个 user 之间）完成后：把工具调用、思考链、中间说明折叠成
 * 一个 [TimelineEntry.WorkGroup]，助手回复按「收起后显示的回复」决定是否留在分组外；
 * 运行过程中不分组，逐条实时展示。
 *
 * 参数：
 * - [groupWorkSteps]：false（「完全展开」档）时完全不分组，工作步骤按普通消息逐条展示；
 * - [deferCollapse]：true（「下次有新消息时」）时最新一轮完成后保持逐条展开，
 *   直到后面出现新的用户输入；
 * - [keepAllReplies]：true（「全部回复」）时所有 assistant 回复都留在分组外；
 *   false（「仅最终回答」）时只有本轮最后一条回复留在分组外，其余收进工作步骤分组。
 */
internal sealed interface TimelineEntry {
    val key: String

    data class Single(val message: ChatMessage) : TimelineEntry {
        override val key: String get() = "m:${message.id}"
    }

    data class WorkGroup(
        val id: String,
        val items: List<ChatMessage>,
    ) : TimelineEntry {
        override val key: String get() = "w:$id"
    }
}

internal fun buildTimelineEntries(
    messages: List<ChatMessage>,
    running: Boolean,
    groupWorkSteps: Boolean = true,
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
        val deferred = deferCollapse && start == latestRunStart
        if (!groupWorkSteps || deferred) {
            run.forEach { entries += TimelineEntry.Single(it) }
        } else {
            appendTurn(entries, run, running, keepAllReplies)
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

/** 一轮工作的分组规则：最后一条 assistant 是汇报，其余在完成后折叠。 */
private fun appendTurn(
    entries: MutableList<TimelineEntry>,
    run: List<ChatMessage>,
    running: Boolean,
    keepAllReplies: Boolean,
) {
    val reportIndex = run.indexOfLast { it.role == ChatRole.ASSISTANT }
    val report = run.getOrNull(reportIndex)
    val isComplete = !running && report != null && !report.streaming

    if (!isComplete || report == null) {
        run.forEach { entries += TimelineEntry.Single(it) }
        return
    }

    val visibleReplies = if (keepAllReplies) {
        run.indices.filter { run[it].role == ChatRole.ASSISTANT }.toSet()
    } else {
        setOf(reportIndex)
    }
    val work = run.filterIndexed { itemIndex, _ -> itemIndex !in visibleReplies }
    if (work.isEmpty()) {
        run.forEach { entries += TimelineEntry.Single(it) }
        return
    }

    var emittedGroup = false
    run.forEachIndexed { itemIndex, message ->
        if (itemIndex in visibleReplies) {
            entries += TimelineEntry.Single(message)
        } else if (!emittedGroup) {
            entries += TimelineEntry.WorkGroup(
                id = work.first().id.ifBlank { "turn-${work.first().hashCode()}" },
                items = work,
            )
            emittedGroup = true
        }
    }
}
