package com.catmoecircle.areyouok.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.QuestionOption
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 引用的待处理卡片外形（提问/审批共用）。 */
private val DockShape = RoundedCornerShape(24.dp)

/** 审批卡片的强调色（截图里的橙色「等待审批」）。 */
private val ApprovalAccent = Color(0xFFF0A020)

/**
 * 待处理卡片（底部弹窗），**替换聊天输入框**。
 *
 * 只展示队首一条：审批显示「等待审批 + 说明 + 拒绝/允许一次」，
 * 提问显示「需要用户输入 + 选项 + 回答 + 提交」。
 * 回答成功由 repository 立即移除，卡片消失后输入框自动恢复。
 */
@Composable
fun InterventionDock(
    interventions: List<Intervention>,
    onAllowOnce: (String) -> Unit,
    onReject: (String) -> Unit,
    onAnswerQuestion: (eventId: String, answers: List<QuestionAnswer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = interventions.firstOrNull() ?: return
    val pendingCount = interventions.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 键盘收起时的最高高度；键盘弹起时父级的 ime inset 会把可用高度压得更小。
            .heightIn(max = 460.dp)
            .shadow(elevation = 12.dp, shape = DockShape, clip = false)
            .clip(DockShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (current.isQuestion) {
            QuestionDockContent(
                intervention = current,
                pendingCount = pendingCount,
                onAnswerQuestion = { answers -> onAnswerQuestion(current.eventId, answers) },
            )
        } else {
            ApprovalDockContent(
                intervention = current,
                pendingCount = pendingCount,
                onAllowOnce = { onAllowOnce(current.eventId) },
                onReject = { onReject(current.eventId) },
            )
        }
    }
}

// ------------------------------------------------------------------ 审批

/**
 * 审批内容：顶部橙色「等待审批」条 + 说明正文 + 底部「拒绝 / 允许一次」。
 *
 * 与 DSH 桌面端一致，只有「允许一次 / 拒绝」两种结果（没有「始终允许」）。
 */
@Composable
private fun ApprovalDockContent(
    intervention: Intervention,
    pendingCount: Int,
    onAllowOnce: () -> Unit,
    onReject: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ApprovalAccent.copy(alpha = 0.12f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ApprovalAccent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.gateway_approval_waiting),
                color = ApprovalAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (pendingCount > 1) {
                Text(
                    text = "+${pendingCount - 1}",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(
                text = approvalBody(intervention),
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ApprovalButton(
                    label = stringResource(R.string.gateway_reject),
                    filled = false,
                    onClick = onReject,
                )
                Spacer(Modifier.width(10.dp))
                ApprovalButton(
                    label = stringResource(R.string.gateway_allow_once),
                    filled = true,
                    onClick = onAllowOnce,
                )
            }
        }
    }
}

@Composable
private fun approvalBody(intervention: Intervention): String {
    // DSH 的 displayReason 是本地化文案（如「允许本次操作使用 workspace-write 权限：…」）。
    intervention.displayReason?.takeIf { it.isNotBlank() }?.let { return it }
    val tool = intervention.toolName?.takeIf { it.isNotBlank() }
    return if (tool == null) {
        stringResource(R.string.gateway_approval_body)
    } else {
        stringResource(R.string.gateway_approval_body_tool, tool)
    }
}

/** 审批按钮：拒绝为描边、允许一次为深色实心。 */
@Composable
private fun ApprovalButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val background = if (filled) colorScheme.onSurface else colorScheme.surface
    val contentColor = if (filled) colorScheme.surface else colorScheme.onSurface

    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(shape)
            .background(background)
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.border(1.dp, colorScheme.outline.copy(alpha = 0.35f), shape)
                },
            )
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ------------------------------------------------------------------ 提问

/**
 * 提问内容：标题「需要用户输入」+ 问题/选项/回答输入框 + 右下「提交」。
 *
 * 点选项只改选中态，提交时才发答案；全部问题作答后才可提交。
 */
@Composable
private fun QuestionDockContent(
    intervention: Intervention,
    pendingCount: Int,
    onAnswerQuestion: (List<QuestionAnswer>) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.gateway_question_title),
                color = colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pendingCount > 1) {
                Text(
                    text = "+${pendingCount - 1}",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (intervention.questions.isEmpty()) {
            Text(
                text = stringResource(R.string.gateway_question_hint),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        } else {
            QuestionComposer(
                intervention = intervention,
                onSubmit = onAnswerQuestion,
                // 剩余空间由问题内容占用；内容更高时在卡片内滚动。
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 提问表单：每个问题一组「选项 + 自定义回答」，底部一个「提交」。 */
@Composable
private fun QuestionComposer(
    intervention: Intervention,
    onSubmit: (List<QuestionAnswer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    // 按 eventId 保存草稿：切到下一条提问时自动重置。
    val drafts = remember(intervention.eventId) {
        mutableStateMapOf<String, QuestionDraft>()
    }
    val questions = intervention.questions
    if (questions.isEmpty()) return

    val canSubmit = QuestionDrafts.allAnswered(questions, drafts)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        questions.forEachIndexed { index, question ->
            if (index > 0) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(14.dp))
            }
            QuestionBlock(
                question = question,
                draft = drafts[question.id] ?: QuestionDraft(),
                onDraftChange = { drafts[question.id] = it },
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SubmitButton(
                enabled = canSubmit,
                onClick = { onSubmit(QuestionDrafts.buildAll(questions, drafts)) },
            )
        }
    }
}

/** 一道题的 header、问题文案、选项和自定义回答输入框。 */
@Composable
private fun QuestionBlock(
    question: Question,
    draft: QuestionDraft,
    onDraftChange: (QuestionDraft) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    question.header?.takeIf { it.isNotBlank() }?.let { header ->
        Text(
            text = header,
            color = colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(3.dp))
    }

    val text = question.text
    if (text.isNotBlank()) {
        Text(
            text = text,
            color = colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }

    question.detail?.takeIf { it.isNotBlank() }?.let { detail ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }

    if (question.options.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        question.options.forEachIndexed { index, option ->
            if (index > 0) {
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
            QuestionOptionRow(
                option = option,
                selected = option.label in draft.selected,
                multiSelect = question.multiSelect,
                onClick = { onDraftChange(QuestionDrafts.toggle(draft, option.label, question.multiSelect)) },
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.gateway_question_custom_label),
        color = colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(6.dp))
    QuestionTextField(
        value = draft.custom,
        onValueChange = { onDraftChange(QuestionDrafts.withCustom(draft, it)) },
    )
}

/** 截图里那圈灰色填充的「请输入回复」输入框。 */
@Composable
private fun QuestionTextField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            color = colorScheme.onSurface,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        ),
        cursorBrush = SolidColor(colorScheme.primary),
        maxLines = 4,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.gateway_question_custom_hint),
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        fontSize = 15.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 一个选项：单选是 radio、多选是 checkbox；点击只改变选中态，不提交。 */
@Composable
private fun QuestionOptionRow(
    option: QuestionOption,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = if (multiSelect) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp, end = 8.dp),
        ) {
            Text(
                text = option.label,
                color = colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/** 底部「提交」按钮：未作答置灰，作答后可点。 */
@Composable
private fun SubmitButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val background = if (enabled) colorScheme.onSurface else colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val contentColor = if (enabled) colorScheme.surface else colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val label = stringResource(R.string.gateway_question_submit)
    val clickModifier = if (enabled) {
        Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .then(clickModifier)
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ------------------------------------------------------------------ preview

@Preview(name = "InterventionDock - Approval", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun ApprovalDockPreview() {
    val intervention = Intervention(
        eventId = "wf-approval",
        sessionId = "s-preview",
        kind = "approval.request",
        data = buildJsonObject {
            put("toolName", "write")
            put(
                "reason",
                "允许本次操作使用 workspace-write 权限：你要求再测一次需要授权的修改操作，" +
                    "我需要以 workspace-write 权限在工作区写入该测试文件以触发并验证授权弹窗。",
            )
        },
    )

    AreYouOKTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomCenter,
        ) {
            InterventionDock(
                interventions = listOf(intervention),
                onAllowOnce = {},
                onReject = {},
                onAnswerQuestion = { _, _ -> },
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Preview(name = "InterventionDock - Question", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun QuestionDockPreview() {
    val intervention = Intervention(
        eventId = "wf-preview",
        sessionId = "s-preview",
        kind = "question.request",
        data = buildJsonObject {
            put(
                "questions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "q1")
                            put("header", "选项测试")
                            put("question", "功能弹窗测试：请选择一个方案，或使用「其他」输入自定义回复。")
                            put(
                                "options",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("label", "方案 A (Recommended)")
                                            put("description", "确认第一个方案选项可以选择并正常返回。")
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("label", "方案 B")
                                            put("description", "确认多个方案能够同时展示。")
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("label", "方案 C")
                                            put("description", "确认第三个方案和「其他」自定义输入入口。")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    AreYouOKTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomCenter,
        ) {
            InterventionDock(
                interventions = listOf(intervention),
                onAllowOnce = {},
                onReject = {},
                onAnswerQuestion = { _, _ -> },
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
