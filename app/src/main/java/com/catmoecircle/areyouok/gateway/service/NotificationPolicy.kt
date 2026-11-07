package com.catmoecircle.areyouok.gateway.service

import com.catmoecircle.areyouok.gateway.protocol.Intervention

/**
 * 审批 / 提问通知的可见性策略。
 *
 * 设置页「通知」子页允许分别关闭「审批」与「提问」两类推送：
 * - [Intervention.isApproval] 为 true（`kind = approval`）的干预只看 `approvalsEnabled`；
 * - 其余的干预（`kind = question`）只看 `questionsEnabled`。
 *
 * 纯函数，不读取任何全局状态，便于单测与复用；返回顺序与入参一致。
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
}
