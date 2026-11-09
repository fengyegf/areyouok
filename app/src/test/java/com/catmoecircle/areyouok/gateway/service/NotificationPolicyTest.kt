package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `task completion notifies when the app is away`() {
        assertTrue(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = "s1",
                appVisible = false,
                notificationsEnabled = true,
                completionsEnabled = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `task completion is suppressed while watching the same conversation`() {
        // 用户正看着这个对话、结果就在眼前：不重复弹提醒。
        assertFalse(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = "s1",
                appVisible = true,
                notificationsEnabled = true,
                completionsEnabled = true,
                permissionGranted = true,
            ),
        )
        // 前台但看的是别的对话：仍然提醒，点通知回到结束的那个对话。
        assertTrue(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = "s2",
                appVisible = true,
                notificationsEnabled = true,
                completionsEnabled = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `task completion respects switches and permission`() {
        assertTrue(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = null,
                appVisible = false,
                notificationsEnabled = true,
                completionsEnabled = true,
                permissionGranted = true,
            ),
        )
        assertFalse(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = null,
                appVisible = false,
                notificationsEnabled = false,
                completionsEnabled = true,
                permissionGranted = true,
            ),
        )
        assertFalse(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = null,
                appVisible = false,
                notificationsEnabled = true,
                completionsEnabled = false,
                permissionGranted = true,
            ),
        )
        assertFalse(
            NotificationPolicy.shouldNotifyCompletion(
                sessionId = "s1",
                selectedSessionId = null,
                appVisible = false,
                notificationsEnabled = true,
                completionsEnabled = true,
                permissionGranted = false,
            ),
        )
    }
}
