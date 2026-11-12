package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已作答提问卡片的展示模型：单选/多选/自定义/跳过/多题（纯函数，无需 Compose 运行时）。 */
class AnsweredQuestionCardTest {

    private fun question(
        id: String = "q1",
        text: String? = "选一个方案",
        header: String? = null,
        options: List<String> = listOf("A", "B", "C"),
    ) = Question(
        id = id,
        question = text,
        header = header,
        options = options.map { QuestionOption(label = it) },
    )

    @Test
    fun `single select keeps header question and the chosen option`() {
        val views = answeredQuestionViews(
            listOf(question(header = "测试问答")),
            listOf(QuestionAnswer(id = "q1", selected = listOf("B"))),
        )

        val view = views.single()
        assertEquals("测试问答", view.header)
        assertEquals("选一个方案", view.question)
        assertEquals(listOf("B"), view.selected)
        assertNull(view.custom)
        assertFalse(view.skipped)
    }

    @Test
    fun `multi select lists every chosen option in order`() {
        val views = answeredQuestionViews(
            listOf(question()),
            listOf(
                QuestionAnswer(
                    id = "q1",
                    selected = listOf("C", "A", " A ", ""),
                    custom = "  补充说明  ",
                ),
            ),
        )

        val view = views.single()
        // 空白项丢弃、重复项去重、顺序保持提交顺序。
        assertEquals(listOf("C", "A"), view.selected)
        assertEquals("补充说明", view.custom)
        assertFalse(view.skipped)
    }

    @Test
    fun `custom reply alone is shown instead of options`() {
        val views = answeredQuestionViews(
            listOf(question()),
            listOf(QuestionAnswer(id = "q1", selected = emptyList(), custom = "我自己填的")),
        )

        val view = views.single()
        assertTrue(view.selected.isEmpty())
        assertEquals("我自己填的", view.custom)
        assertFalse(view.skipped)
    }

    @Test
    fun `question without answer is marked skipped`() {
        val views = answeredQuestionViews(
            listOf(question(), question(id = "q2", text = "第二个问题")),
            listOf(QuestionAnswer(id = "q1", selected = listOf("A"))),
        )

        assertEquals(2, views.size)
        assertFalse(views[0].skipped)
        assertTrue(views[1].skipped)
        assertTrue(views[1].selected.isEmpty())
        assertNull(views[1].custom)
    }

    @Test
    fun `multiple questions keep their own answers and hide duplicated header text`() {
        val views = answeredQuestionViews(
            listOf(
                question(id = "q1", text = "先选方案", header = "方案"),
                // 没有正文时 header 就是正文，不能显示两遍。
                question(id = "q2", text = null, header = "补充信息"),
            ),
            listOf(
                QuestionAnswer(id = "q2", selected = listOf("C")),
                QuestionAnswer(id = "q1", selected = listOf("A")),
            ),
        )

        assertEquals(2, views.size)
        assertEquals("方案", views[0].header)
        assertEquals("先选方案", views[0].question)
        assertEquals(listOf("A"), views[0].selected)
        assertEquals("补充信息", views[1].header)
        assertEquals("", views[1].question)
        assertEquals(listOf("C"), views[1].selected)
    }

    @Test
    fun `title says skipped when every question was skipped`() {
        val skipped = answeredQuestionViews(
            listOf(question()),
            listOf(QuestionAnswer(id = "q1")),
        )
        assertEquals("提问 · 已跳过", answeredQuestionTitle(skipped))

        val answered = answeredQuestionViews(
            listOf(question()),
            listOf(QuestionAnswer(id = "q1", selected = listOf("A"))),
        )
        assertEquals("提问 · 已作答", answeredQuestionTitle(answered))

        assertEquals("提问 · 已作答", answeredQuestionTitle(answeredQuestionViews(emptyList(), emptyList())))
    }
}
