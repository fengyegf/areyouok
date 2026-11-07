package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Workspace

/**
 * 侧边栏一级「项目」+ 二级「会话」，末尾的「聊天」分组只收纳未归属任何项目的会话。
 *
 * [workspace] 为 null 表示「聊天」分组；DSH 的默认工作区也是一个普通项目，
 * 只是标题按桌面端习惯显示成「默认工作区」。
 */
data class SessionProject(
    val workspace: Workspace?,
    val sessions: List<SessionSummary>,
) {
    /** 稳定 key：项目用 workspaceId，兜底分组用固定 key。 */
    val key: String
        get() = workspace?.key?.takeIf { it.isNotBlank() } ?: ORPHAN_KEY

    val title: String
        get() = workspace?.let {
            if (it.isDefaultWorkspace) DEFAULT_WORKSPACE_DISPLAY_TITLE else it.displayName
        } ?: ORPHAN_TITLE

    /** 项目目录（用于展示/匹配），没有则为空。 */
    val path: String?
        get() = workspace?.displayPath

    companion object {
        const val ORPHAN_KEY = "__orphan__"

        /** 「聊天」分组标题：只放不归属任何项目的会话。 */
        const val ORPHAN_TITLE = "聊天"

        /** DSH 默认工作区在桌面端 / 本应用里的展示标题。 */
        const val DEFAULT_WORKSPACE_DISPLAY_TITLE = "默认工作区"
    }
}

/**
 * 把 DSH 的 workspace 列表与会话列表分组成「项目 → 会话」。
 *
 * 默认工作区（`title == "default-workspace"`）与普通项目一样单独成行，
 * 标题显示为「默认工作区」；它不再和未归属会话合并，避免「聊天」这个名字
 * 同时代表默认工作区和真正的未分组会话。
 *
 * 归属规则（按优先级）：
 * 1. `workspace.sessionIds` 里明确列出的会话（DSH `workspace/follow` baseline 的成员表）；
 * 2. 会话摘要自带的 `workspaceId`：本地在某个项目里新建会话后，`workspace/follow`
 *    的成员表还没追上的窗口期，先按这个提示归位；
 * 3. 兜底：会话 `cwd` 位于项目目录下，取**路径最深的项目**（否则父项目会把
 *    子项目里的会话抢走，例如父项目 `/repo/app` 抢走子项目 `/repo/app/plugins/sdk`）；
 * 4. 仍未归属的会话放进末尾的「聊天」分组（保持可见，不丢数据）。
 *
 * 纯函数便于 JVM 单测；项目顺序沿用 DSH baseline 顺序，「聊天」固定在项目段之后，
 * 组内会话按最近活跃倒序。
 */
object SessionProjectGrouping {

    fun group(
        workspaces: List<Workspace>,
        sessions: List<SessionSummary>,
    ): List<SessionProject> {
        if (workspaces.isEmpty() && sessions.isEmpty()) return emptyList()
        val byId = sessions.associateBy { it.id }
        val claimed = HashSet<String>()
        // 每个 workspace 的成员；key 保持 baseline 顺序。
        val membersByWorkspace = LinkedHashMap<String, LinkedHashSet<String>>()
        for (workspace in workspaces) {
            val key = workspace.key
            if (key.isNotBlank()) membersByWorkspace[key] = LinkedHashSet()
        }

        // 1. 显式成员表。先整体认领，避免后面的 cwd 兜底把子项目的会话抢给父项目。
        for (workspace in workspaces) {
            val members = membersByWorkspace[workspace.key] ?: continue
            for (id in workspace.sessionIds) {
                if (id.isBlank() || id in claimed || id !in byId) continue
                members += id
                claimed += id
            }
        }

        // 2. 会话自带的 workspaceId（本地新建、成员表还没追上的窗口期）。
        for (session in sessions) {
            if (session.id in claimed) continue
            val key = session.workspaceId?.takeIf { it.isNotBlank() } ?: continue
            val members = membersByWorkspace[key] ?: continue
            members += session.id
            claimed += session.id
        }

        // 3. cwd 兜底：同一目录树里可能有父子项目，取根路径最长的那个。
        val roots = workspaces.mapNotNull { workspace ->
            workspace.pathRoot()?.let { root -> workspace to root }
        }
        for (session in sessions) {
            if (session.id in claimed) continue
            val cwd = session.cwd?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() } ?: continue
            val owner = roots
                .filter { (_, root) -> cwd == root || cwd.startsWith("$root/") || cwd.startsWith("$root\\") }
                .maxByOrNull { (_, root) -> root.length }
                ?.first
                ?: continue
            val members = membersByWorkspace[owner.key] ?: continue
            members += session.id
            claimed += session.id
        }

        val projects = workspaces.mapNotNull { workspace ->
            val members = membersByWorkspace[workspace.key] ?: return@mapNotNull null
            SessionProject(
                workspace = workspace,
                sessions = members.mapNotNull(byId::get).sortedByDescending { it.updatedAt ?: 0L },
            )
        }.toMutableList()

        val orphans = sessions
            .filter { it.id !in claimed }
            .sortedByDescending { it.updatedAt ?: 0L }
        // 只有确实存在未归属会话时才显示「聊天」，不再为了默认工作区保留空入口。
        if (orphans.isNotEmpty()) {
            projects += SessionProject(workspace = null, sessions = orphans)
        }
        return projects
    }

    private fun Workspace.pathRoot(): String? =
        (displayPath ?: cwd)?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }
}
