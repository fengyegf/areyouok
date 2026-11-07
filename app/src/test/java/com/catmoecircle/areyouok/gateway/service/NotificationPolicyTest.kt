package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention
import org.junit.Assert.assertEquals
import org.junit.Test

/** 审批/提问通知的细分开关过滤。 */
class NotificationPolicyTest {

    private fun approval(id: String) = Intervention(eventId = id, kind = Intervention.KIND_APPROVAL)

    private fun question(id: String) = Intervention(eventId = id, kind = Intervention.KIND_QUESTION)

    @Test
    fun `both switches on keeps every intervention`() {
        val interventions = listOf(approval("a1"), question("q1"), approval("a2"))

        val visible = NotificationPolicy.visibleInterventions(
            interventions = interventions,
            approvalsEnabled = true,
            questionsEnabled = true,
        )

        assertEquals(listOf("a1", "q1", "a2"), visible.map { it.eventId })
    }

    @Test
    fun `only approvals enabled keeps approvals`() {
        val interventions = listOf(approval("a1"), question("q1"), approval("a2"))

        val visible = NotificationPolicy.visibleInterventions(
            interventions = interventions,
            approvalsEnabled = true,
            questionsEnabled = false,
        )

        assertEquals(listOf("a1", "a2"), visible.map { it.eventId })
        assertEquals(listOf(true, true), visible.map { it.isApproval })
    }

    @Test
    fun `only questions enabled keeps questions`() {
        val interventions = listOf(approval("a1"), question("q1"), question("q2"))

        val visible = NotificationPolicy.visibleInterventions(
            interventions = interventions,
            approvalsEnabled = false,
            questionsEnabled = true,
        )

        assertEquals(listOf("q1", "q2"), visible.map { it.eventId })
        assertEquals(listOf(false, false), visible.map { it.isApproval })
    }

    @Test
    fun `both switches off yields nothing`() {
        val interventions = listOf(approval("a1"), question("q1"))

        val visible = NotificationPolicy.visibleInterventions(
            interventions = interventions,
            approvalsEnabled = false,
            questionsEnabled = false,
        )

        assertEquals(emptyList<Intervention>(), visible)
    }
}
