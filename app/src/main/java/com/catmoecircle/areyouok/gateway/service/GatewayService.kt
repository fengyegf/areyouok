package com.catmoecircle.areyouok.gateway.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.catmoecircle.areyouok.MainActivity
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewayRuntime
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.InterventionAnswer
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 前台服务（`dataSync`）：维持 `/v1/stream` 长连接，并把审批/提问变成可操作通知。
 *
 * - 常驻通知「DSH 网关 · <name>」：连接状态、断开/重连入口；
 * - 审批通知两个 action「允许一次 / 拒绝」，`PendingIntent` 直接回到服务里
 *   `POST /v1/interventions/{eventId}/answer`，不必打开界面；
 * - Android 13+ 未授予 POST_NOTIFICATIONS 时降级：前台服务照常运行，但不再发审批通知。
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var runtime: GatewayRuntime
    private var foregroundStarted = false
    private val notificationManager by lazy {
        NotificationManagerCompat.from(this)
    }

    override fun onCreate() {
        super.onCreate()
        runtime = GatewayRuntime.get(this)
        GatewayChannels.ensure(this)
        startForegroundSafely(runtime.repository.connection.value)
        scope.launch {
            runtime.repository.connection.collect { state ->
                updateConnectionNotification(state)
            }
        }
        scope.launch {
            runtime.repository.pendingInterventions.collectLatest { interventions ->
                syncInterventionNotifications(interventions)
            }
        }
        if (!runtime.repository.connection.value.isOnline) {
            runtime.connectLast()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> handleAnswer(intent)
            ACTION_RECONNECT -> runtime.reconnect()
            ACTION_DISCONNECT -> {
                runtime.disconnect()
                stopSelf()
            }
            ACTION_CONNECT -> runtime.reconnect()
            ACTION_REFRESH -> refreshInterventionNotifications()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ 通知

    private fun startForegroundSafely(state: ConnectionState) {
        val notification = buildConnectionNotification(state)
        try {
            ServiceCompat.startForeground(
                this,
                CONNECTION_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            foregroundStarted = true
        } catch (error: Throwable) {
            // 后台启动限制 / 权限缺失（Android 12+ 可能抛 ForegroundServiceStartNotAllowedException）。
            android.util.Log.w(TAG, "startForeground 失败", error)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun updateConnectionNotification(state: ConnectionState) {
        if (!foregroundStarted) return
        runCatching { notificationManager.notify(CONNECTION_NOTIFICATION_ID, buildConnectionNotification(state)) }
    }

    private fun buildConnectionNotification(state: ConnectionState): Notification {
        val online = state.phase == ConnectionPhase.CONNECTED
        val title = when (state.phase) {
            ConnectionPhase.CONNECTED -> "DSH 网关 · ${state.displayName}"
            ConnectionPhase.CONNECTING -> "正在连接 ${state.displayName}…"
            ConnectionPhase.RECONNECTING -> "与 ${state.displayName} 重连中（第 ${state.attempt} 次）"
            ConnectionPhase.ERROR -> "网关连接失败"
            ConnectionPhase.IDLE -> "DSH 网关未连接"
        }
        val text = when {
            state.phase == ConnectionPhase.ERROR -> state.error ?: "请检查桌面端是否运行"
            state.error != null && !online -> state.error
            state.endpoint != null -> state.endpoint.baseUrl
            else -> "在侧边栏选择网关或扫码配对"
        }
        return NotificationCompat.Builder(this, GatewayChannels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                "断开",
                servicePendingIntent(ACTION_DISCONNECT, requestCode = 11),
            )
            .addAction(
                0,
                "重连",
                servicePendingIntent(ACTION_RECONNECT, requestCode = 12),
            )
            .build()
    }

    /**
     * 通知开关在设置页变更后：按当前开关重算可见列表并重发/撤销通知。
     *
     * 服务不在前台（例如已被系统回收但 startService 又把服务拉起）时直接跳过，
     * 避免在拿不到通知权限/渠道时做无意义的同步。
     */
    private fun refreshInterventionNotifications() {
        if (!foregroundStarted) return
        runCatching {
            syncInterventionNotifications(runtime.repository.pendingInterventions.value)
        }
    }

    private fun syncInterventionNotifications(interventions: List<Intervention>) {
        val canPost = canPostNotifications()
        // 总开关关闭 / 没有通知权限时可见列表为空：不仅不新发通知，还要撤销之前已发的
        // 事件通知，避免用户关掉通知后旧审批卡片还挂在通知栏里。
        val visible = if (canPost) {
            NotificationPolicy.visibleInterventions(
                interventions = interventions,
                approvalsEnabled = runtime.settings.notifyApprovals,
                questionsEnabled = runtime.settings.notifyQuestions,
            )
        } else {
            emptyList()
        }
        val alive = visible.mapNotNull { it.eventId.takeIf { id -> id.isNotBlank() } }.toSet()
        postedInterventionIds.keys
            .filterNot { it in alive }
            .forEach { eventId ->
                postedInterventionIds.remove(eventId)?.let { notificationId ->
                    runCatching { notificationManager.cancel(notificationId) }
                }
            }
        if (!canPost) return
        visible.forEach { intervention ->
            if (intervention.eventId.isBlank()) return@forEach
            val notificationId = notificationIdFor(intervention.eventId)
            postedInterventionIds[intervention.eventId] = notificationId
            runCatching {
                notificationManager.notify(notificationId, buildInterventionNotification(intervention))
            }
        }
    }

    private fun buildInterventionNotification(intervention: Intervention): Notification {
        val isApproval = intervention.isApproval
        val title = if (isApproval) {
            "需要审批：${intervention.toolName ?: "工具调用"}"
        } else {
            "DSH 提问"
        }
        val body = if (isApproval) {
            intervention.reason?.takeIf { it.isNotBlank() } ?: "点击「允许一次」放行，或拒绝"
        } else {
            intervention.questions.firstOrNull()?.prompt
                ?: intervention.reason
                ?: "打开 App 回答问题"
        }
        val builder = NotificationCompat.Builder(this, GatewayChannels.INTERVENTIONS)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (isApproval) {
            builder.addAction(
                0,
                ALLOW_ONCE_LABEL,
                answerPendingIntent(intervention.eventId, InterventionAnswer.ALLOWED_ONCE),
            )
            builder.addAction(
                0,
                REJECT_LABEL,
                answerPendingIntent(intervention.eventId, InterventionAnswer.REJECTED),
            )
        } else {
            builder.addAction(
                0,
                "打开回答",
                openAppPendingIntent(),
            )
        }
        return builder.build()
    }

    private fun handleAnswer(intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val value = intent.getStringExtra(EXTRA_ANSWER_VALUE) ?: InterventionAnswer.REJECTED
        postedInterventionIds.remove(eventId)?.let { runCatching { notificationManager.cancel(it) } }
        scope.launch {
            runtime.repository.answer(eventId, JsonPrimitive(value))
        }
    }

    private fun canPostNotifications(): Boolean {
        if (!runtime.settings.notificationsEnabled) return false
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return granted
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, GatewayService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun answerPendingIntent(eventId: String, value: String): PendingIntent {
        val intent = Intent(this, GatewayService::class.java)
            .setAction(ACTION_ANSWER)
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_ANSWER_VALUE, value)
        return PendingIntent.getService(
            this,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(eventId: String): Int =
        INTERVENTION_NOTIFICATION_BASE + (eventId.hashCode() and 0x0FFF)

    companion object {
        private const val TAG = "GatewayService"
        const val ACTION_CONNECT = "com.catmoecircle.areyouok.gateway.action.CONNECT"
        const val ACTION_RECONNECT = "com.catmoecircle.areyouok.gateway.action.RECONNECT"
        const val ACTION_DISCONNECT = "com.catmoecircle.areyouok.gateway.action.DISCONNECT"
        const val ACTION_ANSWER = "com.catmoecircle.areyouok.gateway.action.ANSWER"
        const val ACTION_REFRESH = "com.catmoecircle.areyouok.gateway.action.REFRESH_NOTIFICATIONS"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_ANSWER_VALUE = "answerValue"

        private const val CONNECTION_NOTIFICATION_ID = 1001
        private const val INTERVENTION_NOTIFICATION_BASE = 2000
        private const val REQUEST_OPEN_APP = 21
        private const val ALLOW_ONCE_LABEL = "允许一次"
        private const val REJECT_LABEL = "拒绝"

        private val postedInterventionIds =
            java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GatewayService::class.java).setAction(ACTION_CONNECT),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GatewayService::class.java).setAction(ACTION_DISCONNECT),
                )
            }
        }

        /**
         * 通知开关变更后重同步：设置页切换「审批 / 提问」开关时调用，
         * 让已发出但已被关掉类型的通知立即撤销，新开启类型的通知立即补发。
         */
        fun refreshNotifications(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GatewayService::class.java).setAction(ACTION_REFRESH),
                )
            }
        }
    }
}

/** 通知渠道定义（connectedDevice 语义：常驻低打扰 + 审批高打扰）。 */
object GatewayChannels {
    const val CONNECTION = "gateway_connection"
    const val INTERVENTIONS = "gateway_interventions_v2"
    private const val LEGACY_INTERVENTIONS = "gateway_interventions"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val connection = NotificationChannel(
            CONNECTION,
            "网关连接",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持与桌面 DSH 网关的长连接"
            setShowBadge(false)
            enableVibration(false)
        }
        val interventions = NotificationChannel(
            INTERVENTIONS,
            "审批与提问",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "工具审批与 DSH 提问，可直接在通知上回答"
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
        }
        // 渠道的重要性和声音一旦被用户或系统降级，App 无法在原渠道上恢复。
        // 删除旧渠道，让新版本按高优先级和系统默认声音重新建立。
        manager.deleteNotificationChannel(LEGACY_INTERVENTIONS)
        manager.createNotificationChannels(listOf(connection, interventions))
    }
}
