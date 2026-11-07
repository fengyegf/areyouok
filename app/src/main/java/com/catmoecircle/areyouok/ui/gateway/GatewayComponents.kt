package com.catmoecircle.areyouok.ui.gateway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.gateway.repository.SessionProject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Folder

/** 状态点：绿=在线，橙=连接/重连，红=错误，灰=空闲。 */
@Composable
fun GatewayStatusDot(
    phase: ConnectionPhase,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 9.dp,
) {
    val colorScheme = MaterialTheme.colorScheme
    val dotColor = when (phase) {
        ConnectionPhase.CONNECTED -> Color(0xFF34C759)
        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> Color(0xFFFF9F0A)
        ConnectionPhase.ERROR -> Color(0xFFFF3B30)
        ConnectionPhase.IDLE -> colorScheme.onSurfaceVariant
    }
    Canvas(modifier = modifier.size(size)) {
        drawCircle(
            color = dotColor.copy(alpha = 0.22f),
            radius = this.size.minDimension * 0.72f,
            center = center,
        )
        drawCircle(color = dotColor, radius = this.size.minDimension * 0.40f, center = center)
        if (phase == ConnectionPhase.CONNECTED) {
            drawCircle(
                color = dotColor.copy(alpha = 0.35f),
                radius = this.size.minDimension * 0.9f,
                center = Offset(center.x, center.y),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/** 玻璃质感的胶囊按钮（与侧边栏底部按钮同一套视觉语言）。 */
@Composable
fun GatewayPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val background = when {
        !enabled -> colorScheme.surfaceVariant.copy(alpha = 0.6f)
        emphasized -> colorScheme.primary
        else -> colorScheme.surface
    }
    val contentColor = when {
        !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        emphasized -> colorScheme.onPrimary
        else -> colorScheme.onSurface
    }
    val clickModifier = if (enabled) {
        Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.24f), CircleShape)
            .then(clickModifier)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 侧边栏顶部连接卡片：网关名、状态、地址、错误提示，以及「重连 / 设置」入口。
 */
@Composable
fun GatewayStatusCard(
    connection: ConnectionState,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, colorScheme.outline.copy(alpha = 0.18f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GatewayStatusDot(phase = connection.phase)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.displayName,
                    color = colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = connectionStatusText(connection),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        connection.endpoint?.let { endpoint ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = endpoint.baseUrl,
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        connection.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                color = colorScheme.error,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GatewayPillButton(
                label = stringResource(R.string.gateway_reconnect),
                onClick = onReconnect,
            )
            GatewayPillButton(
                label = stringResource(R.string.settings),
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
fun connectionStatusText(connection: ConnectionState): String = when (connection.phase) {
    ConnectionPhase.CONNECTED ->
        if (connection.lan) "${stringResource(R.string.gateway_connected)} · 局域网" else stringResource(R.string.gateway_connected)
    ConnectionPhase.CONNECTING -> stringResource(R.string.gateway_connecting)
    ConnectionPhase.RECONNECTING -> stringResource(R.string.gateway_reconnecting, connection.attempt)
    ConnectionPhase.ERROR -> connection.error ?: stringResource(R.string.gateway_error)
    ConnectionPhase.IDLE -> stringResource(R.string.gateway_idle)
}

/** 侧边栏展示 mDNS 发现的网关，点一下直连（局域网免 token）。 */
@Composable
fun GatewayDiscoveryList(
    gateways: List<com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway>,
    onSelect: (com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (gateways.isEmpty()) return
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gateway_discovered),
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        gateways.take(5).forEach { gateway ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onSelect(gateway) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gateway.name,
                        color = colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    gateway.baseUrl?.let { url ->
                        Text(
                            text = url,
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.gateway_connect),
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 侧边栏会话列表：标题 + 新建入口 + 可滚动列表。 */
@Composable
fun GatewaySessionList(
    sessions: List<SessionSummary>,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gateway_sessions),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            GatewayPillButton(
                label = stringResource(R.string.gateway_new_session),
                onClick = onNewSession,
            )
        }

        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.gateway_sessions_empty),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 2.dp,
                bottom = 128.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedSessionId,
                    onClick = { onSelectSession(session.id) },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 该会话里有待处理的审批/提问：标题右侧显示橙色「!」，点进会话才会在底部看到事件。 */
    hasPending: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colorScheme.surfaceVariant.copy(alpha = 0.85f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        session.running -> Color(0xFF34C759)
                        session.pendingInput -> Color(0xFFFF9F0A)
                        else -> colorScheme.outline.copy(alpha = 0.5f)
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 空白会话没有标题（DSH 里还没发过消息），显示成「新对话」而不是 id 前缀。
                text = if (session.isBlank) stringResource(R.string.new_chat) else session.displayTitle,
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.running) {
                Text(
                    text = stringResource(R.string.gateway_running),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        if (hasPending) {
            Spacer(Modifier.width(8.dp))
            PendingInterventionBadge()
        }
    }
}

/**
 * 分组末尾的折叠行：「展开其余 N 个对话」或「收起」。
 *
 * 缩进与会话行对齐（「聊天」分组 8dp，项目 14dp），字号更小、颜色更弱，
 * 整行可点；右侧 ChevronForward 展开时朝下（90°）、收起时朝上（-90°）。
 */
@Composable
private fun SessionFoldToggleRow(
    label: String,
    expanded: Boolean,
    isChats: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (isChats) 8.dp else 14.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = MiuixIcons.Regular.ChevronForward,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier
                .size(13.dp)
                .rotate(if (expanded) -90f else 90f),
        )
    }
}

/** 会话行右侧的橙色「!」：表示该会话里有待处理的审批/提问。 */
@Composable
private fun PendingInterventionBadge() {
    val accent = Color(0xFFF0A020)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.5.dp, accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}


/** 展开 / 收起用的 key 集合：以 `List<String>` 形式交给 rememberSaveable 保存。 */
private val StringSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * 侧边栏列表：「项目」分组 + 末尾的「聊天」分组。
 *
 * DSH 的默认工作区（`title == "default-workspace"`）是一个普通项目行，标题显示「默认工作区」；
 * 「聊天」只收纳确实不属于任何项目的会话，没有未归属会话时不显示。
 *
 * 一级项目行：文件夹 + 项目名 + ChevronForward（展开指示）+ 右侧「新对话」按钮。
 * 点右侧铅笔图标会在该项目里新建会话（网关 `session/create {workspaceId}`）；
 * 点行本身只展开 / 收起二级会话列表。
 *
 * 展开状态：默认全部展开，选中会话所在项目自动展开；用户手动收起后不再自动展开，
 * 直到用户再次点开（避免会话列表刷新时反复弹开）。
 * 展开 / 收起结果用 [rememberSaveable] 保存：侧边栏关闭时面板会离开组合，
 * 靠外层 `SaveableStateHolder` 恢复，关掉再打开不会被重置成「全部展开」。
 *
 * 二级会话默认先露 [SESSION_PREVIEW_LIMIT] 条：超过时折叠成一行「展开其余 N 个对话」，
 * 点开后全部展开、末尾给一行「收起」。哪些分组被点开过同样用 [rememberSaveable] +
 * 外层 `SaveableStateHolder` 保存，侧边栏关掉再打开保持原样。
 */
@Composable
fun GatewayProjectList(
    projects: List<SessionProject>,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewSessionIn: (workspaceId: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** 有待处理审批/提问的会话 id：会话行显示橙色「!」。 */
    pendingSessionIds: Set<String> = emptySet(),
) {
    val colorScheme = MaterialTheme.colorScheme
    // 默认全部项目展开（二级会话直接可见）；用户收起后保持收起，直到再次点开。
    var expandedKeys by rememberSaveable(stateSaver = StringSetSaver) {
        mutableStateOf(emptySet<String>())
    }
    var collapsedByUser by rememberSaveable(stateSaver = StringSetSaver) {
        mutableStateOf(emptySet<String>())
    }
    var initialized by rememberSaveable { mutableStateOf(false) }
    // 已经点过「展开其余 N 个对话」的分组：元素是 [SessionProject.key]。
    var sessionsExpandedAllKeys by rememberSaveable(stateSaver = StringSetSaver) {
        mutableStateOf(emptySet<String>())
    }
    // 用户手动点过「收起」的分组：自动展开选中会话时跳过它们（沿用 collapsedByUser 的思路）。
    var sessionsCollapsedByUser by rememberSaveable(stateSaver = StringSetSaver) {
        mutableStateOf(emptySet<String>())
    }

    LaunchedEffect(projects) {
        if (!initialized && projects.isNotEmpty()) {
            expandedKeys = projects.map { it.key }.toSet()
            initialized = true
        }
    }

    LaunchedEffect(projects, selectedSessionId) {
        if (selectedSessionId == null) return@LaunchedEffect
        val owner = projects.firstOrNull { project ->
            project.sessions.any { it.id == selectedSessionId }
        } ?: return@LaunchedEffect
        if (owner.key !in collapsedByUser) expandedKeys = expandedKeys + owner.key
    }

    // 选中的会话排在第 5 条及之后时，它默认被折叠行藏着：自动展开该分组，
    // 否则点进来（或新建后）选中的是「看不见的那一条」。用户手动收起过的分组不再自动展开。
    LaunchedEffect(projects, selectedSessionId) {
        if (selectedSessionId == null) return@LaunchedEffect
        val owner = projects.firstOrNull { project ->
            project.sessions.any { it.id == selectedSessionId }
        } ?: return@LaunchedEffect
        if (owner.key in sessionsCollapsedByUser) return@LaunchedEffect
        val index = owner.sessions.indexOfFirst { it.id == selectedSessionId }
        if (index >= SESSION_PREVIEW_LIMIT) {
            sessionsExpandedAllKeys = sessionsExpandedAllKeys + owner.key
        }
    }

    val namedProjects = projects.filter { it.workspace != null }
    val chats = projects.firstOrNull { it.workspace == null }

    /** 一个一级分组（项目或「聊天」）+ 展开后的二级会话。 */
    fun LazyListScope.groupItems(project: SessionProject, isChats: Boolean) {
        val expanded = project.key in expandedKeys
        val selected = project.sessions.any { it.id == selectedSessionId }
        item(key = "group:${project.key}") {
            ProjectRow(
                project = project,
                isChats = isChats,
                expanded = expanded,
                selected = selected,
                hasPending = project.sessions.any { it.id in pendingSessionIds },
                onToggle = {
                    if (expanded) {
                        expandedKeys = expandedKeys - project.key
                        collapsedByUser = collapsedByUser + project.key
                    } else {
                        expandedKeys = expandedKeys + project.key
                        collapsedByUser = collapsedByUser - project.key
                    }
                },
                onNewChat = { onNewSessionIn(project.workspace?.key) },
            )
        }
        if (!expanded) return
        if (project.sessions.isEmpty()) {
            item(key = "empty:${project.key}") {
                Text(
                    text = stringResource(R.string.gateway_project_empty),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        start = if (isChats) 26.dp else 40.dp,
                        top = 4.dp,
                        bottom = 8.dp,
                    ),
                )
            }
        } else {
            val sessionsExpandedAll = project.key in sessionsExpandedAllKeys
            val hiddenCount = hiddenSessionCount(project.sessions.size)
            items(
                visibleSessions(project.sessions, expanded = sessionsExpandedAll),
                key = { "session:${it.id}" },
            ) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedSessionId,
                    onClick = { onSelectSession(session.id) },
                    modifier = Modifier.padding(start = if (isChats) 8.dp else 14.dp),
                    hasPending = session.id in pendingSessionIds,
                )
            }
            if (hiddenCount > 0) {
                if (sessionsExpandedAll) {
                    item(key = "less:${project.key}") {
                        SessionFoldToggleRow(
                            label = stringResource(R.string.gateway_sessions_collapse),
                            expanded = true,
                            isChats = isChats,
                            onClick = {
                                sessionsExpandedAllKeys = sessionsExpandedAllKeys - project.key
                                sessionsCollapsedByUser = sessionsCollapsedByUser + project.key
                            },
                        )
                    }
                } else {
                    item(key = "more:${project.key}") {
                        SessionFoldToggleRow(
                            label = stringResource(
                                R.string.gateway_sessions_expand_more,
                                hiddenCount,
                            ),
                            expanded = false,
                            isChats = isChats,
                            onClick = {
                                sessionsExpandedAllKeys = sessionsExpandedAllKeys + project.key
                                sessionsCollapsedByUser = sessionsCollapsedByUser - project.key
                            },
                        )
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (projects.isEmpty()) {
            ProjectSectionLabel(stringResource(R.string.gateway_projects))
            Text(
                text = stringResource(R.string.gateway_sessions_empty),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                end = 16.dp,
                top = 2.dp,
                bottom = 128.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (namedProjects.isNotEmpty()) {
                item(key = "header:projects") {
                    ProjectSectionLabel(stringResource(R.string.gateway_projects))
                }
                namedProjects.forEach { project -> groupItems(project, isChats = false) }
            }
            if (chats != null) {
                // 「聊天」固定排在项目段之后；没有项目时不需要额外的间隔。
                if (namedProjects.isNotEmpty()) {
                    item(key = "header:chats-gap") {
                        Spacer(Modifier.height(10.dp))
                    }
                }
                groupItems(chats, isChats = true)
            }
        }
    }
}

/** 「项目」分组的小标题（含 LazyColumn 的 8dp contentPadding 后与品牌区 24dp 对齐）。 */
@Composable
private fun ProjectSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    )
}

/**
 * 一级分组行：文件夹图标（仅项目）+ 标题 + ChevronForward + 右侧「新对话」按钮。
 *
 * 展开时箭头旋转 90°（朝下），收起时朝右，与参考图的 ChevronForward 指示一致。
 */
@Composable
private fun ProjectRow(
    project: SessionProject,
    isChats: Boolean,
    expanded: Boolean,
    selected: Boolean,
    hasPending: Boolean,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val newChatLabel = if (isChats) {
        stringResource(R.string.gateway_new_chat_in_chats)
    } else {
        stringResource(R.string.gateway_new_chat_in_project, project.title)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) colorScheme.surfaceVariant.copy(alpha = 0.55f) else Color.Transparent,
            )
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isChats) {
            Icon(
                imageVector = MiuixIcons.Regular.Folder,
                contentDescription = null,
                tint = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        // 标题 + 展开箭头：箭头紧跟标题，剩余空白留在右侧，和参考图一致。
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = project.title,
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier
                    .size(14.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (hasPending) {
            Spacer(Modifier.width(6.dp))
            PendingInterventionBadge()
        }
        Spacer(Modifier.width(4.dp))
        // 右侧「在分组里新建对话」：单独可点，不会触发展开 / 收起。
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = newChatLabel, role = Role.Button, onClick = onNewChat)
                .semantics { contentDescription = newChatLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Edit,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
