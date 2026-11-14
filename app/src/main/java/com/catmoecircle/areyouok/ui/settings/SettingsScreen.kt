package com.catmoecircle.areyouok.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewaySettings
import com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import com.catmoecircle.areyouok.ui.theme.CanvasLight
import com.catmoecircle.areyouok.ui.theme.SettingsCanvasLight
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Stopwatch

/**
 * 设置页的分类入口。
 *
 * 根页面只展示「连接 / 网关 / 通知 / 关于」四个分类（不再把连接表单、开关直接铺开），
 * 点进去才是对应的子页；返回键在子页先回根页面，再退出设置。
 */
internal enum class SettingsPage(@StringRes val titleRes: Int) {
    ROOT(R.string.settings_title),
    CONNECTION(R.string.settings_page_connection),
    GATEWAY(R.string.settings_page_gateway),
    WORK_STEPS(R.string.work_step_display_title),
    NOTIFICATIONS(R.string.settings_page_notifications),
    ABOUT(R.string.settings_page_about),
}

/**
 * 独立设置页：全屏页面，替代原来的配对弹窗。
 *
 * 结构：
 * - 根页面：连接设备 / 网关 / 通知 / 关于四个分类入口；
 * - 连接页：已连接过的设备 + 连接开关，末尾「添加连接」直接唤起扫码；
 * - 网关页：自动发现同一局域网的 DSH 网关（点一下直连）+ 地址 / 配对码手动连接；
 * - 工作步骤显示：聊天里工具调用细节的 4 个档位（简洁 / 标准 / 详细 / 完全展开）；
 * - 通知页：气泡提醒（置顶，开启后展开「激活气泡」）、通知权限、通知总开关、审批 / 提问 / 完成提醒；
 * - 关于页：应用名、设备名、版本号。
 *
 * 扫码 / 深链带入的邀请由调用方通过 [initialUrl]、[initialCode]、[inviteLabel]、
 * [inviteFingerprint] 预填（指纹说明公网自签证书已固定进 App）；
 * 配对失败时 [error] 会把页面自动切到网关页，方便用户改地址/配对码后重试；
 * 配对请求进行中（[busy]）时禁止返回，避免页面关掉但请求还在飞。
 */
@Composable
fun SettingsScreen(
    connection: ConnectionState,
    devices: List<GatewaySettings.RecentGateway>,
    discoveredGateways: List<DiscoveredGateway>,
    initialUrl: String,
    initialCode: String,
    inviteLabel: String?,
    inviteFingerprint: String? = null,
    busy: Boolean,
    error: String?,
    notificationsEnabled: Boolean,
    notifyApprovals: Boolean,
    notifyQuestions: Boolean,
    notifyCompletion: Boolean,
    notificationPermissionGranted: Boolean,
    bubbleEnabled: Boolean,
    bubblesAllowed: Boolean,
    workStepDisplay: WorkStepDisplay,
    workStepCollapseTiming: WorkStepCollapseTiming,
    workStepFoldedReplies: WorkStepFoldedReplies,
    deviceName: String,
    onBack: () -> Unit,
    onConnect: (url: String, code: String) -> Unit,
    onScan: () -> Unit,
    onScanImage: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectDevice: (GatewaySettings.RecentGateway) -> Unit,
    onSelectDiscoveredGateway: (DiscoveredGateway) -> Unit,
    onClearError: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onApprovalsChange: (Boolean) -> Unit,
    onQuestionsChange: (Boolean) -> Unit,
    onCompletionChange: (Boolean) -> Unit,
    onBubbleChange: (Boolean) -> Unit,
    onRequestBubbleSettings: () -> Unit,
    onWorkStepDisplayChange: (WorkStepDisplay) -> Unit,
    onWorkStepCollapseTimingChange: (WorkStepCollapseTiming) -> Unit,
    onWorkStepFoldedRepliesChange: (WorkStepFoldedReplies) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    /** 打开系统的「通知管理」页（不是应用通知渠道页）。 */
    onOpenNotificationManagement: () -> Unit,
    /** 打开 MIUI 的「后台弹出界面」权限页；非 MIUI 机型不显示该入口。 */
    onOpenBackgroundPopupSettings: () -> Unit,
    /** 是否显示「后台弹出界面」入口（MIUI 专属权限）。 */
    backgroundPopupSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val versionName = remember<String>(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // 浅色主题下设置页用更浅的画布，让白色卡片层次更接近系统设置页。
    val settingsBackground = if (colorScheme.background == CanvasLight) {
        SettingsCanvasLight
    } else {
        colorScheme.background
    }

    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    // 扫码/深链带入邀请时自动落到网关页并预填，省掉再点一次「网关」。
    LaunchedEffect(inviteLabel) {
        if (inviteLabel != null) page = SettingsPage.GATEWAY
    }

    // 配对失败时切到网关页：错误提示和可修改的地址/配对码都在那里。
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) page = SettingsPage.GATEWAY
    }

    // 配对期间吞掉返回键，和原来的弹窗行为保持一致。
    BackHandler(enabled = true) {
        if (busy) return@BackHandler
        if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(settingsBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 页面是盖在聊天界面之上的浮层：吞掉落在留白处的点击，避免点穿到下面的聊天控件。
                // 只处理点击，不拦截拖动，否则会在触摸滑动超过阈值前吞掉移动事件，导致列表滚不动。
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
        SettingsHeader(
            busy = busy,
            onBack = {
                if (busy) return@SettingsHeader
                if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT
            },
        )

        AnimatedContent(
            targetState = page,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                if (targetState == SettingsPage.ROOT) {
                    (slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(240)) { it } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(160)))
                }
            },
            label = "settings-page",
        ) { target ->
            when (target) {
                SettingsPage.ROOT -> SettingsRootPage(
                    connection = connection,
                    notificationsEnabled = notificationsEnabled,
                    notificationPermissionGranted = notificationPermissionGranted,
                    workStepDisplay = workStepDisplay,
                    workStepCollapseTiming = workStepCollapseTiming,
                    workStepFoldedReplies = workStepFoldedReplies,
                    versionName = versionName,
                    onOpenPage = { page = it },
                    onWorkStepCollapseTimingChange = onWorkStepCollapseTimingChange,
                    onWorkStepFoldedRepliesChange = onWorkStepFoldedRepliesChange,
                )

                SettingsPage.CONNECTION -> SettingsConnectionPage(
                    connection = connection,
                    devices = devices,
                    busy = busy,
                    onConnectDevice = onConnectDevice,
                    onDisconnectDevice = onDisconnect,
                    onAddConnection = onScan,
                    onScanImage = onScanImage,
                )

                SettingsPage.GATEWAY -> SettingsGatewayPage(
                    connection = connection,
                    discoveredGateways = discoveredGateways,
                    initialUrl = initialUrl,
                    initialCode = initialCode,
                    inviteLabel = inviteLabel,
                    inviteFingerprint = inviteFingerprint,
                    busy = busy,
                    error = error,
                    onSelectDiscoveredGateway = onSelectDiscoveredGateway,
                    onConnect = onConnect,
                    onClearError = onClearError,
                )

                SettingsPage.WORK_STEPS -> SettingsWorkStepDisplayPage(
                    selected = workStepDisplay,
                    onSelect = onWorkStepDisplayChange,
                )

                SettingsPage.NOTIFICATIONS -> SettingsNotificationPage(
                    notificationsEnabled = notificationsEnabled,
                    notifyApprovals = notifyApprovals,
                    notifyQuestions = notifyQuestions,
                    notifyCompletion = notifyCompletion,
                    permissionGranted = notificationPermissionGranted,
                    bubbleEnabled = bubbleEnabled,
                    bubblesAllowed = bubblesAllowed,
                    onRequestPermission = onRequestNotificationPermission,
                    onOpenSystemSettings = onOpenNotificationSettings,
                    onOpenNotificationManagement = onOpenNotificationManagement,
                    onOpenBackgroundPopupSettings = onOpenBackgroundPopupSettings,
                    backgroundPopupSupported = backgroundPopupSupported,
                    onNotificationsChange = onNotificationsChange,
                    onApprovalsChange = onApprovalsChange,
                    onQuestionsChange = onQuestionsChange,
                    onCompletionChange = onCompletionChange,
                    onBubbleChange = onBubbleChange,
                    onRequestBubbleSettings = onRequestBubbleSettings,
                )

                SettingsPage.ABOUT -> SettingsAboutPage(
                    deviceName = deviceName,
                    versionName = versionName,
                )
            }
        }
        }
    }
}

/**
 * 顶部区域：只保留返回箭头。
 *
 * 页面大标题放进 [SettingsPageScaffold] 的滚动内容里，这样在标题附近滑动也能滚动，
 * 滚动时标题随内容一起移出，不会出现内容从固定标题下方穿过的割裂感。
 */
@Composable
private fun SettingsHeader(
    busy: Boolean,
    onBack: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = if (busy) {
                            colorScheme.onSurface.copy(alpha = 0.35f)
                        } else {
                            colorScheme.onSurface
                        },
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/** 设置页通用滚动容器：大标题 + 内容同属滚动区，统一最大宽度、水平留白与底部安全区。 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = title,
                color = colorScheme.onSurface,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 24.dp),
            )
            content()
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/**
 * 设置分组：分组标题 + 一整块纯白圆角卡片。
 *
 * 卡片之间不再画分隔线，行与行靠行内留白区分；标题比卡片多缩进 16dp。
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                color = colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colorScheme.surface),
            content = content,
        )
    }
}

/**
 * 可点击的导航行：裸图标 + 标题/说明 + 右侧值 + 箭头。
 *
 * 分类入口（根页面）用 [value] 展示当前连接/版本等摘要；子页里的入口行只用标题。
 * 行内留白较大（16dp 上下 / 20dp 左右），卡片里不再画分隔线。
 */
@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val clickModifier = if (onClick != null && enabled) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    val contentColor = if (enabled) colorScheme.onSurface else colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                colorScheme.onSurface.copy(alpha = 0.62f)
            } else {
                colorScheme.onSurface.copy(alpha = 0.28f)
            },
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = contentColor,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                color = valueColor ?: colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.3f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 带开关的设置行：标题/说明 + Switch。 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** 左标签 + 右值的信息行（关于页 / 当前网关）。 */
@Composable
internal fun SettingsInfoRow(label: String, value: String, valueColor: Color? = null) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            color = valueColor ?: colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 带下拉档位的设置行：白色浮层贴在行的下沿并右对齐，尺寸紧凑。 */
@Composable
private fun <T> WorkStepSelectorRow(
    icon: ImageVector,
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedLabel = optionLabel(selected)
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val gapPx = remember(density) { with(density) { 6.dp.roundToPx() } }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = "$title，$selectedLabel" }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.onSurface.copy(alpha = 0.62f),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colorScheme.onSurface,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = MiuixIcons.Regular.ChevronForward,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(90f),
                    )
                }

                if (expanded) {
                    Popup(
                        onDismissRequest = { expanded = false },
                        properties = PopupProperties(focusable = true),
                        popupPositionProvider = remember(gapPx) {
                            SettingsRowMenuPositionProvider(gapPx)
                        },
                    ) {
                        Surface(
                            modifier = Modifier.width(210.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = colorScheme.surface,
                            contentColor = colorScheme.onSurface,
                            shadowElevation = 14.dp,
                        ) {
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                options.forEach { option ->
                                    WorkStepPickerRow(
                                        label = optionLabel(option),
                                        selected = option == selected,
                                        onClick = {
                                            expanded = false
                                            onSelect(option)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 把档位浮层贴在当前值下方并右对齐；下方空间不足时翻到上方。 */
private class SettingsRowMenuPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - gapPx).coerceAtLeast(gapPx)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(gapPx, maxX)
        val below = anchorBounds.bottom + gapPx
        val y = if (below + popupContentSize.height <= windowSize.height - gapPx) {
            below
        } else {
            (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(gapPx)
        }
        return IntOffset(x, y)
    }
}

/** 浮层里的一行：选中项染成主题色并带缩放勾选，和 ComposerMenuRow 保持一致。 */
@Composable
private fun WorkStepPickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "settingsPickerSelected",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurface,
        animationSpec = tween(durationMillis = 180),
        label = "settingsPickerLabel",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    alpha = selectedProgress
                    val scale = 0.7f + 0.3f * selectedProgress
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Ok,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 设置根页面：只做分类入口，不再直接展示连接表单和通知开关。
 */
@Composable
private fun SettingsRootPage(
    connection: ConnectionState,
    notificationsEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    workStepDisplay: WorkStepDisplay,
    workStepCollapseTiming: WorkStepCollapseTiming,
    workStepFoldedReplies: WorkStepFoldedReplies,
    versionName: String,
    onOpenPage: (SettingsPage) -> Unit,
    onWorkStepCollapseTimingChange: (WorkStepCollapseTiming) -> Unit,
    onWorkStepFoldedRepliesChange: (WorkStepFoldedReplies) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    val connectionSummary = when {
        connection.endpoint == null || connection.phase == ConnectionPhase.IDLE ->
            stringResource(R.string.gateway_idle)
        connection.isOnline -> stringResource(R.string.settings_device_connected)
        else -> connection.displayName
    }
    val gatewaySummary = connection.endpoint?.baseUrl
        ?.let(::gatewayAddressLabel)
        ?: stringResource(R.string.gateway_idle)
    val notificationSummary = when {
        !notificationPermissionGranted -> stringResource(R.string.settings_notifications_permission_not_granted)
        notificationsEnabled -> stringResource(R.string.settings_notifications_on)
        else -> stringResource(R.string.settings_notifications_off)
    }
    val notificationSummaryColor = if (!notificationPermissionGranted) {
        colorScheme.error
    } else {
        null
    }

    SettingsPageScaffold(title = stringResource(R.string.settings_title)) {
        SettingsGroup(title = stringResource(R.string.settings_section_connection)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Link,
                title = stringResource(R.string.settings_root_connection),
                value = connectionSummary,
                onClick = { onOpenPage(SettingsPage.CONNECTION) },
            )
            SettingsNavRow(
                icon = MiuixIcons.Regular.SearchDevice,
                title = stringResource(R.string.settings_root_gateway),
                value = gatewaySummary,
                onClick = { onOpenPage(SettingsPage.GATEWAY) },
            )
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_app)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Reply,
                title = stringResource(R.string.settings_root_notifications),
                value = notificationSummary,
                valueColor = notificationSummaryColor,
                onClick = { onOpenPage(SettingsPage.NOTIFICATIONS) },
            )
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_chat)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Playlist,
                title = stringResource(R.string.work_step_display_title),
                description = stringResource(R.string.work_step_display_desc),
                value = stringResource(workStepDisplay.labelRes),
                onClick = { onOpenPage(SettingsPage.WORK_STEPS) },
            )
            WorkStepSelectorRow(
                icon = MiuixIcons.Regular.Stopwatch,
                title = stringResource(R.string.work_step_collapse_title),
                description = stringResource(R.string.work_step_collapse_desc),
                options = WorkStepCollapseTiming.entries,
                selected = workStepCollapseTiming,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = onWorkStepCollapseTimingChange,
            )
            WorkStepSelectorRow(
                icon = MiuixIcons.Regular.Reply,
                title = stringResource(R.string.work_step_folded_replies_title),
                description = stringResource(R.string.work_step_folded_replies_desc),
                options = WorkStepFoldedReplies.entries,
                selected = workStepFoldedReplies,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = onWorkStepFoldedRepliesChange,
            )
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_about)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Info,
                title = stringResource(R.string.settings_root_about),
                value = versionName.ifBlank { "—" },
                onClick = { onOpenPage(SettingsPage.ABOUT) },
            )
        }
    }
}

@Preview(name = "Settings root", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun SettingsScreenPreview() {
    AreYouOKTheme {
        SettingsScreen(
            connection = ConnectionState(),
            devices = listOf(
                GatewaySettings.RecentGateway("http://192.168.1.5:7788", "我的电脑"),
            ),
            discoveredGateways = emptyList(),
            initialUrl = "",
            initialCode = "",
            inviteLabel = null,
            busy = false,
            error = null,
            notificationsEnabled = true,
            notifyApprovals = true,
            notifyQuestions = true,
            notifyCompletion = true,
            notificationPermissionGranted = true,
            bubbleEnabled = false,
            bubblesAllowed = false,
            workStepDisplay = WorkStepDisplay.DEFAULT,
            workStepCollapseTiming = WorkStepCollapseTiming.DEFAULT,
            workStepFoldedReplies = WorkStepFoldedReplies.DEFAULT,
            deviceName = "Pixel 8",
            onBack = {},
            onConnect = { _, _ -> },
            onScan = {},
            onScanImage = {},
            onDisconnect = {},
            onConnectDevice = {},
            onSelectDiscoveredGateway = {},
            onClearError = {},
            onNotificationsChange = {},
            onApprovalsChange = {},
            onQuestionsChange = {},
            onCompletionChange = {},
            onBubbleChange = {},
            onRequestBubbleSettings = {},
            onWorkStepDisplayChange = {},
            onWorkStepCollapseTimingChange = {},
            onWorkStepFoldedRepliesChange = {},
            onRequestNotificationPermission = {},
            onOpenNotificationSettings = {},
            onOpenNotificationManagement = {},
            onOpenBackgroundPopupSettings = {},
            backgroundPopupSupported = false,
        )
    }
}
