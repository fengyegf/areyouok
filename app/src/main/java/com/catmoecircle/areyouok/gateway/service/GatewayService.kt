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
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.catmoecircle.areyouok.MainActivity
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewayRuntime
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.InterventionAnswer
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.gateway.repository.SessionCompletion
import com.catmoecircle.areyouok.ui.gateway.BubbleActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 前台服务（`dataSync`）：维持 `/v1/stream` 长连接，并把审批/提问变成可操作通知。
 *
 * - 常驻通知「DSH 网关 · <name>」：连接状态、断开/重连入口；
 * - 审批通知两个 action「允许一次 / 拒绝」，`PendingIntent` 直接回到服务里
 *   `POST /v1/interventions/{eventId}/answer`，不必打开界面；
 * - 提问 / 任务完成通知带「回复」输入框（`RemoteInput`），可以直接在通知栏里回答或继续对话；
 * - 上述通知都附带 `BubbleMetadata`：用户允许气泡时以系统通知气泡显示，**不需要悬浮窗权限**；
 *   没允许时就是普通通知，功能不变；
 * - 通知点击后都带上 `sessionId`，由 MainActivity 切到对应对话；
 * - 每轮对话结束（网关的 `turn.completed`）弹「任务完成」，用户正看着该会话时不打扰；
 * - Android 13+ 未授予 POST_NOTIFICATIONS 时降级：前台服务照常运行，但不再发通知。
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var runtime: GatewayRuntime
    private var foregroundStarted = false

    private val notificationManager by lazy {
        NotificationManagerCompat.from(this)
    }

    /** 上一次连接通知的可见内容签名：状态流按事件重发，但通知栏不该跟着反复刷新。 */
    private var lastConnectionSignature: String? = null

    /** 常驻对话气泡：当前会话 id → 通知 id；切换会话时先撤掉上一条。 */
    private var conversationBubble: Pair<String, Int>? = null

    /** 常驻对话气泡上次写入的内容签名，避免流式 delta 让它反复重发。 */
    private var conversationSignature: String? = null

    /** 切对话后补发的刷新任务（新切换会取消上一次，避免连点会话时堆积）。 */
    private var conversationPostJob: Job? = null

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
        // 会话跑完一轮 → 弹「任务完成」，点通知回到对应对话。
        scope.launch {
            runtime.repository.sessionCompletions.collect { completion ->
                notifySessionCompleted(completion)
            }
        }
        // 常驻「对话窗口气泡」：气泡授权开着时，当前对话默认就有一个气泡入口，
        // 不用等审批/提问才出现。临时提醒各占一个气泡，处理完就撤，这个不动。
        scope.launch {
            combine(
                runtime.repository.selectedSessionId,
                runtime.repository.sessions,
                runtime.repository.sessionRunning,
                runtime.repository.messages,
                runtime.repository.pendingInterventions,
            ) { selected, sessions, running, messages, pending ->
                ConversationBubbleState(selected, sessions, running, messages, pending)
            }.collect { state -> syncConversationBubble(state) }
        }
        // 用户回到 App：完成提醒已经当场看到，连着气泡一起撤掉（临时提醒不留在屏上）。
        scope.launch {
            runtime.appVisibleFlow.collect { visible ->
                if (visible) cancelTaskNotifications()
            }
        }
        if (!runtime.repository.connection.value.isOnline) {
            runtime.connectLast()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> handleAnswer(intent)
            ACTION_QUICK_REPLY -> handleQuickReply(intent)
            ACTION_QUICK_ANSWER -> handleQuickAnswer(intent)
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
        // 服务停了就不该再留一个「当前离线对话」的常驻气泡。
        cancelConversationBubble()
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
        // 连接状态会随每条流事件重新 emit（serverSeq 变化），但通知栏上的可见内容没变。
        // 只在真正变化时重发，避免通知栏被每秒几十次刷新（HyperOS/MIUI 还会反复响铃）。
        val signature = connectionSignature(state)
        if (signature == lastConnectionSignature) return
        lastConnectionSignature = signature
        runCatching { notificationManager.notify(CONNECTION_NOTIFICATION_ID, buildConnectionNotification(state)) }
    }

    private fun connectionSignature(state: ConnectionState): String = buildString {
        append(state.phase.name)
        append('|').append(state.displayName)
        append('|').append(state.attempt)
        append('|').append(state.error.orEmpty())
        append('|').append(state.endpoint?.baseUrl.orEmpty())
    }

    // ------------------------------------------------------------ 通知气泡 / 通知内直接回复

    /**
     * 通知气泡（`BubbleMetadata`）：点开后用 [BubbleActivity] 展开该会话。
     *
     * 气泡的展开视图来自 Activity，所以目标必须是 Manifest 里声明了
     * `allowEmbedded` + `resizeableActivity` 的 [BubbleActivity]（缺一个就只会显示成普通通知）。
     */
    private fun bubbleMetadata(
        sessionId: String?,
        notificationId: Int,
        withBubble: Boolean,
    ): NotificationCompat.BubbleMetadata? {
        if (!withBubble) return null
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(this, BubbleActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(NotificationConversations.contentUri(session))
            .putExtra(EXTRA_SESSION_ID, session)
        val pending = PendingIntent.getActivity(
            this,
            BUBBLE_REQUEST_BASE + notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.BubbleMetadata.Builder(
            pending,
            IconCompat.createWithResource(this, R.drawable.ic_bubble_gateway),
        )
            .setDesiredHeight((BUBBLE_DESIRED_HEIGHT_DP * resources.displayMetrics.density).toInt())
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()
    }

    /**
     * 把通知标记成「某会话的气泡通知」：发布会话快捷方式、`setShortcutId`、`addPerson`。
     *
     * 这是 Android 11+ 的硬要求：targetSdk 30+ 的通知必须引用共享快捷方式且带 Person，
     * 系统才承认它是对话并显示成气泡；否则（哪怕挂了 BubbleMetadata）只显示普通通知。
     * 气泡通知同时 `setAutoCancel(false)`——取消通知会连气泡一起移除。
     */
    private fun applyConversation(
        builder: NotificationCompat.Builder,
        sessionId: String?,
        title: String?,
        body: String,
        withBubble: Boolean,
    ) {
        if (!withBubble) return
        val conversation = NotificationConversations.publish(this, sessionId, title) ?: return
        builder.setShortcutId(conversation.shortcutId)
        // 与 socialite 一致：LocusId 让系统把通知和快捷方式关联起来。
        builder.setLocusId(LocusIdCompat(conversation.shortcutId))
        builder.addPerson(conversation.person)
        // Android 11+ 只有 MessagingStyle + Person 的通知才算「对话」，才会以气泡显示；
        // 普通 BigTextStyle 即使挂了 BubbleMetadata 也会被系统当成普通通知。
        builder.setStyle(
            NotificationCompat.MessagingStyle(conversation.person)
                .setConversationTitle(title)
                .addMessage(body, System.currentTimeMillis(), conversation.person),
        )
        builder.setAutoCancel(false)
    }

    /**
     * 通知里的「回复」输入框（`RemoteInput`）。
     *
     * 提问用 [eventId]（直接回答），任务完成用 [sessionId]（继续对话），两者只会传一个。
     * `PendingIntent` 必须是 `FLAG_MUTABLE`，否则系统无法把输入内容写回。
     */
    private fun replyAction(
        notificationId: Int,
        label: String,
        eventId: String? = null,
        sessionId: String? = null,
    ): NotificationCompat.Action {
        val intent = Intent(this, GatewayService::class.java).apply {
            if (eventId != null) {
                action = ACTION_QUICK_ANSWER
                putExtra(EXTRA_EVENT_ID, eventId)
            } else {
                action = ACTION_QUICK_REPLY
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        val pending = PendingIntent.getService(
            this,
            QUICK_REPLY_REQUEST_BASE + notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending)
            .addRemoteInput(RemoteInput.Builder(REPLY_RESULT_KEY).setLabel(label).build())
            .setAllowGeneratedReplies(false)
            .build()
    }

    /** 从通知栏输入框取回文字（`RemoteInput` 的结果挂在收到的 Intent 上）。 */
    private fun remoteReplyText(intent: Intent): String =
        RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REPLY_RESULT_KEY)
            ?.toString()
            .orEmpty()

    /** 通知栏里的「继续对话」：直接往对应会话发一条消息（默认回答完成后追加）。 */
    private fun handleQuickReply(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val text = remoteReplyText(intent).trim()
        if (text.isEmpty()) return
        scope.launch { runtime.repository.sendMessage(text, NotificationReplies.DEFAULT_MODE, sessionId) }
    }

    /** 通知栏里的「直接回答」：把输入文字作为该问题的自定义回复提交。 */
    private fun handleQuickAnswer(intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)?.takeIf { it.isNotBlank() } ?: return
        val intervention = runtime.repository.pendingInterventions.value
            .firstOrNull { it.eventId == eventId }
            ?: return
        val answers = NotificationReplies.quickAnswer(intervention, remoteReplyText(intent)) ?: return
        postedInterventionIds.remove(eventId)?.let { notificationId ->
            runCatching { notificationManager.cancel(notificationId) }
        }
        scope.launch { runtime.repository.answerQuestion(eventId, answers) }
    }

    /** 会话标题：通知的 `subText`，让用户知道这条提醒来自哪个对话。 */
    private fun sessionLabel(sessionId: String?): String? =
        sessionId?.takeIf { it.isNotBlank() }
            ?.let { runtime.repository.sessionTitle(it) }
            ?.takeIf { it.isNotBlank() }

    /**
     * 气泡此刻是否可用：设置开关 + 系统渠道「允许气泡」。
     *
     * HyperOS / MIUI 在应用没开系统气泡时，会把整条带 `BubbleMetadata` 的通知移除，
     * 所以渠道不允许时干脆不挂气泡元数据，先保证通知能送达。
     */
    /**
     * 是否按气泡发通知：只看用户开关。
     *
     * 对齐官方 socialite 示例：不预先判断「系统是否允许气泡」，直接按对话格式发出，
     * 由系统决定显示成气泡还是普通通知；ROM 吞掉时再由 [postNotification] 兜底。
     */
    private fun canUseBubbles(): Boolean = runtime.settings.bubbleNotificationsEnabled

    /**
     * 气泡通知实际可用的渠道：优先 [preferred]，该渠道不允许气泡时退回审批渠道。
     *
     * 系统气泡权限是**按渠道**给的（渠道设置里的「允许气泡」/ 应用级气泡开关都会收窄到
     * 具体渠道）。「任务完成」是独立渠道，用户或 ROM 只给「审批与提问」开了气泡时，
     * 完成提醒就会永远只出普通通知——看起来像「审批能冒气泡、完成后不冒」。
     * 这里退回审批渠道，保证「气泡提醒开着 = 审批 / 提问 / 完成都按气泡格式发」。
     */
    private fun bubbleChannel(preferred: String): String {
        val manager = getSystemService(NotificationManager::class.java) ?: return preferred
        val channel = manager.getNotificationChannel(preferred) ?: return preferred
        return if (channel.canBubble()) preferred else GatewayChannels.INTERVENTIONS
    }

    /**
     * 发通知，并在气泡被系统吞掉时退回普通通知。
     *
     * 实测 HyperOS：应用未开启「气泡」时，带 `BubbleMetadata` 的通知会被 MIUI 的气泡
     * 管理器直接移除——通知栏里一条都不剩，用户完全收不到审批/提问。这里先按气泡发一次，
     * [BUBBLE_FALLBACK_DELAY_MS] 后确认通知还在；被吞掉就重发不带气泡的普通通知。
     */
    private fun postNotification(
        notificationId: Int,
        withBubble: Boolean,
        build: (Boolean) -> Notification,
    ) {
        runCatching { notificationManager.notify(notificationId, build(withBubble)) }
        if (!withBubble) return
        scope.launch {
            delay(BUBBLE_FALLBACK_DELAY_MS)
            val active = runCatching {
                notificationManager.activeNotifications.toList()
            }.getOrDefault(emptyList())
            if (active.any { it.id == notificationId }) return@launch
            // 通知被 ROM 的气泡管理器吞掉了：退回不带气泡元数据的版本，保证送达。
            runCatching { notificationManager.notify(notificationId, build(false)) }
        }
    }

    // ------------------------------------------------------------ 常驻对话气泡

    /** 常驻对话气泡的数据快照：当前会话、会话列表、运行状态、消息预览。 */
    private data class ConversationBubbleState(
        val selectedSessionId: String?,
        val sessions: List<SessionSummary>,
        val running: Boolean,
        val messages: List<ChatMessage>,
        val pending: List<Intervention>,
    )

    /**
     * 同步「对话窗口气泡」：气泡授权开着时，当前选中的对话始终有一个气泡入口。
     *
     * 它和审批 / 提问 / 完成那类**临时气泡**分开：临时气泡随事件来去，这个只跟着
     * 「当前对话」走——切对话换标题、跑起来显示运行中、结束后显示最后一条消息。
     * 系统那边它同样是一条通知，通知栏里也能看到，点一下回到对话。
     */
    private fun syncConversationBubble(state: ConversationBubbleState) {
        if (!foregroundStarted) return
        // 有待处理操作时，气泡优先指向「需要你处理的那个对话」：点气泡就是打开那个对话去授权/回答，
        // 不再为每次审批单独冒一个临时气泡（气泡只保留「对话窗口」这一种）。
        val selected = state.selectedSessionId?.takeIf { it.isNotBlank() }
        val pendingAction = state.pending.firstOrNull { it.sessionId == selected }
            ?: state.pending.firstOrNull()
        val sessionId = pendingAction?.sessionId?.takeIf { it.isNotBlank() } ?: selected
        if (sessionId == null || !canUseBubbles() || !canPostNotifications() || !systemAllowsBubbles()) {
            cancelConversationBubble()
            return
        }
        val title = state.sessions.firstOrNull { it.id == sessionId }?.displayTitle
            ?.takeIf { it.isNotBlank() }
            ?: sessionLabel(sessionId)
            ?: getString(R.string.title_activity_bubble)
        val last = state.messages.lastOrNull {
            it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT
        }
        val preview = last?.text?.trim()?.replace('\n', ' ')?.take(80)
        val actionText = pendingAction?.let { intervention ->
            if (intervention.isApproval) {
                getString(
                    R.string.bubble_needs_approval,
                    intervention.toolName?.takeIf { it.isNotBlank() } ?: getString(R.string.bubble_action_unknown),
                )
            } else {
                getString(
                    R.string.bubble_needs_answer,
                    intervention.questions.firstOrNull()?.prompt
                        ?.takeIf { it.isNotBlank() }
                        ?: intervention.reason?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.bubble_action_unknown),
                )
            }
        }
        val text = when {
            actionText != null -> actionText.take(80)
            state.running -> getString(R.string.bubble_conversation_running)
            !preview.isNullOrBlank() -> preview
            else -> getString(R.string.bubble_conversation_idle)
        }
        // 流式 delta 会不停改 text，但消息 id 不变：只在「换会话 / 换标题 / 运行状态 / 新消息」时重发，
        // 避免气泡跟着每个 token 闪。
        val signature = "$sessionId|$title|${state.running}|${last?.id ?: ""}|${pendingAction?.eventId ?: ""}"
        if (signature == conversationSignature && conversationBubble?.first == sessionId) return
        conversationSignature = signature

        // 固定通知 id：切对话时原地更新这一条通知，不去「撤掉旧气泡 + 新建新气泡」。
        // MIUI 的气泡窗口在短时间内先移除再新增时会留下一块空白区域，要等用户点一下、
        // 收起一次气泡才重绘；原地更新就不会触发这套移除/新增流程。
        val previousSession = conversationBubble?.first
        val sessionChanged = previousSession != null && previousSession != sessionId
        val notificationId = conversationNotificationId()
        conversationBubble = sessionId to notificationId

        fun postBubble() {
            val builder = NotificationCompat.Builder(this, bubbleChannel(GatewayChannels.CONVERSATION))
                .setSmallIcon(R.drawable.ic_stat_gateway)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(getString(R.string.bubble_conversation_subtitle))
                .setContentIntent(openAppPendingIntent(sessionId, openAppRequestCode(notificationId)))
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            bubbleMetadata(sessionId, notificationId, true)?.let(builder::setBubbleMetadata)
            applyConversation(builder, sessionId, title, text, withBubble = true)
            runCatching { notificationManager.notify(notificationId, builder.build()) }
        }
        postBubble()

        // 切对话后再补一次内容相同的更新：HyperOS 上气泡窗口偶尔不跟着这次更新重绘，
        // 表现为气泡图标/面板那块是空白，点一下才恢复；第二次 notify 能把窗口刷出来。
        conversationPostJob?.cancel()
        if (sessionChanged) {
            conversationPostJob = scope.launch {
                delay(CONVERSATION_BUBBLE_REFRESH_DELAY_MS)
                postBubble()
            }
        }
    }

    /** 撤掉常驻对话气泡（关气泡开关 / 断通知权限 / 没有选中会话时）。 */
    private fun cancelConversationBubble() {
        conversationBubble?.let { (_, id) -> runCatching { notificationManager.cancel(id) } }
        conversationBubble = null
        conversationSignature = null
    }

    /** 用当前仓库状态重算一次常驻气泡（设置页改开关后由 refresh 触发）。 */
    private fun refreshConversationBubble() {
        val repository = runtime.repository
        syncConversationBubble(
            ConversationBubbleState(
                selectedSessionId = repository.selectedSessionId.value,
                sessions = repository.sessions.value,
                running = repository.sessionRunning.value,
                messages = repository.messages.value,
                pending = repository.pendingInterventions.value,
            ),
        )
    }

    /** 系统是否真的允许本应用显示气泡：渠道「允许气泡」或应用级气泡授权任一成立。 */
    @Suppress("DEPRECATION") // areBubblesAllowed() 在 API 36 标记弃用，但仍是唯一能读到应用级气泡开关的入口。
    private fun systemAllowsBubbles(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        val channelAllows = manager.getNotificationChannel(GatewayChannels.CONVERSATION)?.canBubble() == true
        return channelAllows || manager.areBubblesAllowed()
    }

    /** 撤掉所有「任务完成」临时提醒（用户回到 App 后它们已经没用了）。 */
    private fun cancelTaskNotifications() {
        postedTaskIds.values.forEach { notificationId ->
            runCatching { notificationManager.cancel(notificationId) }
        }
        postedTaskIds.clear()
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
            .setContentIntent(openAppPendingIntent(requestCode = openAppRequestCode(CONNECTION_NOTIFICATION_ID)))
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
            // 总开关或「任务完成提醒」关掉后，把还挂在通知栏的完成提醒一起撤掉。
            if (!canPostNotifications() || !runtime.settings.notifyCompletion) {
                cancelTaskNotifications()
            }
            // 气泡开关 / 当前会话 / 运行状态变化后，常驻对话气泡跟着重算。
            refreshConversationBubble()
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
        // 审批 / 提问改走与设置页「测试通知」完全相同的对话气泡通道（同一渠道、同一套
        // 快捷方式 + MessagingStyle + BubbleMetadata）。这样每条事件都会有一个可展开的
        // 对话气泡，点开直接进对应对话处理；不再依赖单气泡切换会话。
        val withBubble = canUseBubbles()
        visible.forEach { intervention ->
            if (intervention.eventId.isBlank()) return@forEach
            val notificationId = notificationIdFor(intervention.eventId)
            postedInterventionIds[intervention.eventId] = notificationId
            postNotification(notificationId, withBubble) { bubble ->
                buildInterventionNotification(intervention, notificationId, bubble)
            }
        }
    }

    private fun buildInterventionNotification(
        intervention: Intervention,
        notificationId: Int,
        withBubble: Boolean,
    ): Notification {
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
        // 点通知回到「发起审批/提问的那个会话」，而不是 App 上次停在哪。
        val openApp = openAppPendingIntent(
            sessionId = intervention.sessionId,
            requestCode = openAppRequestCode(notificationId),
        )
        val builder = NotificationCompat.Builder(this, GatewayChannels.INTERVENTIONS)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setSubText(sessionLabel(intervention.sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // 用户允许气泡时以系统通知气泡显示（不需要悬浮窗权限），否则就是普通通知。
        bubbleMetadata(intervention.sessionId, notificationId, withBubble)?.let(builder::setBubbleMetadata)
        applyConversation(
            builder = builder,
            sessionId = intervention.sessionId,
            title = sessionLabel(intervention.sessionId) ?: intervention.toolName,
            body = body,
            withBubble = withBubble,
        )
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
            // 单问题提问：先在通知栏里直接回答；多问题仍回 App 的问答卡片。
            if (NotificationReplies.canQuickAnswer(intervention)) {
                builder.addAction(
                    replyAction(
                        notificationId = notificationId,
                        label = "直接回答",
                        eventId = intervention.eventId,
                    ),
                )
            }
            builder.addAction(
                0,
                "打开回答",
                openApp,
            )
        }
        return builder.build()
    }

    /**
     * 一轮对话结束：弹「任务完成」通知，点一下回到该对话。
     *
     * 用户正看着这个会话（前台 + 选中）时不打扰；看别的会话或 App 在后台时照常提醒。
     */
    private fun notifySessionCompleted(completion: SessionCompletion) {
        val shouldNotify = NotificationPolicy.shouldNotifyCompletion(
            sessionId = completion.sessionId,
            selectedSessionId = runtime.repository.selectedSessionId.value,
            appVisible = runtime.appVisible,
            notificationsEnabled = runtime.settings.notificationsEnabled,
            completionsEnabled = runtime.settings.notifyCompletion,
            permissionGranted = hasNotificationPermission(),
        )
        if (!shouldNotify) return
        val notificationId = taskNotificationId(completion.sessionId)
        postedTaskIds[completion.sessionId] = notificationId
        // 完成提醒同样不再额外冒气泡：当前对话的气泡本身就会更新成「已跑完」的状态。
        postNotification(notificationId, false) { bubble ->
            buildTaskNotification(completion, notificationId, bubble)
        }
    }

    private fun buildTaskNotification(
        completion: SessionCompletion,
        notificationId: Int,
        withBubble: Boolean,
    ): Notification {
        val name = completion.title?.takeIf { it.isNotBlank() }
            ?: completion.sessionId.take(8)
        val text = "已跑完一轮，点击回到该对话查看结果"
        // 完成提醒默认走独立渠道；要冒气泡时优先选「允许气泡」的渠道（见 [bubbleChannel]）。
        val channelId = if (withBubble) bubbleChannel(GatewayChannels.TASKS) else GatewayChannels.TASKS
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("任务完成 · $name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                openAppPendingIntent(
                    sessionId = completion.sessionId,
                    requestCode = openAppRequestCode(notificationId),
                ),
            )
            // 与审批通知对齐：通知栏里能看出这条提醒来自哪个对话（气泡会话标题也用它兜底）。
            .setSubText(sessionLabel(completion.sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .also { builder ->
                // 系统通知气泡 + 通知栏里的「继续对话」输入框。
                bubbleMetadata(completion.sessionId, notificationId, withBubble)?.let(builder::setBubbleMetadata)
                applyConversation(
                    builder = builder,
                    sessionId = completion.sessionId,
                    // 会话标题偶发拿不到（刚连上的会话还没进列表）：用 subText 同源的标题兜底，
                    // 避免气泡的会话标题为空。
                    title = completion.title?.takeIf { it.isNotBlank() }
                        ?: sessionLabel(completion.sessionId),
                    body = text,
                    withBubble = withBubble,
                )
                builder.addAction(
                    replyAction(
                        notificationId = notificationId,
                        label = "回复",
                        sessionId = completion.sessionId,
                    ),
                )
            }
            .build()
    }

    private fun handleAnswer(intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val value = intent.getStringExtra(EXTRA_ANSWER_VALUE) ?: InterventionAnswer.REJECTED
        postedInterventionIds.remove(eventId)?.let { runCatching { notificationManager.cancel(it) } }
        scope.launch {
            runtime.repository.answer(eventId, JsonPrimitive(value))
        }
    }

    private fun canPostNotifications(): Boolean =
        runtime.settings.notificationsEnabled && hasNotificationPermission()

    /** Android 13+ 的 POST_NOTIFICATIONS 权限；低版本默认有。 */
    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 打开 App 的 `PendingIntent`。
     *
     * `PendingIntent` 的相等性只看 action/data/component，不看 extras：每个通知必须
     * 用不同的 requestCode，否则不同会话的通知点开后会共用同一个 PendingIntent，
     * 全部跳到最后一个会话。带 `sessionId` 时 MainActivity 会切到对应对话。
     */
    private fun openAppPendingIntent(
        sessionId: String? = null,
        requestCode: Int = openAppRequestCode(CONNECTION_NOTIFICATION_ID),
    ): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            sessionId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppRequestCode(notificationId: Int): Int = OPEN_APP_REQUEST_BASE + notificationId

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
        INTERVENTION_NOTIFICATION_BASE + (eventId.hashCode() and 0x03FF)

    private fun taskNotificationId(sessionId: String): Int =
        TASK_NOTIFICATION_BASE + (sessionId.hashCode() and 0x03FF)

    /** 常驻对话气泡的通知 id：始终是同一条通知，切对话只改内容，不新增/移除气泡。 */
    private fun conversationNotificationId(): Int = CONVERSATION_NOTIFICATION_ID

    companion object {
        private const val TAG = "GatewayService"
        const val ACTION_CONNECT = "com.catmoecircle.areyouok.gateway.action.CONNECT"
        const val ACTION_RECONNECT = "com.catmoecircle.areyouok.gateway.action.RECONNECT"
        const val ACTION_DISCONNECT = "com.catmoecircle.areyouok.gateway.action.DISCONNECT"
        const val ACTION_ANSWER = "com.catmoecircle.areyouok.gateway.action.ANSWER"
        const val ACTION_REFRESH = "com.catmoecircle.areyouok.gateway.action.REFRESH_NOTIFICATIONS"
        const val ACTION_QUICK_REPLY = "com.catmoecircle.areyouok.gateway.action.QUICK_REPLY"
        const val ACTION_QUICK_ANSWER = "com.catmoecircle.areyouok.gateway.action.QUICK_ANSWER"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_ANSWER_VALUE = "answerValue"

        /** 通知点击要打开的会话 id：MainActivity 读完即消费。 */
        const val EXTRA_SESSION_ID = "sessionId"

        private const val CONNECTION_NOTIFICATION_ID = 1001

        /** RemoteInput 的结果键：通知栏里输入的文字由系统挂在收到的 Intent 上。 */
        private const val REPLY_RESULT_KEY = "gateway.reply"

        // 通知 id 区间互不重叠：审批/提问 2000–3023，任务完成 4000–5023，对话气泡固定 6425。
        private const val INTERVENTION_NOTIFICATION_BASE = 2000
        private const val TASK_NOTIFICATION_BASE = 4000

        /** 常驻对话气泡的固定通知 id（不随会话变化，切对话原地更新）。 */
        private const val CONVERSATION_NOTIFICATION_ID = 6425

        // PendingIntent 相等性不看 extras，用「通知 id + 固定偏移」保证每个通知独立。
        private const val OPEN_APP_REQUEST_BASE = 10_000
        private const val BUBBLE_REQUEST_BASE = 20_000
        private const val QUICK_REPLY_REQUEST_BASE = 30_000

        /** 带气泡发通知后等多久去确认它没被 ROM 吞掉（HyperOS 实测几十毫秒内移除）。 */
        private const val BUBBLE_FALLBACK_DELAY_MS = 2_000L

        /** 切对话后补一次气泡刷新，避免 HyperOS 的气泡窗口停在空白态。 */
        private const val CONVERSATION_BUBBLE_REFRESH_DELAY_MS = 700L

        /** 气泡展开时的目标高度（dp）：参照官方示例。 */
        private const val BUBBLE_DESIRED_HEIGHT_DP = 600
        private const val ALLOW_ONCE_LABEL = "允许一次"
        private const val REJECT_LABEL = "拒绝"

        private val postedInterventionIds =
            java.util.concurrent.ConcurrentHashMap<String, Int>()

        /** 已发的「任务完成」通知：关开关时用来撤销。 */
        private val postedTaskIds =
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
         * 通知开关变更后重同步：设置页切换「审批 / 提问 / 气泡」开关时调用，
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

    /** 审批与提问：v3 起允许系统通知气泡（旧渠道由 [LEGACY] 清理）。 */
    const val INTERVENTIONS = "gateway_interventions_v3"

    /** 任务完成：v2 起允许系统通知气泡，与审批渠道分开，可以单独静音。 */
    const val TASKS = "gateway_tasks_v2"

    /** 常驻「对话窗口」气泡：当前对话的入口，安静、不重复响。 */
    const val CONVERSATION = "gateway_conversation_v1"

    /** 旧版渠道：重要性和气泡开关在创建后无法修改，只能换新 id 再删旧渠道。 */
    private val LEGACY = listOf(
        "gateway_interventions",
        "gateway_interventions_v2",
        "gateway_tasks",
    )

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
            // 通知气泡由系统通知承载：渠道允许气泡后，用户开启系统气泡即可显示悬浮气泡，
            // 不需要（也不申请）悬浮窗权限；用户关掉时自动回到普通通知。
            setAllowBubbles(true)
        }
        // 常驻对话气泡用独立渠道：安静、可单独关掉，不影响审批与提问的高打扰提醒。
        val conversation = NotificationChannel(
            CONVERSATION,
            "对话气泡",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "当前对话的常驻气泡入口，点按回到对话"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            setAllowBubbles(true)
        }
        // 任务完成用独立渠道：可以单独调静音/关气泡，不影响审批与提问。
        val tasks = NotificationChannel(
            TASKS,
            "任务完成",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "对话每跑完一轮就提醒一次，点通知回到对应对话"
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(false)
            setAllowBubbles(true)
        }
        // 渠道的重要性和声音一旦被用户或系统降级，App 无法在原渠道上恢复。
        // 删除旧 id 的渠道，让新版本按新配置重新建立。
        LEGACY.forEach { id -> manager.deleteNotificationChannel(id) }
        manager.createNotificationChannels(listOf(connection, interventions, tasks, conversation))
    }
}
