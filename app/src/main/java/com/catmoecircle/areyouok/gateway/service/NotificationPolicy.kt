package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention

/**
 * 通知的可见性策略（纯函数，便于单测与复用）。
 *
 * - 审批 / 提问：设置页允许分别关闭，按 [Intervention.isApproval] 过滤；
 * - 任务完成：受通知总开关、独立开关与系统权限约束，且用户正看着该会话时不打扰。
 */
object NotificationPolicy {

    /**
     * 按开关过滤待处理的审批 / 提问。
     *
     * @param interventions 网关当前待处理的干预列表。
     * @param approvalsEnabled 是否推送审批类通知。
     * @param questionsEnabled 是否推送提问类通知。
     * @return 应该发出通知的干预；两个开关都关闭时返回空列表。
     */
    fun visibleInterventions(
        interventions: List<Intervention>,
        approvalsEnabled: Boolean,
        questionsEnabled: Boolean,
    ): List<Intervention> = interventions.filter { intervention ->
        if (intervention.isApproval) approvalsEnabled else questionsEnabled
    }

    /**
     * 是否弹「任务完成」提醒。
     *
     * 三个前提缺一不可：通知总开关打开、任务完成开关打开、系统通知权限已授予。
     * 例外：App 在前台且用户正看着这个会话——结果已经直接显示在眼前，不再重复提醒；
     * 在前台看别的会话时仍然提醒（点通知回到结束的那个对话）。
     *
     * @param sessionId 刚结束的会话。
     * @param selectedSessionId 当前打开的会话。
     * @param appVisible App 是否在前台。
     * @param notificationsEnabled 通知总开关。
     * @param completionsEnabled 「任务完成提醒」开关。
     * @param permissionGranted 系统通知权限。
     */
    fun shouldNotifyCompletion(
        sessionId: String,
        selectedSessionId: String?,
        appVisible: Boolean,
        notificationsEnabled: Boolean,
        completionsEnabled: Boolean,
        permissionGranted: Boolean,
    ): Boolean {
        if (!notificationsEnabled || !completionsEnabled || !permissionGranted) return false
        return !(appVisible && sessionId == selectedSessionId)
    }
}
