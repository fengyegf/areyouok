package com.catmoecircle.areyouok.gateway.repository

/**
 * 一次「对话跑完一轮」（网关的 `turn.completed`）。
 *
 * 前台服务据此弹「任务完成」通知；[sessionId] 同时用于通知点击后回到对应对话。
 *
 * 它只由网关在**真实结束的那一刻**发一次（网关订阅宿主 `turn/end`），不是从
 * `running` 状态翻转推断出来的——后者在重连补齐、网关重启后会重放历史状态，
 * 把「早就结束的对话」再提醒一遍。
 *
 * @param sessionId 结束的会话 id。
 * @param title 会话标题（取不到时通知用 id 前 8 位兜底）。
 * @param time 结束时间（epoch ms，本机时钟）。
 */
data class SessionCompletion(
    val sessionId: String,
    val title: String?,
    val time: Long,
)
