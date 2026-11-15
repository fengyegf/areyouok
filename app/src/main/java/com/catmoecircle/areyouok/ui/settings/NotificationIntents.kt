package com.catmoecircle.areyouok.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.catmoecircle.areyouok.gateway.service.GatewayChannels

/**
 * 设置页「通知」子页用到的系统跳转。
 *
 * 所有入口都按「最精确 → 次精确 → 应用详情」的顺序回退，任何一档 ActivityNotFound 都不会
 * 让点击变成无响应：
 * - 本 App 的通知设置：[Settings.ACTION_APP_NOTIFICATION_SETTINGS] + [Settings.EXTRA_APP_PACKAGE]；
 * - 单个通知渠道：[Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS] + [Settings.EXTRA_CHANNEL_ID]；
 * - 兜底：[Settings.ACTION_APPLICATION_DETAILS_SETTINGS] + package Uri。
 *
 * 注意：**不要**再用 "android.settings.NOTIFICATION_SETTINGS"（系统级「通知管理」应用列表），
 * 那个页面跟本应用无关，用户还要再点两级才能找到本 App。
 */

/** 打开本 App 的通知设置页（应用级：总开关、各通知类别入口）。 */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    launch(context, intent) { openApplicationDetailsSettings(context) }
}

/** 打开「审批与提问」通知类别。 */
fun openApprovalChannelSettings(context: Context) {
    openChannelNotificationSettings(context, GatewayChannels.INTERVENTIONS)
}

/** 打开「任务完成」通知类别。 */
fun openTaskChannelSettings(context: Context) {
    openChannelNotificationSettings(context, GatewayChannels.TASKS)
}

/** 打开「对话气泡」通知类别。 */
fun openConversationChannelSettings(context: Context) {
    openChannelNotificationSettings(context, GatewayChannels.CONVERSATION)
}

/**
 * 打开某个通知渠道（通知类别）的设置页。
 *
 * 渠道 id 为空或系统没有该页面时退回 [openAppNotificationSettings]，再退回应用详情页。
 */
fun openChannelNotificationSettings(context: Context, channelId: String) {
    if (channelId.isBlank()) {
        openAppNotificationSettings(context)
        return
    }
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    launch(context, intent) { openAppNotificationSettings(context) }
}

/**
 * 打开 MIUI / HyperOS 的「后台弹出界面」权限页（应用权限编辑器，开关在「其他权限」里）。
 *
 * 没有这个权限时，系统会在拉起气泡 Activity 那一步直接拒绝（Permission Denied /
 * result 102），气泡展开就是一片白板；打开后点气泡才能进到对话界面。
 * 非 MIUI 上该页面不存在，退回应用详情页。
 */
fun openBackgroundPopupSettings(context: Context) {
    val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
        setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity",
        )
        putExtra("extra_pkgname", context.packageName)
        putExtra("extra_pkg_name", context.packageName)
    }
    if (!tryLaunch(context, miuiIntent)) openApplicationDetailsSettings(context)
}

/** 系统「应用详情」页：所有通知入口的最后一档兜底。 */
private fun openApplicationDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    tryLaunch(context, intent)
}

/** 尝试打开 [intent]，失败时执行 [fallback]。 */
private fun launch(context: Context, intent: Intent, fallback: (() -> Unit)? = null) {
    if (!tryLaunch(context, intent)) fallback?.invoke()
}

/** 尝试打开 [intent]；Activity 不存在 / 被系统拦住时返回 false（不抛异常）。 */
private fun tryLaunch(context: Context, intent: Intent): Boolean =
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
