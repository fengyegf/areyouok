package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通知栏「直接回答」的纯逻辑：只有单问题提问才提供输入框。 */
class NotificationRepliesTest {

    private fun questionIntervention(vararg questionIds: String): Intervention {
        val questions = JsonArray(
            questionIds.map { id ->
                buildJsonObject {
                    put("id", id)
                    put("question", "问题 $id")
                }
            },
        )
        return Intervention(
            eventId = "event-1",
            sessionId = "session-1",
            kind = Intervention.KIND_QUESTION,
            data = buildJsonObject { put("questions", questions) },
        )
    }

    private fun approval() = Intervention(eventId = "event-2", sessionId = "session-1")

    @Test
    fun `single question offers quick answer`() {
        assertTrue(NotificationReplies.canQuickAnswer(questionIntervention("q1")))
    }

    @Test
    fun `multiple questions fall back to the app`() {
        assertFalse(NotificationReplies.canQuickAnswer(questionIntervention("q1", "q2")))
        assertNull(NotificationReplies.quickAnswer(questionIntervention("q1", "q2"), "回答"))
    }

    @Test
    fun `approval never offers quick answer`() {
        assertFalse(NotificationReplies.canQuickAnswer(approval()))
        assertNull(NotificationReplies.quickAnswer(approval(), "允许"))
    }

    @Test
    fun `quick answer trims text into custom answer`() {
        val answers = NotificationReplies.quickAnswer(questionIntervention("q1"), "  就用 A 方案  ")

        assertEquals(1, answers?.size)
        assertEquals("q1", answers?.first()?.id)
        assertEquals(emptyList<String>(), answers?.first()?.selected)
        assertEquals("就用 A 方案", answers?.first()?.custom)
    }

    @Test
    fun `blank text is ignored`() {
        assertNull(NotificationReplies.quickAnswer(questionIntervention("q1"), "   "))
        assertNull(NotificationReplies.quickAnswer(questionIntervention("q1"), null))
    }
}
