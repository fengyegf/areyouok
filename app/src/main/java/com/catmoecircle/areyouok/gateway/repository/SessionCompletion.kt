package com.catmoecircle.areyouok.gateway.repository

/**
 * 一次「任务结束」：某个会话从运行中（`running = true`）落到空闲（`false`）。
 *
 * 前台服务据此弹「任务完成」通知；[sessionId] 同时用于通知点击后回到对应对话。
 *
 * @param sessionId 结束的会话 id。
 * @param title 会话标题（取不到时通知用 id 前 8 位兜底）。
 * @param time 结束时间（epoch ms）。
 */
data class SessionCompletion(
    val sessionId: String,
    val title: String?,
    val time: Long,
)
