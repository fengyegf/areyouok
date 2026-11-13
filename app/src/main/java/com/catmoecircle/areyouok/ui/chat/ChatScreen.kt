package com.catmoecircle.areyouok.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewayRuntime
import com.catmoecircle.areyouok.gateway.GatewaySettings
import com.catmoecircle.areyouok.gateway.discovery.DiscoveredGateway
import com.catmoecircle.areyouok.gateway.pairing.PairInvite
import com.catmoecircle.areyouok.gateway.pairing.PairInviteParser
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.ModelCatalog
import com.catmoecircle.areyouok.gateway.protocol.ModelSelection
import com.catmoecircle.areyouok.gateway.protocol.OutgoingAttachment
import com.catmoecircle.areyouok.gateway.protocol.PermissionCatalog
import com.catmoecircle.areyouok.gateway.protocol.PermissionOption
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.ReasoningEffort
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.effectivePermissionPreset
import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.SessionProject
import com.catmoecircle.areyouok.gateway.repository.SessionProjectGrouping
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.service.GatewayChannels
import com.catmoecircle.areyouok.gateway.service.GatewayService
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.toUserMessage
import com.catmoecircle.areyouok.ui.gateway.GatewayStatusDot
import com.catmoecircle.areyouok.ui.gateway.InterventionDock
import com.catmoecircle.areyouok.ui.gateway.ImageFetcher
import com.catmoecircle.areyouok.ui.gateway.MessageTimeline
import com.catmoecircle.areyouok.ui.gateway.rememberQrScannerLauncher
import com.catmoecircle.areyouok.ui.settings.SettingsScreen
import com.catmoecircle.areyouok.ui.settings.WorkStepCollapseTiming
import com.catmoecircle.areyouok.ui.settings.WorkStepDisplay
import com.catmoecircle.areyouok.ui.settings.WorkStepFoldedReplies
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/** 输入框胶囊高度，聚焦前后保持一致。 */
private val ComposerHeight = 50.dp

/** 胶囊圆角半径，50dp 高度下正好是完整胶囊形。 */
private val ComposerShape = RoundedCornerShape(25.dp)

/**
 * 参考图中未聚焦时胶囊左右留白约占屏宽 9%，聚焦后收到约 3%。
 * 固定 dp 值与参考图在 393dp 宽设备上的比例一致。
 */
private val ComposerSideInsetCollapsed = 36.dp
private val ComposerSideInsetExpanded = 13.dp

private val ComposerTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 17.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp,
)

/** 输入框底部浮层的形态：思考强度、模型列表、权限。 */
private enum class ComposerMenu { NONE, MODEL, MODEL_PICKER, PERMISSION }

/**
 * 聊天页的数据快照，便于预览与测试时构造稳定状态。
 */
data class ChatScreenState(
    val connection: ConnectionState = ConnectionState(),
    val projects: List<SessionProject> = emptyList(),
    val selectedSessionId: String? = null,
    val selectedSession: SessionSummary? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingInterventions: List<Intervention> = emptyList(),
    val running: Boolean = false,
    /** 历史是否还有更早的一页（滚动到顶自动加载）。 */
    val hasOlderMessages: Boolean = false,
    /** 更早历史正在加载。 */
    val loadingOlderMessages: Boolean = false,
    /** DSH 模型目录（含厂商分组与思考强度）；空目录时输入框不显示模型入口。 */
    val modelCatalog: ModelCatalog = ModelCatalog(),
    /** DSH 沙箱权限目录；空目录时输入框不显示权限入口。 */
    val permissionCatalog: PermissionCatalog = PermissionCatalog(),
)

/**
 * 单页聊天界面：没有底部导航，左上角是侧边栏入口，底部是磨砂输入区。
 *
 * 接线 dsh-gateway：消息来自 [com.catmoecircle.areyouok.gateway.repository.GatewayRepository]，
 * 底部输入框发送（queue 模式），running 时同一位置变成停止按钮；
 * 待处理审批/提问以顶部浮层卡片展示，「允许一次 / 拒绝」立即回写网关。
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime = remember(context) { GatewayRuntime.get(context) }
    val repository = runtime.repository
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 这些文案会随重组（配置变化）更新，避免在回调里用 LocalContext 取字符串。
    val notificationDeniedText = stringResource(R.string.settings_notifications_permission_denied)
    val scanFailedText = stringResource(R.string.gateway_scan_failed)
    val invalidUrlText = stringResource(R.string.gateway_pair_invalid)
    val attachmentReadFailedText = stringResource(R.string.composer_attachment_read_failed)

    val connection by repository.connection.collectAsState()
    val sessions by repository.sessions.collectAsState()
    val workspaces by repository.workspaces.collectAsState()
    val projects = remember(workspaces, sessions) {
        SessionProjectGrouping.group(workspaces, sessions)
    }
    val selectedSessionId by repository.selectedSessionId.collectAsState()
    val messages by repository.messages.collectAsState()
    val running by repository.sessionRunning.collectAsState()
    val hasOlderMessages by repository.hasOlderMessages.collectAsState()
    val loadingOlderMessages by repository.loadingOlderMessages.collectAsState()
    val pendingInterventions by repository.pendingInterventions.collectAsState()
    val modelCatalog by repository.modelCatalog.collectAsState()
    val permissionCatalog by repository.permissionCatalog.collectAsState()
    val selectedSession = remember(sessions, selectedSessionId) {
        sessions.firstOrNull { it.id == selectedSessionId }
    }
    val invite by runtime.pendingInvite.collectAsState()
    val discoveredGateways by runtime.discovery.gateways.collectAsState()
    val openSessionRequest by runtime.openSessionRequest.collectAsState()

    var draft by rememberSaveable { mutableStateOf("") }
    // 输入框里待发送的附件：选文件时读进内存，发送成功后清空（切会话也清空）。
    var attachments by remember { mutableStateOf<List<ComposerAttachment>>(emptyList()) }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    // 扫码失败后用来预填网关页的邀请（不写进 runtime 的深链 pendingInvite，避免互相覆盖）。
    var scannedInvite by remember { mutableStateOf<PairInvite?>(null) }
    var notificationsEnabled by remember { mutableStateOf(runtime.settings.notificationsEnabled) }
    var notifyApprovals by remember { mutableStateOf(runtime.settings.notifyApprovals) }
    var notifyQuestions by remember { mutableStateOf(runtime.settings.notifyQuestions) }
    var notifyCompletion by remember { mutableStateOf(runtime.settings.notifyCompletion) }
    var workStepDisplay by remember {
        mutableStateOf(WorkStepDisplay.fromStorage(runtime.settings.workStepDisplay))
    }
    var workStepCollapseTiming by remember {
        mutableStateOf(WorkStepCollapseTiming.fromStorage(runtime.settings.workStepCollapseTiming))
    }
    var workStepFoldedReplies by remember {
        mutableStateOf(WorkStepFoldedReplies.fromStorage(runtime.settings.workStepFoldedReplies))
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(context))
    }
    // 记录是否已经在设置页点过授权：第一次被拒只提示，再次被拒（系统不再弹窗）才兜底去系统设置。
    var permissionDeniedOnce by rememberSaveable { mutableStateOf(false) }
    var knownDevices by remember { mutableStateOf(runtime.settings.recent()) }

    // 用户可能去系统设置里改通知权限；回到前台时同步一次，避免设置页显示过期状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermissionGranted = isNotificationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首启拒绝通知权限后，用户仍可在设置页重新授权：这里再申请一次。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted || isNotificationPermissionGranted(context)
        if (granted) {
            notificationsEnabled = true
            runtime.settings.notificationsEnabled = true
            GatewayService.refreshNotifications(context)
        } else {
            Toast.makeText(
                context,
                notificationDeniedText,
                Toast.LENGTH_SHORT,
            ).show()
            // 第一次拒绝只提示；再次点击仍被拒说明系统不再弹授权框，兜底打开系统通知设置。
            if (permissionDeniedOnce) {
                openNotificationSettings(context)
            }
            permissionDeniedOnce = true
        }
    }

    // 添加文件：系统文件选择器（可多选），读进内存后作为待发送附件挂在输入框上方。
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = ArrayList<ComposerAttachment>(uris.size)
            uris.forEachIndexed { index, uri ->
                readComposerAttachment(
                    context = context,
                    uri = uri,
                    id = "attachment-${System.currentTimeMillis()}-$index",
                )?.let { attachment -> picked += attachment }
            }
            if (picked.size < uris.size) {
                Toast.makeText(context, attachmentReadFailedText, Toast.LENGTH_SHORT).show()
            }
            if (picked.isNotEmpty()) attachments = attachments + picked
        }
    }

    // 配对新网关的公共流程：配对码为空按局域网直连，否则先 POST /v1/pair 换 token。
    suspend fun performConnect(url: String, code: String, invite: PairInvite?): GatewayEndpoint {
        val baseUrl = GatewayEndpoint.normalizeBaseUrl(url)
            ?: throw IllegalArgumentException(invalidUrlText)
        // 邀请里的网关名只对同一个地址有效；手改地址后不再沿用。
        val inviteName = invite
            ?.takeIf { GatewayEndpoint.normalizeBaseUrl(it.baseUrl) == baseUrl }
            ?.name
        val endpoint = if (code.isBlank()) {
            runtime.pairing.endpointFor(baseUrl, inviteName)
        } else {
            runtime.pairing.pair(
                PairInvite(baseUrl = baseUrl, code = code, name = inviteName),
                runtime.deviceName,
            ).endpoint
        }
        runtime.connect(endpoint)
        return endpoint
    }

    // 扫码入口：识别成功后直接配对/连接，不再要求用户回到网关页手动点「连接」。
    val launchScanner = rememberQrScannerLauncher(
        prompt = stringResource(R.string.gateway_scan_prompt),
    ) { raw ->
        val scanned = PairInviteParser.parse(raw)
        if (scanned == null) {
            Toast.makeText(context, scanFailedText, Toast.LENGTH_SHORT).show()
            return@rememberQrScannerLauncher
        }

        val targetName = scanned.name
            ?: GatewayEndpoint.normalizeBaseUrl(scanned.baseUrl)
            ?: scanned.baseUrl
        pairError = null
        scannedInvite = null
        Toast.makeText(
            context,
            context.getString(R.string.settings_connecting_toast, targetName),
            Toast.LENGTH_SHORT,
        ).show()

        scope.launch {
            pairing = true
            try {
                val endpoint = performConnect(scanned.baseUrl, scanned.code, scanned)
                runtime.consumeInvite()
                knownDevices = runtime.settings.recent()
                settingsOpen = false
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.settings_connected_toast,
                        endpoint.name ?: targetName,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (error: Throwable) {
                // 失败时留在设置页：把扫码内容预填到网关页，用户可改地址/配对码后重试。
                pairError = error.toUserMessage()
                scannedInvite = scanned
                settingsOpen = true
            } finally {
                pairing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        repository.notices.collect { notice ->
            Toast.makeText(context, notice.text, Toast.LENGTH_SHORT).show()
        }
    }

    // 通知点击（审批 / 提问 / 任务完成）：切到通知对应的会话。
    // 冷启动时先等连接和会话列表就绪，否则会被「默认选中第一个会话」覆盖。
    LaunchedEffect(openSessionRequest, connection.phase, sessions) {
        val request = openSessionRequest ?: return@LaunchedEffect
        if (!connection.isOnline || sessions.isEmpty()) return@LaunchedEffect
        settingsOpen = false
        sidebarOpen = false
        focusManager.clearFocus()
        repository.selectSession(request.sessionId)
        runtime.consumeOpenSession()
    }

    LaunchedEffect(invite) {
        if (invite != null) {
            pairError = null
            settingsOpen = true
        }
    }

    // 连接成功后刷新设备列表；首次进入也扫一次本地记录。
    LaunchedEffect(connection.endpoint?.baseUrl) {
        knownDevices = runtime.settings.recent()
    }

    // 未连接时持续做 mDNS 发现，连上后由 GatewayRuntime 关闭以省电。
    LaunchedEffect(connection.phase) {
        if (connection.phase == com.catmoecircle.areyouok.gateway.repository.ConnectionPhase.IDLE ||
            connection.phase == com.catmoecircle.areyouok.gateway.repository.ConnectionPhase.ERROR
        ) {
            runtime.startDiscovery()
        }
    }

    // 设置页打开期间也保持发现：已连接时网关页仍能看到局域网里的其他网关；
    // 关闭设置页且当前在线时恢复省电，停掉浏览。
    LaunchedEffect(settingsOpen, connection.isOnline) {
        if (settingsOpen) {
            runtime.startDiscovery()
        } else if (connection.isOnline) {
            runtime.stopDiscovery()
        }
    }

    // 切换会话时丢弃未发送的附件：上一段对话的草稿不该跟着跑到新会话里。
    LaunchedEffect(selectedSessionId) {
        attachments = emptyList()
    }

    // 设置页打开时让聊天内容退出无障碍树，避免读屏聚焦到被盖住的控件。
    val chatModifier = if (settingsOpen) {
        modifier.clearAndSetSemantics { }
    } else {
        modifier
    }

    // 设置页只展示本地记录过的设备；当前连接可能来自 mDNS/深链，补进列表保证可见。
    val settingsDevices = remember(knownDevices, connection.endpoint) {
        mergeSettingsDevices(knownDevices, connection)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatScreenContent(
            state = ChatScreenState(
                connection = connection,
                projects = projects,
                selectedSessionId = selectedSessionId,
                selectedSession = selectedSession,
                messages = messages,
                pendingInterventions = pendingInterventions,
                running = running,
                hasOlderMessages = hasOlderMessages,
                loadingOlderMessages = loadingOlderMessages,
                modelCatalog = modelCatalog,
                permissionCatalog = permissionCatalog,
            ),
            draft = draft,
            onDraftChange = { draft = it },
            attachments = attachments,
            onPickAttachments = {
                focusManager.clearFocus()
                attachmentPicker.launch(arrayOf("*/*"))
            },
            onRemoveAttachment = { id -> attachments = attachments.filterNot { it.id == id } },
            onSend = { text ->
                // 发送时快照一份附件：上传期间用户继续改动输入框也不影响这条消息。
                val pending = attachments
                scope.launch {
                    if (repository.sendMessage(
                            text = text,
                            attachments = pending.map { attachment ->
                                OutgoingAttachment(attachment.name, attachment.mimeType, attachment.bytes)
                            },
                        )
                    ) {
                        draft = ""
                        attachments = emptyList()
                    }
                }
            },
            onStop = { scope.launch { repository.interrupt() } },
            onLoadOlder = { repository.loadOlderMessages() },
            onOpenSidebar = { sidebarOpen = true },
            sidebarOpen = sidebarOpen,
            onDismissSidebar = { sidebarOpen = false },
            onSelectSession = { id ->
                repository.selectSession(id)
                sidebarOpen = false
            },
            onNewSession = {
                scope.launch { repository.createSession()?.let { sidebarOpen = false } }
            },
            onNewSessionInProject = { workspaceId ->
                scope.launch {
                    repository.createSession(workspaceId = workspaceId)?.let { sidebarOpen = false }
                }
            },
            onOpenSettings = {
                focusManager.clearFocus()
                sidebarOpen = false
                pairError = null
                scannedInvite = null
                settingsOpen = true
            },
            onOpenScanner = launchScanner,
            onAllowOnce = { eventId -> scope.launch { repository.allowOnce(eventId) } },
            onReject = { eventId -> scope.launch { repository.reject(eventId) } },
            onAnswerQuestion = { eventId, answers ->
                scope.launch { repository.answerQuestion(eventId, answers) }
            },
            imageFetcher = remember(repository) { ImageFetcher { url -> repository.fetchImage(url) } },
            onSelectModel = { provider, model, effort ->
                scope.launch { repository.selectModel(provider, model, effort) }
            },
            onSelectPermission = { preset ->
                scope.launch { repository.selectPermission(preset) }
            },
            discoveredGateways = discoveredGateways,
            onSelectGateway = { gateway ->
                gateway.baseUrl?.let { url -> runtime.connectTo(url, gateway.name) }
            },
            workStepDisplay = workStepDisplay,
            workStepCollapseTiming = workStepCollapseTiming,
            workStepFoldedReplies = workStepFoldedReplies,
            modifier = chatModifier,
        )

        // 设置页：全屏独立页面，从右侧滑入，替代原来的配对弹窗。
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(animationSpec = tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(animationSpec = tween(220)) { it } + fadeOut(tween(160)),
        ) {
            SettingsScreen(
                connection = connection,
                devices = settingsDevices,
                discoveredGateways = discoveredGateways,
                initialUrl = scannedInvite?.baseUrl
                    ?: invite?.baseUrl
                    ?: runtime.settings.lastBaseUrl.orEmpty(),
                initialCode = scannedInvite?.code ?: invite?.code.orEmpty(),
                inviteLabel = scannedInvite?.name
                    ?: scannedInvite?.baseUrl
                    ?: invite?.name
                    ?: invite?.baseUrl,
                busy = pairing,
                error = pairError,
                notificationsEnabled = notificationsEnabled,
                notifyApprovals = notifyApprovals,
                notifyQuestions = notifyQuestions,
                notifyCompletion = notifyCompletion,
                notificationPermissionGranted = notificationPermissionGranted,
                workStepDisplay = workStepDisplay,
                workStepCollapseTiming = workStepCollapseTiming,
                workStepFoldedReplies = workStepFoldedReplies,
                deviceName = runtime.deviceName,
                onBack = {
                    if (!pairing) {
                        settingsOpen = false
                        scannedInvite = null
                        runtime.consumeInvite()
                    }
                },
                onScan = launchScanner,
                onConnect = { url, code ->
                    pairing = true
                    pairError = null
                    scope.launch {
                        try {
                            performConnect(url, code, scannedInvite ?: invite)
                            settingsOpen = false
                            scannedInvite = null
                            runtime.consumeInvite()
                        } catch (error: Throwable) {
                            pairError = error.toUserMessage()
                        } finally {
                            pairing = false
                        }
                    }
                },
                onDisconnect = { runtime.disconnect() },
                onConnectDevice = { device ->
                    pairError = null
                    if (runtime.connectTo(device.baseUrl, device.name) == null) {
                        Toast.makeText(context, invalidUrlText, Toast.LENGTH_SHORT).show()
                    } else {
                        knownDevices = runtime.settings.recent()
                    }
                },
                onSelectDiscoveredGateway = { gateway ->
                    val baseUrl = gateway.baseUrl
                    if (baseUrl == null) {
                        Toast.makeText(context, invalidUrlText, Toast.LENGTH_SHORT).show()
                    } else {
                        runtime.connectTo(baseUrl, gateway.name)
                        knownDevices = runtime.settings.recent()
                    }
                },
                onClearError = { pairError = null },
                onNotificationsChange = { enabled ->
                    if (enabled && !notificationPermissionGranted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationsEnabled = enabled
                        runtime.settings.notificationsEnabled = enabled
                        GatewayService.refreshNotifications(context)
                    }
                },
                onApprovalsChange = { enabled ->
                    notifyApprovals = enabled
                    runtime.settings.notifyApprovals = enabled
                    GatewayService.refreshNotifications(context)
                },
                onQuestionsChange = { enabled ->
                    notifyQuestions = enabled
                    runtime.settings.notifyQuestions = enabled
                    GatewayService.refreshNotifications(context)
                },
                onCompletionChange = { enabled ->
                    notifyCompletion = enabled
                    runtime.settings.notifyCompletion = enabled
                },
                onWorkStepDisplayChange = { level ->
                    workStepDisplay = level
                    runtime.settings.workStepDisplay = level.name
                },
                onWorkStepCollapseTimingChange = { timing ->
                    workStepCollapseTiming = timing
                    runtime.settings.workStepCollapseTiming = timing.name
                },
                onWorkStepFoldedRepliesChange = { replies ->
                    workStepFoldedReplies = replies
                    runtime.settings.workStepFoldedReplies = replies.name
                },
                onRequestNotificationPermission = {
                    if (notificationPermissionGranted) {
                        openNotificationSettings(context)
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenNotificationSettings = { openNotificationSettings(context) },
            )
        }
    }
}

@Composable
fun ChatScreenContent(
    state: ChatScreenState,
    draft: String,
    onDraftChange: (String) -> Unit,
    /** 输入框里待发送的附件（点「+」从系统文件选择器挑进来的本地文件）。 */
    attachments: List<ComposerAttachment> = emptyList(),
    /** 打开系统文件选择器添加附件。 */
    onPickAttachments: () -> Unit = {},
    /** 从待发送列表里移除一个附件（参数是 [ComposerAttachment.id]）。 */
    onRemoveAttachment: (String) -> Unit = {},
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onLoadOlder: () -> Unit = {},
    onOpenSidebar: () -> Unit,
    sidebarOpen: Boolean,
    onDismissSidebar: () -> Unit,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit = {},
    onAllowOnce: (String) -> Unit,
    onReject: (String) -> Unit,
    onAnswerQuestion: (eventId: String, answers: List<QuestionAnswer>) -> Unit,
    onSelectModel: (provider: String, model: String, effort: String?) -> Unit = { _, _, _ -> },
    onSelectPermission: (preset: String) -> Unit = {},
    discoveredGateways: List<DiscoveredGateway> = emptyList(),
    onSelectGateway: (DiscoveredGateway) -> Unit = {},
    onNewSessionInProject: (workspaceId: String?) -> Unit = {},
    workStepDisplay: WorkStepDisplay = WorkStepDisplay.DEFAULT,
    workStepCollapseTiming: WorkStepCollapseTiming = WorkStepCollapseTiming.DEFAULT,
    workStepFoldedReplies: WorkStepFoldedReplies = WorkStepFoldedReplies.DEFAULT,
    /** 按需取图（图片只按链接预览，不落盘）；null 时不加载图片。 */
    imageFetcher: ImageFetcher? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current

    // 画布保持中性、干净，仅用极轻的纵向渐变增加层次。
    val canvasTop = colorScheme.background
    val canvasBottom = lerpToSurface(canvasTop, colorScheme.surface, 0.4f)
    val canvas = Brush.verticalGradient(colors = listOf(canvasTop, canvasBottom))

    // 捕获聊天内容，供底部磨砂栏做背景模糊。
    val backdrop = rememberLayerBackdrop {
        drawRect(brush = canvas)
        drawContent()
    }

    // 只把「当前对话」的审批/提问弹到底部；其他对话的事件在侧边栏对应会话行上打标记。
    val sessionPending = Intervention.pendingForSession(state.pendingInterventions, state.selectedSessionId)
    val pendingSessionIds = Intervention.pendingSessionIds(state.pendingInterventions)

    BackHandler(enabled = sidebarOpen) {
        onDismissSidebar()
    }

    // ---- 输入框的模型 / 思考强度 / 权限 -------------------------------------
    // 目录来自网关（/v1/models、/v1/permissions），当前值来自当前会话投影；
    // 没有会话或目录尚未加载时隐藏入口，避免点了没反应。
    val modelOptions = composerModelOptions(state.modelCatalog)
    val currentSelection = state.selectedSession?.projections?.values?.modelSelection?.current
        ?: state.modelCatalog.default
    val currentModel = currentSelection?.let { selection ->
        state.modelCatalog.model(selection.provider, selection.model)
    }
    val currentModelLabel = currentModel?.displayName ?: currentSelection?.model.orEmpty()
    val currentEffort = currentSelection?.reasoningEffort ?: currentModel?.defaultEffort
    val currentEffortOptions = composerEffortOptions(currentModel?.efforts.orEmpty())
    val currentEffortLabel = currentEffort?.let { id ->
        currentEffortOptions.firstOrNull { it.id == id }?.label ?: effortDisplayName(id)
    }
    val permissionOptions = composerPermissionOptions(state.permissionCatalog)
    // 权限：有会话投影用投影值；新建的空白会话没有投影时回退目录默认档（第二档），
    // 否则菜单打开后没有任何勾选、图标退化成兜底的锁。
    val currentPermission = state.selectedSession?.effectivePermissionPreset(state.permissionCatalog)
    val composerToolsReady = state.connection.phase == ConnectionPhase.CONNECTED &&
        state.selectedSession != null

    var composerMenu by remember { mutableStateOf(ComposerMenu.NONE) }
    var lastComposerMenu by remember { mutableStateOf(ComposerMenu.MODEL) }
    var pendingFullAccess by remember { mutableStateOf<ComposerPermissionOption?>(null) }
    val menuVisible = composerMenu != ComposerMenu.NONE
    if (menuVisible) lastComposerMenu = composerMenu
    val shownMenu = if (menuVisible) composerMenu else lastComposerMenu

    // 选中后先让勾选/图标动画落位（约 160ms），再收起菜单；
    // 否则点一下菜单立刻消失，看不到「换到哪一档」的过渡。
    val menuScope = rememberCoroutineScope()
    fun closeMenuAfterSelection(menu: ComposerMenu) {
        menuScope.launch {
            kotlinx.coroutines.delay(160)
            if (composerMenu == menu) composerMenu = ComposerMenu.NONE
        }
    }

    // 切换会话时收起浮层，避免在 A 会话里改到 B 会话的值。
    LaunchedEffect(state.selectedSessionId) {
        composerMenu = ComposerMenu.NONE
        pendingFullAccess = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(canvas)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            // 点击空白区域收起键盘并释放焦点，胶囊随之收回默认宽度。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures { focusManager.clearFocus() }
                    }
            )

            MessageTimeline(
                messages = state.messages,
                workStepDisplay = workStepDisplay,
                workStepCollapseTiming = workStepCollapseTiming,
                workStepFoldedReplies = workStepFoldedReplies,
                running = state.running,
                hasOlder = state.hasOlderMessages,
                loadingOlder = state.loadingOlderMessages,
                onLoadOlder = onLoadOlder,
                sessionId = state.selectedSessionId,
                imageFetcher = imageFetcher,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 56.dp),
            )

            SidebarMenuButton(
                onClick = {
                    focusManager.clearFocus()
                    onOpenSidebar()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, top = 12.dp)
            )

            ConnectionPill(
                state = state.connection,
                onClick = onOpenSidebar,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 16.dp, top = 15.dp),
            )
        }

        // 当前对话有待处理的审批/提问时，底部输入框整体换成待处理卡片（底部弹窗）。
        if (sessionPending.isEmpty()) {
            ChatComposerDock(
                backdrop = backdrop,
                value = draft,
                onValueChange = onDraftChange,
                attachments = attachments,
                onPickAttachments = onPickAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onSend = { onSend(draft) },
                running = state.running,
                onStop = onStop,
                modelLabel = currentModelLabel,
                effortLabel = currentEffortLabel,
                onModelClick = if (composerToolsReady && modelOptions.isNotEmpty()) {
                    ({ composerMenu = ComposerMenu.MODEL })
                } else {
                    null
                },
                permissionValue = currentPermission,
                onPermissionClick = if (composerToolsReady && permissionOptions.isNotEmpty()) {
                    ({ composerMenu = ComposerMenu.PERMISSION })
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            InterventionDock(
                interventions = sessionPending,
                onAllowOnce = onAllowOnce,
                onReject = onReject,
                onAnswerQuestion = onAnswerQuestion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 底部安全区与键盘取并集：键盘弹出时卡片上移，收起时避开手势条。
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }

        // ---- 模型 / 思考强度 / 权限浮层 ------------------------------------
        // 点浮层以外的位置关闭；卡片贴在输入框上方并跟随键盘上移。
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(100)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                    ) { composerMenu = ComposerMenu.NONE },
            )
        }
        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically(tween(180)) { it / 8 } + fadeIn(tween(140)),
            exit = slideOutVertically(tween(140)) { it / 8 } + fadeOut(tween(100)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                .windowInsetsPadding(WindowInsets.ime)
                .padding(bottom = 124.dp, start = 12.dp, end = 12.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                when (shownMenu) {
                    ComposerMenu.MODEL -> {
                        val selection = currentSelection
                        ComposerModelMenu(
                            currentModelLabel = currentModelLabel.ifBlank {
                                stringResource(R.string.composer_model)
                            },
                            effortOptions = currentEffortOptions,
                            currentEffort = currentEffort,
                            onSelectEffort = { effort ->
                                if (selection != null) {
                                    onSelectModel(selection.provider, selection.model, effort)
                                    closeMenuAfterSelection(ComposerMenu.MODEL)
                                }
                            },
                            onOpenModelPicker = { composerMenu = ComposerMenu.MODEL_PICKER },
                        )
                    }

                    ComposerMenu.MODEL_PICKER -> ComposerModelPickerMenu(
                        options = modelOptions,
                        selection = currentSelection?.let {
                            ComposerModelSelection(it.provider, it.model, it.reasoningEffort)
                        },
                        onSelect = { option ->
                            val effort = option.efforts.firstOrNull { it.id == currentEffort }?.id
                                ?: option.defaultEffort
                                ?: option.efforts.firstOrNull()?.id
                            onSelectModel(option.provider, option.model, effort)
                            closeMenuAfterSelection(ComposerMenu.MODEL_PICKER)
                        },
                    )

                    ComposerMenu.PERMISSION -> ComposerPermissionMenu(
                        options = permissionOptions,
                        currentValue = currentPermission,
                        onSelect = { option ->
                            if (option.requiresConfirmation) {
                                // 先收起键盘，让确认弹窗完整居中显示（否则会被键盘压住）。
                                focusManager.clearFocus()
                                composerMenu = ComposerMenu.NONE
                                pendingFullAccess = option
                            } else {
                                onSelectPermission(option.value)
                                closeMenuAfterSelection(ComposerMenu.PERMISSION)
                            }
                        },
                    )

                    ComposerMenu.NONE -> Unit
                }
            }
        }

        // 选择「完全权限」前的风险确认（参考图 6）。
        pendingFullAccess?.let { option ->
            FullAccessConfirmDialog(
                onDismiss = { pendingFullAccess = null },
                onConfirm = {
                    pendingFullAccess = null
                    composerMenu = ComposerMenu.NONE
                    onSelectPermission(option.value)
                },
            )
        }

        ModalSidebar(
            open = sidebarOpen,
            onDismiss = onDismissSidebar,
            connection = state.connection,
            projects = state.projects,
            selectedSessionId = state.selectedSessionId,
            discoveredGateways = discoveredGateways,
            onSelectGateway = onSelectGateway,
            onSelectSession = onSelectSession,
            onNewSession = onNewSession,
            onNewSessionInProject = onNewSessionInProject,
            onOpenSettings = onOpenSettings,
            onScan = onOpenScanner,
            pendingSessionIds = pendingSessionIds,
        )
    }
}

/** 顶部右侧的连接状态胶囊，点按打开侧边栏。 */
@Composable
private fun ConnectionPill(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.22f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GatewayStatusDot(phase = state.phase, size = 8.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            text = state.displayName,
            color = colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}

private fun lerpToSurface(start: Color, end: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * clamped,
        green = start.green + (end.green - start.green) * clamped,
        blue = start.blue + (end.blue - start.blue) * clamped,
        alpha = start.alpha + (end.alpha - start.alpha) * clamped,
    )
}

/**
 * 底部磨砂输入区：大范围包裹输入框，替代原来的投影。
 *
 * 使用 Miuix 的渐进背景模糊，模糊和半透明着色从底部向上淡出；
 * 底栏随着 IME 动画整体上移，与输入框的撑开动画共用同一份 insets。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatComposerDock(
    backdrop: LayerBackdrop,
    value: String,
    onValueChange: (String) -> Unit,
    /** 已选好、等待随下一条消息发送的附件。 */
    attachments: List<ComposerAttachment> = emptyList(),
    onPickAttachments: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onSend: () -> Unit,
    running: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    modelLabel: String = "",
    effortLabel: String? = null,
    onModelClick: (() -> Unit)? = null,
    permissionValue: String? = null,
    onPermissionClick: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.surface.luminance() < 0.35f
    val frostTint = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 键盘弹起时不再额外叠加导航栏高度，键盘收起时磨砂区延伸到屏幕底部。
                .windowInsetsPadding(
                    WindowInsets.navigationBars.exclude(WindowInsets.ime)
                )
                .padding(top = 28.dp, bottom = 14.dp)
        ) {
            ChatComposer(
                value = value,
                onValueChange = onValueChange,
                attachments = attachments,
                onPickAttachments = onPickAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onSend = onSend,
                running = running,
                onStop = onStop,
                modelLabel = modelLabel,
                effortLabel = effortLabel,
                onModelClick = onModelClick,
                permissionValue = permissionValue,
                onPermissionClick = onPermissionClick,
            )
        }
    }
}

/**
 * 底部输入框胶囊。
 *
 * 宽度由 IME 动画进度驱动：键盘上升时胶囊同步向两侧撑开，键盘下降时同步收窄，
 * 与系统键盘动画逐帧对齐，而不是等升降结束再跳变。高度随多行输入自然增长。
 *
 * 右侧是发送/停止按钮：running 时显示停止（打断当前 turn），否则显示发送。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandedOverride: Boolean? = null,
    /** 已选好、等待随下一条消息发送的附件。 */
    attachments: List<ComposerAttachment> = emptyList(),
    /** 操作行左侧「+」：打开系统文件选择器。 */
    onPickAttachments: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onSend: (() -> Unit)? = null,
    running: Boolean = false,
    onStop: (() -> Unit)? = null,
    modelLabel: String = "",
    effortLabel: String? = null,
    onModelClick: (() -> Unit)? = null,
    permissionValue: String? = null,
    onPermissionClick: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeAnimationTarget = WindowInsets.imeAnimationTarget

    // 键盘是否在屏幕上（收起动画一开始就会变 false，用它清焦点）。
    val imeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            keyboardWasVisible = true
        } else if (keyboardWasVisible) {
            keyboardWasVisible = false
            if (focused) {
                focusManager.clearFocus()
            }
        }
    }

    // 0..1 的展开进度直接取「当前键盘高度 / 键盘完全展开高度」。
    // 不用 imeAnimationSource/imeAnimationTarget 做区间映射：键盘收起动画的
    // target 会变成 0，区间退化时中间帧会被算成 0，卡片先掉下去再弹回来。
    // 这里在键盘可见期间记住完全展开高度，收起时一直用它做分母。
    var imeFullHeight by remember { mutableStateOf(0f) }
    SideEffect {
        val height = maxOf(
            imeInsets.getBottom(density),
            imeAnimationTarget.getBottom(density),
        ).toFloat()
        if (imeVisible && height > imeFullHeight) imeFullHeight = height
    }
    val imeFraction by remember(imeInsets, imeAnimationTarget, imeFullHeight, density) {
        derivedStateOf {
            val current = imeInsets.getBottom(density).toFloat()
            val target = imeAnimationTarget.getBottom(density).toFloat()
            val denominator = if (target > 0f) target else imeFullHeight
            if (denominator <= 1f) 0f else (current / denominator).coerceIn(0f, 1f)
        }
    }

    // ---- 展开进度：整个「胶囊 → 卡片」的形变共用同一个 0..1 进度 ----------
    // 有软键盘时只跟键盘高度走：键盘开始上移的同帧卡片开始形变，键盘到位同帧到 1；
    // 收起时随键盘回落同步收完（不会有回弹）。
    // 键盘迟迟不出现（硬件键盘/预览）才退回本地 220ms 补间，保证操作行仍可用。
    val fallbackFraction by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "composerFallback",
    )
    var fallbackActive by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            // 只有「从来没出现过软键盘」的输入环境（硬件键盘/预览）才用本地补间：
            // 等 400ms 仍没有 IME 高度才启用，避免和刚起来的键盘动画抢同一段过渡
            // （抢的结果就是先弹开、再掉回去的「回弹」）。
            kotlinx.coroutines.delay(400)
            if (imeFullHeight <= 0f) fallbackActive = true
        } else {
            fallbackActive = false
        }
    }
    // 只要键盘动画在跑，就彻底让 IME 驱动，禁止本地补间插手（否则结束时会回弹）。
    if (imeFraction > 0f && fallbackActive) {
        SideEffect { fallbackActive = false }
    }
    val morph = expandedOverride?.let { if (it) 1f else 0f }
        ?: when {
            imeFraction > 0f -> imeFraction
            fallbackActive -> fallbackFraction
            else -> 0f
        }

    // 宽度、左右留白、底行高度、文字可用宽度全部由同一个进度插值。
    val sideInset = ComposerSideInsetCollapsed +
        (ComposerSideInsetExpanded - ComposerSideInsetCollapsed) * morph
    val horizontalStart = 20.dp + (14.dp - 20.dp) * morph
    val textEndInset = 44.dp + (8.dp - 44.dp) * morph
    val bottomInset = 4.dp + 2.dp * morph
    val controlsHeight = 36.dp * morph

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sideInset)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ComposerShape)
                .background(colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = colorScheme.outline.copy(alpha = 0.22f),
                    shape = ComposerShape,
                )
                .heightIn(min = ComposerHeight)
                .padding(start = horizontalStart, end = 8.dp, top = 4.dp, bottom = bottomInset),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 待发送附件条：只在有附件时出现，没选文件时输入框高度与原来完全一致。
                if (attachments.isNotEmpty()) {
                    ComposerAttachmentRail(
                        attachments = attachments,
                        onRemove = onRemoveAttachment,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 6.dp),
                    )
                }
                // 输入行始终在同一个组合位置：形变过程中不会重建 BasicTextField，焦点不丢。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerTextField(
                        value = value,
                        onValueChange = onValueChange,
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, end = textEndInset, top = 8.dp, bottom = 8.dp),
                    )
                }

                // 操作行高度随同一进度从 0 长到 36dp，内容同步淡入；
                // 不是「先到位再补动画」，而是和键盘同一条时间线。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(controlsHeight)
                        .clipToBounds(),
                ) {
                    if (controlsHeight > 0.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .graphicsLayer { alpha = morph }
                                .padding(end = 44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComposerAttachButton(onClick = onPickAttachments)
                            if (onPermissionClick != null) {
                                ComposerPermissionButton(
                                    currentValue = permissionValue,
                                    onClick = onPermissionClick,
                                )
                            } else {
                                Spacer(Modifier.width(6.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            if (onModelClick != null && modelLabel.isNotBlank()) {
                                ComposerModelChip(
                                    modelLabel = modelLabel,
                                    effortLabel = effortLabel,
                                    onClick = onModelClick,
                                )
                            }
                        }
                    }
                }
            }

            // 发送/停止按钮的纵向落点随 morph 连续插值：
            // 收起时在胶囊内上下居中，展开时落到底部操作行（与下方内容同一条中线）。
            // 用自定义 Alignment 而不是 BottomEnd，避免两个状态之间跳变。
            ComposerActionButton(
                running = running,
                // 只有附件没有文字也能发：文件本身就是这条消息的内容。
                canSend = value.isNotBlank() || attachments.isNotEmpty(),
                onSend = onSend,
                onStop = onStop,
                modifier = Modifier.align(
                    ComposerActionAlignment(verticalFraction = 0.5f + 0.5f * morph)
                ),
            )
        }
    }
}

/** 输入框本体：空内容时显示占位提示。 */
@Composable
private fun ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = ComposerTextStyle.copy(color = colorScheme.onSurface),
        cursorBrush = SolidColor(colorScheme.primary),
        maxLines = 5,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.composer_hint),
                        style = ComposerTextStyle.copy(color = colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 发送键的纵向落点：0f = 顶、0.5f = 内容区正中、1f = 底。
 *
 * Box 传给 [Alignment.align] 的 `space` 是内容区可用尺寸，`size` 是按钮自身尺寸，
 * 所以 (space.height - size.height) * fraction 就是按钮上沿的偏移。
 * 收起态内容区上下留白对称（各 4dp），0.5f 正好等于胶囊正中；
 * 展开态内容区下方多出一条操作行，1f 让它和操作行同中线。
 */
private class ComposerActionAlignment(
    private val verticalFraction: Float,
) : Alignment {
    override fun align(
        size: IntSize,
        space: IntSize,
        layoutDirection: LayoutDirection,
    ): IntOffset = IntOffset(
        x = space.width - size.width,
        y = ((space.height - size.height) * verticalFraction).roundToInt(),
    )
}

/** 胶囊右侧的发送/停止按钮：绿色向上箭头 / 红色方块。 */
@Composable
private fun ComposerActionButton(
    running: Boolean,
    canSend: Boolean,
    onSend: (() -> Unit)?,
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val active = running || canSend
    val background = when {
        running -> Color(0xFFFF3B30)
        canSend -> colorScheme.primary
        else -> colorScheme.surfaceVariant.copy(alpha = 0.8f)
    }
    val label = if (running) {
        stringResource(R.string.gateway_stop)
    } else {
        stringResource(R.string.gateway_send)
    }

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = active,
                role = Role.Button,
                onClickLabel = label,
                onClick = {
                    if (running) onStop?.invoke() else onSend?.invoke()
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) "■" else "↑",
            color = if (active) Color.White else colorScheme.onSurfaceVariant,
            fontSize = if (running) 12.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---- 目录 → 输入框 UI 模型映射 -------------------------------------------

/** 把 DSH 模型目录映射成输入框菜单需要的 UI 模型（厂商名、思考强度本地化）。 */
@Composable
private fun composerModelOptions(catalog: ModelCatalog): List<ComposerModelOption> =
    catalog.groups.flatMap { group ->
        val providerLabel = when (group.id) {
            "deepseek-official" -> stringResource(R.string.composer_provider_deepseek_official)
            "deepseek-account" -> stringResource(R.string.composer_provider_deepseek_account)
            else -> group.displayName
        }
        group.models.map { model ->
            ComposerModelOption(
                provider = group.id,
                providerLabel = providerLabel,
                model = model.modelId,
                label = model.displayName,
                description = model.displayDescription,
                efforts = composerEffortOptions(model.efforts),
                defaultEffort = model.defaultEffort,
            )
        }
    }

/** 思考强度选项：已知 id 用中文（低/高/Max…），未知 id 用目录里的名字。 */
@Composable
private fun composerEffortOptions(efforts: List<ReasoningEffort>): List<ComposerEffortOption> =
    efforts.map { effort ->
        ComposerEffortOption(
            id = effort.id,
            label = effortLabel(effort),
            description = effort.description,
        )
    }

@Composable
private fun effortLabel(effort: ReasoningEffort): String = when (effort.id.lowercase()) {
    "off" -> stringResource(R.string.composer_effort_off)
    "minimal" -> stringResource(R.string.composer_effort_minimal)
    "low" -> stringResource(R.string.composer_effort_low)
    "medium" -> stringResource(R.string.composer_effort_medium)
    "high" -> stringResource(R.string.composer_effort_high)
    "max" -> stringResource(R.string.composer_effort_max)
    else -> effort.displayName
}

/** 当前强度 id 不在模型目录里时的兜底标签。 */
@Composable
private fun effortDisplayName(id: String): String = when (id.lowercase()) {
    "off" -> stringResource(R.string.composer_effort_off)
    "minimal" -> stringResource(R.string.composer_effort_minimal)
    "low" -> stringResource(R.string.composer_effort_low)
    "medium" -> stringResource(R.string.composer_effort_medium)
    "high" -> stringResource(R.string.composer_effort_high)
    "max" -> stringResource(R.string.composer_effort_max)
    else -> id
}

/** 权限档位：DSH 内置 3 档用中文，目录里的自定义档位用 host 给的标签。 */
@Composable
private fun composerPermissionOptions(catalog: PermissionCatalog): List<ComposerPermissionOption> =
    catalog.options.map { option ->
        ComposerPermissionOption(
            value = option.value,
            label = permissionLabel(option),
            description = permissionDescription(option),
        )
    }

@Composable
private fun permissionLabel(option: PermissionOption): String = when (option.value) {
    PERMISSION_READ_ONLY -> stringResource(R.string.composer_permission_read_only)
    PERMISSION_WORKSPACE_WRITE -> stringResource(R.string.composer_permission_workspace_write)
    PERMISSION_FULL_ACCESS -> stringResource(R.string.composer_permission_full_access)
    PERMISSION_AUTO, PERMISSION_AUTO_REVIEW -> stringResource(R.string.composer_permission_auto)
    else -> option.displayName
}

@Composable
private fun permissionDescription(option: PermissionOption): String? = when (option.value) {
    PERMISSION_READ_ONLY -> stringResource(R.string.composer_permission_read_only_desc)
    PERMISSION_WORKSPACE_WRITE -> stringResource(R.string.composer_permission_workspace_write_desc)
    PERMISSION_FULL_ACCESS -> stringResource(R.string.composer_permission_full_access_desc)
    PERMISSION_AUTO, PERMISSION_AUTO_REVIEW -> stringResource(R.string.composer_permission_auto_desc)
    else -> option.description
}

/** Android 13+ 需要 POST_NOTIFICATIONS 权限；更早版本默认允许。 */
private fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/** 直接打开“审批与提问”渠道，方便用户开启系统控制的悬浮通知与声音。 */
private fun openNotificationSettings(context: Context) {
    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, GatewayChannels.INTERVENTIONS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(channelIntent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

/** 设备列表：把当前连接（可能来自 mDNS / 深链）补进最近列表，避免当前设备不显示。 */
private fun mergeSettingsDevices(
    recent: List<GatewaySettings.RecentGateway>,
    connection: ConnectionState,
): List<GatewaySettings.RecentGateway> {
    val endpoint = connection.endpoint ?: return recent
    val current = GatewayEndpoint.normalizeBaseUrl(endpoint.baseUrl) ?: endpoint.baseUrl
    val exists = recent.any { device ->
        (GatewayEndpoint.normalizeBaseUrl(device.baseUrl) ?: device.baseUrl) == current
    }
    return if (exists) {
        recent
    } else {
        listOf(GatewaySettings.RecentGateway(endpoint.baseUrl, endpoint.name)) + recent
    }
}

@Preview(name = "Chat screen", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun ChatScreenPreview() {
    AreYouOKTheme {
        ChatScreenContent(
            state = ChatScreenState(),
            draft = "",
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onOpenSidebar = {},
            sidebarOpen = false,
            onDismissSidebar = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenSettings = {},
            onAllowOnce = {},
            onReject = {},
            onAnswerQuestion = { _, _ -> },
        )
    }
}

@Preview(name = "Composer focused", showBackground = true, widthDp = 393, heightDp = 200)
@Composable
private fun ComposerExpandedPreview() {
    AreYouOKTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ChatComposer(
                value = "",
                onValueChange = {},
                expandedOverride = true,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
