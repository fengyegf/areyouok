package com.catmoecircle.areyouok.ui.gateway

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole
import com.catmoecircle.areyouok.ui.gateway.markdown.MarkdownText
import com.catmoecircle.areyouok.ui.settings.WorkStepCollapseTiming
import com.catmoecircle.areyouok.ui.settings.WorkStepDisplay
import com.catmoecircle.areyouok.ui.settings.WorkStepFoldedReplies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val UserBubbleShape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
private val ToolCardShape = RoundedCornerShape(10.dp)
private val ThinkingShape = RoundedCornerShape(9.dp)
private val GroupShape = RoundedCornerShape(12.dp)
private val prettyJson = Json { prettyPrint = true }

/** 列表底部为输入区留出的空间（磨砂栏 + 展开态操作行 + 手势条）。 */
private val ComposerClearance = 168.dp

/**
 * 会话消息时间线。
 *
 * - user：右对齐气泡（纯文本，保留换行）；
 * - thinking：思考链折叠行（默认收起，流式时显示「思考中…」）；
 * - assistant：左对齐 Markdown 正文，流式时末尾带光标；
 * - tool：按 [WorkStepDisplay] 渲染工作步骤折叠行；
 * - 一轮工作完成后（[running] 为 false）：工具/思考/中间说明折叠成一条
 *   「工作步骤 · N 项 · 已完成」分组，助手回复按「收起后显示的回复」保留在分组外；
 *   「完全展开」档不分组，工作步骤按普通消息逐条展示；
 * - system：系统通知（后台任务完成、子代理结算、团队消息等）折叠灰字行，
 *   点按展开——它永远不会画成用户自己的话；
 * - 顶部还有更早历史时（[hasOlder]）滚到顶自动加载上一页，并保持阅读位置。
 *
 * 前三档（简洁 / 标准 / 详细）共用两层折叠：先展开分组，再展开组内的思考 / 工具行，
 * 档位只决定折叠行的摘要露出多少；「完全展开」不折叠分组，但每行自身仍默认折叠
 * （见 [WorkStepDisplay]）。收起时机与保留哪些回复见 [WorkStepCollapseTiming] /
 * [WorkStepFoldedReplies]。
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    // 键盘弹起时输入区整体上移，列表底部也要让出同样的高度，
    // 否则最新消息会留在输入框/键盘后面看不见。
    val imeBottom = with(density) { imeInsets.getBottom(density).toDp() }

    val groupWorkSteps = workStepDisplay.groupsWorkSteps
    val deferCollapse = workStepCollapseTiming == WorkStepCollapseTiming.NEXT_INPUT
    val keepAllReplies = workStepFoldedReplies == WorkStepFoldedReplies.ALL
    val entries = remember(messages, running, groupWorkSteps, deferCollapse, keepAllReplies) {
        buildTimelineEntries(
            messages,
            running,
            groupWorkSteps = groupWorkSteps,
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
        if (!countChanged && !listState.isAtBottom()) return@LaunchedEffect
        listState.scrollToItem(entries.lastIndex + headerCount)
        settled = true
        // 最后一条可能比屏幕高：scrollToItem 只对齐顶部，再补一次溢出量让视口停在尾部。
        val info = listState.layoutInfo
        val lastInfo = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val overflow = lastInfo.offset + lastInfo.size - info.viewportEndOffset
        if (overflow > 0) {
            runCatching<Unit> { listState.scrollBy(overflow.toFloat()) }
        }
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

    // 键盘升降是一段逐帧动画：输入区由输入框的 IME inset 抬起，这里让列表按同样的
    // 增量滚动，最新消息就会跟着键盘同步上移/回落，而不是等键盘到位后被盖住。
    // 只在用户本来贴着底部时补偿，正在上滑读历史时不动。
    LaunchedEffect(listState, density, imeInsets) {
        var lastImeBottom = imeInsets.getBottom(density)
        snapshotFlow { imeInsets.getBottom(density) }.collect { current ->
            val delta = current - lastImeBottom
            lastImeBottom = current
            if (delta == 0) return@collect
            if (listState.layoutInfo.totalItemsCount == 0) return@collect
            if (!listState.isAtBottom()) return@collect
            listState.scrollBy(delta.toFloat())
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 8.dp,
            // 输入区留白 + 键盘高度：键盘弹起时最新消息停在输入框上方。
            bottom = ComposerClearance + imeBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasOlder) {
            item(key = "__older_history__") {
                OlderHistoryRow(loading = loadingOlder, onLoad = onLoadOlder)
            }
        }
        items(count = entries.size, key = { index -> keys[index] }) { index ->
            when (val entry = entries[index]) {
                is TimelineEntry.Single -> MessageRow(entry.message, workStepDisplay)
                is TimelineEntry.WorkGroup -> WorkGroupCard(entry, workStepDisplay)
            }
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
private fun MessageRow(message: ChatMessage, workStepDisplay: WorkStepDisplay) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantBubble(message)
        ChatRole.THINKING -> ThinkingRow(message, workStepDisplay)
        ChatRole.TOOL -> ToolStepRow(message, workStepDisplay)
        ChatRole.SYSTEM -> SystemLine(message)
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    val colorScheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
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

/** 工作步骤折叠组：工作结束后只留一条摘要，展开才看工具/思考/中间说明。 */
@Composable
private fun WorkGroupCard(group: TimelineEntry.WorkGroup, level: WorkStepDisplay) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable(group.key, key = "work#${group.key}") {
        mutableStateOf(false)
    }
    val toolCount = group.items.count { it.role == ChatRole.TOOL }
    val count = if (toolCount > 0) toolCount else group.items.size
    val countText = stringResource(R.string.gateway_work_group_count, count)

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
            Text(
                text = stringResource(R.string.gateway_work_group_title),
                color = colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· $countText",
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.gateway_work_group_done),
                color = colorScheme.primary.copy(alpha = 0.85f),
                fontSize = 11.sp,
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
                    WorkItem(item, level)
                }
            }
        }
    }
}

@Composable
private fun WorkItem(message: ChatMessage, level: WorkStepDisplay) {
    when (message.role) {
        ChatRole.THINKING -> ThinkingRow(message, level)
        ChatRole.TOOL -> ToolStepRow(message, level)
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
 * - 标准：露出工具名；
 * - 详细：工具名 + 输出首行（对齐上一版标准）；
 * - 完全展开：工作步骤不再收进分组，但工具行本身仍默认折叠，点按展开参数与输出。
 */
@Composable
private fun ToolStepRow(message: ChatMessage, level: WorkStepDisplay) {
    val colorScheme = MaterialTheme.colorScheme
    val toolName = message.meta?.takeIf { it.isNotBlank() }
    val output = message.text.takeIf { it.isNotBlank() && it != message.meta }
    // key 里带上档位：切换「工作步骤显示」后按新档位重新取默认展开态，
    // 避免 LazyColumn 的 SaveableStateHolder 把上一档的展开状态张冠李戴。
    var expanded by rememberSaveable(
        message.id,
        level,
        key = "tool#${message.id}#${level.name}",
    ) {
        mutableStateOf(false)
    }

    val label = if (level.showsRowHint) {
        toolName ?: stringResource(R.string.gateway_tool_prefix)
    } else {
        stringResource(R.string.gateway_tool_call)
    }
    val hint = if (level.showsOutputPreview) {
        output?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }

    StepRow(
        shape = ToolCardShape,
        label = label,
        labelColor = colorScheme.onSurface,
        hint = hint,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // 只有展开时才做 JSON 美化：折叠状态下大参数不占主线程。
        val arguments = remember(message.arguments) { formatArguments(message.arguments) }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                if (arguments.isNullOrBlank() && output.isNullOrBlank()) {
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
