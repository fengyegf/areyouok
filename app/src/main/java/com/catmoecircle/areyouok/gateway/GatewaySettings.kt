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

    /**
     * 通知气泡：把审批 / 提问 / 完成提醒以系统通知气泡（BubbleMetadata）显示。
     *
     * 默认关闭，必须由用户点一次「激活气泡」才启用：气泡需要系统侧的授权
     * （首次显示气泡时系统会弹权限框），静默打开只会让用户不知道发生了什么。
     * 气泡完全由系统通知承载：不需要悬浮窗权限，用户随时可以在系统设置里关掉。
     */
    var bubbleNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE_NOTIFICATIONS, value).apply()

    /**
     * 思考链翻译总开关。默认关闭：翻译会把思考原文发给第三方服务，
     * 必须由用户主动打开（设置页里有隐私提示）。
     */
    var translationEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSLATION_ENABLED, value).apply()

    /** 翻译服务 id：`bing` / `google`（对应网关 /v1/translate 的 provider）。 */
    var translationProvider: String
        get() = prefs.getString(KEY_TRANSLATION_PROVIDER, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TRANSLATION_PROVIDER
        set(value) = prefs.edit().putString(KEY_TRANSLATION_PROVIDER, value).apply()

    /**
     * 目标语言（BCP-47）。空串表示「跟随应用」——界面是中文就翻成
     * [DEFAULT_TRANSLATION_TARGET]，由 UI 在发起翻译时解析。
     *
     * 这里存原始值，不在 gateway 层判断语言，避免网关层反向依赖 ui。
     */
    var translationTarget: String
        get() = prefs.getString(KEY_TRANSLATION_TARGET, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_TRANSLATION_TARGET, value).apply()

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

    /**
     * 自签证书固定表：键是主机名，值是 `指纹|DER base64`。
     *
     * 只存本 App 自己的固定信息，**不写入系统证书库**；公网网关重启后
     * `GatewayRuntime.connect` 会按主机名恢复，TLS 握手才能继续通过。
     */
    fun pinnedCertificate(host: String): String? =
        prefs.getString(pinKey(host), null)?.takeIf { it.isNotBlank() }

    fun savePinnedCertificate(host: String, encoded: String) {
        if (encoded.isBlank()) return
        prefs.edit().putString(pinKey(host), encoded).apply()
    }

    fun clearPinnedCertificate(host: String) {
        prefs.edit().remove(pinKey(host)).apply()
    }

    private fun pinKey(host: String): String = "pin_" + host.lowercase()

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
        /** 默认翻译服务：与网关 /v1/translate/providers 的 defaultProvider 一致。 */
        const val DEFAULT_TRANSLATION_PROVIDER = "bing"

        /** 「跟随应用」实际使用的目标语言（简体中文）。 */
        const val DEFAULT_TRANSLATION_TARGET = "zh-CN"

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
        private const val KEY_BUBBLE_NOTIFICATIONS = "bubble_notifications_enabled"
        private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
        private const val KEY_TRANSLATION_PROVIDER = "translation_provider"
        private const val KEY_TRANSLATION_TARGET = "translation_target"
        private const val KEY_RECENT = "recent_gateways"
        private const val MAX_RECENT = 8
    }
}
