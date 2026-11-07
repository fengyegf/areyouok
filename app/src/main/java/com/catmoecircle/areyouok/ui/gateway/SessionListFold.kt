package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.SessionSummary

/**
 * 侧边栏每个一级分组（项目以及末尾的「聊天」分组）默认只先露 4 条会话，
 * 避免项目里会话一多就把侧边栏撑爆：多于 4 条时折叠成一行「展开其余 N 个对话」，
 * 点开后再全部展开。
 */
const val SESSION_PREVIEW_LIMIT = 4

/**
 * 分组内实际要渲染的会话列表。
 *
 * [expanded] 为 true（用户点过「展开其余 N 个对话」）时原样返回全部会话；
 * 否则只返回前 [limit] 条，其余由「展开其余 N 个对话」一行收着。
 */
fun visibleSessions(
    sessions: List<SessionSummary>,
    expanded: Boolean,
    limit: Int = SESSION_PREVIEW_LIMIT,
): List<SessionSummary> = if (expanded) sessions else sessions.take(limit.coerceAtLeast(0))

/**
 * 折叠后被隐藏的会话条数（「展开其余 N 个对话」里的 N）。
 *
 * 总数不超过 [limit] 时返回 0，调用方据此决定是否显示展开 / 收起行。
 */
fun hiddenSessionCount(size: Int, limit: Int = SESSION_PREVIEW_LIMIT): Int =
    (size - limit).coerceAtLeast(0)
