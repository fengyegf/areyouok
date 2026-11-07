package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 侧边栏「项目 → 会话」分组。 */
class ProjectGroupingTest {

    private fun session(
        id: String,
        cwd: String? = null,
        workspaceId: String? = null,
        updatedAt: Long = 0,
    ) = SessionSummary(id = id, cwd = cwd, workspaceId = workspaceId, updatedAt = updatedAt)

    @Test
    fun `groups sessions by workspace sessionIds and sorts by activity`() {
        val workspaces = listOf(
            Workspace(workspaceId = "w1", title = "areyouok", path = "E:/work/areyouok", sessionIds = listOf("s1", "s2")),
            Workspace(workspaceId = "w2", title = "scratch", path = "D:/scratch", sessionIds = emptyList()),
        )
        val sessions = listOf(
            session("s1", updatedAt = 100),
            session("s2", updatedAt = 300),
            session("s3", cwd = "D:/scratch/tmp", updatedAt = 200),
        )

        val projects = SessionProjectGrouping.group(workspaces, sessions)

        assertEquals(listOf("w1", "w2"), projects.map { it.key })
        assertEquals(listOf("s2", "s1"), projects[0].sessions.map { it.id })
        // 没有 sessionIds 时用 cwd 兜底（含子目录）。
        assertEquals(listOf("s3"), projects[1].sessions.map { it.id })
        assertEquals("areyouok", projects[0].title)
    }

    @Test
    fun `unclaimed sessions go to the chat group`() {
        val workspaces = listOf(Workspace(workspaceId = "w1", title = "proj", path = "E:/proj", sessionIds = listOf("s1")))
        val sessions = listOf(
            session("s1", updatedAt = 1),
            session("s9", cwd = "C:/elsewhere", updatedAt = 2),
        )

        val projects = SessionProjectGrouping.group(workspaces, sessions)

        assertEquals(2, projects.size)
        val orphan = projects.last()
        assertNull(orphan.workspace)
        assertEquals(SessionProject.ORPHAN_KEY, orphan.key)
        // 不归属任何项目的会话，分组标题是「聊天」。
        assertEquals(SessionProject.ORPHAN_TITLE, orphan.title)
        assertEquals("聊天", orphan.title)
        assertEquals(listOf("s9"), orphan.sessions.map { it.id })
    }

    @Test
    fun `locally created session stays in its project before membership catches up`() {
        // session/create{workspaceId} 之后、workspace/follow 成员表还没更新时，
        // 先按 SessionSummary.workspaceId 把新会话归到项目里。
        val workspaces = listOf(
            Workspace(workspaceId = "w1", title = "proj", path = null, sessionIds = emptyList()),
        )
        val sessions = listOf(SessionSummary(id = "s-new", workspaceId = "w1", updatedAt = 5))

        val projects = SessionProjectGrouping.group(workspaces, sessions)

        assertEquals(1, projects.size)
        assertEquals(listOf("s-new"), projects.single().sessions.map { it.id })
    }

    @Test
    fun `a session is claimed by its first workspace only`() {
        val workspaces = listOf(
            Workspace(workspaceId = "w1", title = "first", path = "E:/a", sessionIds = listOf("s1")),
            Workspace(workspaceId = "w2", title = "second", path = "E:/b", sessionIds = listOf("s1")),
        )
        val projects = SessionProjectGrouping.group(workspaces, listOf(session("s1")))
        assertEquals(listOf("s1"), projects[0].sessions.map { it.id })
        assertEquals(emptyList<String>(), projects[1].sessions.map { it.id })
    }

    @Test
    fun `empty state has no groups`() {
        assertEquals(emptyList<SessionProject>(), SessionProjectGrouping.group(emptyList(), emptyList()))
    }

    @Test
    fun `projects without sessions are kept so they can host new conversations`() {
        val workspaces = listOf(
            Workspace(workspaceId = "w1", title = "empty-proj", path = "E:/empty"),
        )
        val projects = SessionProjectGrouping.group(workspaces, emptyList())
        assertEquals(1, projects.size)
        assertEquals("w1", projects[0].key)
        assertEquals(emptyList<SessionSummary>(), projects[0].sessions)
    }

    @Test
    fun `default workspace is a project titled 默认工作区`() {
        // DSH 首次启动会建一个 title 固定为 default-workspace 的工作区（桌面端显示「默认工作区」）；
        // 它和普通项目一样单独成行，不再把默认工作和未分组会话混进「聊天」。
        val workspaces = listOf(
            Workspace(workspaceId = "w1", title = "proj", path = "E:/proj", sessionIds = listOf("s1")),
            Workspace(
                workspaceId = "w-default",
                title = Workspace.DEFAULT_WORKSPACE_TITLE,
                path = "D:/Documents/deepseek-harness/default-workspace",
                sessionIds = listOf("s2", "s3"),
            ),
        )
        val sessions = listOf(
            session("s1", updatedAt = 1),
            session("s2", updatedAt = 30),
            session("s3", updatedAt = 20),
            session("s9", cwd = "C:/elsewhere", updatedAt = 40),
        )

        val projects = SessionProjectGrouping.group(workspaces, sessions)

        assertEquals(listOf("w1", "w-default", SessionProject.ORPHAN_KEY), projects.map { it.key })
        assertEquals(
            listOf("proj", SessionProject.DEFAULT_WORKSPACE_DISPLAY_TITLE, "聊天"),
            projects.map { it.title },
        )
        assertEquals("默认工作区", projects[1].title)
        // 默认工作区只收自己的会话，按最近活跃倒序。
        assertEquals(listOf("s2", "s3"), projects[1].sessions.map { it.id })
        // 「聊天」只收真正未归属的会话。
        assertNull(projects.last().workspace)
        assertEquals(listOf("s9"), projects.last().sessions.map { it.id })
    }

    @Test
    fun `empty default workspace is kept without an empty chat group`() {
        val workspaces = listOf(
            Workspace(
                workspaceId = "w-default",
                title = Workspace.DEFAULT_WORKSPACE_TITLE,
                path = "D:/Documents/deepseek-harness/default-workspace",
            ),
        )

        val projects = SessionProjectGrouping.group(workspaces, emptyList())

        assertEquals(1, projects.size)
        assertEquals("w-default", projects.single().key)
        assertEquals("默认工作区", projects.single().title)
        assertEquals(emptyList<SessionSummary>(), projects.single().sessions)
    }

    @Test
    fun `renamed default workspace is a regular project again`() {
        // 与 DSH 桌面端一致：只有仍带着自动标题 default-workspace 的工作区才算默认工作区。
        val workspaces = listOf(
            Workspace(workspaceId = "w1", title = "my-chats", path = "D:/mine", sessionIds = listOf("s1")),
        )
        val projects = SessionProjectGrouping.group(workspaces, listOf(session("s1")))
        assertEquals(listOf("w1"), projects.map { it.key })
        assertEquals("my-chats", projects.single().title)
    }

    @Test
    fun `nested project keeps its sessions instead of the parent project`() {
        // 复现真实数据：sdk 是 parent-app/plugins/sdk，
        // 父项目先按 cwd 兜底会把子项目的会话抢走，导致侧边栏里 sdk 显示 0 条。
        val workspaces = listOf(
            Workspace(
                workspaceId = "w-parent",
                title = "parent-app",
                path = "F:\\parent-app",
                sessionIds = listOf("s-parent"),
            ),
            Workspace(
                workspaceId = "w-sdk",
                title = "sdk",
                path = "F:\\parent-app\\plugins\\sdk",
                sessionIds = listOf("s-sdk"),
            ),
        )
        val sessions = listOf(
            session("s-parent", cwd = "F:\\parent-app", updatedAt = 1),
            session(
                "s-sdk",
                cwd = "F:\\parent-app\\plugins\\sdk",
                updatedAt = 2,
            ),
        )

        val projects = SessionProjectGrouping.group(workspaces, sessions)

        assertEquals(listOf("s-parent"), projects[0].sessions.map { it.id })
        assertEquals(listOf("s-sdk"), projects[1].sessions.map { it.id })
    }

    @Test
    fun `cwd fallback picks the deepest matching project`() {
        // 子项目成员表还没追上时，cwd 兜底也必须落到路径最深的项目。
        val workspaces = listOf(
            Workspace(
                workspaceId = "w-parent",
                title = "parent",
                path = "F:\\work\\parent",
                sessionIds = emptyList(),
            ),
            Workspace(
                workspaceId = "w-child",
                title = "child",
                path = "F:\\work\\parent\\child",
                sessionIds = emptyList(),
            ),
        )
        val sessions = listOf(session("s-deep", cwd = "F:\\work\\parent\\child\\src"))

        val projects = SessionProjectGrouping.group(workspaces, sessions)

        assertEquals(emptyList<String>(), projects[0].sessions.map { it.id })
        assertEquals(listOf("s-deep"), projects[1].sessions.map { it.id })
    }
}
