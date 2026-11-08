package com.catmoecircle.areyouok.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.catmoecircle.areyouok.gateway.GatewaySettings
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.ui.gateway.GatewayPillButton
import com.catmoecircle.areyouok.ui.gateway.connectionStatusText
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Unlock

/**
 * 连接子页：已连接/曾连接的设备列表 + 每行一个连接开关，底部「添加连接」直接唤起扫码。
 *
 * 同一时间只保留一条网关长连接，所以开关是「单选」语义：打开某个设备会连上它，
 * 关闭当前设备会断开；已配对的设备用本地保存的 token 直接重连。
 */
@Composable
internal fun SettingsConnectionPage(
    connection: ConnectionState,
    devices: List<GatewaySettings.RecentGateway>,
    busy: Boolean,
    onConnectDevice: (GatewaySettings.RecentGateway) -> Unit,
    onDisconnectDevice: () -> Unit,
    onAddConnection: () -> Unit,
) {
    SettingsPageScaffold {
        SettingsGroup(title = stringResource(R.string.settings_section_connection)) {
            if (devices.isEmpty()) {
                SettingsEmptyDeviceRow()
                SettingsDivider(startInset = 16.dp)
            } else {
                devices.forEach { device ->
                    SettingsDeviceRow(
                        device = device,
                        connection = connection,
                        busy = busy,
                        onConnect = { onConnectDevice(device) },
                        onDisconnect = onDisconnectDevice,
                    )
                    SettingsDivider()
                }
            }

            SettingsNavRow(
                icon = MiuixIcons.Regular.AddCircle,
                title = stringResource(R.string.settings_add_connection),
                description = stringResource(R.string.settings_add_connection_desc),
                onClick = onAddConnection,
                enabled = !busy,
            )
        }
    }
}

/** 空状态：还没有连过任何网关。 */
@Composable
private fun SettingsEmptyDeviceRow() {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.ScreenMirroring,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(R.string.settings_devices_empty),
                color = colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_devices_empty_desc),
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

/** 单个设备行：名称、状态、连接开关。 */
@Composable
private fun SettingsDeviceRow(
    device: GatewaySettings.RecentGateway,
    connection: ConnectionState,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val active = isActiveDevice(connection, device.baseUrl)
    val name = deviceDisplayName(device)
    val status = when {
        active && connection.phase == ConnectionPhase.CONNECTED ->
            if (connection.lan) {
                stringResource(R.string.settings_device_connected_lan)
            } else {
                stringResource(R.string.settings_device_connected)
            }

        active -> connectionStatusText(connection)
        else -> device.baseUrl
    }
    val statusColor = when {
        active && connection.phase == ConnectionPhase.CONNECTED -> colorScheme.primary
        active && connection.phase == ConnectionPhase.ERROR -> colorScheme.error
        else -> colorScheme.onSurfaceVariant
    }
    val toggleLabel = stringResource(R.string.settings_device_toggle, name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.ScreenMirroring,
            contentDescription = null,
            tint = if (active) colorScheme.primary else colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = status,
                color = statusColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = active,
            onCheckedChange = { enabled ->
                if (enabled) onConnect() else onDisconnect()
            },
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = toggleLabel },
        )
    }
}

/**
 * 网关子页：自动发现同一局域网里的 DSH 网关（点一下直连），
 * 下面保留地址 + 配对码的手动连接方式。
 */
@Composable
internal fun SettingsGatewayPage(
    connection: ConnectionState,
    discoveredGateways: List<com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway>,
    initialUrl: String,
    initialCode: String,
    inviteLabel: String?,
    busy: Boolean,
    error: String?,
    onSelectDiscoveredGateway: (com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway) -> Unit,
    onConnect: (url: String, code: String) -> Unit,
    onClearError: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val fieldShape = RoundedCornerShape(14.dp)

    // 邀请变更（扫码 / 深链）时用新值重置输入框。
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var code by rememberSaveable(initialCode) { mutableStateOf(initialCode) }

    SettingsPageScaffold {
        SettingsGroup(title = stringResource(R.string.settings_gateway_discovery_title)) {
            if (discoveredGateways.isEmpty()) {
                SettingsNavRow(
                    icon = MiuixIcons.Regular.SearchDevice,
                    title = stringResource(
                        if (connection.isOnline) {
                            R.string.settings_gateway_discovery_none
                        } else {
                            R.string.settings_gateway_discovery_searching
                        },
                    ),
                    description = stringResource(R.string.settings_gateway_discovery_hint),
                    showChevron = false,
                )
            } else {
                discoveredGateways.forEachIndexed { index, gateway ->
                    SettingsNavRow(
                        icon = MiuixIcons.Regular.ScreenMirroring,
                        title = gateway.name,
                        description = gateway.baseUrl
                            ?: stringResource(R.string.settings_gateway_discovery_lan),
                        onClick = { onSelectDiscoveredGateway(gateway) },
                        enabled = !busy,
                    )
                    if (index != discoveredGateways.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        SettingsGroup(title = stringResource(R.string.settings_gateway_manual_title)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { value ->
                        url = value
                        if (error != null) onClearError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.gateway_pair_url_label), fontSize = 12.sp)
                    },
                    placeholder = {
                        Text(stringResource(R.string.gateway_pair_url_hint), fontSize = 13.sp)
                    },
                    shape = fieldShape,
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        code = value
                        if (error != null) onClearError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.gateway_pair_code_hint), fontSize = 12.sp)
                    },
                    shape = fieldShape,
                )

                inviteLabel?.takeIf { it.isNotBlank() }?.let { label ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.gateway_pair_invite, label),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = error, color = colorScheme.error, fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_pair_direct_hint),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    GatewayPillButton(
                        label = stringResource(R.string.gateway_pair_submit),
                        onClick = { if (!busy) onConnect(url.trim(), code.trim()) },
                        emphasized = true,
                        enabled = !busy,
                    )
                }

                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_busy_connecting),
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // 当前连接状态：只做只读提示，断开/重连入口在连接页。
        connection.endpoint?.baseUrl?.let { address ->
            Spacer(Modifier.height(18.dp))
            SettingsGroup(title = stringResource(R.string.settings_gateway_current)) {
                SettingsInfoRow(
                    label = connection.displayName,
                    value = connectionStatusText(connection),
                    valueColor = if (connection.phase == ConnectionPhase.ERROR) {
                        colorScheme.error
                    } else {
                        null
                    },
                )
                SettingsDivider(startInset = 16.dp)
                SettingsInfoRow(
                    label = stringResource(R.string.gateway_pair_url_label),
                    value = address,
                )
            }
        }
    }
}

/**
 * 通知子页：权限入口 + 通知总开关 + 审批/提问提醒。
 *
 * 未授权时点「通知权限」会重新走系统授权（ChatScreen 里实现）；被永久拒绝时
 * ChatScreen 会兜底跳到系统通知设置，避免第一次取消授权后找不到入口。
 */
@Composable
internal fun SettingsNotificationPage(
    notificationsEnabled: Boolean,
    notifyApprovals: Boolean,
    notifyQuestions: Boolean,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onApprovalsChange: (Boolean) -> Unit,
    onQuestionsChange: (Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val childEnabled = notificationsEnabled && permissionGranted

    SettingsPageScaffold {
        SettingsGroup {
            SettingsNavRow(
                icon = if (permissionGranted) MiuixIcons.Regular.Unlock else MiuixIcons.Regular.Lock,
                title = stringResource(R.string.settings_notifications_permission_title),
                value = if (permissionGranted) {
                    stringResource(R.string.settings_notifications_permission_granted)
                } else {
                    stringResource(R.string.settings_notifications_permission_not_granted)
                },
                valueColor = if (permissionGranted) null else colorScheme.error,
                onClick = onRequestPermission,
            )
            SettingsDivider()
            SettingsNavRow(
                icon = MiuixIcons.Regular.Settings,
                title = stringResource(R.string.settings_notifications_system_title),
                description = stringResource(R.string.settings_notifications_system_desc),
                onClick = onOpenSystemSettings,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_notifications_permission_hint),
            color = colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(18.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_notifications)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_title),
                description = stringResource(R.string.settings_notifications_desc),
                checked = notificationsEnabled,
                onCheckedChange = onNotificationsChange,
            )
            SettingsDivider(startInset = 16.dp)
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_approval_title),
                description = stringResource(R.string.settings_notifications_approval_desc),
                checked = notifyApprovals,
                onCheckedChange = onApprovalsChange,
                enabled = childEnabled,
            )
            SettingsDivider(startInset = 16.dp)
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_question_title),
                description = stringResource(R.string.settings_notifications_question_desc),
                checked = notifyQuestions,
                onCheckedChange = onQuestionsChange,
                enabled = childEnabled,
            )
        }
    }
}

/** 工作步骤显示子页：4 个档位单选，当前档位右侧打勾。 */
@Composable
internal fun SettingsWorkStepDisplayPage(
    selected: WorkStepDisplay,
    onSelect: (WorkStepDisplay) -> Unit,
) {
    SettingsPageScaffold {
        SettingsGroup(title = stringResource(R.string.settings_section_chat)) {
            WorkStepDisplay.entries.forEachIndexed { index, level ->
                WorkStepOptionRow(
                    level = level,
                    selected = level == selected,
                    onClick = { onSelect(level) },
                )
                if (index != WorkStepDisplay.entries.lastIndex) {
                    SettingsDivider(startInset = 16.dp)
                }
            }
        }
    }
}

/** 单个档位行：标题 + 说明，选中时右侧显示对勾。 */
@Composable
private fun WorkStepOptionRow(
    level: WorkStepDisplay,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(level.labelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(level.descriptionRes),
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Icon(
                imageVector = MiuixIcons.Regular.Ok,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

/** 关于子页：基础信息（应用、设备名、版本）。 */
@Composable
internal fun SettingsAboutPage(deviceName: String, versionName: String) {
    SettingsPageScaffold {
        SettingsGroup(title = stringResource(R.string.settings_about_title)) {
            SettingsInfoRow(
                label = stringResource(R.string.settings_app_label),
                value = stringResource(R.string.app_name),
            )
            SettingsDivider(startInset = 16.dp)
            SettingsInfoRow(
                label = stringResource(R.string.settings_device_name),
                value = deviceName,
            )
            SettingsDivider(startInset = 16.dp)
            SettingsInfoRow(
                label = stringResource(R.string.settings_version),
                value = versionName.ifBlank { "—" },
            )
        }
    }
}

/** 当前连接是否指向该设备（忽略地址末尾的斜杠等差异）。 */
private fun isActiveDevice(connection: ConnectionState, baseUrl: String): Boolean {
    val current = connection.endpoint?.baseUrl ?: return false
    if (connection.phase == ConnectionPhase.IDLE) return false
    val normalizedCurrent = GatewayEndpoint.normalizeBaseUrl(current) ?: current
    val normalizedTarget = GatewayEndpoint.normalizeBaseUrl(baseUrl) ?: baseUrl
    return normalizedCurrent == normalizedTarget
}

/** 设备展示名：优先用配对时保存的名字，否则退回地址里的主机名。 */
internal fun deviceDisplayName(device: GatewaySettings.RecentGateway): String {
    device.name?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching { java.net.URI(device.baseUrl).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: device.baseUrl
}

/** 地址展示：去掉 http(s):// 与末尾斜杠，root 列表里用 host:port 更省空间。 */
internal fun gatewayAddressLabel(baseUrl: String): String =
    baseUrl.removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
