package com.catmoecircle.areyouok.ui.gateway.markdown

/**
 * 聊天消息的 Markdown 解析结果。
 *
 * 大模型输出的正文是 Markdown（标题 / 列表 / 代码块 / 表格 / 引用 / 加粗链接等），
 * 手机端按块渲染才能有正确的层级、缩进与代码样式。这里只做「解析成结构」，
 * 不依赖 Android / Compose，方便 JVM 单测直接覆盖。
 *
 * 支持范围（覆盖 DSH / 大模型常见输出）：
 * - ATX 标题 `#`~`######`、Setext 标题（`===` / `---`）；
 * - 段落与换行（源文本里的换行保留为换行，适合聊天阅读）；
 * - 围栏代码块 ``` / ~~~（带语言；流式未闭合时按输出中处理）；
 * - 无序 / 有序 / 嵌套列表、任务列表 `- [ ]` / `- [x]`；
 * - 引用块、GFM 告警（`> [!NOTE]`）、GFM 表格、分隔线；
 * - 行内：`code`、**粗体**、*斜体*、~~删除线~~、[链接](url)、![图片](url)、
 *   自动链接（`<https://…>` 与裸 `http(s)://…`）、`<br>`、反斜杠转义、HTML 标签剥离。
 *
 * 解析结果不可变；渲染层（MarkdownText.kt）负责映射成 Compose。
 */
sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock

    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
        /** 流式输出中围栏还没闭合时为 false（UI 可显示「输出中」）。 */
        val closed: Boolean,
    ) : MdBlock

    data class Quote(
        val alert: MdAlert?,
        val blocks: List<MdBlock>,
    ) : MdBlock

    data class ListBlock(
        val ordered: Boolean,
        /** 有序列表起始序号（`3. foo` 从 3 开始）。 */
        val start: Int,
        val items: List<MdListItem>,
    ) : MdBlock

    data class Table(
        val alignments: List<MdAlign>,
        val header: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock

    data object ThematicBreak : MdBlock
}

data class MdListItem(
    /** 任务列表：null = 普通项，false = 未勾选，true = 已勾选。 */
    val checked: Boolean?,
    val blocks: List<MdBlock>,
)

sealed interface MdInline {
    data class Text(val text: String) : MdInline

    data class Code(val text: String) : MdInline

    data class Strong(val children: List<MdInline>) : MdInline

    data class Emphasis(val children: List<MdInline>) : MdInline

    data class Strikethrough(val children: List<MdInline>) : MdInline

    data class Link(val children: List<MdInline>, val url: String) : MdInline

    /** 图片在手机聊天里不加载，退化成可点的说明文字。 */
    data class Image(val alt: String, val url: String) : MdInline

    data object LineBreak : MdInline
}

enum class MdAlign { NONE, LEFT, CENTER, RIGHT }

/** GFM 告警块（`> [!NOTE]` 等）。 */
enum class MdAlert { NOTE, TIP, IMPORTANT, WARNING, CAUTION }

/**
 * 单遍扫描的 Markdown 解析器。
 *
 * 设计取舍：不追求完整 CommonMark（聊天场景用不到引用式链接、HTML 块等），
 * 但要求流式输出下不崩、不吞字：未闭合的代码围栏、加粗、行内代码都按字面渲染。
 */
object MarkdownParser {

    private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})\\s*(\\S*).*$")
    private val atxHeadingPattern = Regex("^ {0,3}(#{1,6})(?:\\s+(.*))?$")
    private val setextPattern = Regex("^ {0,3}(={3,}|-{3,})\\s*$")
    private val thematicBreakPattern = Regex("^ {0,3}(?:\\*\\s*){3,}$|^ {0,3}(?:-\\s*){3,}$|^ {0,3}(?:_\\s*){3,}$")
    private val bulletPattern = Regex("^(\\s*)([-+*])(\\s+)(.*)$")
    private val orderedPattern = Regex("^(\\s*)(\\d{1,9})([.)])(\\s+)(.*)$")
    private val taskPattern = Regex("^\\[([ xX])]\\s+(.*)$")
    private val tableDelimiterCell = Regex("^:?-{1,}:?$")
    private val alertPattern = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*$", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<[A-Za-z/!][^>]*>")
    private val titledDestination = Regex("^(\\S+?)\\s+(\".*\"|'.*'|\\(.*\\))$")

    fun parse(source: String): List<MdBlock> {
        if (source.isBlank()) return emptyList()
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        return parseLines(normalized.split('\n'))
    }

    // ---------------------------------------------------------------- blocks

    private fun parseLines(lines: List<String>): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            // 围栏代码块（流式时可能没有结尾围栏）。
            val fence = parseFence(line)
            if (fence != null) {
                var j = i + 1
                val code = StringBuilder()
                var closed = false
                while (j < lines.size) {
                    if (isClosingFence(lines[j], fence.marker)) {
                        closed = true
                        j++
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[j])
                    j++
                }
                blocks += MdBlock.CodeBlock(fence.language, code.toString(), closed)
                i = j
                continue
            }

            // ATX 标题
            val heading = atxHeadingPattern.find(line)
            if (heading != null) {
                blocks += MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2].trim()))
                i++
                continue
            }

            // 分隔线
            if (thematicBreakPattern.matches(line)) {
                blocks += MdBlock.ThematicBreak
                i++
                continue
            }

            // 引用
            if (line.trimStart().startsWith(">")) {
                val quoted = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted += stripQuoteMarker(lines[i])
                    i++
                }
                var alert: MdAlert? = null
                if (quoted.isNotEmpty()) {
                    alertPattern.find(quoted.first().trim())?.let { match ->
                        alert = runCatching { MdAlert.valueOf(match.groupValues[1].uppercase()) }.getOrNull()
                        if (alert != null) quoted.removeAt(0)
                    }
                }
                blocks += MdBlock.Quote(alert, parseLines(quoted))
                continue
            }

            // 表格
            if (i + 1 < lines.size && isTableStart(line, lines[i + 1])) {
                val headerCells = splitTableRow(line)
                val rawRows = ArrayList<List<String>>()
                var j = i + 2
                while (j < lines.size) {
                    val row = lines[j]
                    if (row.isBlank() || !row.contains('|')) break
                    if (startsOtherBlock(row)) break
                    rawRows += splitTableRow(row)
                    j++
                }
                // 列数以表头与所有行里最多的为准：多余单元格不丢，缺失的补空。
                val columns = maxOf(headerCells.size, rawRows.maxOfOrNull { it.size } ?: 0)
                fun padded(cells: List<String>): List<String> =
                    List(columns) { index -> cells.getOrElse(index) { "" }.trim() }
                val alignments = parseAlignments(lines[i + 1])
                blocks += MdBlock.Table(
                    alignments = List(columns) { index -> alignments.getOrElse(index) { MdAlign.NONE } },
                    header = padded(headerCells).map(::parseInline),
                    rows = rawRows.map { cells -> padded(cells).map(::parseInline) },
                )
                i = j
                continue
            }

            // 列表
            if (matchListMarker(line) != null) {
                val (list, next) = parseList(lines, i)
                blocks += list
                i = next
                continue
            }

            // Setext 标题：一行正文 + === / ---
            if (i + 1 < lines.size && setextPattern.matches(lines[i + 1]) && !startsOtherBlock(line)) {
                val level = if (lines[i + 1].trim().startsWith("=")) 1 else 2
                blocks += MdBlock.Heading(level, parseInline(line.trim()))
                i += 2
                continue
            }

            // 段落
            val paragraph = ArrayList<String>()
            var j = i
            while (j < lines.size) {
                val current = lines[j]
                if (current.isBlank()) break
                if (paragraph.isNotEmpty() && startsOtherBlock(current)) break
                paragraph += current
                j++
            }
            if (paragraph.isEmpty()) {
                // 理论上到不了；防御性前进，避免死循环。
                i++
                continue
            }
            blocks += MdBlock.Paragraph(joinParagraph(paragraph))
            i = j
        }
        return blocks
    }

    private fun joinParagraph(lines: List<String>): List<MdInline> {
        // 段落内的软换行保留成换行：聊天场景里模型常按行排版，挤成一段反而难读。
        return parseInline(lines.joinToString("\n") { it.trimStart() })
    }

    private data class Fence(val marker: Char, val language: String?)

    private fun parseFence(line: String): Fence? {
        val match = fencePattern.find(line) ?: return null
        val marker = match.groupValues[1].first()
        return Fence(marker, match.groupValues[2].takeIf { it.isNotBlank() })
    }

    private fun isClosingFence(line: String, marker: Char): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        return trimmed.all { it == marker }
    }

    private fun startsOtherBlock(line: String): Boolean {
        val trimmed = line.trimStart()
        return parseFence(line) != null ||
            atxHeadingPattern.matches(line) ||
            thematicBreakPattern.matches(line) ||
            trimmed.startsWith(">") ||
            bulletPattern.matches(line) ||
            orderedPattern.matches(line) ||
            setextPattern.matches(line)
    }

    private fun stripQuoteMarker(line: String): String {
        var rest = line.trimStart()
        if (rest.startsWith(">")) rest = rest.substring(1)
        if (rest.startsWith(" ")) rest = rest.substring(1)
        return rest
    }

    // ----------------------------------------------------------------- lists

    private data class ListMarker(
        val indent: Int,
        val ordered: Boolean,
        val number: Int,
        val contentIndent: Int,
        val content: String,
    )

    private fun matchListMarker(line: String): ListMarker? {
        bulletPattern.find(line)?.let { match ->
            val indent = match.groupValues[1].length
            val spaces = match.groupValues[3].length
            return ListMarker(
                indent = indent,
                ordered = false,
                number = 1,
                contentIndent = indent + 1 + spaces,
                content = match.groupValues[4],
            )
        }
        orderedPattern.find(line)?.let { match ->
            val indent = match.groupValues[1].length
            val digits = match.groupValues[2].length
            val spaces = match.groupValues[4].length
            return ListMarker(
                indent = indent,
                ordered = true,
                number = match.groupValues[2].toIntOrNull() ?: 1,
                contentIndent = indent + digits + 1 + spaces,
                content = match.groupValues[5],
            )
        }
        return null
    }

    private fun parseList(lines: List<String>, start: Int): Pair<MdBlock.ListBlock, Int> {
        val first = matchListMarker(lines[start]) ?: return MdBlock.ListBlock(false, 1, emptyList()) to start + 1
        val baseIndent = first.indent
        val ordered = first.ordered
        val items = ArrayList<MdListItem>()
        var i = start

        while (i < lines.size) {
            val marker = matchListMarker(lines[i]) ?: break
            if (marker.indent != baseIndent || marker.ordered != ordered) break

            val itemLines = ArrayList<String>()
            var checked: Boolean? = null
            var firstContent = marker.content
            taskPattern.find(firstContent)?.let { task ->
                checked = task.groupValues[1].equals("x", ignoreCase = true)
                firstContent = task.groupValues[2]
            }
            itemLines += firstContent
            i++

            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    // 空行后如果仍是本项更深的续行则继续；否则列表结束。
                    var j = i
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j >= lines.size) {
                        i = j
                        break
                    }
                    val nextMarker = matchListMarker(lines[j])
                    val nextIndent = indentOf(lines[j])
                    if (nextMarker != null && nextMarker.indent == baseIndent) break
                    if (nextIndent > baseIndent) {
                        itemLines += ""
                        i++
                        continue
                    }
                    break
                }
                val nextMarker = matchListMarker(line)
                if (nextMarker != null && nextMarker.indent == baseIndent) break
                if (indentOf(line) <= baseIndent) break
                // 去掉本项内容缩进，嵌套列表 / 代码块相对本项从 0 重新开始。
                val strip = minOf(indentOf(line), marker.contentIndent)
                itemLines += line.substring(strip)
                i++
            }

            items += MdListItem(checked, parseLines(itemLines))
        }

        return MdBlock.ListBlock(ordered, first.number, items) to i
    }

    private fun indentOf(line: String): Int {
        var count = 0
        for (char in line) {
            if (char == ' ') count++ else break
        }
        return count
    }

    // ---------------------------------------------------------------- tables

    private fun isTableStart(header: String, delimiter: String): Boolean {
        if (!header.contains('|') || !delimiter.contains('|')) return false
        val cells = splitTableRow(delimiter)
        return cells.isNotEmpty() && cells.all { tableDelimiterCell.matches(it.trim()) }
    }

    private fun parseAlignments(delimiter: String): List<MdAlign> =
        splitTableRow(delimiter).map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(":") && trimmed.endsWith(":") -> MdAlign.CENTER
                trimmed.endsWith(":") -> MdAlign.RIGHT
                trimmed.startsWith(":") -> MdAlign.LEFT
                else -> MdAlign.NONE
            }
        }

    /**
     * 按 `|` 拆表格行，但 `\|` 转义与行内代码（`...`）里的 `|` 不拆。
     *
     * 真实 DSH 表格里经常直接写 `` `a|b|c` ``（未转义），按 GFM 严格解析会把列拆错、
     * 尾部单元格丢失；这里对代码 span 内做容错。
     */
    private fun splitTableRow(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|") && !row.endsWith("\\|")) row = row.substring(0, row.length - 1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var codeTicks = 0
        var i = 0
        while (i < row.length) {
            val char = row[i]
            if (char == '\\' && i + 1 < row.length) {
                if (row[i + 1] == '|') {
                    current.append('|')
                } else {
                    current.append(char).append(row[i + 1])
                }
                i += 2
                continue
            }
            if (char == '`') {
                var run = 0
                while (i + run < row.length && row[i + run] == '`') run++
                if (codeTicks == 0) {
                    codeTicks = run
                } else if (run == codeTicks) {
                    codeTicks = 0
                }
                current.append(row, i, i + run)
                i += run
                continue
            }
            if (char == '|' && codeTicks == 0) {
                cells += current.toString().trim()
                current.clear()
                i++
                continue
            }
            current.append(char)
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    // ---------------------------------------------------------------- inline

    fun parseInline(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        return InlineScanner(text).scan(0, text.length)
    }

    private class InlineScanner(private val text: String) {

        fun scan(from: Int, to: Int): List<MdInline> {
            val nodes = ArrayList<MdInline>()
            val buffer = StringBuilder()
            fun flush() {
                if (buffer.isNotEmpty()) {
                    nodes += MdInline.Text(buffer.toString())
                    buffer.clear()
                }
            }

            var i = from
            while (i < to) {
                val char = text[i]
                when {
                    char == '\n' -> {
                        flush()
                        nodes += MdInline.LineBreak
                        i++
                    }

                    char == '\\' && i + 1 < to && text[i + 1].isEscapable() -> {
                        buffer.append(text[i + 1])
                        i += 2
                    }

                    char == '`' -> {
                        val run = runLength(i, to, '`')
                        val close = findClosingRun(i + run, to, '`', run)
                        if (close == null) {
                            buffer.append(text, i, i + run)
                            i += run
                        } else {
                            flush()
                            var code = text.substring(i + run, close)
                            if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
                                code = code.substring(1, code.length - 1)
                            }
                            nodes += MdInline.Code(code)
                            i = close + run
                        }
                    }

                    char == '*' || char == '_' -> {
                        // 连续 1/2/3 个定界符分别对应斜体 / 粗体 / 粗斜体；更长的连续串按字面处理。
                        val run = runLength(i, to, char)
                        val delimiterLength = when {
                            run >= 3 -> 3
                            run == 2 -> 2
                            else -> 1
                        }
                        val intraword = char == '_' && i > from && text[i - 1].isLetterOrDigit() &&
                            i + delimiterLength < to && text[i + delimiterLength].isLetterOrDigit()
                        val close = if (intraword) {
                            null
                        } else {
                            findClosingDelimiter(i + delimiterLength, to, char, delimiterLength)
                        }
                        if (close == null) {
                            buffer.append(text, i, i + run)
                            i += run
                        } else {
                            flush()
                            val children = scan(i + delimiterLength, close)
                            nodes += when (delimiterLength) {
                                3 -> MdInline.Strong(listOf(MdInline.Emphasis(children)))
                                2 -> MdInline.Strong(children)
                                else -> MdInline.Emphasis(children)
                            }
                            i = close + delimiterLength
                        }
                    }

                    char == '~' && i + 1 < to && text[i + 1] == '~' -> {
                        val close = findClosingDelimiter(i + 2, to, '~', 2)
                        if (close == null) {
                            buffer.append("~~")
                            i += 2
                        } else {
                            flush()
                            nodes += MdInline.Strikethrough(scan(i + 2, close))
                            i = close + 2
                        }
                    }

                    char == '!' && i + 1 < to && text[i + 1] == '[' -> {
                        val image = parseLinkLike(i + 1, to, isImage = true)
                        if (image == null) {
                            buffer.append('!')
                            i++
                        } else {
                            flush()
                            nodes += image.first
                            i = image.second
                        }
                    }

                    char == '[' -> {
                        val link = parseLinkLike(i, to, isImage = false)
                        if (link == null) {
                            buffer.append('[')
                            i++
                        } else {
                            flush()
                            nodes += link.first
                            i = link.second
                        }
                    }

                    char == '<' -> {
                        val handled = parseAngle(i, to)
                        if (handled == null) {
                            buffer.append('<')
                            i++
                        } else {
                            flush()
                            val inline = handled.first
                            if (inline !is MdInline.Text || inline.text.isNotEmpty()) {
                                nodes += inline
                            }
                            i = handled.second
                        }
                    }

                    char == 'h' && startsWithUrl(i, to) -> {
                        val url = scanBareUrl(i, to)
                        if (url == null) {
                            buffer.append(char)
                            i++
                        } else {
                            flush()
                            nodes += MdInline.Link(listOf(MdInline.Text(url)), url)
                            i += url.length
                        }
                    }

                    else -> {
                        buffer.append(char)
                        i++
                    }
                }
            }
            flush()
            return nodes
        }

        private fun runLength(start: Int, to: Int, char: Char): Int {
            var end = start
            while (end < to && text[end] == char) end++
            return end - start
        }

        private fun findClosingRun(from: Int, to: Int, char: Char, length: Int): Int? {
            var i = from
            while (i < to) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text[i] == char) {
                    var end = i
                    while (end < to && text[end] == char) end++
                    if (end - i == length) return i
                    i = end
                    continue
                }
                i++
            }
            return null
        }

        /**
         * 找到成对的强调定界符（`*` / `**` / `***` / `_` / `~~`）。
         *
         * 关闭串必须与开启串长度完全一致，且跳过转义字符、行内代码，
         * 以及 `_` 的「词内下划线」（`snake_case` 不当作斜体）。
         */
        private fun findClosingDelimiter(from: Int, to: Int, char: Char, length: Int): Int? {
            var i = from
            while (i < to) {
                val current = text[i]
                if (current == '\\') {
                    i += 2
                    continue
                }
                if (current == '`') {
                    val run = runLength(i, to, '`')
                    val close = findClosingRun(i + run, to, '`', run)
                    i = if (close == null) i + run else close + run
                    continue
                }
                if (current == char) {
                    var run = 0
                    while (i + run < to && text[i + run] == char) run++
                    // 关闭符前面必须有正文（`** bold **` 不是强调）。
                    val hasBody = i > from && !text[i - 1].isWhitespace()
                    val intraword = char == '_' && i + run < to &&
                        text[i - 1].isLetterOrDigit() && text[i + run].isLetterOrDigit()
                    if (run == length && hasBody && !intraword) return i
                    i += run
                    continue
                }
                i++
            }
            return null
        }

        private fun parseLinkLike(start: Int, to: Int, isImage: Boolean): Pair<MdInline, Int>? {
            // start 指向 '['。
            var depth = 0
            var closeBracket = -1
            var i = start
            while (i < to) {
                val char = text[i]
                if (char == '\\') {
                    i += 2
                    continue
                }
                if (char == '[') depth++
                if (char == ']') {
                    depth--
                    if (depth == 0) {
                        closeBracket = i
                        break
                    }
                }
                i++
            }
            if (closeBracket < 0 || closeBracket + 1 >= to || text[closeBracket + 1] != '(') return null

            var depthParen = 0
            var endParen = -1
            i = closeBracket + 1
            while (i < to) {
                val char = text[i]
                if (char == '\\') {
                    i += 2
                    continue
                }
                if (char == '(') depthParen++
                if (char == ')') {
                    depthParen--
                    if (depthParen == 0) {
                        endParen = i
                        break
                    }
                }
                i++
            }
            if (endParen < 0) return null

            val label = text.substring(start + 1, closeBracket)
            val destination = text.substring(closeBracket + 2, endParen).trim()
            val url = extractDestinationUrl(destination) ?: return null
            val node = if (isImage) {
                MdInline.Image(label.ifBlank { url }, url)
            } else {
                MdInline.Link(scan(start + 1, closeBracket), url)
            }
            return node to endParen + 1
        }

        private fun extractDestinationUrl(destination: String): String? {
            if (destination.isEmpty()) return null
            if (destination.startsWith("<")) {
                val end = destination.indexOf('>')
                if (end > 1) return destination.substring(1, end)
            }
            val titled = titledDestination.find(destination)
            val candidate = titled?.groupValues?.get(1) ?: destination.split(Regex("\\s+")).firstOrNull()
            return candidate?.takeIf { it.isNotBlank() }
        }

        private fun parseAngle(from: Int, to: Int): Pair<MdInline, Int>? {
            val close = text.indexOf('>', from + 1)
            if (close < 0 || close >= to) return null
            val inner = text.substring(from + 1, close)
            val lowered = inner.lowercase()
            if (lowered == "br" || lowered == "br/" || lowered == "br /") {
                return MdInline.LineBreak to close + 1
            }
            if (inner.startsWith("http://") || inner.startsWith("https://")) {
                return MdInline.Link(listOf(MdInline.Text(inner)), inner) to close + 1
            }
            if (inner.startsWith("mailto:")) {
                val address = inner.removePrefix("mailto:")
                return MdInline.Link(listOf(MdInline.Text(address)), inner) to close + 1
            }
            if (htmlTag.matches(text.substring(from, close + 1))) {
                // 其它 HTML 标签（<details>、<div>、<span> 等）直接剥离，避免手机端显示尖括号噪音。
                return MdInline.Text("") to close + 1
            }
            return null
        }

        private fun startsWithUrl(index: Int, to: Int): Boolean {
            return (text.startsWith("http://", index) || text.startsWith("https://", index)) && index + 8 <= to
        }

        /**
         * 裸链接：只吃 URL 合法字符，遇到空白、中文、全角标点、Markdown 定界符就停。
         *
         * 不能整段正则匹配（会把后面的 `**`、中文句子一起吞进链接），也不能跨过 [to]
         * （在粗体内部解析时 [to] 是闭合定界符位置）。
         */
        private fun scanBareUrl(start: Int, to: Int): String? {
            var end = start
            while (end < to && isUrlChar(text[end])) end++
            // 末尾常见的句末标点不属于 URL。
            while (end > start && text[end - 1] in ".,;:!?") end--
            if (end - start < 12) return null
            return text.substring(start, end)
        }

        private fun isUrlChar(char: Char): Boolean {
            val asciiAlnum = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
            return asciiAlnum || char in "-._~:/?#@!$&%+;=[]"
        }
    }

    private fun Char.isEscapable(): Boolean = this in "\\`*_{}[]()#+-.!<>~|"
}
