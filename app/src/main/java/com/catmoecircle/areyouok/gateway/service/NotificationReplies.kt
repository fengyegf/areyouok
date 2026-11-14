package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.SendMessageRequest

/**
 * 通知里的「直接回复」纯逻辑（不碰 Android 类型，便于单测）。
 *
 * 悬浮窗被移除后，审批 / 提问 / 任务完成全部由通知承载：审批用通知按钮，
 * 提问与「继续对话」用通知里的 RemoteInput 直接在通知栏输入，不用打开 App。
 */
object NotificationReplies {

    /** 通知里回复默认走「回答完成后追加」，与聊天输入框的默认行为一致。 */
    const val DEFAULT_MODE: String = SendMessageRequest.MODE_QUEUE

    /**
     * 是否给这条提问通知挂「直接回答」输入框。
     *
     * 只有单个问题才能用一次自由输入回答：多问题的提问一次输入给不全，
     * 交给 App 里的问答卡片处理。
     */
    fun canQuickAnswer(intervention: Intervention): Boolean =
        intervention.isQuestion && intervention.questions.size == 1

    /**
     * 把通知栏里输入的文字变成 DSH 的答案：作为该问题的 `custom` 提交。
     *
     * @return 空文本、非提问或多问题时返回 null（调用方直接忽略这次输入）。
     */
    fun quickAnswer(intervention: Intervention, text: CharSequence?): List<QuestionAnswer>? {
        if (!canQuickAnswer(intervention)) return null
        val question = intervention.questions.single()
        val custom = text?.toString()?.trim().orEmpty()
        if (custom.isEmpty()) return null
        return listOf(QuestionAnswer(id = question.id, selected = emptyList(), custom = custom))
    }
}
