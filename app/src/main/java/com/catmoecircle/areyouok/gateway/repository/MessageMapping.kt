package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessageSource

/**
 * DSH 消息 → 聊天时间线角色（纯 Kotlin，无 Android 依赖，JVM 单测覆盖）。
 *
 * DSH 的 `user/message` 带 `data.source.kind`：
 *
 * - `user`：真人输入（普通发送、排队、中途 steer 插入都算）→ 用户气泡；
 * - `runtime-context` / `skill-catalog` / `time-context` / `user-approval` /
 *   `compact-checkpoint` / `tool-jobs`：模型侧上下文或后台任务完成通知 →
 *   聊天里不展示（与桌面端一致：任务输出已经在工具结果行里）；
 * - `subagent-settled` / `agent-message` 等：系统通知 → 系统提示行，
 *   **绝不能画成用户自己发的话**。
 *
 * 老网关（本轮之前）不返回 `source`，退回按文本前缀识别机器注入消息。
 */
internal object MessageMapping {

    /** 模型侧上下文与后台任务通知：聊天里完全不展示。 */
    private val HIDDEN_SOURCE_KINDS = setOf(
        "runtime-context",
        "skill-catalog",
        "time-context",
        "user-approval",
        "compact-checkpoint",
        // 后台任务完成通知（“background job … finished”）：只是上下文注入，
        // 输出已经在工具结果里；显示出来既打断分段折叠，也不是对话内容。
        "tool-jobs",
    )

    /** 无 `source` 时按文本前缀兜底过滤的机器注入消息。 */
    private val INJECTED_PREFIXES = listOf(
        "Current runtime context.",
        "<system-reminder>",
        "Time sampled while preparing",
        "Background subagent ",
        "background job ",
        "Team message from ",
        // 真机实测：旧网关不带 source 时，审批策略变更通知的正文是这句
        // （比历史文档里的 "The approval policy changed …" 多一个 "user"）。
        "The user changed the approval policy",
        "The approval policy changed",
    )

    /** 真人消息来源（`source.kind == "user"`）。 */
    fun isHumanSource(source: MessageSource?): Boolean = source?.kind == "user"

    /**
     * 一条 `user/message` 在聊天里的角色；返回 `null` 表示不展示。
     *
     * 没有 `source` 的旧网关数据用前缀兜底；有 `source` 但 kind 不认识时按系统
     * 通知展示（宁可多一条灰字提示，也不要冒充用户消息）。
     */
    fun userRole(message: Message): ChatRole? {
        val kind = message.source?.kind
        return when {
            kind == null -> if (message.text.isInjectedNoticeText()) null else ChatRole.USER
            kind == "user" -> ChatRole.USER
            kind in HIDDEN_SOURCE_KINDS -> null
            else -> ChatRole.SYSTEM
        }
    }

    /** 系统通知的元信息（来源 kind），UI 可用来分组/label。 */
    fun noticeMeta(message: Message): String? = message.source?.kind ?: message.kind

    /** DSH 注入的机器 user 消息文本前缀。 */
    fun String.isInjectedNoticeText(): Boolean {
        val text = trimStart()
        return INJECTED_PREFIXES.any { text.startsWith(it) }
    }
}
