package com.catmoecircle.areyouok.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.QuestionOption
import com.catmoecircle.areyouok.gateway.service.OverlayCard
import com.catmoecircle.areyouok.gateway.service.OverlaySendModes
import com.catmoecircle.areyouok.gateway.service.OverlayUiState
import com.catmoecircle.areyouok.ui.gateway.QuestionDraft
import com.catmoecircle.areyouok.ui.gateway.QuestionDrafts
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme

/** 展开面板的圆角。 */
private val PanelShape = RoundedCornerShape(28.dp)

/** 收起态胶囊：完全圆角。 */
private val PillShape = RoundedCornerShape(percent = 50)

/**
 * 后台悬浮气泡的 Compose 内容（纯展示）。
 *
 * 观感对齐 ChatGPT 的悬浮面板：收起态是一枚只有文字的黑色胶囊；点开后窗口由控制器做
 * 「向上收到屏幕顶部」的位移动画，这里只负责画一张无阴影、无图标的白卡：
 * 一句标题 + 一段说明 + 纯文字操作（批准 / 跳过、提交、继续回复 …）。
 *
 * 输入法是最麻烦的一块：悬浮窗默认不可聚焦（否则会抢走 App 焦点、键盘退不掉）。点
 * 「回复 / 继续回复」时通过 [onInputFocusChanged] 通知控制器临时去掉
 * FLAG_NOT_FOCUSABLE，等 [OverlayUiState.inputFocused] 变 true 之后再把焦点给输入框。
 * OEM（MIUI / HyperOS）可能压根不给 overlay 输入法，所以每处输入都留了「在 App 中回复」
 * 兜底入口。
 */
@Composable
fun OverlayBubbleHost(
    ui: OverlayUiState,
    onTogglePanel: () -> Unit,
    onCollapsePanel: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onAllowOnce: (String) -> Unit,
    onReject: (String) -> Unit,
    onAnswerQuestion: (eventId: String, answers: List<QuestionAnswer>) -> Unit,
    onSendMessage: (sessionId: String, text: String, mode: String) -> Boolean,
    onOpenSession: (String) -> Unit,
    onOpenApp: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
) {
    AreYouOKTheme {
        if (ui.expanded) {
            ExpandedPanel(
                ui = ui,
                onCollapsePanel = onCollapsePanel,
                onOpenApp = onOpenApp,
                onAllowOnce = onAllowOnce,
                onReject = onReject,
                onAnswerQuestion = onAnswerQuestion,
                onSendMessage = onSendMessage,
                onOpenSession = onOpenSession,
                onInputFocusChanged = onInputFocusChanged,
            )
        } else {
            CollapsedPill(
                ui = ui,
                onTogglePanel = onTogglePanel,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
            )
        }
    }
}

// ------------------------------------------------------------------ 收起态

/**
 * 收起态：一枚只有文字的胶囊（深色底、浅色字），没有图标、角标和呼吸动画。
 *
 * 拖动仍然保留：窗口按内容自适应大小，松手后由控制器吸附到最近侧边。
 */
@Composable
private fun CollapsedPill(
    ui: OverlayUiState,
    onTogglePanel: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 有新提醒时只让气泡「弹一下」：不展开、不遮挡，用户点开才看详情。
    val scale = remember { Animatable(1f) }
    var seenAttention by remember { mutableIntStateOf(ui.attention) }
    LaunchedEffect(ui.attention) {
        if (ui.attention <= seenAttention) return@LaunchedEffect
        seenAttention = ui.attention
        scale.snapTo(0.86f)
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        )
    }
    Text(
        text = collapsedLabel(ui),
        color = colors.surface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .padding(2.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(PillShape)
            .background(colors.onSurface)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragBy(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            }
            .clickable(onClick = onTogglePanel)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/** 胶囊上的文案：只讲状态，不放数字角标。 */
private fun collapsedLabel(ui: OverlayUiState): String {
    val pending = ui.state.pending.size
    val unread = (ui.badge.count - pending).coerceAtLeast(0)
    return when {
        pending > 0 -> "$pending 个待处理"
        unread > 0 -> if (unread == 1) "任务完成" else "$unread 条完成"
        ui.running -> "运行中"
        else -> "查看"
    }
}

// ------------------------------------------------------------------ 展开面板

/** 展开态：贴顶的一张白卡（无阴影），标题 + 说明 + 纯文字操作。 */
@Composable
private fun ExpandedPanel(
    ui: OverlayUiState,
    onCollapsePanel: () -> Unit,
    onOpenApp: () -> Unit,
    onAllowOnce: (String) -> Unit,
    onReject: (String) -> Unit,
    onAnswerQuestion: (String, List<QuestionAnswer>) -> Unit,
    onSendMessage: (String, String, String) -> Boolean,
    onOpenSession: (String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    // 键盘弹出时收窄面板，避免被 IME 盖住（窗口本身已经贴在屏幕顶部）。
    val maxHeightDp = config.screenHeightDp * (if (ui.inputFocused) 0.46f else 0.66f)

    var drafts by remember { mutableStateOf<Map<String, QuestionDraft>>(emptyMap()) }
    var composer by remember { mutableStateOf<Composer?>(null) }
    var composerText by remember { mutableStateOf("") }
    var composerMode by remember { mutableStateOf(OverlaySendModes.QUEUE) }
    var composerError by remember { mutableStateOf<String?>(null) }

    fun openComposer(target: Composer) {
        composer = target
        composerText = ""
        composerError = null
        onInputFocusChanged(true)
    }

    fun closeComposer() {
        composer = null
        composerText = ""
        composerError = null
        onInputFocusChanged(false)
    }

    fun submitComposer() {
        val target = composer ?: return
        when (target) {
            is Composer.Message -> {
                val trimmed = composerText.trim()
                if (trimmed.isEmpty()) {
                    composerError = "先输入要发送的内容"
                    return
                }
                if (onSendMessage(target.sessionId, trimmed, composerMode)) {
                    closeComposer()
                } else {
                    composerError = "发送失败，可在 App 中重试"
                }
            }

            is Composer.Answer -> {
                val custom = composerText.trim()
                if (custom.isEmpty()) {
                    composerError = "先输入回答内容"
                    return
                }
                val draft = QuestionDrafts.withCustom(
                    drafts[target.question.id] ?: QuestionDraft(),
                    custom,
                )
                val merged = drafts + (target.question.id to draft)
                onAnswerQuestion(target.eventId, QuestionDrafts.buildAll(target.questions, merged))
                closeComposer()
            }
        }
    }

    // 面板从窗口上消失（收起 / App 回到前台）时，务必把窗口的可聚焦状态还回去。
    DisposableEffect(Unit) {
        onDispose { onInputFocusChanged(false) }
    }

    // 淡入 + 轻微上浮：和窗口本身的「上移到屏幕顶部」叠在一起，收起时不回放。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "overlay-panel-alpha",
    )
    val contentRise by animateFloatAsState(
        targetValue = if (appeared) 0f else 18f,
        animationSpec = tween(durationMillis = 240),
        label = "overlay-panel-rise",
    )

    val panelWidthPx = ui.panelWidthPx.takeIf { it > 0 }

    Column(
        modifier = Modifier
            .then(
                if (panelWidthPx != null) {
                    Modifier.requiredWidth(with(density) { panelWidthPx.toDp() })
                } else {
                    Modifier.fillMaxWidth()
                },
            )
            .graphicsLayer {
                alpha = contentAlpha
                translationY = with(density) { contentRise.dp.toPx() }
            }
            .clip(PanelShape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.45f), PanelShape)
            .heightIn(max = maxHeightDp.dp),
    ) {
        PanelHandle()

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (ui.cards.isEmpty()) {
                Text(
                    text = if (ui.running) "任务运行中，完成后会在这里显示。" else "暂无待处理请求。",
                    color = colors.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            ui.cards.forEachIndexed { index, card ->
                if (index > 0) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                }
                when (card) {
                    is OverlayCard.Approval -> ApprovalCardView(
                        card = card,
                        onAllowOnce = onAllowOnce,
                        onReject = onReject,
                    )

                    is OverlayCard.Question -> QuestionCardView(
                        card = card,
                        drafts = drafts,
                        onDraftChange = { questionId, draft -> drafts = drafts + (questionId to draft) },
                        onSubmit = { answers -> onAnswerQuestion(card.eventId, answers) },
                        onReply = { question -> openComposer(Composer.Answer(card.eventId, card.questions, question)) },
                    )

                    is OverlayCard.Completion -> CompletionCardView(
                        card = card,
                        onOpenSession = onOpenSession,
                        onReply = { openComposer(Composer.Message(card.sessionId, card.title)) },
                    )
                }
            }
        }

        val target = composer
        if (target != null) {
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            ComposerBar(
                target = target,
                text = composerText,
                onTextChange = {
                    composerText = it
                    composerError = null
                },
                mode = composerMode,
                onModeChange = { composerMode = it },
                error = composerError,
                onSend = { submitComposer() },
                onClose = { closeComposer() },
                onOpenApp = {
                    closeComposer()
                    when (target) {
                        is Composer.Message -> onOpenSession(target.sessionId)
                        is Composer.Answer -> onOpenApp()
                    }
                },
            )
        }

        PanelFooter(onOpenApp = onOpenApp, onCollapse = onCollapsePanel)
    }
}

/** 顶部三个小点：纯装饰（GPT 同款），不参与点击。 */
@Composable
private fun PanelHandle() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) {
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(colors.onSurface.copy(alpha = 0.16f)),
            )
        }
    }
}

/** 底部两个纯文字入口：去 App 里操作 / 收起面板。 */
@Composable
private fun PanelFooter(
    onOpenApp: () -> Unit,
    onCollapse: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterLink(text = "在 App 中打开", onClick = onOpenApp)
        Spacer(Modifier.weight(1f))
        FooterLink(text = "收起", onClick = onCollapse)
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        color = colors.onSurfaceVariant,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ 卡片

/** 审批卡：一句标题 + 一段说明 + 「批准 / 跳过」两个纯文字按钮。 */
@Composable
private fun ApprovalCardView(
    card: OverlayCard.Approval,
    onAllowOnce: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val title = card.toolName?.takeIf { it.isNotBlank() }?.let { "批准运行 $it" } ?: "需要你的批准"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = title,
            color = colors.onSurface,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = card.reason ?: "这次工具调用需要授权后才能继续。",
            color = colors.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ActionLabel(
                text = "批准",
                color = colors.onSurface,
                onClick = { onAllowOnce(card.eventId) },
            )
            ActionLabel(
                text = "跳过",
                color = colors.onSurfaceVariant,
                onClick = { onReject(card.eventId) },
            )
        }
    }
}

/** 提问卡：题目当标题、说明当正文，选项是整行可点的文字（不用 radio / checkbox 图标）。 */
@Composable
private fun QuestionCardView(
    card: OverlayCard.Question,
    drafts: Map<String, QuestionDraft>,
    onDraftChange: (questionId: String, draft: QuestionDraft) -> Unit,
    onSubmit: (List<QuestionAnswer>) -> Unit,
    onReply: (Question) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        if (card.questions.isEmpty()) {
            Text(
                text = "这道提问没有题目信息，请在 App 中回答。",
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        card.questions.forEachIndexed { index, question ->
            if (index > 0) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(14.dp))
            }
            QuestionBlockView(
                question = question,
                draft = drafts[question.id] ?: QuestionDraft(),
                onDraftChange = { draft -> onDraftChange(question.id, draft) },
                onReply = { onReply(question) },
            )
        }
        if (card.questions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ActionLabel(
                text = "提交",
                color = colors.onSurface,
                enabled = QuestionDrafts.allAnswered(card.questions, drafts),
                onClick = { onSubmit(QuestionDrafts.buildAll(card.questions, drafts)) },
            )
        }
    }
}

/** 一道题：题头 / 题干 / 说明 / 选项 / 「回复文字」入口。 */
@Composable
private fun QuestionBlockView(
    question: Question,
    draft: QuestionDraft,
    onDraftChange: (QuestionDraft) -> Unit,
    onReply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        question.header?.takeIf { it.isNotBlank() }?.let { header ->
            Text(
                text = header,
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (question.text.isNotBlank()) {
            Text(
                text = question.text,
                color = colors.onSurface,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        question.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (question.options.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            question.options.forEach { option ->
                QuestionOptionRow(
                    option = option,
                    selected = option.label in draft.selected,
                    multiSelect = question.multiSelect,
                    onClick = {
                        onDraftChange(
                            QuestionDrafts.toggle(draft, option.label, question.multiSelect),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (draft.custom.isNotBlank()) "已填写：" + draft.custom else "回复文字…",
            color = colors.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onReply)
                .padding(vertical = 4.dp),
        )
    }
}

/** 选项行：选中时只加一层浅色底 + 加粗，不画单选/多选图标。 */
@Composable
private fun QuestionOptionRow(
    option: QuestionOption,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(
                role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = option.label,
            color = colors.onSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        option.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/** 完成卡：标题 + 说明 + 「继续回复 / 打开对话」，右下角是完成时间。 */
@Composable
private fun CompletionCardView(
    card: OverlayCard.Completion,
    onOpenSession: (String) -> Unit,
    onReply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = "任务完成",
            color = colors.onSurface,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = card.title?.takeIf { it.isNotBlank() } ?: "会话已跑完一轮",
            color = colors.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionLabel(
                text = "继续回复",
                color = colors.onSurface,
                onClick = onReply,
            )
            Spacer(Modifier.width(24.dp))
            ActionLabel(
                text = "打开对话",
                color = colors.onSurfaceVariant,
                onClick = { onOpenSession(card.sessionId) },
            )
            Spacer(Modifier.weight(1f))
            val time = formatTime(card.time)
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 纯文字操作：没有底色、没有图标，只有加粗和颜色层级。 */
@Composable
private fun ActionLabel(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = text,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ 输入区

/** 一次输入的目标：给会话发消息，或给某道提问写自定义回答。 */
private sealed interface Composer {
    data class Message(
        val sessionId: String,
        val title: String?,
    ) : Composer

    data class Answer(
        val eventId: String,
        val questions: List<Question>,
        val question: Question,
    ) : Composer
}

/** 底部输入区：发送模式（立即 / 完成后追加）+ 输入框 + 发送 +「在 App 中回复」兜底。 */
@Composable
private fun ComposerBar(
    target: Composer,
    text: String,
    onTextChange: (String) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    error: String?,
    onSend: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }

    // 窗口已经可聚焦（inputFocused=true）之后再把焦点给输入框，否则 IME 不会弹。
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (target is Composer.Message) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeLink(
                    label = "立即发送",
                    selected = mode == OverlaySendModes.STEER,
                    onClick = { onModeChange(OverlaySendModes.STEER) },
                )
                Spacer(Modifier.width(16.dp))
                ModeLink(
                    label = "完成后追加",
                    selected = mode == OverlaySendModes.QUEUE,
                    onClick = { onModeChange(OverlaySendModes.QUEUE) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "取消",
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                ),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = if (target is Composer.Message) "输入下一个任务…" else "输入自定义回答…",
                                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            ActionLabel(
                text = if (target is Composer.Message) "发送" else "提交",
                color = colors.onSurface,
                onClick = onSend,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = error ?: hintFor(target),
                color = if (error != null) colors.error else colors.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "在 App 中回复",
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenApp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** 输入区提示：发送模式 / 答案语义。 */
private fun hintFor(target: Composer): String = when (target) {
    is Composer.Message -> "立即发送=引导当前回答；完成后追加=这一轮结束再发"
    is Composer.Answer -> "文字会作为这道题的自定义回答提交"
}

/** 发送模式：选中加粗、未选中灰色，不用胶囊底色。 */
@Composable
private fun ModeLink(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** 完成时间（本机时区 HH:mm）；时间异常时返回空串而不是崩溃。 */
private fun formatTime(time: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(time)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")
