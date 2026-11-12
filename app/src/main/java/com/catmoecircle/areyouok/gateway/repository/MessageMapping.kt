package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessageSource

/**
 * DSH 消息 → 聊天时间线角色（纯 Kotlin，无 Android 依赖，JVM 单测覆盖）。
 *
 * DSH 的 `user/message` 带 `data.source.kind`：
 *
 * - `user`：真人输入（普通发送、排队、中途 steer 插入都算）→ 用户气泡；
 * - 模型侧注入的上下文（runtime-context / skill-catalog / agent-instructions /
 *   time-context / user-approval / goal / tool-jobs …）→ 聊天里不展示：
 *   它们是发给模型的提示词，不是对话内容；
 * - `subagent-settled` / `agent-message` 等真人可读通知：系统提示行，
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
        // 工作区指令（AGENTS.md / CLAUDE.md）：随每轮注入的 `<system-reminder>`，
        // 只是给模型的上下文。真机 bug：漏在 SYSTEM 灰字行里，像一条系统通知。
        "agent-instructions",
        // 技能调用注入的 `<skill_content>` 正文：模型照着执行，不是聊天内容。
        "skill-invocation",
        // 模型侧的提醒/目标/引用上下文：正文是给模型的指令（"You are repeating…"、
        // "<goal_round>" …），显示出来只会像一段莫名其妙的系统通知。
        "repeat-tool-reminder",
        "goal",
        "session-reference",
        "working-directory",
        // 后台任务完成通知（“background job … finished”）：只是上下文注入，
        // 输出已经在工具结果里；显示出来既打断分段折叠，也不是对话内容。
        "tool-jobs",
        // dsh-ops 插件的「步骤尾部上下文」（batching-guidance 等）：
        // 每跑完一个程序就注入一条 `[Plugin context from "…"; …]`，是给模型的提示词。
        "dsh-ops:step-tail",
    )

    /** dsh-ops 注入来源的 kind 前缀（`dsh-ops:step-tail`，以后可能有别的）。 */
    private const val HIDDEN_SOURCE_PREFIX = "dsh-ops:"

    /** dsh-ops 插件上下文的正文包裹前缀。 */
    private const val PLUGIN_CONTEXT_PREFIX = "[Plugin context from "

    /** DSH 注入模型侧上下文的固定包裹标签。 */
    private const val SYSTEM_REMINDER_TAG = "<system-reminder>"

    /** 无 `source` 时按文本前缀兜底过滤的机器注入消息。 */
    private val INJECTED_PREFIXES = listOf(
        "Current runtime context.",
        "<system-reminder>",
        "Time sampled while preparing",
        "Background subagent ",
        "background job ",
        "Team message from ",
        // dsh-ops 插件注入的步骤上下文（旧网关不带 source 时的文本兜底）。
        PLUGIN_CONTEXT_PREFIX,
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
        // `<system-reminder>` 包裹的是模型侧上下文（工作区指令、技能目录、重复调用提醒…），
        // 不是对话内容。按文本兜底：DSH 以后新增上下文来源（kind 不认识）时也不会漏进时间线；
        // 真人消息（kind == "user"）不参与这条过滤，避免误伤用户自己输入的尖括号文本。
        if (kind != "user" && message.text.isSystemReminderText()) return null
        // dsh-ops 注入的步骤上下文：正文是给模型的提示词（每个程序跑完一条），
        // 桌面端 Chat 不展示；App 之前按「未知来源 = 系统通知」画成大片灰字，把
        // 正常对话全淹了。kind 只认 dsh-ops 前缀，避免误伤子代理/团队通知。
        if (kind != null && kind.startsWith(HIDDEN_SOURCE_PREFIX)) return null
        if (kind != "user" && message.text.isPluginContextText()) return null
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

    /** 文本是否为 `<system-reminder>` 包裹的模型侧上下文。 */
    fun String.isSystemReminderText(): Boolean = trimStart().startsWith(SYSTEM_REMINDER_TAG)

    /** 文本是否为 dsh-ops 插件注入的 `[Plugin context from "…"; …]` 上下文。 */
    fun String.isPluginContextText(): Boolean = trimStart().startsWith(PLUGIN_CONTEXT_PREFIX)
}
