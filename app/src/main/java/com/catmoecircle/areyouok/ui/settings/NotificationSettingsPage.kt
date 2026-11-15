package com.catmoecircle.areyouok.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Unlock

/**
 * 通知子页：气泡提醒（置顶）+ 通知权限 / 系统类别入口 + 通知总开关 + 审批 / 提问 / 完成开关。
 *
 * 和旧页面的区别：
 * - 「通知权限」行直接打开**本 App** 的通知设置页（[openAppNotificationSettings]），
 *   不再跳系统级「通知管理」应用列表；
 * - 系统类别拆成三个独立入口——审批与提问（[GatewayChannels.INTERVENTIONS]）、
 *   任务完成（[GatewayChannels.TASKS]）、对话气泡（[GatewayChannels.CONVERSATION]），
 *   因为系统里它们本来就是分开的渠道，混在一个入口里用户改不到想改的那一类；
 * - 未授权时额外给一行「重新授权通知」，走系统授权框（永久拒绝后仍可用上面的入口去系统里开）。
 *
 * 气泡提醒打开时向下展开「激活气泡」，关闭时收起，避免无效入口占位。
 * 后台弹出界面是 MIUI 专属，[backgroundPopupSupported] 为 false 时不渲染。
 */
@Composable
internal fun SettingsNotificationPage(
    notificationsEnabled: Boolean,
    notifyApprovals: Boolean,
    notifyQuestions: Boolean,
    notifyCompletion: Boolean,
    permissionGranted: Boolean,
    bubbleEnabled: Boolean,
    bubblesAllowed: Boolean,
    backgroundPopupSupported: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    onApprovalsChange: (Boolean) -> Unit,
    onQuestionsChange: (Boolean) -> Unit,
    onCompletionChange: (Boolean) -> Unit,
    onBubbleChange: (Boolean) -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenApprovalChannelSettings: () -> Unit,
    onOpenTaskChannelSettings: () -> Unit,
    onOpenConversationChannelSettings: () -> Unit,
    onOpenBackgroundPopupSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestBubbleSettings: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val childEnabled = notificationsEnabled && permissionGranted

    SettingsPageScaffold(title = stringResource(R.string.settings_page_notifications)) {
        // 气泡提醒置顶：打开后向下展开「激活气泡」，关闭时整体收起。
        SettingsGroup(title = stringResource(R.string.settings_bubbles_section)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_bubbles_enabled_title),
                description = stringResource(R.string.settings_bubbles_enabled_desc),
                checked = bubbleEnabled,
                onCheckedChange = onBubbleChange,
            )
            AnimatedVisibility(
                visible = bubbleEnabled,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    SettingsNavRow(
                        icon = if (bubbleEnabled && bubblesAllowed) {
                            MiuixIcons.Regular.Unlock
                        } else {
                            MiuixIcons.Regular.Lock
                        },
                        title = stringResource(R.string.settings_bubbles_activate_title),
                        description = stringResource(R.string.settings_bubbles_activate_desc),
                        value = if (bubbleEnabled && bubblesAllowed) {
                            stringResource(R.string.settings_bubbles_activated)
                        } else {
                            stringResource(R.string.settings_bubbles_not_activated)
                        },
                        valueColor = if (bubbleEnabled && bubblesAllowed) null else colorScheme.error,
                        onClick = onRequestBubbleSettings,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_notifications_app_settings_title)) {
            // 权限行：进本 App 的通知设置页，而不是系统级「通知管理」应用列表。
            SettingsNavRow(
                icon = if (permissionGranted) MiuixIcons.Regular.Unlock else MiuixIcons.Regular.Lock,
                title = stringResource(R.string.settings_notifications_permission_title),
                description = stringResource(R.string.settings_notifications_app_settings_desc),
                value = if (permissionGranted) {
                    stringResource(R.string.settings_notifications_permission_granted)
                } else {
                    stringResource(R.string.settings_notifications_permission_not_granted)
                },
                valueColor = if (permissionGranted) null else colorScheme.error,
                onClick = onOpenAppNotificationSettings,
            )
            // 未授权时补一个「重新授权」入口：系统授权框被永久拒绝后，上面的入口仍能进系统页。
            if (!permissionGranted) {
                SettingsNavRow(
                    icon = MiuixIcons.Regular.Lock,
                    title = stringResource(R.string.settings_notifications_permission_request_title),
                    description = stringResource(R.string.settings_notifications_permission_request_desc),
                    onClick = onRequestPermission,
                )
            }
            // 三个系统通知类别分别直达，和系统「通知类别」里的一一对应。
            SettingsNavRow(
                icon = MiuixIcons.Regular.Settings,
                title = stringResource(R.string.settings_notifications_approval_channel_title),
                description = stringResource(R.string.settings_notifications_approval_channel_desc),
                onClick = onOpenApprovalChannelSettings,
            )
            SettingsNavRow(
                icon = MiuixIcons.Regular.Settings,
                title = stringResource(R.string.settings_notifications_task_channel_title),
                description = stringResource(R.string.settings_notifications_task_channel_desc),
                onClick = onOpenTaskChannelSettings,
            )
            SettingsNavRow(
                icon = MiuixIcons.Regular.Settings,
                title = stringResource(R.string.settings_notifications_conversation_channel_title),
                description = stringResource(R.string.settings_notifications_conversation_channel_desc),
                onClick = onOpenConversationChannelSettings,
            )
            // MIUI 专属：气泡被系统在「后台弹出界面」这一档拦掉，单独给个入口去开。
            if (backgroundPopupSupported) {
                SettingsNavRow(
                    icon = MiuixIcons.Regular.Settings,
                    title = stringResource(R.string.settings_background_popup_title),
                    description = stringResource(R.string.settings_background_popup_desc),
                    onClick = onOpenBackgroundPopupSettings,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_notifications_permission_hint),
            color = colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_section_notifications)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_title),
                description = stringResource(R.string.settings_notifications_desc),
                checked = notificationsEnabled,
                onCheckedChange = onNotificationsChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_approval_title),
                description = stringResource(R.string.settings_notifications_approval_desc),
                checked = notifyApprovals,
                onCheckedChange = onApprovalsChange,
                enabled = childEnabled,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_question_title),
                description = stringResource(R.string.settings_notifications_question_desc),
                checked = notifyQuestions,
                onCheckedChange = onQuestionsChange,
                enabled = childEnabled,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_notifications_completion_title),
                description = stringResource(R.string.settings_notifications_completion_desc),
                checked = notifyCompletion,
                onCheckedChange = onCompletionChange,
                enabled = childEnabled,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_notifications_channels_hint),
            color = colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
