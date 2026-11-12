package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer

/**
 * 一条「已作答的提问」在 repository 里的留存形态。
 *
 * 时间线卡片本身只活在当前 [MessageAssembler] 里；切会话/重载历史时时间线由
 * REST 历史整体重建，服务端历史里没有问答记录，卡片会丢。这里按会话缓存一份，
 * 重建后按 [afterSeq]（作答时时间线里最大的消息 seq）插回原位置。
 */
internal data class AnsweredQuestionCard(
    val id: String,
    val questions: List<Question>,
    val answers: List<QuestionAnswer>,
    val time: Long?,
    /** 作答时的消息 seq 锚点：历史重建后插到第一条 seq 更大的消息之前。 */
    val afterSeq: Long?,
) {
    fun toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        role = ChatRole.QUESTION,
        text = "",
        time = time,
        questions = questions,
        answers = answers,
    )
}
