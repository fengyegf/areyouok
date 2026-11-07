package com.catmoecircle.areyouok.ui.gateway.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun plain(inlines: List<MdInline>): String {
        val builder = StringBuilder()
        fun visit(nodes: List<MdInline>) {
            for (node in nodes) {
                when (node) {
                    is MdInline.Text -> builder.append(node.text)
                    is MdInline.Code -> builder.append(node.text)
                    is MdInline.Strong -> visit(node.children)
                    is MdInline.Emphasis -> visit(node.children)
                    is MdInline.Strikethrough -> visit(node.children)
                    is MdInline.Link -> visit(node.children)
                    is MdInline.Image -> builder.append(node.alt)
                    MdInline.LineBreak -> builder.append('\n')
                }
            }
        }
        visit(inlines)
        return builder.toString()
    }

    private fun paragraphs(blocks: List<MdBlock>): List<String> =
        blocks.filterIsInstance<MdBlock.Paragraph>().map { plain(it.inlines) }

    @Test
    fun `headings keep their level and text`() {
        val blocks = MarkdownParser.parse("# 一级\n\n## 二级\n\n### 三级标题")
        assertEquals(3, blocks.size)
        assertEquals(listOf(1, 2, 3), blocks.map { (it as MdBlock.Heading).level })
        assertEquals(listOf("一级", "二级", "三级标题"), blocks.map { plain((it as MdBlock.Heading).inlines) })
    }

    @Test
    fun `setext headings are recognised`() {
        val blocks = MarkdownParser.parse("标题\n===\n\n副标题\n---")
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
    }

    @Test
    fun `fenced code keeps language and raw body`() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\nprintln(x)\n```")
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(code.closed)
    }

    @Test
    fun `open fence during streaming is still a code block`() {
        val blocks = MarkdownParser.parse("```js\nconsole.log('hi')\nmore")
        val code = blocks.single() as MdBlock.CodeBlock
        assertFalse(code.closed)
        assertEquals("js", code.language)
        assertEquals("console.log('hi')\nmore", code.code)
    }

    @Test
    fun `bullet list supports nesting`() {
        val blocks = MarkdownParser.parse(
            """
            - 一级
              - 二级
              - 二级 B
            - 一级 B
            """.trimIndent(),
        )
        val list = blocks.single() as MdBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks.filterIsInstance<MdBlock.ListBlock>().single()
        assertEquals(2, nested.items.size)
        assertEquals("一级", plain(list.items[0].blocks.filterIsInstance<MdBlock.Paragraph>().single().inlines))
    }

    @Test
    fun `ordered list keeps start number`() {
        val blocks = MarkdownParser.parse("3. 三\n4. 四")
        val list = blocks.single() as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `task list parses checked state`() {
        val blocks = MarkdownParser.parse("- [ ] 待办\n- [x] 完成")
        val list = blocks.single() as MdBlock.ListBlock
        assertEquals(false, list.items[0].checked)
        assertEquals(true, list.items[1].checked)
    }

    @Test
    fun `table parses header rows and alignment`() {
        val blocks = MarkdownParser.parse(
            """
            | 项目 | 状态 |
            | :--- | ---: |
            | 网关 | 在线 |
            | 手机 | 可见 |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("项目", "状态"), table.header.map(::plain))
        assertEquals(MdAlign.LEFT, table.alignments[0])
        assertEquals(MdAlign.RIGHT, table.alignments[1])
        assertEquals(2, table.rows.size)
        assertEquals(listOf("网关", "在线"), table.rows[0].map(::plain))
    }

    @Test
    fun `table cell keeps escaped pipe`() {
        val blocks = MarkdownParser.parse("| a | b |\n| --- | --- |\n| x \\| y | z |")
        val table = blocks.single() as MdBlock.Table
        assertEquals("x | y", plain(table.rows[0][0]))
    }

    @Test
    fun `table row keeps pipe inside inline code`() {
        val blocks = MarkdownParser.parse(
            "| 序号 | 能力 | 状态 |\n" +
                "| --- | --- | --- |\n" +
                "| 3 | `session/list|create|prompt`、其它 | ✅ 可做 |",
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(3, table.header.size)
        assertEquals(3, table.rows.single().size)
        assertEquals("3", plain(table.rows[0][0]))
        assertEquals("session/list|create|prompt、其它", plain(table.rows[0][1]))
        assertEquals("✅ 可做", plain(table.rows[0][2]))
    }

    @Test
    fun `table extra cells are kept and columns padded`() {
        val blocks = MarkdownParser.parse("| a | b |\n| --- | --- |\n| 1 | 2 | 3 |")
        val table = blocks.single() as MdBlock.Table
        assertEquals(3, table.header.size)
        assertEquals(3, table.rows.single().size)
        assertEquals("3", plain(table.rows[0][2]))
        assertEquals(3, table.alignments.size)
    }

    @Test
    fun `blockquote and gfm alert`() {
        val blocks = MarkdownParser.parse("> [!WARNING]\n> 小心操作\n> 第二行")
        val quote = blocks.single() as MdBlock.Quote
        assertEquals(MdAlert.WARNING, quote.alert)
        assertEquals(listOf("小心操作\n第二行"), paragraphs(quote.blocks))
    }

    @Test
    fun `inline styles links and code`() {
        val inlines = MarkdownParser.parseInline("普通 **粗体** 和 *斜体*、`code`、~~删除~~、[链接](https://example.com) 完成")
        val kinds = inlines.map { it::class.simpleName }
        assertTrue(inlines.any { it is MdInline.Strong })
        assertTrue(inlines.any { it is MdInline.Emphasis })
        assertTrue(inlines.any { it is MdInline.Code })
        assertTrue(inlines.any { it is MdInline.Strikethrough })
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com", link.url)
        assertEquals("链接", plain(link.children))
        assertTrue(kinds.isNotEmpty())
    }

    @Test
    fun `bare url becomes a link`() {
        val inlines = MarkdownParser.parseInline("见 https://example.com/docs 说明")
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/docs", link.url)
    }

    @Test
    fun `images degrade to alt text`() {
        val inlines = MarkdownParser.parseInline("看图 ![截图](https://example.com/a.png) 结束")
        val image = inlines.filterIsInstance<MdInline.Image>().single()
        assertEquals("截图", image.alt)
        assertEquals("https://example.com/a.png", image.url)
    }

    @Test
    fun `nested and triple emphasis are parsed`() {
        val triple = MarkdownParser.parseInline("***x***")
        assertEquals("x", plain(triple))
        assertTrue(triple.single() is MdInline.Strong)
        assertTrue((triple.single() as MdInline.Strong).children.single() is MdInline.Emphasis)

        val mixed = MarkdownParser.parseInline("*a **b** c*")
        assertEquals("a b c", plain(mixed))
        assertEquals(1, mixed.count { it is MdInline.Emphasis })
        assertTrue((mixed.single() as MdInline.Emphasis).children.any { it is MdInline.Strong })

        val underscored = MarkdownParser.parseInline("_foo_bar_")
        assertEquals("foo_bar", plain(underscored))
        assertEquals(1, underscored.count { it is MdInline.Emphasis })
    }

    @Test
    fun `bare url inside bold does not swallow the closing delimiters`() {
        val inlines = MarkdownParser.parseInline("**https://www.google.com/**")
        val strong = inlines.single() as MdInline.Strong
        val link = strong.children.single() as MdInline.Link
        assertEquals("https://www.google.com/", link.url)
        assertFalse(plain(inlines).contains("**"))
    }

    @Test
    fun `bare url stops at chinese punctuation and text`() {
        val inlines = MarkdownParser.parseInline("见 https://a.com，然后继续")
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://a.com", link.url)
        assertEquals("见 https://a.com，然后继续", plain(inlines))
    }

    @Test
    fun `bare url does not eat surrounding parentheses`() {
        val inlines = MarkdownParser.parseInline("https://a.com/x_(y)")
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://a.com/x_", link.url)
        assertEquals("https://a.com/x_(y)", plain(inlines))
    }

    @Test
    fun `line breaks and br tags`() {
        val inlines = MarkdownParser.parseInline("第一行<br>第二行\n第三行")
        val breaks = inlines.count { it == MdInline.LineBreak }
        assertEquals(2, breaks)
    }

    @Test
    fun `escaped punctuation stays literal`() {
        val inlines = MarkdownParser.parseInline("\\*不是斜体\\*")
        assertEquals("*不是斜体*", plain(inlines))
    }

    @Test
    fun `unclosed bold during streaming stays literal`() {
        val inlines = MarkdownParser.parseInline("部分 **粗")
        assertEquals("部分 **粗", plain(inlines))
        assertTrue(inlines.none { it is MdInline.Strong })
    }

    @Test
    fun `thematic break is parsed`() {
        val blocks = MarkdownParser.parse("上文\n\n---\n\n下文")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.ThematicBreak)
    }

    @Test
    fun `html tags are stripped like dsh details blocks`() {
        val inlines = MarkdownParser.parseInline("<details><summary>点开</summary>正文</details>")
        assertEquals("点开正文", plain(inlines))
    }

    @Test
    fun `real dsh style answer renders as blocks`() {
        val markdown = """
            ## 结论

            已完成 **Markdown** 适配，主要改动：
            - 标题 / 列表 / 代码块
            - 表格：

            | 文件 | 说明 |
            | --- | --- |
            | MessageTimeline.kt | 消息渲染 |
            | Markdown.kt | 解析器 |

            ```kotlin
            Text("hello")
            ```
            > 注意：流式输出也要稳定
        """.trimIndent()
        val blocks = MarkdownParser.parse(markdown)
        assertTrue(blocks.first() is MdBlock.Heading)
        assertTrue(blocks.any { it is MdBlock.ListBlock })
        assertTrue(blocks.any { it is MdBlock.Table })
        assertTrue(blocks.any { it is MdBlock.CodeBlock })
        assertTrue(blocks.any { it is MdBlock.Quote })
    }
}
