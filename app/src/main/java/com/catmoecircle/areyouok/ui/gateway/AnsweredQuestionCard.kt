package com.catmoecircle.areyouok.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer

/**
 * 已作答的提问卡片：把「问了什么 + 我选了什么」留在对话里。
 *
 * 桌面端会把 ask_user_question 的题目和用户选择渲染进时间线；App 之前提交后
 * 底部提问卡直接消失，聊天记录里完全看不到选项信息。这里在提交成功后补一张卡片，
 * 右对齐（和用户气泡同侧），内容按题展示：标题、问题、已选选项、自定义回复。
 */

/** 卡片里一道题要展示的内容（纯数据，JVM 单测直接覆盖）。 */
internal data class AnsweredQuestionView(
    /** 题目小标题（DSH `header`，如「测试问答」）。 */
    val header: String?,
    /** 问题正文；和 [header] 相同时留空，避免同一句话显示两遍。 */
    val question: String,
    /** 已选选项（保持提交顺序，已去空白、去重）。 */
    val selected: List<String>,
    /** 自定义回复（已 trim；没有时为 null）。 */
    val custom: String?,
    /** 既没选选项也没填自定义：这道题被跳过了。 */
    val skipped: Boolean,
)

/**
 * 把题目和用户提交的答案拼成卡片展示模型（纯函数）。
 *
 * 与桌面端一致：一个问题一块，选项全部列出；自定义回复单独成段；
 * 没选也没填的题标成「已跳过」。
 */
internal fun answeredQuestionViews(
    questions: List<Question>,
    answers: List<QuestionAnswer>,
): List<AnsweredQuestionView> {
    if (questions.isEmpty()) return emptyList()
    val answersById = answers.associateBy { it.id }
    return questions.map { question ->
        val answer = answersById[question.id]
        val selected = answer?.selected.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val custom = answer?.custom?.trim()?.takeIf { it.isNotEmpty() }
        val header = question.header?.trim()?.takeIf { it.isNotEmpty() }
        val text = question.text.trim()
        AnsweredQuestionView(
            header = header,
            question = text.takeIf { it != header }.orEmpty(),
            selected = selected,
            custom = custom,
            skipped = selected.isEmpty() && custom == null,
        )
    }
}

/** 卡片标题：全部跳过时明说，避免「已作答」误导。 */
internal fun answeredQuestionTitle(views: List<AnsweredQuestionView>): String =
    if (views.isNotEmpty() && views.all { it.skipped }) "提问 · 已跳过" else "提问 · 已作答"

@Composable
internal fun AnsweredQuestionCard(message: ChatMessage) {
    val colorScheme = MaterialTheme.colorScheme
    val views = answeredQuestionViews(message.questions, message.answers)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(CardWidthFraction)
                .clip(QuestionCardShape)
                .background(colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, colorScheme.primary.copy(alpha = 0.16f), QuestionCardShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✓",
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = answeredQuestionTitle(views),
                    color = colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (views.isEmpty()) {
                // 防旧网关只给了 eventId 没给题目：至少让用户看到「这里回答过」。
                Text(
                    text = "已提交回答",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        views.forEachIndexed { index, view ->
                            if (index > 0) {
                                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                            AnsweredQuestionBlock(view)
                        }
                    }
                }
            }
        }
    }
}

/** 一道题：header、问题正文、已选选项（多选全部列出）、自定义回复或「已跳过」。 */
@Composable
private fun AnsweredQuestionBlock(view: AnsweredQuestionView) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        view.header?.let { header ->
            Text(
                text = header,
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (view.question.isNotBlank()) {
            Text(
                text = view.question,
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (view.selected.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                view.selected.forEach { label -> AnswerChip(label = label) }
            }
        }
        view.custom?.let { custom -> AnswerCustomReply(text = custom) }
        if (view.skipped) {
            Text(
                text = "未作答（已跳过）",
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

/** 用户选中的选项：浅色胶囊 + 勾选标记；长文案自动换行。 */
@Composable
private fun AnswerChip(label: String) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(AnswerChipShape)
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.6f), AnswerChipShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "✓",
            color = colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

/** 自定义回复：单独一块，和选项区分开。 */
@Composable
private fun AnswerCustomReply(text: String) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AnswerChipShape)
            .background(colorScheme.surface)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "自定义回复",
            color = colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = text,
            color = colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

private val CardWidthFraction = 0.94f
private val QuestionCardShape = RoundedCornerShape(18.dp)
private val AnswerChipShape = RoundedCornerShape(8.dp)
