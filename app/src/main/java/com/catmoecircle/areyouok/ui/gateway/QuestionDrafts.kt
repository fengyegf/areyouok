package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer

/** 一个问题在卡片里的编辑态：已选选项 label + 自定义回复文本。 */
data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
)

/**
 * 提问卡片的答题逻辑（纯函数，便于 JVM 单测）。
 *
 * 与 DSH 桌面端保持一致：
 * - 点选项只改选中态、不提交；单选替换、多选切换；
 * - 单选时自定义回复优先（提交时 `selected` 置空），多选时两者并存；
 * - 选项和自定义都为空表示「跳过该问题」。
 */
object QuestionDrafts {

    /** 点选一个选项：单选替换、多选切换。 */
    fun toggle(draft: QuestionDraft, label: String, multiSelect: Boolean): QuestionDraft =
        if (multiSelect) {
            draft.copy(
                selected = if (label in draft.selected) {
                    draft.selected - label
                } else {
                    draft.selected + label
                },
            )
        } else {
            draft.copy(selected = listOf(label))
        }

    /** 修改自定义回复文本。 */
    fun withCustom(draft: QuestionDraft, text: String): QuestionDraft = draft.copy(custom = text)

    /** 该题是否已作答（选了选项或输入了自定义回复）。 */
    fun answered(draft: QuestionDraft): Boolean =
        draft.selected.isNotEmpty() || draft.custom.isNotBlank()

    /** 所有问题都已作答才允许提交（与桌面端「提交」按钮一致）。 */
    fun allAnswered(questions: List<Question>, drafts: Map<String, QuestionDraft>): Boolean =
        questions.isNotEmpty() && questions.all { answered(drafts[it.id] ?: QuestionDraft()) }

    /** 生成一个问题的答案项。 */
    fun build(question: Question, draft: QuestionDraft): QuestionAnswer {
        val custom = draft.custom.trim()
        val selected = if (custom.isNotEmpty() && !question.multiSelect) emptyList() else draft.selected
        return QuestionAnswer(
            id = question.id,
            selected = selected,
            custom = custom.takeIf { it.isNotEmpty() },
        )
    }

    /** 生成整批答案（每个问题恰好一项）。 */
    fun buildAll(questions: List<Question>, drafts: Map<String, QuestionDraft>): List<QuestionAnswer> =
        questions.map { build(it, drafts[it.id] ?: QuestionDraft()) }
}
