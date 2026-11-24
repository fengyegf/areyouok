package com.catmoecircle.areyouok.ui.chat

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.protocol.ActivityJob
import com.catmoecircle.areyouok.gateway.protocol.ActivitySubagent
import com.catmoecircle.areyouok.gateway.protocol.SessionAgentTeam
import com.catmoecircle.areyouok.gateway.protocol.TeamActivity
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 右侧面板宽度：320dp；窄屏（如 360dp 宽设备）时再收到屏宽的 88%。 */
private val TeamPanelMaxWidth = 320.dp

/** 右侧贴边面板：只给左侧做 20dp 圆角，右侧贴屏幕边保持直角。 */
private val TeamPanelShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)

/**
 * 从右侧滑出的「团队协作」面板。
 *
 * 数据来自 `GET /v1/sessions/{id}/activity`（[TeamActivity]，由 repository 每 2s 刷新）：
 * 上半部分是当前会话下的子智能体，下半部分是后台任务，头部带团队任务板状态。
 * 打开时面板 spring 滑入、遮罩渐显；点遮罩收起；关闭时快速滑出。
 *
 * @param open 面板是否打开
 * @param activity 最近一次拉到的团队活动；null 时两个列表都显示空态
 * @param unsupported 当前网关没有 activity 端点（旧 dsh-gateway）：显示降级提示
 * @param onDismiss 点遮罩 / 关闭按钮时回调（返回键由 ChatScreen 的 BackHandler 处理）
 * @param onOpenSubagent 点子智能体行：打开它的只读对话（由 ChatScreen 收起面板并展示全屏页）
 */
@Composable
fun TeamActivityPanel(
    open: Boolean,
    activity: TeamActivity?,
    unsupported: Boolean = false,
    onDismiss: () -> Unit,
    onOpenSubagent: (ActivitySubagent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = updateTransition(targetState = open, label = "teamPanel")

    val scrimAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = if (targetState) 200 else 160) },
        label = "teamPanelScrim",
    ) { isOpen -> if (isOpen) 0.34f else 0f }

    val slide by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
            } else {
                tween(durationMillis = 200, easing = FastOutLinearInEasing)
            }
        },
        label = "teamPanelSlide",
    ) { isOpen -> if (isOpen) 0f else 1f }

    val visible = open || scrimAlpha > 0f || slide < 1f

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (!visible) return@BoxWithConstraints

        // 窄屏兜底：320dp 在 360dp 宽设备上会盖住九成宽度，收到 88% 更接近桌面端观感。
        val panelWidth = minOf(TeamPanelMaxWidth, maxWidth * 0.88f)
        val panelWidthPx = with(LocalDensity.current) { panelWidth.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .pointerInput(open) {
                    detectTapGestures { onDismiss() }
                }
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(panelWidth)
                .offset { IntOffset((panelWidthPx * slide).roundToInt(), 0) }
                .shadow(
                    elevation = 18.dp,
                    shape = TeamPanelShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.18f),
                )
                .clip(TeamPanelShape)
                .background(colorScheme.surface)
        ) {
            TeamActivityPanelContent(
                activity = activity,
                unsupported = unsupported,
                onDismiss = onDismiss,
                onOpenSubagent = onOpenSubagent,
            )
        }
    }
}

@Composable
private fun TeamActivityPanelContent(
    activity: TeamActivity?,
    unsupported: Boolean,
    onDismiss: () -> Unit,
    onOpenSubagent: (ActivitySubagent) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    // 面板自己每秒 tick 一次算时长：网关数据 2s 才刷新，且内容不变时 StateFlow 不会重复发射，
    // 只靠数据驱动会让「运行中 · 12s」停住不动。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val subagents = activity?.subagents.orEmpty()
    val jobs = activity?.jobs.orEmpty()
    val team = activity?.team

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        TeamPanelHeader(onDismiss = onDismiss)

        if (team != null) {
            Spacer(modifier = Modifier.height(14.dp))
            TeamStatusRow(team = team, modifier = Modifier.padding(horizontal = 20.dp))
        }

        if (unsupported) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.team_panel_unsupported),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        TeamPanelDivider()

        // 上半区：子智能体（运行中优先，网关已排好序）。
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SectionHeader(
                title = stringResource(R.string.team_panel_subagents),
                count = subagents.size,
            )
            if (subagents.isEmpty()) {
                EmptyHint(text = stringResource(R.string.team_panel_subagents_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 10.dp),
                ) {
                    items(subagents) { agent ->
                        SubagentRow(agent = agent, now = now, onOpen = onOpenSubagent)
                    }
                }
            }
        }

        TeamPanelDivider()

        // 下半区：后台任务（运行中优先；已结算的弱化显示）。
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SectionHeader(
                title = stringResource(R.string.team_panel_jobs),
                count = jobs.size,
            )
            if (jobs.isEmpty()) {
                EmptyHint(text = stringResource(R.string.team_panel_jobs_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 10.dp),
                ) {
                    items(jobs) { job ->
                        JobRow(job = job, now = now)
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamPanelHeader(onDismiss: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.team_panel_title),
                color = colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.team_panel_subtitle),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        CloseButton(onDismiss = onDismiss)
    }
}

/** 团队状态行：开启/关闭 + 任务板计数（没有 agentTeam 投影时整行不渲染）。 */
@Composable
private fun TeamStatusRow(
    team: SessionAgentTeam,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val inProgress = team.tasks.count { it.isInProgress }
    val pending = team.tasks.count { it.isPending }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = team.enabled)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (team.enabled) R.string.team_panel_team_enabled else R.string.team_panel_team_disabled,
            ),
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.team_panel_task_counts, inProgress, pending),
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = count.toString(),
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 子智能体行：状态点 + 标题（空标题回退 id 前 8 位）+「模型 · 运行中/已空闲 · 时长」+ 行尾箭头。
 *
 * 整行可点击：打开该子代理的只读对话（[onOpen]）；按下时行底色高亮，不依赖 ripple。
 */
@Composable
private fun SubagentRow(
    agent: ActivitySubagent,
    now: Long,
    onOpen: (ActivitySubagent) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val model = agent.model?.takeIf { it.isNotBlank() }
    val status = stringResource(
        if (agent.running) R.string.team_panel_running else R.string.team_panel_idle,
    )
    val duration = agent.updatedAt?.let { TeamActivityFormat.elapsed(now, it) }
    val subtitle = listOfNotNull(model, status, duration).joinToString(" · ")
    val openLabel = stringResource(R.string.team_panel_open_subagent)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (pressed) colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = openLabel,
                role = Role.Button,
            ) { onOpen(agent) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(active = agent.running, modifier = Modifier.padding(top = 5.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agent.displayTitle,
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "›",
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 4.dp),
        )
    }
}

/** 后台任务行：已结算（完成/终止/失败）整体 alpha 0.6，表示「刚结束、弱化保留」。 */
@Composable
private fun JobRow(job: ActivityJob, now: Long) {
    val colorScheme = MaterialTheme.colorScheme
    val kind = job.kind.takeIf { it.isNotBlank() }
    val status = stringResource(jobStatusResId(job.status))
    val duration = job.startedAt?.let { TeamActivityFormat.elapsed(now, it, job.finishedAt) }
    val subtitle = listOfNotNull(kind, status, duration).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (job.isSettled) 0.6f else 1f)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(
            active = job.isRunning,
            failed = job.isFailed,
            modifier = Modifier.padding(top = 5.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = job.firstLineLabel,
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** job 状态 → 文案资源。未知状态原样降级成「未知」，不让面板出现空白副行。 */
private fun jobStatusResId(status: String): Int = when (status) {
    ActivityJob.STATUS_RUNNING -> R.string.team_panel_running
    ActivityJob.STATUS_STOPPING -> R.string.team_panel_job_stopping
    ActivityJob.STATUS_COMPLETED -> R.string.team_panel_job_completed
    ActivityJob.STATUS_KILLED -> R.string.team_panel_job_killed
    ActivityJob.STATUS_FAILED -> R.string.team_panel_job_failed
    else -> R.string.team_panel_job_unknown
}

@Composable
private fun EmptyHint(text: String) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StatusDot(
    active: Boolean,
    failed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val color = when {
        failed -> colorScheme.error
        active -> colorScheme.primary
        else -> colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Canvas(modifier = modifier.size(7.dp)) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

/** 关闭按钮：用 Canvas 画「×」，不为了一个图标引入依赖。 */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.team_panel_close)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onDismiss)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val iconColor = colorScheme.onSurfaceVariant
        Canvas(modifier = Modifier.size(12.dp)) {
            val stroke = 1.8.dp.toPx()
            drawLine(
                color = iconColor,
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = iconColor,
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun TeamPanelDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
