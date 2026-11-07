package com.catmoecircle.areyouok.ui.gateway.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R

/** 手机端正文基准字号 / 行高（与聊天正文一致，表格 / 代码按比例缩小）。 */
private val BodyFontSize = 15.sp
private val BodyLineHeight = 23.sp

/**
 * 把 Markdown 消息渲染成手机友好的聊天样式：
 *
 * - 标题按层级缩放（h1 20sp → h4+ 15sp），不出现桌面端那样夸张的字号；
 * - 段落保留换行，列表带缩进与项目符号，嵌套列表逐级缩进；
 * - 代码块等宽字体 + 横向滚动 + 独立卡片 + 一键复制，长代码块内部纵向滚动；
 * - 表格可横向滚动，表头加粗、列宽按内容估算，避免窄屏挤压；
 * - 引用块左侧色条 + 次要文字色，GFM `[!NOTE]` 等告警额外提供彩色标题；
 * - 链接用主题色 + 下划线，点按交给系统浏览器；
 * - [showCursor] 为 true 时在末尾渲染流式光标（▍）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = BodyFontSize,
    lineHeight: TextUnit = BodyLineHeight,
    showCursor: Boolean = false,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    if (blocks.isEmpty()) {
        if (showCursor) CursorLine()
        return
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            MdBlockView(
                block = block,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                showCursor = showCursor && isLast,
            )
            // 代码块 / 表格 / 分隔线：光标单独补一行，避免插进结构里。
            if (isLast && showCursor && block !is MdBlock.Paragraph && block !is MdBlock.Heading &&
                block !is MdBlock.ListBlock && block !is MdBlock.Quote
            ) {
                CursorLine()
            }
        }
    }
}

@Composable
private fun CursorLine() {
    Text(
        text = "▍",
        color = MaterialTheme.colorScheme.primary,
        fontSize = BodyFontSize,
        lineHeight = BodyLineHeight,
    )
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    showCursor: Boolean,
) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(
            inlines = block.inlines,
            style = TextStyle(fontSize = fontSize, lineHeight = lineHeight, color = color),
            modifier = Modifier.fillMaxWidth(),
            showCursor = showCursor,
        )

        is MdBlock.Heading -> HeadingView(block, color, showCursor)

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.Quote -> QuoteView(block, color, fontSize, lineHeight, showCursor)

        is MdBlock.ListBlock -> ListView(block, color, fontSize, lineHeight, showCursor)

        is MdBlock.Table -> TableView(block, color)

        MdBlock.ThematicBreak -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 3.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
        )
    }
}

@Composable
private fun HeadingView(block: MdBlock.Heading, color: Color, showCursor: Boolean) {
    val size: TextUnit = when (block.level) {
        1 -> 20.sp
        2 -> 18.sp
        3 -> 16.5.sp
        else -> 15.sp
    }
    val weight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold
    val lineHeight = (size.value * 1.35f).sp
    InlineText(
        inlines = block.inlines,
        style = TextStyle(
            fontSize = size,
            lineHeight = lineHeight,
            fontWeight = weight,
            color = color,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (block.level <= 2) 5.dp else 2.dp),
        showCursor = showCursor,
    )
}

@Composable
private fun ListView(
    block: MdBlock.ListBlock,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    showCursor: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = when {
                        item.checked != null -> if (item.checked) "☑" else "☐"
                        block.ordered -> "${block.start + index}."
                        else -> "•"
                    },
                    color = if (item.checked == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        color.copy(alpha = 0.75f)
                    },
                    fontSize = 14.sp,
                    lineHeight = lineHeight,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(if (block.ordered) 28.dp else 20.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val itemBlocks = item.blocks
                    if (itemBlocks.isEmpty()) {
                        Text(text = " ", fontSize = fontSize, lineHeight = lineHeight, color = color)
                    } else {
                        itemBlocks.forEachIndexed { childIndex, child ->
                            MdBlockView(
                                block = child,
                                color = color,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                showCursor = showCursor && index == block.items.lastIndex &&
                                    childIndex == itemBlocks.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteView(
    block: MdBlock.Quote,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    showCursor: Boolean,
) {
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val alertColor = block.alert?.let { alertColor(it, isLightTheme) }
    val barColor = alertColor ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(3.dp)
                .heightIn(min = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            block.alert?.let { alert ->
                Text(
                    text = alertTitle(alert),
                    color = alertColor ?: color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val quoteColor = color.copy(alpha = 0.82f)
            block.blocks.forEachIndexed { index, child ->
                MdBlockView(
                    block = child,
                    color = quoteColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    showCursor = showCursor && index == block.blocks.lastIndex,
                )
            }
        }
    }
}

private fun alertTitle(alert: MdAlert): String = when (alert) {
    MdAlert.NOTE -> "备注"
    MdAlert.TIP -> "提示"
    MdAlert.IMPORTANT -> "重要"
    MdAlert.WARNING -> "警告"
    MdAlert.CAUTION -> "注意"
}

private fun alertColor(alert: MdAlert, lightTheme: Boolean): Color = if (lightTheme) {
    // 浅色背景上用加深版本，保证 12sp 文本达到 WCAG AA。
    when (alert) {
        MdAlert.NOTE -> Color(0xFF1F5FCC)
        MdAlert.TIP -> Color(0xFF17712C)
        MdAlert.IMPORTANT -> Color(0xFF6E2CB0)
        MdAlert.WARNING -> Color(0xFF9A5B00)
        MdAlert.CAUTION -> Color(0xFFB3261E)
    }
} else {
    when (alert) {
        MdAlert.NOTE -> Color(0xFF7FA9FF)
        MdAlert.TIP -> Color(0xFF5FD37A)
        MdAlert.IMPORTANT -> Color(0xFFC08BEA)
        MdAlert.WARNING -> Color(0xFFFFB74D)
        MdAlert.CAUTION -> Color(0xFFFF8A80)
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val copiedText = stringResource(R.string.markdown_code_copied)
    val copyText = stringResource(R.string.markdown_code_copy)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, colorScheme.outline.copy(alpha = 0.16f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language?.lowercase() ?: if (block.closed) "code" else "输出中…",
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = copyText,
                color = colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(block.code) {
                        detectTapGestures {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("markdown-code", block.code))
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = block.code.ifEmpty { " " },
                color = colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TableView(block: MdBlock.Table, color: Color) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val cellPadding = 10.dp

    // 列宽按内容字符数估算，窄屏下整体横向滚动，避免每格都被挤成一行一个字。
    val widths = remember(block) {
        val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
        List(columns) { index ->
            val headerLength = block.header.getOrNull(index)?.let { plainLength(it) } ?: 0
            val rowLength = block.rows.maxOfOrNull { plainLength(it.getOrNull(index).orEmpty()) } ?: 0
            val chars = maxOf(headerLength, rowLength, 3)
            (chars * 7.2f + 22f).coerceIn(64f, 190f).dp
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.18f), shape),
    ) {
        // 表头与表体共用一个横向滚动状态，左右滑动时列始终对齐。
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.background(colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            ) {
                block.header.forEachIndexed { index, cell ->
                    TableCell(
                        inlines = cell,
                        width = widths.getOrElse(index) { 80.dp },
                        align = block.alignments.getOrNull(index) ?: MdAlign.NONE,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        padding = cellPadding,
                    )
                }
            }
            block.rows.forEachIndexed { rowIndex, row ->
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.14f), thickness = 1.dp)
                Row(
                    modifier = Modifier.background(
                        if (rowIndex % 2 == 1) colorScheme.surfaceVariant.copy(alpha = 0.22f) else Color.Transparent,
                    ),
                ) {
                    widths.indices.forEach { index ->
                        TableCell(
                            inlines = row.getOrNull(index).orEmpty(),
                            width = widths[index],
                            align = block.alignments.getOrNull(index) ?: MdAlign.NONE,
                            color = color,
                            fontWeight = FontWeight.Normal,
                            padding = cellPadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    inlines: List<MdInline>,
    width: Dp,
    align: MdAlign,
    color: Color,
    fontWeight: FontWeight,
    padding: Dp,
) {
    val textAlign = when (align) {
        MdAlign.CENTER -> TextAlign.Center
        MdAlign.RIGHT -> TextAlign.End
        else -> TextAlign.Start
    }
    InlineText(
        inlines = inlines,
        style = TextStyle(
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
        ),
        modifier = Modifier
            .width(width)
            .padding(horizontal = padding, vertical = 8.dp),
    )
}

private fun plainLength(inlines: List<MdInline>): Int {
    var length = 0
    fun visit(nodes: List<MdInline>) {
        for (node in nodes) {
            when (node) {
                is MdInline.Text -> length += displayLength(node.text)
                is MdInline.Code -> length += displayLength(node.text)
                is MdInline.Strong -> visit(node.children)
                is MdInline.Emphasis -> visit(node.children)
                is MdInline.Strikethrough -> visit(node.children)
                is MdInline.Link -> visit(node.children)
                is MdInline.Image -> length += displayLength(node.alt)
                MdInline.LineBreak -> length += 1
            }
        }
    }
    visit(inlines)
    return length
}

/** 估算一行的显示宽度：中文 / 全角字符按 2 个宽度算，拉丁字符按 1。 */
private fun displayLength(text: String): Int {
    var width = 0
    for (char in text) {
        width += if (char.isWide()) 2 else 1
    }
    return width
}

private fun Char.isWide(): Boolean {
    val code = code
    return code in 0x1100..0x115F ||
        code in 0x2E80..0x303E ||
        code in 0x3041..0x33FF ||
        code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xA000..0xA4CF ||
        code in 0xAC00..0xD7A3 ||
        code in 0xF900..0xFAFF ||
        code in 0xFE30..0xFE4F ||
        code in 0xFF00..0xFF60 ||
        code in 0xFFE0..0xFFE6 ||
        code in 0x1F300..0x1FAFF
}

private data class LinkRange(val start: Int, val end: Int, val url: String)

private data class RenderedInline(
    val text: AnnotatedString,
    val links: List<LinkRange>,
)

/**
 * 一段行内文本：把 [MdInline] 映射成 [AnnotatedString]（粗体 / 斜体 / 删除线 / 行内代码 / 链接），
 * 并按链接区间处理点按（打开系统浏览器）。
 */
@Composable
private fun InlineText(
    inlines: List<MdInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val linkColor = colorScheme.primary
    val codeBackground = colorScheme.surfaceVariant.copy(alpha = 0.85f)

    val rendered = remember(inlines, style, showCursor, linkColor, codeBackground) {
        buildInlineString(inlines, style, linkColor, codeBackground, showCursor)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = rendered.text,
        style = style,
        modifier = modifier.pointerInput(rendered) {
            detectTapGestures { position ->
                val layoutResult = layout ?: return@detectTapGestures
                val offset = layoutResult.getOffsetForPosition(position)
                val link = rendered.links.firstOrNull { offset in it.start until it.end }
                    ?: return@detectTapGestures
                runCatching { uriHandler.openUri(link.url) }
            }
        },
        onTextLayout = { layout = it },
    )
}

private fun buildInlineString(
    inlines: List<MdInline>,
    style: TextStyle,
    linkColor: Color,
    codeBackground: Color,
    showCursor: Boolean,
): RenderedInline {
    val links = ArrayList<LinkRange>()
    val baseSize = style.fontSize.takeIf { it.isSp } ?: BodyFontSize
    val linkDecoration = TextDecoration.Underline

    val text = buildAnnotatedString {
        fun appendText(text: String, bold: Boolean, italic: Boolean, strike: Boolean) {
            val span = SpanStyle(
                fontWeight = if (bold) FontWeight.SemiBold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (strike) TextDecoration.LineThrough else null,
            )
            if (span == SpanStyle()) append(text) else withStyle(span) { append(text) }
        }

        fun appendNodes(
            nodes: List<MdInline>,
            bold: Boolean = false,
            italic: Boolean = false,
            strike: Boolean = false,
        ) {
            for (node in nodes) {
                when (node) {
                    is MdInline.Text -> appendText(node.text, bold, italic, strike)
                    MdInline.LineBreak -> append('\n')
                    is MdInline.Code -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseSize.value * 0.92f).sp,
                            background = codeBackground,
                        ),
                    ) {
                        append(node.text)
                    }

                    is MdInline.Strong -> appendNodes(node.children, bold = true, italic = italic, strike = strike)
                    is MdInline.Emphasis -> appendNodes(node.children, bold = bold, italic = true, strike = strike)
                    is MdInline.Strikethrough -> appendNodes(node.children, bold = bold, italic = italic, strike = true)
                    is MdInline.Link -> {
                        val start = length
                        withStyle(
                            SpanStyle(color = linkColor, textDecoration = linkDecoration),
                        ) {
                            appendNodes(node.children, bold, italic, strike)
                        }
                        links += LinkRange(start, length, node.url)
                    }

                    is MdInline.Image -> {
                        val start = length
                        withStyle(
                            SpanStyle(color = linkColor, textDecoration = linkDecoration),
                        ) {
                            append("🖼 ")
                            append(node.alt)
                        }
                        links += LinkRange(start, length, node.url)
                    }
                }
            }
        }

        appendNodes(inlines)
        if (showCursor) {
            withStyle(SpanStyle(color = linkColor)) { append(" ▍") }
        }
    }

    return RenderedInline(text, links)
}
