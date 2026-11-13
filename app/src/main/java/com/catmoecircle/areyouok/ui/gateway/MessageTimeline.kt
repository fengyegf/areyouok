package com.catmoecircle.areyouok.ui.gateway

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole
import com.catmoecircle.areyouok.gateway.repository.toolPathFileName
import com.catmoecircle.areyouok.ui.gateway.markdown.MarkdownText
import com.catmoecircle.areyouok.ui.settings.WorkStepCollapseTiming
import com.catmoecircle.areyouok.ui.settings.WorkStepDisplay
import com.catmoecircle.areyouok.ui.settings.WorkStepFoldedReplies
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward

private val UserBubbleShape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
private val ToolCardShape = RoundedCornerShape(10.dp)
private val ThinkingShape = RoundedCornerShape(9.dp)
private val GroupShape = RoundedCornerShape(12.dp)
private val prettyJson = Json { prettyPrint = true }

/** 列表底部为输入区留出的空间（磨砂栏 + 展开态操作行 + 手势条）。 */
private val ComposerClearance = 168.dp

/** 「回到底部」按钮与输入区之间的留白。 */
private val ScrollToBottomClearance = 104.dp

/**
 * 会话消息时间线。
 *
 * - user：右对齐气泡（纯文本，保留换行）；
 * - thinking：思考链折叠行（默认收起，流式时显示「思考中…」）；
 * - assistant：左对齐 Markdown 正文，始终作为普通消息展示；
 * - tool：按 [WorkStepDisplay] 渲染工作步骤折叠行；
 * - 工作步骤按「段」折叠：一轮里连续的思考 / 工具调用收成一个分组，助手回复把它截断。
 *   运行中分组标题显示实时活动（「正在运行命令 · git status」），结束后显示完成摘要
 *   （「已读取文件、执行了命令」）；分组默认收起，点按展开；
 * - 「详细」档运行中逐条展示、结束后折叠，「完全展开」档不分组；
 * - system：系统通知（后台任务完成、子代理结算、团队消息等）折叠灰字行，
 *   点按展开——它永远不会画成用户自己的话；
 * - 顶部还有更早历史时（[hasOlder]）滚到顶自动加载上一页，并保持阅读位置。
 *
 * 收起时机与保留哪些回复见 [WorkStepCollapseTiming] / [WorkStepFoldedReplies]。
 */
@Composable
fun MessageTimeline(
    messages: List<ChatMessage>,
    workStepDisplay: WorkStepDisplay = WorkStepDisplay.DEFAULT,
    workStepCollapseTiming: WorkStepCollapseTiming = WorkStepCollapseTiming.DEFAULT,
    workStepFoldedReplies: WorkStepFoldedReplies = WorkStepFoldedReplies.DEFAULT,
    running: Boolean = false,
    hasOlder: Boolean = false,
    loadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    /** 当前会话 id：图片预览按会话解析 PC 上的本地路径。 */
    sessionId: String? = null,
    /** 按需取图（不落盘）；null 时不加载图片，只留文字。 */
    imageFetcher: ImageFetcher? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    // 用户是否仍在跟随最新消息。键盘改变视口高度时不改动这个值，
    // 避免布局收缩后的瞬时“不在底部”把原本贴底的阅读位置误判成用户上滑。
    var followLatest by remember { mutableStateOf(true) }

    val groupWorkSteps = workStepDisplay.groupsWorkSteps
    val groupWhileRunning = workStepDisplay.groupsWhileRunning
    val deferCollapse = workStepCollapseTiming == WorkStepCollapseTiming.NEXT_INPUT
    val keepAllReplies = workStepFoldedReplies == WorkStepFoldedReplies.ALL
    val entries = remember(
        messages,
        running,
        groupWorkSteps,
        groupWhileRunning,
        deferCollapse,
        keepAllReplies,
    ) {
        buildTimelineEntries(
            messages,
            running,
            groupWorkSteps = groupWorkSteps,
            groupWhileRunning = groupWhileRunning,
            deferCollapse = deferCollapse,
            keepAllReplies = keepAllReplies,
        )
    }
    val keys = remember(entries) { uniqueEntryKeys(entries) }
    var lastCount by remember { mutableStateOf(0) }

    // 顶部翻页状态：锚定当前阅读位置（key + 偏移），插入更早消息后滚回原处。
    var anchorKey by remember { mutableStateOf<String?>(null) }
    var anchorOffset by remember { mutableStateOf(0) }
    var loadArmed by remember { mutableStateOf(true) }
    var settled by remember { mutableStateOf(false) }
    val headerCount = if (hasOlder) 1 else 0

    // 新消息或流式增量到达时贴底；用户上滑阅读历史时不再强行拉回。
    LaunchedEffect(entries.size, entries.lastOrNull()?.lastTextLength()) {
        if (entries.isEmpty()) {
            lastCount = 0
            settled = false
            loadArmed = true
            anchorKey = null
            return@LaunchedEffect
        }
        val countChanged = entries.size != lastCount
        lastCount = entries.size
        if (!countChanged && !followLatest) return@LaunchedEffect
        listState.scrollToTimelineEnd(
            index = entries.lastIndex + headerCount,
            bottomClearancePx = with(density) { ComposerClearance.toPx() },
        )
        followLatest = true
        settled = true
    }

    // 更早的一页插入后：把锚定的那条消息滚回原来的屏幕位置，避免列表整体跳动。
    LaunchedEffect(entries.size, hasOlder) {
        val key = anchorKey ?: return@LaunchedEffect
        val index = keys.indexOf(key)
        if (index >= 0) listState.scrollToItem(index + headerCount, anchorOffset)
        anchorKey = null
        loadArmed = true
    }

    // 滚到最早一条时自动加载上一页；首屏定位完成后才触发，一次加载只触发一次。
    LaunchedEffect(listState.firstVisibleItemIndex, hasOlder, loadingOlder, entries.size, settled) {
        if (!settled || !hasOlder || loadingOlder) return@LaunchedEffect
        val entryIndex = listState.firstVisibleItemIndex - headerCount
        if (entryIndex > 0) {
            loadArmed = true
            return@LaunchedEffect
        }
        if (!loadArmed || entries.isEmpty()) return@LaunchedEffect
        loadArmed = false
        anchorKey = entries.first().key
        anchorOffset = listState.firstVisibleItemScrollOffset
        onLoadOlder()
    }

    if (entries.isEmpty()) {
        EmptyChatHint(modifier)
        return
    }

    val showScrollToBottom by remember(listState) {
        derivedStateOf { !listState.isAtBottom(margin = 24) }
    }

    // 只有用户主动滚动时才更新“跟随最新消息”状态；布局因键盘变化而缩短不会改变它。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to listState.isAtBottom(margin = 24)
        }.collect { (scrolling, atBottom) ->
            if (scrolling || atBottom) followLatest = atBottom
        }
    }

    // 键盘展开时滚动范围增加，需要按同一增量跟到底部。收起时滚动范围减少，
    // LazyColumn 会自动把越界位置夹回新的最大值；这里不能再补负增量，
    // 否则每次收起都会把内容额外往下推一截，反复开合后逐渐被输入框遮挡。
    LaunchedEffect(listState, density, imeInsets) {
        var lastImeBottom = imeInsets.getBottom(density)
        snapshotFlow { imeInsets.getBottom(density) }.collect { current ->
            val delta = current - lastImeBottom
            lastImeBottom = current
            if (delta <= 0f) return@collect
            if (listState.layoutInfo.totalItemsCount == 0) return@collect
            if (!followLatest) return@collect
            listState.scrollBy(delta.toFloat())
        }
    }

    CompositionLocalProvider(
        LocalImageFetcher provides imageFetcher,
        LocalImageSessionId provides sessionId,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 8.dp,
                    // 输入区留白；键盘高度已经由外层 imePadding 缩短视口。
                    bottom = ComposerClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasOlder) {
                    item(key = "__older_history__") {
                        OlderHistoryRow(loading = loadingOlder, onLoad = onLoadOlder)
                    }
                }
                items(count = entries.size, key = { index -> keys[index] }) { index ->
                    // 只有运行中会话的最后一条（正在进行的工具调用）才显示「调用中…」，
                    // 历史里未被结果配对的调用不会误显示成还在跑。
                    val liveTail = running && index == entries.lastIndex
                    when (val entry = entries[index]) {
                        is TimelineEntry.Single -> MessageRow(entry.message, workStepDisplay, liveTail)
                        is TimelineEntry.WorkGroup -> WorkGroupCard(entry, workStepDisplay)
                    }
                }
            }

            ScrollToBottomButton(
                visible = showScrollToBottom,
                onClick = {
                    scope.launch {
                        val lastIndex = entries.lastIndex + headerCount
                        listState.scrollToTimelineEnd(
                            index = lastIndex,
                            bottomClearancePx = with(density) { ComposerClearance.toPx() },
                        )
                        followLatest = true
                        settled = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = ScrollToBottomClearance),
            )
        }
    }
}

/**
 * 先定位最后一条，再补上条目自身溢出和输入区预留高度。
 * 这样最后一条消息会停在输入框上方，而不是停在列表的视口底边。
 */
private suspend fun LazyListState.scrollToTimelineEnd(index: Int, bottomClearancePx: Float) {
    scrollToItem(index)
    val info = layoutInfo
    val lastInfo = info.visibleItemsInfo.lastOrNull() ?: return
    val overflow = (lastInfo.offset + lastInfo.size - info.viewportEndOffset).coerceAtLeast(0)
    val delta = overflow + bottomClearancePx
    if (delta > 0f) {
        runCatching<Unit> { scrollBy(delta) }
    }
}

/** 消息下方还有内容时出现的快捷按钮；点击后直接跳到时间线末尾。 */
@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.chat_scroll_to_bottom)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(120)) + scaleIn(
            initialScale = 0.9f,
            animationSpec = tween(140),
        ),
        exit = fadeOut(tween(90)) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(110),
        ),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.14f),
                )
                .clip(CircleShape)
                .background(colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = colorScheme.outline.copy(alpha = 0.24f),
                    shape = CircleShape,
                )
                .clickable(
                    onClickLabel = label,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                tint = colorScheme.onSurface,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(90f),
            )
        }
    }
}

/** 顶部「加载更早消息」行：自动翻页时显示进度，加载失败/内容不足时可手动点。 */
@Composable
private fun OlderHistoryRow(loading: Boolean, onLoad: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = !loading, onClick = onLoad)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.gateway_history_loading),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.gateway_history_load),
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                fontSize = 11.sp,
            )
        }
    }
}

private fun TimelineEntry.lastTextLength(): Int = when (this) {
    is TimelineEntry.Single -> message.text.length
    is TimelineEntry.WorkGroup -> items.sumOf { it.text.length }
}

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.gateway_chat_empty),
            color = colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

// ------------------------------------------------------------------ 分组

/** 条目 key 去重，避免 LazyColumn 重复 key 闪退。 */
private fun uniqueEntryKeys(entries: List<TimelineEntry>): List<String> {
    val seen = HashMap<String, Int>(entries.size)
    return entries.map { entry ->
        val base = entry.key
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "$base#$count"
    }
}

/** 视口是否已经贴在列表底部（尚未完成首帧布局时按「是」处理）。 */
private fun LazyListState.isAtBottom(margin: Int = 8): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + margin
}

// ------------------------------------------------------------------ 渲染

@Composable
private fun MessageRow(message: ChatMessage, workStepDisplay: WorkStepDisplay, live: Boolean) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantBubble(message)
        ChatRole.THINKING -> ThinkingRow(message, workStepDisplay)
        ChatRole.TOOL -> ToolStepRow(message, workStepDisplay, live)
        ChatRole.SYSTEM -> SystemLine(message)
        ChatRole.QUESTION -> AnsweredQuestionCard(message)
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    val colorScheme = MaterialTheme.colorScheme
    val sessionId = LocalImageSessionId.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (image in message.images) {
            RemoteImage(
                url = attachmentPreviewUrl(image, sessionId),
                contentDescription = image.name,
                maxWidth = 240.dp,
                maxHeight = 320.dp,
            )
        }
        if (message.text.isNotBlank()) {
            Text(
                text = message.text,
                color = colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier
                    .clip(UserBubbleShape)
                    .background(colorScheme.primary.copy(alpha = if (message.pending) 0.10f else 0.16f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatMessage) {
    // 长按可选中复制：Markdown 渲染后的正文仍然支持文本选择。
    SelectionContainer {
        MarkdownText(
            markdown = message.text,
            modifier = Modifier.padding(vertical = 2.dp),
            showCursor = message.streaming,
        )
    }
}

/**
 * 思考链折叠行：默认收起，档位决定折叠态露出多少（简洁无摘要、标准起露出首行），
 * 点开看完整思考过程。与正文严格分开，避免「思考内容」混进汇报里。
 */
@Composable
private fun ThinkingRow(message: ChatMessage, level: WorkStepDisplay) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable(
        message.id,
        level,
        key = "thinking#${message.id}#${level.name}",
    ) {
        mutableStateOf(false)
    }
    val hint = if (level.showsRowHint) {
        message.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }

    StepRow(
        shape = ThinkingShape,
        label = stringResource(R.string.gateway_thinking),
        labelColor = colorScheme.onSurfaceVariant,
        hint = hint,
        streaming = message.streaming,
        streamingText = stringResource(R.string.gateway_thinking_streaming),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        SelectionContainer {
            MarkdownText(
                markdown = message.text,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * 工作步骤折叠组：一行标题概括这一段工作，默认收起。
 *
 * 运行中标题是实时活动（「正在运行命令 · git status」），结束后是完成摘要
 * （「已读取文件、执行了命令」）；点按展开组内每一行。
 */
@Composable
private fun WorkGroupCard(group: TimelineEntry.WorkGroup, level: WorkStepDisplay) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable(
        group.key,
        level,
        key = "work#${group.key}#${level.name}",
    ) {
        mutableStateOf(false)
    }
    val summary = remember(group.items, group.closed) { summarizeWork(group.items) }
    val live = !group.closed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, colorScheme.outline.copy(alpha = 0.18f), GroupShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(6.dp))
            if (live) {
                LivePulse(Modifier.padding(end = 7.dp))
            }
            WorkGroupTitle(
                live = live,
                summary = summary,
                level = level,
                modifier = Modifier.weight(1f),
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.items.forEach { item ->
                    WorkItem(item, level, live)
                }
            }
        }
    }
}

/** 分组标题：运行中显示带高光的实时活动，结束后显示完成摘要。 */
@Composable
private fun WorkGroupTitle(
    live: Boolean,
    summary: WorkSummary,
    level: WorkStepDisplay,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    if (!live) {
        Text(
            text = doneTitle(summary),
            color = colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    val alpha = rememberLivePulseAlpha()
    val activity = summary.running ?: WorkActivity.THINKING
    val label = stringResource(workStepLiveLabelRes(activity))
    val detail = if (level.showsLiveDetail) summary.runningDetail else ""
    val separator = stringResource(R.string.work_step_live_detail_separator)
    val title = buildAnnotatedString {
        withStyle(SpanStyle(color = colorScheme.onSurface.copy(alpha = alpha))) {
            append(label)
        }
        if (detail.isNotBlank()) {
            withStyle(SpanStyle(color = colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.88f))) {
                append(separator)
                append(detail)
            }
        }
    }
    Text(
        text = title,
        color = colorScheme.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 结束后的标题：按出现次数取前三类活动，拼成「已读取文件、执行了命令」。 */
@Composable
private fun doneTitle(summary: WorkSummary): String {
    val activities = summary.titleActivities
    if (activities.isEmpty()) return stringResource(R.string.work_step_done_thinking)
    val labels = activities.map { stringResource(workStepDoneLabelRes(it)) }
    val title = when (labels.size) {
        1 -> labels[0]
        2 -> {
            val prefix = stringResource(R.string.work_step_done_shared_prefix)
            val second = if (prefix.isNotEmpty() &&
                labels[0].startsWith(prefix) &&
                labels[1].startsWith(prefix)
            ) {
                labels[1].removePrefix(prefix)
            } else {
                labels[1]
            }
            stringResource(R.string.work_step_done_join_two, labels[0], second)
        }
        else -> labels.joinToString(stringResource(R.string.work_step_done_separator))
    }
    return if (summary.titleHasMore) stringResource(R.string.work_step_done_more, title) else title
}

/** 运行中的呼吸透明度：标题与圆点共用一个由亮到弱的循环。 */
@Composable
private fun rememberLivePulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "work-step-live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "work-step-live-alpha",
    )
    return alpha
}

@Composable
private fun LivePulse(modifier: Modifier = Modifier) {
    val alpha = rememberLivePulseAlpha()
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

@StringRes
private fun workStepLiveLabelRes(activity: WorkActivity): Int = when (activity) {
    WorkActivity.THINKING -> R.string.work_step_live_thinking
    WorkActivity.READ -> R.string.work_step_live_read
    WorkActivity.READ_IMAGE -> R.string.work_step_live_read_image
    WorkActivity.SEARCH -> R.string.work_step_live_search
    WorkActivity.WRITE -> R.string.work_step_live_write
    WorkActivity.EDIT -> R.string.work_step_live_edit
    WorkActivity.COMMANDS -> R.string.work_step_live_commands
    WorkActivity.CODE -> R.string.work_step_live_code
    WorkActivity.WEB_SEARCH -> R.string.work_step_live_web_search
    WorkActivity.WEB_FETCH -> R.string.work_step_live_web_fetch
    WorkActivity.SUBAGENTS -> R.string.work_step_live_subagents
    WorkActivity.PLAN -> R.string.work_step_live_plan
    WorkActivity.QUESTIONS -> R.string.work_step_live_questions
    WorkActivity.TOOLS -> R.string.work_step_live_tools
}

@StringRes
private fun workStepDoneLabelRes(activity: WorkActivity): Int = when (activity) {
    WorkActivity.THINKING -> R.string.work_step_done_thinking
    WorkActivity.READ -> R.string.work_step_done_read
    WorkActivity.READ_IMAGE -> R.string.work_step_done_read_image
    WorkActivity.SEARCH -> R.string.work_step_done_search
    WorkActivity.WRITE -> R.string.work_step_done_write
    WorkActivity.EDIT -> R.string.work_step_done_edit
    WorkActivity.COMMANDS -> R.string.work_step_done_commands
    WorkActivity.CODE -> R.string.work_step_done_code
    WorkActivity.WEB_SEARCH -> R.string.work_step_done_web_search
    WorkActivity.WEB_FETCH -> R.string.work_step_done_web_fetch
    WorkActivity.SUBAGENTS -> R.string.work_step_done_subagents
    WorkActivity.PLAN -> R.string.work_step_done_plan
    WorkActivity.QUESTIONS -> R.string.work_step_done_questions
    WorkActivity.TOOLS -> R.string.work_step_done_tools
}

@Composable
private fun WorkItem(message: ChatMessage, level: WorkStepDisplay, live: Boolean) {
    when (message.role) {
        ChatRole.THINKING -> ThinkingRow(message, level)
        ChatRole.TOOL -> ToolStepRow(message, level, live)
        // 中间说明：工作过程里的 assistant 文字，弱化显示。
        else -> {
            SelectionContainer {
                MarkdownText(
                    markdown = message.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    showCursor = message.streaming,
                )
            }
        }
    }
}

/**
 * 工具调用折叠行。
 *
 * - 简洁：只露「工具调用」标签；
 * - 标准 / 详细：露出工具名；
 * - 结果未回时标签后带「 · 调用中…」，与分组标题的实时活动一致；
 * - 展开后显示参数与输出；是否收进分组由档位决定。
 */
@Composable
private fun ToolStepRow(message: ChatMessage, level: WorkStepDisplay, live: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val toolName = message.meta?.takeIf { it.isNotBlank() }
    val output = message.text.takeIf { it.isNotBlank() && it != message.meta }
    // 工具结果信封（<path>/<type>/<content>）已在仓储层解析：这里只消费解析后的字段。
    val toolPath = message.toolPath?.trim()?.takeIf { it.isNotEmpty() }
    val isImageResult = message.toolType?.trim()?.equals("image", ignoreCase = true) == true
    val fileName = toolPathFileName(toolPath)
    val imageLabel = stringResource(R.string.gateway_tool_image)
    val sessionId = LocalImageSessionId.current
    // 图片信封的路径交给网关现场读；取不到图就不画，不影响输出文本。
    val previewUrl = remember(toolPath, isImageResult, sessionId) {
        if (isImageResult) markdownImageUrl(toolPath.orEmpty(), sessionId) else null
    }
    // key 里带上档位：切换「工作步骤展示」后按新档位重新取默认展开态，
    // 避免 LazyColumn 的 SaveableStateHolder 把上一档的展开状态张冠李戴。
    var expanded by rememberSaveable(
        message.id,
        level,
        key = "tool#${message.id}#${level.name}",
    ) {
        mutableStateOf(false)
    }

    val label = if (level.showsRowHint) {
        toolName ?: stringResource(R.string.gateway_tool_call)
    } else {
        stringResource(R.string.gateway_tool_call)
    }

    // 收起态摘要：优先文件名（image 结果再标出「图片」），没有路径时至少标出类型。
    val hint = if (level.showsRowHint) {
        when {
            fileName != null && isImageResult -> "$fileName · $imageLabel"
            fileName != null -> fileName
            isImageResult -> imageLabel
            else -> null
        }
    } else {
        null
    }

    StepRow(
        shape = ToolCardShape,
        label = label,
        labelColor = colorScheme.onSurface,
        hint = hint,
        streaming = live && message.running,
        streamingText = stringResource(R.string.gateway_tool_running),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // 只有展开时才做 JSON 美化：折叠状态下大参数不占主线程。
        val arguments = remember(message.arguments) { formatArguments(message.arguments) }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (image in message.images) {
                    RemoteImage(
                        url = attachmentPreviewUrl(image, sessionId),
                        contentDescription = image.name,
                        maxWidth = 300.dp,
                        maxHeight = 420.dp,
                    )
                }
                if (toolPath != null) {
                    Text(
                        text = stringResource(R.string.gateway_tool_file),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = toolPath,
                        color = colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                    if (previewUrl != null) {
                        RemoteImage(
                            url = previewUrl,
                            contentDescription = fileName,
                            maxWidth = 300.dp,
                            maxHeight = 420.dp,
                        )
                    }
                }
                if (!arguments.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.gateway_tool_args),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    CodeSurface(text = arguments)
                }
                if (!output.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.gateway_tool_output),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    CodeSurface(text = output)
                }
                if (arguments.isNullOrBlank() && output.isNullOrBlank() &&
                    message.images.isEmpty() && toolPath == null
                ) {
                    Text(
                        text = stringResource(R.string.gateway_tool_running),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/**
 * 折叠行通用骨架：一行「箭头 + 标签 + 可选摘要」，点按展开内容。
 * 思考行和工具行共用；档位只影响 [hint] 是否为空。
 */
@Composable
private fun StepRow(
    shape: RoundedCornerShape,
    label: String,
    labelColor: Color,
    hint: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    streaming: Boolean = false,
    streamingText: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.32f))
            .border(1.dp, colorScheme.outline.copy(alpha = 0.15f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = labelColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (streaming) {
                Text(
                    text = " · $streamingText",
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
            if (!expanded && !hint.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = hint,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                content()
            }
        }
    }
}

/** 工具参数 / 输出的等宽代码块：长内容内部滚动，横向可滚动。 */
@Composable
private fun CodeSurface(text: String) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(shape)
            .background(colorScheme.surface.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            color = colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

/** 工具参数优先按 JSON 美化；不是 JSON 就原样显示。 */
private fun formatArguments(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
    return runCatching {
        val element: JsonElement = prettyJson.parseToJsonElement(trimmed)
        prettyJson.encodeToString(JsonElement.serializer(), element)
    }.getOrDefault(trimmed)
}

@Composable
private fun SystemLine(message: ChatMessage) {
    val colorScheme = MaterialTheme.colorScheme
    // 系统通知（后台任务完成 / 子代理结算 / 团队消息…）可能很长：默认只显示 3 行，
    // 点按展开全文；它永远不是用户气泡，避免被误认为「我发的话」。
    var expanded by rememberSaveable(message.id, key = "sys#${message.id}") {
        mutableStateOf(false)
    }
    val canExpand = message.text.length > 120 || message.text.count { it == '\n' } > 2
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = message.text,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canExpand) { expanded = !expanded }
                .background(colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
