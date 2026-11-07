package com.catmoecircle.areyouok.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewaySettings
import com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
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
 * - 通知页：通知权限（未授权时点击授权）、通知总开关、审批 / 提问提醒；
 * - 关于页：应用名、设备名、版本号。
 *
 * 扫码 / 深链带入的邀请由调用方通过 [initialUrl]、[initialCode]、[inviteLabel] 预填；
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
    busy: Boolean,
    error: String?,
    notificationsEnabled: Boolean,
    notifyApprovals: Boolean,
    notifyQuestions: Boolean,
    notificationPermissionGranted: Boolean,
    workStepDisplay: WorkStepDisplay,
    workStepCollapseTiming: WorkStepCollapseTiming,
    workStepFoldedReplies: WorkStepFoldedReplies,
    deviceName: String,
    onBack: () -> Unit,
    onConnect: (url: String, code: String) -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectDevice: (GatewaySettings.RecentGateway) -> Unit,
    onSelectDiscoveredGateway: (DiscoveredGateway) -> Unit,
    onClearError: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onApprovalsChange: (Boolean) -> Unit,
    onQuestionsChange: (Boolean) -> Unit,
    onWorkStepDisplayChange: (WorkStepDisplay) -> Unit,
    onWorkStepCollapseTimingChange: (WorkStepCollapseTiming) -> Unit,
    onWorkStepFoldedRepliesChange: (WorkStepFoldedReplies) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val versionName = remember<String>(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            // 页面是盖在聊天界面之上的浮层：吞掉落在留白处的触摸，避免点穿到下面的聊天控件。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        SettingsTopBar(
            title = stringResource(page.titleRes),
            busy = busy,
            onBack = {
                if (busy) return@SettingsTopBar
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
                )

                SettingsPage.GATEWAY -> SettingsGatewayPage(
                    connection = connection,
                    discoveredGateways = discoveredGateways,
                    initialUrl = initialUrl,
                    initialCode = initialCode,
                    inviteLabel = inviteLabel,
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
                    permissionGranted = notificationPermissionGranted,
                    onRequestPermission = onRequestNotificationPermission,
                    onOpenSystemSettings = onOpenNotificationSettings,
                    onNotificationsChange = onNotificationsChange,
                    onApprovalsChange = onApprovalsChange,
                    onQuestionsChange = onQuestionsChange,
                )

                SettingsPage.ABOUT -> SettingsAboutPage(
                    deviceName = deviceName,
                    versionName = versionName,
                )
            }
        }
    }
}

/** 顶部返回栏：返回按钮 + 居中的页面标题（分类入口显示「设置」，子页显示分类名）。 */
@Composable
private fun SettingsTopBar(title: String, busy: Boolean, onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
                    colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else {
                    colorScheme.onSurface
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Text(
            text = title,
            color = colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )

        // 与左侧返回按钮等宽，保证标题在屏幕上居中。
        Spacer(Modifier.size(48.dp))
    }
}

/** 设置页通用滚动容器：水平留白 + IME / 底部导航栏避让。 */
@Composable
internal fun SettingsPageScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 设置分组：可选小标题 + 圆角卡片容器。 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 2.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, colorScheme.outline.copy(alpha = 0.18f), shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** 分组内的分隔线；默认从图标列右侧开始，和 iOS 设置风格一致。 */
@Composable
internal fun SettingsDivider(startInset: androidx.compose.ui.unit.Dp = 52.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    )
}

/**
 * 可点击的导航行：左侧图标，中间标题/说明，右侧值 + 箭头。
 *
 * 分类入口（根页面）用 [value] 展示当前连接/版本等摘要；子页里的入口行只用标题。
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
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = value,
                color = valueColor ?: colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f),
                modifier = Modifier.size(14.dp),
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
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colorScheme.onSurface,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = valueColor ?: colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 带下拉选择器的设置行：左侧标题 / 说明，右侧当前值 + 箭头。 */
@Composable
private fun <T> WorkStepSelectorRow(
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

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = "$title，$selectedLabel" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = MiuixIcons.Regular.ChevronForward,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(90f),
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = optionLabel(option),
                                    fontSize = 14.sp,
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                            trailingIcon = {
                                if (option == selected) {
                                    Icon(
                                        imageVector = MiuixIcons.Regular.Ok,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
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

    SettingsPageScaffold {
        SettingsGroup(title = stringResource(R.string.settings_section_connection)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Link,
                title = stringResource(R.string.settings_root_connection),
                value = connectionSummary,
                onClick = { onOpenPage(SettingsPage.CONNECTION) },
            )
            SettingsDivider()
            SettingsNavRow(
                icon = MiuixIcons.Regular.SearchDevice,
                title = stringResource(R.string.settings_root_gateway),
                value = gatewaySummary,
                onClick = { onOpenPage(SettingsPage.GATEWAY) },
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_app)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Reply,
                title = stringResource(R.string.settings_root_notifications),
                value = notificationSummary,
                valueColor = notificationSummaryColor,
                onClick = { onOpenPage(SettingsPage.NOTIFICATIONS) },
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_chat)) {
            SettingsNavRow(
                icon = MiuixIcons.Regular.Playlist,
                title = stringResource(R.string.work_step_display_title),
                description = stringResource(R.string.work_step_display_desc),
                value = stringResource(workStepDisplay.labelRes),
                onClick = { onOpenPage(SettingsPage.WORK_STEPS) },
            )
            SettingsDivider()
            WorkStepSelectorRow(
                title = stringResource(R.string.work_step_collapse_title),
                description = stringResource(R.string.work_step_collapse_desc),
                options = WorkStepCollapseTiming.entries,
                selected = workStepCollapseTiming,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = onWorkStepCollapseTimingChange,
            )
            SettingsDivider()
            WorkStepSelectorRow(
                title = stringResource(R.string.work_step_folded_replies_title),
                description = stringResource(R.string.work_step_folded_replies_desc),
                options = WorkStepFoldedReplies.entries,
                selected = workStepFoldedReplies,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = onWorkStepFoldedRepliesChange,
            )
        }

        Spacer(Modifier.height(18.dp))

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
            notificationPermissionGranted = true,
            workStepDisplay = WorkStepDisplay.DEFAULT,
            workStepCollapseTiming = WorkStepCollapseTiming.DEFAULT,
            workStepFoldedReplies = WorkStepFoldedReplies.DEFAULT,
            deviceName = "Pixel 8",
            onBack = {},
            onConnect = { _, _ -> },
            onScan = {},
            onDisconnect = {},
            onConnectDevice = {},
            onSelectDiscoveredGateway = {},
            onClearError = {},
            onNotificationsChange = {},
            onApprovalsChange = {},
            onQuestionsChange = {},
            onWorkStepDisplayChange = {},
            onWorkStepCollapseTimingChange = {},
            onWorkStepFoldedRepliesChange = {},
            onRequestNotificationPermission = {},
            onOpenNotificationSettings = {},
        )
    }
}
