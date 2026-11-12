package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `user/message.source.kind` → 聊天角色的分类（纯 Kotlin，直接跑 JVM 单测）。
 *
 * 现场 bug：后台任务完成通知（`tool-jobs`）被当成用户消息画成蓝色气泡，
 * 用户以为「我发的话被显示成 background job …」。这里逐条锁住分类规则。
 */
class MessageMappingTest {

    private fun message(text: String, source: MessageSource? = null) = Message(
        seq = 1,
        role = "user",
        text = text,
        source = source,
    )

    @Test
    fun `human source renders as user bubble`() {
        val role = MessageMapping.userRole(
            message("不是有实机吗？你用真机调试啊", MessageSource(kind = "user", rpcId = "r1")),
        )
        assertEquals(ChatRole.USER, role)
        assertTrue(MessageMapping.isHumanSource(MessageSource(kind = "user")))
    }

    @Test
    fun `background job notice is hidden from the chat timeline`() {
        // 后台任务完成通知只是上下文注入；桌面端 Chat 同样不展示，
        // 任务输出已经在工具结果行里。
        val notice = message(
            "background job pwsh-38486642c01e4a32 (pwsh: adb shell screenrecord …) finished [status: completed, exit code: 0]. Output:\ndone",
            MessageSource(kind = "tool-jobs", form = "notice", summary = "pwsh adb screenrecord"),
        )
        assertNull(MessageMapping.userRole(notice))
        assertEquals("tool-jobs", MessageMapping.noticeMeta(notice))
    }

    @Test
    fun `subagent settlement and team relay become system notices`() {
        assertEquals(
            ChatRole.SYSTEM,
            MessageMapping.userRole(
                message("Background subagent abc finished.", MessageSource(kind = "subagent-settled")),
            ),
        )
        assertEquals(
            ChatRole.SYSTEM,
            MessageMapping.userRole(
                message("Team message from apk-data: 已完成", MessageSource(kind = "agent-message")),
            ),
        )
    }

    @Test
    fun `runtime context and time context are hidden`() {
        assertNull(
            MessageMapping.userRole(
                message("Current runtime context. …", MessageSource(kind = "runtime-context")),
            ),
        )
        assertNull(
            MessageMapping.userRole(
                message("<system-reminder>技能目录…", MessageSource(kind = "skill-catalog")),
            ),
        )
        assertNull(
            MessageMapping.userRole(
                message("Time sampled while preparing turn 1, step 1", MessageSource(kind = "time-context")),
            ),
        )
        assertNull(
            MessageMapping.userRole(
                message("The user changed the approval policy", MessageSource(kind = "user-approval")),
            ),
        )
    }

    @Test
    fun `workspace instructions reminder is hidden from the chat timeline`() {
        // 真机 bug：AGENTS.md 的 agent-instructions 被画成 SYSTEM 灰字行，
        // 界面上就是一段 `<system-reminder> The following workspace instructions …`。
        assertNull(
            MessageMapping.userRole(
                message(
                    "<system-reminder>\nThe following workspace instructions may be relevant to your work. …",
                    MessageSource(kind = "agent-instructions", form = "instructions"),
                ),
            ),
        )
    }

    @Test
    fun `unknown context kind wrapped in system-reminder stays hidden`() {
        // DSH 以后新增的上下文来源（tmux-context、hooks…）同样用
        // `<system-reminder>` 包裹：按文本兜底，不能漏进时间线。
        assertNull(
            MessageMapping.userRole(
                message(
                    "<system-reminder>\nCompaction checkpoint …",
                    MessageSource(kind = "future-context-kind"),
                ),
            ),
        )
        // 没有 source 的旧网关数据也走同一条兜底。
        assertNull(MessageMapping.userRole(message("<system-reminder>\nA skill is …")))
    }

    @Test
    fun `model-facing instruction context is hidden`() {
        // 技能正文、重复调用提醒、目标轮次：正文是给模型的指令，不是聊天内容。
        assertNull(
            MessageMapping.userRole(
                message(
                    "<skill_content name=\"karpathy-guidelines\">…",
                    MessageSource(kind = "skill-invocation"),
                ),
            ),
        )
        assertNull(
            MessageMapping.userRole(
                message(
                    "You are repeating the exact same tool call with identical arguments.",
                    MessageSource(kind = "repeat-tool-reminder"),
                ),
            ),
        )
        assertNull(
            MessageMapping.userRole(
                message("<goal_round>\nObjective: 修好网关重连", MessageSource(kind = "goal")),
            ),
        )
    }

    @Test
    fun `old gateway without source falls back to text prefixes`() {
        // 老网关不返回 source：按文本前缀识别机器注入内容。
        assertNull(MessageMapping.userRole(message("Current runtime context. snapshot …")))
        assertNull(MessageMapping.userRole(message("background job bash-1 (bash: sleep 1) finished")))
        assertNull(MessageMapping.userRole(message("Team message from apify: hi")))
        // 真机实测：旧网关的审批策略变更通知正文是 "The user changed the approval policy …"。
        assertNull(
            MessageMapping.userRole(
                message("The user changed the approval policy from \"ask the user for approval\" to \"never ask the user for approval\"."),
            ),
        )
        // 真人消息照常显示。
        assertEquals(ChatRole.USER, MessageMapping.userRole(message("帮我看看这个布局")))
    }

    @Test
    fun `dsh-ops step tail plugin context is hidden`() {
        // 真机 bug：batching-guidance 每跑一步注入一条 `[Plugin context from "…";…]`，
        // source.kind = dsh-ops:step-tail。之前按「未知来源 = 系统通知」显示，
        // 时间线里出现大片灰字，正常对话被淹。
        val context = message(
            "[Plugin context from \"batching-guidance\"; not a user instruction or authorization.]\n" +
                "BLOCKING REQUIREMENT: batch your tool calls.",
            MessageSource(kind = "dsh-ops:step-tail"),
        )
        assertNull(MessageMapping.userRole(context))
        // dsh-ops 以后新增的注入 kind 也走前缀隐藏。
        assertNull(
            MessageMapping.userRole(
                message("some future ops context", MessageSource(kind = "dsh-ops:future-kind")),
            ),
        )
    }

    @Test
    fun `plugin context text is hidden without a source too`() {
        // 旧网关不带 source：仍按正文包裹前缀兜底。
        assertNull(
            MessageMapping.userRole(
                message("[Plugin context from \"batching-guidance\"; not a user instruction or authorization.]\n…"),
            ),
        )
        assertTrue(with(MessageMapping) { "[Plugin context from x".isPluginContextText() })
    }

    @Test
    fun `unknown source kind is shown as a system notice`() {
        // 新来源类型宁可显示灰字通知，也绝不冒充用户消息。
        assertEquals(
            ChatRole.SYSTEM,
            MessageMapping.userRole(message("something new", MessageSource(kind = "future-kind"))),
        )
        assertFalse(MessageMapping.isHumanSource(MessageSource(kind = "tool-jobs")))
    }
}
