package com.catmoecircle.areyouok.gateway

import android.content.Context
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint

/** 轻量本地配置：最后连接的网关、最近网关列表、通知开关、聊天显示档位。 */
class GatewaySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastBaseUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_URL, value).apply()

    var lastName: String?
        get() = prefs.getString(KEY_LAST_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_NAME, value).apply()

    /**
     * 「工作步骤显示」档位：存 `WorkStepDisplay` 的枚举名（例如 `STANDARD`）。
     *
     * 这里保持纯字符串，避免 gateway 层反向依赖 ui；读取方用
     * `WorkStepDisplay.fromStorage(...)` 解析，未知/缺失值回退到 STANDARD。
     */
    var workStepDisplay: String?
        get() = prefs.getString(KEY_WORK_STEP_DISPLAY, null)
        set(value) = prefs.edit().putString(KEY_WORK_STEP_DISPLAY, value).apply()

    /** 「工作步骤收起时机」：存 `WorkStepCollapseTiming` 的枚举名，未知值回退到 COMPLETION。 */
    var workStepCollapseTiming: String?
        get() = prefs.getString(KEY_WORK_STEP_COLLAPSE, null)
        set(value) = prefs.edit().putString(KEY_WORK_STEP_COLLAPSE, value).apply()

    /** 「收起后显示的回复」：存 `WorkStepFoldedReplies` 的枚举名，未知值回退到 ALL。 */
    var workStepFoldedReplies: String?
        get() = prefs.getString(KEY_WORK_STEP_FOLDED_REPLIES, null)
        set(value) = prefs.edit().putString(KEY_WORK_STEP_FOLDED_REPLIES, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    /** 是否推送「工具审批」通知（[notificationsEnabled] 为总开关）。 */
    var notifyApprovals: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_APPROVALS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_APPROVALS, value).apply()

    /** 是否推送「DSH 提问」通知（[notificationsEnabled] 为总开关）。 */
    var notifyQuestions: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_QUESTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_QUESTIONS, value).apply()

    /** 是否推送「任务完成」通知（[notificationsEnabled] 为总开关）。 */
    var notifyCompletion: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_COMPLETION, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_COMPLETION, value).apply()

    fun remember(endpoint: GatewayEndpoint) {
        lastBaseUrl = endpoint.baseUrl
        lastName = endpoint.name
        val entry = RecentGateway(endpoint.baseUrl, endpoint.name)
        val merged = (listOf(entry) + recent().filterNot { it.baseUrl == entry.baseUrl })
            .take(MAX_RECENT)
        prefs.edit()
            .putString(KEY_RECENT, merged.joinToString("\n") { it.encode() })
            .apply()
    }

    fun recent(): List<RecentGateway> {
        val raw = prefs.getString(KEY_RECENT, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.split('\n').mapNotNull { RecentGateway.decode(it) }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    data class RecentGateway(val baseUrl: String, val name: String?) {
        fun encode(): String = baseUrl + "\t" + (name ?: "")

        fun toEndpoint(): GatewayEndpoint = GatewayEndpoint(
            baseUrl = baseUrl,
            serverId = baseUrl,
            name = name?.takeIf { it.isNotBlank() },
        )

        companion object {
            fun decode(line: String): RecentGateway? {
                val parts = line.split('\t')
                val baseUrl = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                return RecentGateway(baseUrl, parts.getOrNull(1)?.takeIf { it.isNotBlank() })
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "gateway_settings"
        private const val KEY_LAST_URL = "last_base_url"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_WORK_STEP_DISPLAY = "work_step_display"
        private const val KEY_WORK_STEP_COLLAPSE = "work_step_collapse_timing"
        private const val KEY_WORK_STEP_FOLDED_REPLIES = "work_step_folded_replies"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_NOTIFY_APPROVALS = "notify_approvals"
        private const val KEY_NOTIFY_QUESTIONS = "notify_questions"
        private const val KEY_NOTIFY_COMPLETION = "notify_completion"
        private const val KEY_RECENT = "recent_gateways"
        private const val MAX_RECENT = 8
    }
}
