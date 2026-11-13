package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.Question as ProtocolQuestion
import com.catmoecircle.areyouok.gateway.protocol.SendMessageRequest
import com.catmoecircle.areyouok.gateway.repository.SessionCompletion

/**
 * 后台悬浮气泡的**纯数据 + 纯决策**层。
 *
 * 这里不出现任何 Android / Compose 类型：显示与否、显示什么、角标数字、卡片顺序都在
 * 这个文件里决定（因此能在 JVM 单测里直接覆盖），[OverlayBubbleController] 只负责把它
 * 渲染进 WindowManager，[com.catmoecircle.areyouok.ui.overlay.OverlayBubbles] 只负责画。
 *
 * @param enabled 设置页「后台悬浮气泡」开关。
 * @param appVisible App 是否在前台：在前台时结果已经直接显示，气泡一律隐藏。
 * @param connected 网关是否在线；断线时隐藏（避免显示一个点不动的空洞气泡）。
 * @param connectionLabel 网关显示名（面板副标题）。
 * @param sessionTitle 当前会话标题（面板标题）。
 * @param running 当前会话是否在跑一轮（气泡上的运行中指示）。
 * @param pending 待处理的审批 / 提问（新的在前或按业务排序，原样展示）。
 * @param completions 最近的「任务完成」提醒（新的在前，由上层维护；本地只做已读去重）。
 */
data class OverlayViewState(
    val enabled: Boolean = false,
    val appVisible: Boolean = true,
    val connected: Boolean = false,
    val connectionLabel: String = "",
    val sessionTitle: String? = null,
    val running: Boolean = false,
    val pending: List<Intervention> = emptyList(),
    val completions: List<SessionCompletion> = emptyList(),
)

/**
 * 控制器交给 Compose 的一帧渲染数据：原始状态 + 已经算好的卡片 / 角标 / 展开与输入焦点。
 *
 * [inputFocused] 是控制器持有的窗口可聚焦状态：输入法要等窗口去掉
 * `FLAG_NOT_FOCUSABLE` 之后才可能弹出，Compose 侧据此决定是否给输入框请求焦点。
 */
data class OverlayUiState(
    val state: OverlayViewState = OverlayViewState(),
    val cards: List<OverlayCard> = emptyList(),
    val badge: OverlayBadge = OverlayBadge.NONE,
    val expanded: Boolean = false,
    val inputFocused: Boolean = false,
    /** 新提醒的序号：每来一次新审批 / 未读完成就 +1，收起态气泡据此弹一下。 */
    val attention: Int = 0,
    /**
     * 展开面板的目标宽度（px），由控制器按屏幕宽度算好。
     *
     * 展开时窗口宽度是从胶囊宽度渐变出来的；如果面板内容跟着窗口宽度排版，动画的每一帧
     * 都会重新折行。固定成这个宽度后内容保持最终排版，只是被窗口边缘裁掉一部分。
     */
    val panelWidthPx: Int = 0,
) {
    /** 气泡此刻是否应该出现在窗口上。 */
    val visible: Boolean get() = OverlayBubbleState.shouldShow(state)

    val running: Boolean get() = state.running
}

/** 快速回复的发送模式（与网关 `SendMessageRequest.mode` 对齐）。 */
object OverlaySendModes {
    /** 立即发送：作为 steer 引导当前这一轮回答。 */
    const val STEER: String = SendMessageRequest.MODE_STEER

    /** 回答完成后追加：排队到这一轮结束再作为新任务发送。 */
    const val QUEUE: String = SendMessageRequest.MODE_QUEUE
}

/**
 * 展开面板里的一张卡片。
 *
 * 顺序即展示顺序：[OverlayBubbleState.cards] 保证待处理的审批 / 提问永远排在完成提醒前面。
 */
sealed interface OverlayCard {
    /** 卡片所属会话；「快速回复 / 打开对话」的默认目标。 */
    val sessionId: String?

    /** 去重键：审批/提问用事件 id，完成提醒用 `sessionId#time`。 */
    val key: String

    /** 需要授权的工具调用：允许一次 / 拒绝。 */
    data class Approval(
        val eventId: String,
        override val sessionId: String?,
        val toolName: String?,
        val reason: String?,
    ) : OverlayCard {
        override val key: String get() = "approval:$eventId"
    }

    /** 提问（有无选项都一样）：选项直接选，或输入自定义回复。 */
    data class Question(
        val eventId: String,
        override val sessionId: String?,
        val questions: List<ProtocolQuestion>,
    ) : OverlayCard {
        override val key: String get() = "question:$eventId"
    }

    /** 一轮任务跑完：展示完成气泡 + 「继续回复」。 */
    data class Completion(
        override val sessionId: String,
        val title: String?,
        val time: Long,
    ) : OverlayCard {
        override val key: String get() = OverlayBubbleState.completionKey(sessionId, time)
    }
}

/**
 * 气泡角标。
 *
 * @param count 待处理审批/提问 + 未读完成提醒的总数（界面显示 1..99，超过显示 99+）。
 * @param urgent 是否有需要用户立刻处理的审批/提问（醒目配色）。
 */
data class OverlayBadge(
    val count: Int = 0,
    val urgent: Boolean = false,
) {
    val visible: Boolean get() = count > 0

    companion object {
        val NONE: OverlayBadge = OverlayBadge()
    }
}

/**
 * 气泡的可见性 / 卡片 / 角标决策（纯函数）。
 *
 * 刻意写成 object 而不是塞进控制器：单测可以完全不碰 Android。
 */
object OverlayBubbleState {

    /** 展开面板里最多保留几条完成提醒（更早的已经「过期」，用户要的是最新结果）。 */
    const val MAX_COMPLETION_CARDS: Int = 3

    /** 展开面板一次最多画几张卡片，防止极端情况下无限长。 */
    const val MAX_CARDS: Int = 8

    /**
     * 是否把气泡挂到窗口上。
     *
     * 三个前提缺一不可：设置开关打开、App 不在前台、网关在线。
     * 没有待处理事件时也显示（收起为一个圆形气泡），这样用户能随时点开看运行状态；
     * 关闭 App 前台 / 断线时由控制器移除窗口而不是留一个空壳。
     */
    fun shouldShow(state: OverlayViewState): Boolean =
        state.enabled && !state.appVisible && state.connected

    /**
     * 展开面板里的卡片：待处理的审批 / 提问在前，完成提醒在后；完成提醒按已读集合去重。
     *
     * @param readCompletions 本地已读的完成提醒 key（见 [completionKey]）。
     */
    fun cards(
        state: OverlayViewState,
        readCompletions: Set<String> = emptySet(),
    ): List<OverlayCard> {
        val pending = state.pending.map { it.toCard() }
        val completions = completionCards(state, readCompletions, MAX_COMPLETION_CARDS)
        return (pending + completions).take(MAX_CARDS)
    }

    /**
     * 未读的完成提醒卡片（新的在前、同一会话只留最新一条）。
     *
     * @param limit 最多返回几条；传 [Int.MAX_VALUE] 表示不截断（用于计数）。
     */
    fun completionCards(
        state: OverlayViewState,
        readCompletions: Set<String> = emptySet(),
        limit: Int = MAX_COMPLETION_CARDS,
    ): List<OverlayCard.Completion> =
        state.completions.asSequence()
            .filter { it.sessionId.isNotBlank() }
            .map { OverlayCard.Completion(sessionId = it.sessionId, title = it.title, time = it.time) }
            .filterNot { it.key in readCompletions }
            .distinctBy { it.sessionId }
            .take(limit.coerceAtLeast(0))
            .toList()

    /** 未读完成提醒条数（角标用）。 */
    fun unreadCompletionCount(
        state: OverlayViewState,
        readCompletions: Set<String> = emptySet(),
    ): Int = completionCards(state, readCompletions, Int.MAX_VALUE).size

    /** 角标：待处理数 + 未读完成提醒数；有审批/提问时用醒目色。 */
    fun badge(
        state: OverlayViewState,
        readCompletions: Set<String> = emptySet(),
    ): OverlayBadge {
        if (!shouldShow(state)) return OverlayBadge.NONE
        val pending = state.pending.size
        return OverlayBadge(
            count = pending + unreadCompletionCount(state, readCompletions),
            urgent = pending > 0,
        )
    }

    /**
     * 完成提醒去重键。
     *
     * 网关对一轮对话只发一次 `turn.completed`；这里用「会话 id # 结束时间」当 key，
     * 等价于需求里的 `sessionId#turn`（[SessionCompletion] 没有 turn 序号，时间足以区分）。
     */
    fun completionKey(
        sessionId: String,
        time: Long,
    ): String = "$sessionId#$time"

    fun completionKey(completion: SessionCompletion): String =
        completionKey(completion.sessionId, completion.time)

    /** 已读集合加上这些 key 之后的集合（纯函数，方便控制器落盘前调用）。 */
    fun markRead(
        readCompletions: Set<String>,
        keys: Collection<String>,
    ): Set<String> = if (keys.isEmpty()) readCompletions else readCompletions + keys

    /**
     * 新状态里有没有「用户还没看过」的提醒：新的审批 / 提问，或者新的未读完成提醒。
     *
     * 控制器只用它让收起态气泡弹一下（不再据此展开面板——跑完一轮就弹面板会挡住用户
     * 正在用的界面）。已经在显示同一批事件时返回 false，避免同一事件反复提醒。
     */
    fun hasFreshEvent(
        previous: OverlayViewState?,
        next: OverlayViewState,
        readCompletions: Set<String> = emptySet(),
    ): Boolean {
        if (!shouldShow(next)) return false
        val previousPending = previous?.pending?.map { it.eventId }?.toSet().orEmpty()
        if (next.pending.any { it.eventId !in previousPending }) return true
        val previousCompletions = previous?.completions?.map { completionKey(it) }?.toSet().orEmpty()
        return next.completions.any { completion ->
            val key = completionKey(completion)
            key !in readCompletions && key !in previousCompletions
        }
    }

    /** 面板「快速回复」的目标会话：第一个带 sessionId 的卡片。 */
    fun quickReplySessionId(cards: List<OverlayCard>): String? =
        cards.firstNotNullOfOrNull { card -> card.sessionId?.takeIf { it.isNotBlank() } }

    /** 收起态气泡的辅助描述（无障碍 / 长按提示用）。 */
    fun bubbleLabel(
        state: OverlayViewState,
        readCompletions: Set<String> = emptySet(),
    ): String {
        val pending = state.pending.size
        val unread = unreadCompletionCount(state, readCompletions)
        return when {
            pending > 0 -> "有 $pending 个待处理请求"
            unread > 0 -> "有 $unread 个任务完成提醒"
            state.running -> "任务运行中"
            else -> state.connectionLabel.ifBlank { "DSH 网关" }
        }
    }

    /** [Intervention] → 面板卡片。 */
    fun Intervention.toCard(): OverlayCard = if (isQuestion) {
        OverlayCard.Question(
            eventId = eventId,
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            questions = questions,
        )
    } else {
        OverlayCard.Approval(
            eventId = eventId,
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            toolName = toolName,
            reason = displayReason?.takeIf { it.isNotBlank() },
        )
    }
}
