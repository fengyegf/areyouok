package com.catmoecircle.areyouok.ui.chat

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.ui.gateway.GatewayDiscoveryList
import com.catmoecircle.areyouok.gateway.repository.SessionProject
import com.catmoecircle.areyouok.ui.gateway.GatewayProjectList
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings

/** 侧边栏宽度。 */
private val SidebarWidth = 320.dp

/** 侧边栏面板保持直角贴边，不做任何圆角。 */
private val SidebarShape = RectangleShape

/** 面板在 [rememberSaveableStateHolder] 里的 key：关掉再打开时恢复列表展开/折叠等状态。 */
private const val SidebarPanelStateKey = "sidebar-panel"

/**
 * 左上角的圆形菜单按钮，图标为两条圆头横线，用 Compose 绘制，不引入图标资源。
 */
@Composable
fun SidebarMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkSurface = colorScheme.surface.luminance() < 0.35f
    val label = stringResource(R.string.open_sidebar)

    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.14f),
            )
            .clip(CircleShape)
            .background(colorScheme.surface)
            .then(
                if (darkSurface) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val iconColor = colorScheme.onSurface
        Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            // 第一根线占满宽度。
            drawLine(
                color = iconColor,
                start = Offset(0f, inset),
                end = Offset(size.width, inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            // 第二根线短一些，与第一根线左对齐。
            drawLine(
                color = iconColor,
                start = Offset(0f, size.height - inset),
                end = Offset(size.width * 2f / 3f, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 从左侧滑出的模态侧边栏。
 *
 * 打开时面板通过 spring 滑入，背景遮罩渐显；关闭时面板快速滑出，遮罩渐隐。
 * 点击遮罩或返回键关闭。
 *
 * 内容区由 dsh-gateway 的实时状态驱动：会话列表 + 新建会话入口。
 */
@Composable
fun ModalSidebar(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    connection: ConnectionState? = null,
    projects: List<SessionProject> = emptyList(),
    selectedSessionId: String? = null,
    discoveredGateways: List<DiscoveredGateway> = emptyList(),
    onSelectGateway: (DiscoveredGateway) -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onNewSession: () -> Unit = {},
    onNewSessionInProject: (String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onScan: () -> Unit = {},
    /** 有待处理审批/提问的会话 id：对应会话行上显示橙色「!」标记。 */
    pendingSessionIds: Set<String> = emptySet(),
) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = updateTransition(targetState = open, label = "sidebar")

    val scrimAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = if (targetState) 220 else 180) },
        label = "sidebarScrim",
    ) { isOpen -> if (isOpen) 0.34f else 0f }

    val slide by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
            } else {
                tween(durationMillis = 200, easing = FastOutLinearInEasing)
            }
        },
        label = "sidebarSlide",
    ) { isOpen -> if (isOpen) 0f else 1f }

    val panelWidthPx = with(LocalDensity.current) { SidebarWidth.toPx() }
    val visible = open || scrimAlpha > 0f || slide < 1f

    // 面板关闭时会整体离开组合，用 SaveableStateHolder 把列表的展开/折叠等 UI 状态留住，
    // 否则再次打开侧边栏会按「默认全部展开」重新初始化。
    val panelStateHolder = rememberSaveableStateHolder()

    Box(modifier = modifier.fillMaxSize()) {
        if (visible) {
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
                    .fillMaxHeight()
                    .width(SidebarWidth)
                    .offset { IntOffset((-panelWidthPx * slide).roundToInt(), 0) }
                    .shadow(
                        elevation = 18.dp,
                        shape = SidebarShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.18f),
                    )
                    .clip(SidebarShape)
                    .background(colorScheme.surface)
            ) {
                panelStateHolder.SaveableStateProvider(SidebarPanelStateKey) {
                    SidebarPanel(
                        connection = connection,
                        projects = projects,
                        selectedSessionId = selectedSessionId,
                        discoveredGateways = discoveredGateways,
                        onSelectGateway = onSelectGateway,
                        onSelectSession = onSelectSession,
                        onNewSession = onNewSession,
                        onNewSessionInProject = onNewSessionInProject,
                        onOpenSettings = onOpenSettings,
                        onScan = onScan,
                        pendingSessionIds = pendingSessionIds,
                    )
                }
            }
        }
    }
}

/**
 * 侧边栏内容：顶部品牌区 + dsh-gateway 会话区，底部为半透明磨砂操作区。
 *
 * 磨砂区使用 Miuix 的背景模糊：先捕获面板内容，再在底栏上做渐进模糊 + 半透明着色，
 * 会话列表滚动到底栏下方时会呈现真实的毛玻璃效果。
 */
@Composable
private fun SidebarPanel(
    connection: ConnectionState?,
    projects: List<SessionProject>,
    selectedSessionId: String?,
    discoveredGateways: List<DiscoveredGateway>,
    onSelectGateway: (DiscoveredGateway) -> Unit,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onNewSessionInProject: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
    pendingSessionIds: Set<String>,
) {
    val colorScheme = MaterialTheme.colorScheme
    val panelColor = colorScheme.surface

    val backdrop = rememberLayerBackdrop {
        drawRect(panelColor)
        drawContent()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = stringResource(R.string.sidebar_title),
                    color = colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.sidebar_subtitle),
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colorScheme.outlineVariant)
                )
            }

            // 顶部品牌区与下方的间距：连接卡片已移除，直接进入会话列表。
            Spacer(modifier = Modifier.height(8.dp))

            if ((connection?.isOnline != true) && discoveredGateways.isNotEmpty()) {
                GatewayDiscoveryList(
                    gateways = discoveredGateways,
                    onSelect = onSelectGateway,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            GatewayProjectList(
                projects = projects,
                selectedSessionId = selectedSessionId,
                onSelectSession = onSelectSession,
                onNewSessionIn = onNewSessionInProject,
                pendingSessionIds = pendingSessionIds,
                modifier = Modifier.weight(1f),
            )
        }

        SidebarBottomBar(
            backdrop = backdrop,
            onNewSession = onNewSession,
            onOpenSettings = onOpenSettings,
            onScan = onScan,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 底部磨砂操作区：左侧“新对话”胶囊，右侧圆形「扫码」「设置」按钮。
 *
 * 底栏高度比内容更高，配合 [ProgressiveBlur.Bottom] 让模糊从底部向上柔和淡出。
 */
@Composable
private fun SidebarBottomBar(
    backdrop: LayerBackdrop,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.surface.luminance() < 0.35f
    val frostTint = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.07f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .progressiveTextureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 30f,
                gradient = ProgressiveBlur.Bottom,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(BlendColorEntry(frostTint)),
                ),
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NewChatButton(onClick = onNewSession)

            Spacer(modifier = Modifier.weight(1f))

            ScanButton(onClick = onScan)
            Spacer(modifier = Modifier.width(10.dp))
            SettingsButton(onClick = onOpenSettings)
        }
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.new_chat)

    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.28f), CircleShape)
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Edit,
            contentDescription = null,
            tint = colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = label,
            color = colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 底部圆形「扫码」按钮：调起相机扫描网关二维码。 */
@Composable
private fun ScanButton(onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.gateway_scan)

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.28f), CircleShape)
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Scan,
            contentDescription = null,
            tint = colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.settings)

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.28f), CircleShape)
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Settings,
            contentDescription = null,
            tint = colorScheme.onSurface,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Preview(name = "Sidebar", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun SidebarPreview() {
    AreYouOKTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ModalSidebar(open = true, onDismiss = {})
        }
    }
}
