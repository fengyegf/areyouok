package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.Question
import com.catmoecircle.areyouok.gateway.protocol.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提问卡片的选择/自定义回复/提交逻辑（与 DSH 桌面端语义对齐）。 */
class QuestionDraftsTest {

    private fun question(
        id: String = "q1",
        question: String = "选一个方案",
        multiSelect: Boolean = false,
        options: List<String> = listOf("A", "B", "C"),
    ) = Question(
        id = id,
        question = question,
        multiSelect = multiSelect,
        options = options.map { QuestionOption(label = it) },
    )

    @Test
    fun `single select replaces the previous choice and does not submit`() {
        val q = question()
        var draft = QuestionDraft()
        draft = QuestionDrafts.toggle(draft, "A", q.multiSelect)
        draft = QuestionDrafts.toggle(draft, "B", q.multiSelect)
        assertEquals(listOf("B"), draft.selected)
        // 只改选中态，没有提交动作；自定义回复仍为空。
        assertEquals("", draft.custom)
    }

    @Test
    fun `multi select toggles each option`() {
        val q = question(multiSelect = true)
        var draft = QuestionDraft()
        draft = QuestionDrafts.toggle(draft, "A", q.multiSelect)
        draft = QuestionDrafts.toggle(draft, "C", q.multiSelect)
        assertEquals(listOf("A", "C"), draft.selected)
        draft = QuestionDrafts.toggle(draft, "A", q.multiSelect)
        assertEquals(listOf("C"), draft.selected)
    }

    @Test
    fun `answered requires an option or custom text`() {
        assertFalse(QuestionDrafts.answered(QuestionDraft()))
        assertFalse(QuestionDrafts.answered(QuestionDraft(custom = "   ")))
        assertTrue(QuestionDrafts.answered(QuestionDraft(selected = listOf("A"))))
        assertTrue(QuestionDrafts.answered(QuestionDraft(custom = "自定义")))
    }

    @Test
    fun `custom text wins over selection for single select`() {
        val answer = QuestionDrafts.build(
            question(),
            QuestionDraft(selected = listOf("A"), custom = "  我自己填的 "),
        )
        assertEquals("q1", answer.id)
        assertEquals(emptyList<String>(), answer.selected)
        assertEquals("我自己填的", answer.custom)
    }

    @Test
    fun `multi select submits both selection and custom text`() {
        val answer = QuestionDrafts.build(
            question(multiSelect = true),
            QuestionDraft(selected = listOf("A", "B"), custom = "补充"),
        )
        assertEquals(listOf("A", "B"), answer.selected)
        assertEquals("补充", answer.custom)
    }

    @Test
    fun `blank custom is omitted from the answer`() {
        val answer = QuestionDrafts.build(question(), QuestionDraft(selected = listOf("B"), custom = "  "))
        assertEquals(listOf("B"), answer.selected)
        assertNull(answer.custom)
    }

    @Test
    fun `submit needs every question answered and builds one item per question`() {
        val questions = listOf(question(id = "q1"), question(id = "q2"))
        assertFalse(QuestionDrafts.allAnswered(questions, mapOf("q1" to QuestionDraft(selected = listOf("A")))))

        val drafts = mapOf(
            "q1" to QuestionDraft(selected = listOf("A")),
            "q2" to QuestionDraft(custom = "跳过选项"),
        )
        assertTrue(QuestionDrafts.allAnswered(questions, drafts))

        val answers = QuestionDrafts.buildAll(questions, drafts)
        assertEquals(listOf("q1", "q2"), answers.map { it.id })
        assertEquals(listOf("A"), answers[0].selected)
        assertEquals("跳过选项", answers[1].custom)
        assertEquals(emptyList<String>(), answers[1].selected)
    }
}
