package com.catmoecircle.areyouok.gateway.repository

import com.catmoecircle.areyouok.gateway.protocol.EventFrame
import com.catmoecircle.areyouok.gateway.protocol.EventKind
import com.catmoecircle.areyouok.gateway.protocol.GatewayJson
import com.catmoecircle.areyouok.gateway.protocol.Intervention
import com.catmoecircle.areyouok.gateway.protocol.InterventionAnswer
import com.catmoecircle.areyouok.gateway.protocol.Message
import com.catmoecircle.areyouok.gateway.protocol.MessageImage
import com.catmoecircle.areyouok.gateway.protocol.MessageSource
import com.catmoecircle.areyouok.gateway.protocol.ModelCatalog
import com.catmoecircle.areyouok.gateway.protocol.ModelSelection
import com.catmoecircle.areyouok.gateway.protocol.PermissionCatalog
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.gateway.protocol.SendMessageRequest
import com.catmoecircle.areyouok.gateway.protocol.ServerEvent
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Topics
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import com.catmoecircle.areyouok.gateway.protocol.bool
import com.catmoecircle.areyouok.gateway.protocol.eventKind
import com.catmoecircle.areyouok.gateway.protocol.long
import com.catmoecircle.areyouok.gateway.protocol.string
import com.catmoecircle.areyouok.gateway.protocol.withModelSelection
import com.catmoecircle.areyouok.gateway.protocol.withPermissionPreset
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.GatewayException
import com.catmoecircle.areyouok.gateway.transport.GatewayTransport
import com.catmoecircle.areyouok.gateway.transport.ReconnectPolicy
import com.catmoecircle.areyouok.gateway.transport.StreamUpdate
import com.catmoecircle.areyouok.gateway.transport.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.ListSerializer

/**
 * dsh-gateway 客户端状态机。
 *
 * - 连接生命周期：CONNECTING → CONNECTED（REST 自举 + WS 长连接）→ RECONNECTING/ERROR；
 * - 数据：workspaces / sessions / messages / pending interventions 全部以 StateFlow 暴露；
 * - 事件应用：`message.delta` 合并、`intervention.resolved` 撤卡片、`resync` 用 REST 补齐；
 * - 本机操作（send/interrupt/answer）为挂起函数，UI 自己决定并发；回答成功即从 pending 移除。
 */
class GatewayRepository(
    private val transport: GatewayTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _selectedWorkspaceId = MutableStateFlow<String?>(null)
    val selectedWorkspaceId: StateFlow<String?> = _selectedWorkspaceId.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pendingInterventions = MutableStateFlow<List<Intervention>>(emptyList())
    val pendingInterventions: StateFlow<List<Intervention>> = _pendingInterventions.asStateFlow()

    private val _sessionRunning = MutableStateFlow(false)
    val sessionRunning: StateFlow<Boolean> = _sessionRunning.asStateFlow()

    /** 当前会话在网关里还有更早的历史（分页）；滚动到顶时加载上一页。 */
    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    /** 正在加载更早的历史（UI 显示顶部 loading 行，并防止重复请求）。 */
    private val _loadingOlderMessages = MutableStateFlow(false)
    val loadingOlderMessages: StateFlow<Boolean> = _loadingOlderMessages.asStateFlow()

    /** DSH 模型目录；网关未实现/解析失败时是空目录（UI 隐藏模型入口）。 */
    private val _modelCatalog = MutableStateFlow(ModelCatalog())
    val modelCatalog: StateFlow<ModelCatalog> = _modelCatalog.asStateFlow()

    /** 本机沙箱权限目录；网关未实现该端点时是空目录（UI 隐藏权限入口）。 */
    private val _permissionCatalog = MutableStateFlow(PermissionCatalog())
    val permissionCatalog: StateFlow<PermissionCatalog> = _permissionCatalog.asStateFlow()

    private val _notices = MutableSharedFlow<GatewayNotice>(extraBufferCapacity = 32)
    val notices: SharedFlow<GatewayNotice> = _notices.asSharedFlow()

    private val _sessionCompletions = MutableSharedFlow<SessionCompletion>(extraBufferCapacity = 16)

    /**
     * 会话从运行中落到空闲的完成事件（全局流里所有会话都会发）。
     *
     * 只在「观察过 running=true」之后才发：App 启动/重连时 REST 里已经是 false 的
     * 会话不会补发「任务完成」，避免一打开 App 就弹一堆过期提醒。
     */
    val sessionCompletions: SharedFlow<SessionCompletion> = _sessionCompletions.asSharedFlow()

    /**
     * 最近一次观察到的运行中会话（含未选中会话，靠 global 订阅的 `session.status` 维护）。
     *
     * 用它识别 true → false 的瞬间；`TURN_END` 与 `session.status` 可能同时到达，
     * 先到的那条把状态清掉，后到的不会重复发完成事件。
     */
    private val runningSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 最近一次 `ready` 的 seq 边界。
     *
     * WS 连上后会补发 `lastAppliedSeq` 之后的历史事件（seq ≤ ready.seq），其中的
     * 「运行 → 空闲」是过去的一轮，不能再弹「任务完成」；只有 seq 超过这个边界的
     * 新事件才算实时完成。
     */
    @Volatile
    private var replayBoundary: Long? = null

    private val assembler = MessageAssembler()

    /**
     * 已作答提问卡片（按会话缓存，LRU 上限）。
     *
     * 切会话/重载历史时时间线由 REST 历史整体重建，而服务端历史里没有问答记录；
     * 缓存一份并在重建后按 seq 锚点插回，用户切走再回来仍能看到选了哪些选项。
     */
    private val answeredQuestions = object : LinkedHashMap<String, List<AnsweredQuestionCard>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<AnsweredQuestionCard>>?,
        ): Boolean = size > MAX_ANSWERED_QUESTION_SESSIONS
    }

    private var connectionJob: Job? = null
    private var historyJob: Job? = null

    /** 已加载历史里最小的消息 seq（`beforeSeq` 分页游标）；空列表时为 null。 */
    private var oldestSeq: Long? = null

    @Volatile
    private var lastEndpoint: GatewayEndpoint? = null

    /** WS 事件去重水位（由事件 seq 推进；`ready.seq` 只用于展示，不参与去重）。 */
    private var lastAppliedSeq: Long = 0L

    /**
     * 按需取一张图的预览字节（不落盘）。
     *
     * 走当前网关连接：相对路径由 [GatewayApi.fetchImage] 拼成 `/v1/sessions/…`
     * 并带设备令牌；失败返回 null，UI 显示「无法预览」而不是崩掉。
     */
    suspend fun fetchImage(url: String): ByteArray? {
        if (_connection.value.endpoint == null) return null
        return try {
            transport.api.fetchImage(url)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 连接生命周期

    fun connect(endpoint: GatewayEndpoint) {
        val sameServer = lastEndpoint?.serverId == endpoint.serverId
        if (!sameServer) lastAppliedSeq = 0L
        connectionJob?.cancel()
        connectionJob = null
        transport.shutdown()
        lastEndpoint = endpoint
        _connection.value = ConnectionState(
            phase = ConnectionPhase.CONNECTING,
            endpoint = endpoint,
            name = endpoint.name,
        )
        // `global` 订阅会收到所有会话的事件：别的会话里的审批/提问也能及时标记到侧边栏。
        transport.subscribe(Topics.GLOBAL)
        connectionJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    bootstrap()
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    attempt++
                    val message = error.toUserMessage()
                    if (attempt >= MAX_BOOTSTRAP_ATTEMPTS) {
                        _connection.value = _connection.value.copy(
                            phase = ConnectionPhase.ERROR,
                            error = message,
                            attempt = attempt,
                            lastErrorAt = clock(),
                        )
                        emitNotice(NoticeLevel.ERROR, "连接失败：$message")
                        return@launch
                    }
                    _connection.value = _connection.value.copy(
                        phase = ConnectionPhase.RECONNECTING,
                        error = message,
                        attempt = attempt,
                        lastErrorAt = clock(),
                    )
                    emitNotice(NoticeLevel.WARN, "连接失败：$message，正在重试")
                    delay(ReconnectPolicy.delayMs(attempt))
                }
            }
            try {
                transport.stream(lastAppliedSeq).collect { update ->
                    handleStreamUpdate(update)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _connection.value = _connection.value.copy(
                    phase = ConnectionPhase.ERROR,
                    error = error.toUserMessage(),
                    lastErrorAt = clock(),
                )
            }
        }
    }

    fun reconnect() {
        val endpoint = lastEndpoint ?: _connection.value.endpoint ?: return
        connect(endpoint)
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        historyJob?.cancel()
        transport.shutdown()
        _connection.value = ConnectionState(
            phase = ConnectionPhase.IDLE,
            endpoint = lastEndpoint,
            name = lastEndpoint?.name,
        )
    }

    /** 主动拉一次全量状态（下拉刷新/从错误恢复）。 */
    fun refresh() {
        scope.launch {
            runCatching { reloadSnapshot() }
                .onFailure { emitNotice(NoticeLevel.WARN, "刷新失败：${it.toUserMessage()}") }
        }
    }

    private suspend fun bootstrap() {
        // /v1/health 在公网返回 403（§6.1），但带 Bearer 的其他接口可用，因此不能因它中断。
        val health = try {
            transport.api.health()
        } catch (error: GatewayException) {
            if (error.isForbiddenSource) null else throw error
        }
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.CONNECTED,
            name = health?.name ?: _connection.value.name,
            lan = health?.lan ?: _connection.value.lan,
            serverVersion = health?.dsh ?: _connection.value.serverVersion,
            error = null,
        )
        reloadSnapshot()
        _connection.value = _connection.value.copy(phase = ConnectionPhase.CONNECTED)
    }

    private suspend fun reloadSnapshot() {
        runCatching { transport.api.workspaces() }.onSuccess { _workspaces.value = it }
        val workspaceId = _selectedWorkspaceId.value
        runCatching { transport.api.sessions(workspaceId) }.onSuccess { rawSessions ->
            val sessions = normalizeSessions(rawSessions)
            _sessions.value = sessions
            // 只补记「正在运行」的会话：重连后它结束时仍能提醒；已经是 false 的
            // 会话故意不补，避免把连接前的旧结束当成刚完成。
            sessions.forEach { summary ->
                if (summary.running) runningSessions.add(summary.id)
            }
            val selected = _selectedSessionId.value
            if (selected == null || sessions.none { it.id == selected }) {
                sessions.firstOrNull()?.let { selectSessionInternal(it.id) } ?: selectSessionInternal(null)
            }
        }
        runCatching { transport.api.interventions() }.onSuccess { _pendingInterventions.value = it }
        // 两个目录都是「有更好、没有也能用」：失败静默，绝不让连接自举失败。
        runCatching { transport.api.models() }.onSuccess { _modelCatalog.value = it }
        runCatching { transport.api.permissionCatalog() }.onSuccess { _permissionCatalog.value = it }
    }

    private suspend fun handleStreamUpdate(update: StreamUpdate) {
        when (update) {
            is StreamUpdate.Ready -> {
                // ready.seq 之前的事件都是补发：完成提醒只认真实时的新事件。
                replayBoundary = update.frame.seq
                update.frame.snapshot?.let { snapshot ->
                    if (snapshot.workspaces.isNotEmpty()) _workspaces.value = snapshot.workspaces
                    if (snapshot.sessions.isNotEmpty()) _sessions.value = normalizeSessions(snapshot.sessions)
                    _pendingInterventions.value = snapshot.pending
                }
                _connection.value = _connection.value.copy(
                    phase = ConnectionPhase.CONNECTED,
                    serverSeq = maxOf(_connection.value.serverSeq, update.frame.seq),
                    attempt = 0,
                    error = null,
                )
                if (_selectedSessionId.value == null) {
                    _sessions.value.firstOrNull()?.let { selectSessionInternal(it.id) }
                }
            }

            is StreamUpdate.Event -> {
                val seq = update.frame.seq
                if (seq != null && seq <= lastAppliedSeq) return
                applyEvent(update.frame)
                if (seq != null) {
                    lastAppliedSeq = seq
                    _connection.value = _connection.value.copy(
                        serverSeq = maxOf(_connection.value.serverSeq, seq),
                    )
                }
            }

            is StreamUpdate.Resynced -> {
                emitNotice(NoticeLevel.WARN, "连接出现缺口，正在重新同步")
                // 先把水位记下来再清零：`sinceSeq` 必须是「我们真正看到过的最后一条」，
                // 否则会退化成「把整个 ring 重新回放一遍」（旧实现的补齐串味就在这里）。
                val since = lastAppliedSeq
                lastAppliedSeq = 0L
                catchUpAfterResync(since)
            }

            is StreamUpdate.ProtocolError -> emitNotice(
                NoticeLevel.WARN,
                "网关错误 ${update.code}：${update.message}",
            )

            is StreamUpdate.Disconnected -> {
                if (_connection.value.phase == ConnectionPhase.ERROR) return
                _connection.value = _connection.value.copy(
                    phase = ConnectionPhase.RECONNECTING,
                    attempt = update.attempt,
                    error = update.cause?.toUserMessage(),
                )
            }
        }
    }

    /** `resync` 后用 `/v1/events?sinceSeq=` 补发；缺口太大则整页刷新。 */
    private suspend fun catchUpAfterResync(since: Long) {
        val page = runCatching { transport.api.events(since.takeIf { it > 0L }) }.getOrNull()
        if (page != null && !page.gap && page.events.isNotEmpty()) {
            page.events.forEach { event ->
                val seq = event.seq
                if (seq != null && seq <= lastAppliedSeq) return@forEach
                applyEvent(event.toEventFrame())
                if (seq != null) lastAppliedSeq = seq
            }
            page.seq?.let { seq -> _connection.value = _connection.value.copy(serverSeq = seq) }
        } else {
            lastAppliedSeq = 0L
            runCatching { reloadSnapshot() }
        }
    }

    private fun ServerEvent.toEventFrame(): EventFrame = EventFrame(
        seq = seq,
        id = id,
        topic = topic,
        kind = kind,
        data = data,
    )

    // ------------------------------------------------------------ 事件应用    // ------------------------------------------------------------ 事件应用

    private fun applyEvent(frame: EventFrame) {
        val data = frame.data
        val sessionId: String? = data.string("sessionId")
            ?: data.string("agentId")
            ?: Topics.sessionIdOf(frame.topic)

        when (frame.eventKind()) {
            EventKind.SESSION_STATUS -> {
                updateSessionRunning(sessionId, data.bool("running") ?: false, frame.seq)
            }

            EventKind.SESSION_UPDATED -> {
                val summary = decodeSessionSummary(data) ?: return
                upsertSession(summary)
            }

            EventKind.MESSAGE_USER -> {
                val message = decodeMessage(data) ?: return
                if (!isSelected(sessionId)) return
                // DSH 会把运行时上下文 / system-reminder / 后台任务完成通知等机器内容
                // 以 user 消息落盘。按 `source.kind` 分类：真人消息才进用户气泡，
                // 系统通知进灰色提示行，纯上下文直接丢弃（详见 MessageMapping）。
                val chats = message.toTimelineMessages()
                if (chats.isEmpty()) return
                val only = chats.singleOrNull()
                if (only != null && only.role == ChatRole.SYSTEM) {
                    assembler.appendContext(message, only.meta)
                } else {
                    assembler.appendFull(message)
                }
                publishMessages()
            }

            EventKind.MESSAGE_ASSISTANT -> {
                val message = decodeMessage(data) ?: return
                if (!isSelected(sessionId)) return
                assembler.appendFull(message)
                publishMessages()
            }

            EventKind.MESSAGE_DELTA -> {
                if (!isSelected(sessionId)) return
                val nested = data["message"] as? JsonObject
                val text = data.string("text") ?: data.string("delta") ?: nested?.string("text")
                if (text.isNullOrEmpty()) return
                val messageId = data.string("messageId") ?: nested?.string("id")
                // 网关把流式增量分成 text / reasoning 两类（kind），思考链单独成条，
                // 不能混进 assistant 正文。
                val kind = data.string("kind")
                // 注意：事件 seq ≠ 消息 seq，这里只按 messageId 归并，避免污染历史去重键。
                assembler.appendDelta(text, messageId, kind = kind)
                publishMessages()
            }

            EventKind.TURN_START -> updateSessionRunning(sessionId, true, frame.seq)

            EventKind.TURN_END -> {
                updateSessionRunning(sessionId, false, frame.seq)
                if (isSelected(sessionId)) {
                    assembler.finishStreaming()
                    publishMessages()
                }
            }

            EventKind.TOOL_CALL -> {
                if (!isSelected(sessionId)) return
                assembler.appendTool(
                    toolName = data.string("toolName") ?: data.string("name") ?: data.string("tool"),
                    callId = data.string("callId") ?: data.string("id"),
                    summary = data.string("summary") ?: data.string("title"),
                    seq = data.long("seq"),
                    arguments = data.string("arguments"),
                    running = true,
                )
                publishMessages()
            }

            EventKind.TOOL_RESULT -> {
                if (!isSelected(sessionId)) return
                assembler.appendTool(
                    toolName = data.string("toolName") ?: data.string("name"),
                    callId = data.string("callId") ?: data.string("id"),
                    summary = data.string("summary") ?: data.string("text") ?: data.string("result"),
                    seq = data.long("seq"),
                    mergeWithPending = true,
                    running = false,
                    images = decodeImages(data),
                )
                publishMessages()
            }

            EventKind.APPROVAL_REQUEST -> addIntervention(
                Intervention.fromEvent(frame.id, sessionId, Intervention.KIND_APPROVAL, data),
            )

            EventKind.QUESTION_REQUEST -> addIntervention(
                Intervention.fromEvent(frame.id, sessionId, Intervention.KIND_QUESTION, data),
            )

            EventKind.INTERVENTION_RESOLVED -> {
                val eventId = data.string("eventId") ?: frame.id ?: return
                removeIntervention(eventId)
                val by = data.string("by")
                if (by != null && by != "this") {
                    emitNotice(NoticeLevel.INFO, "该请求已在其他设备处理")
                }
            }

            EventKind.WORKSPACE_UPDATED -> {
                val workspace = runCatching {
                    GatewayJson.json.decodeFromJsonElement(Workspace.serializer(), data)
                }.getOrNull() ?: return
                if (workspace.key.isNotBlank()) {
                    val current = _workspaces.value
                    val index = current.indexOfFirst { it.key == workspace.key }
                    _workspaces.value = if (index >= 0) {
                        current.toMutableList().also { it[index] = workspace }
                    } else {
                        current + workspace
                    }
                }
            }

            EventKind.UNKNOWN -> Unit
        }
    }

    private fun decodeMessage(data: JsonObject): Message? {
        val source = (data["message"] as? JsonObject) ?: data
        val decoded = runCatching {
            GatewayJson.json.decodeFromJsonElement(Message.serializer(), source)
        }.getOrNull() ?: return null
        val images = decodeImages(data)
        // 网关在事件顶层另带 `seq` / `time` / `role` / `text` / `source`：
        // `data.message` 只是 DSH 原始消息（没有 seq），只解码它会丢掉事件 seq。
        // 而 seq 是消息在时间线里的稳定身份：丢了以后重连补发 / 历史分页无法识别
        // 「这条我已经有了」，同一条消息会被再插一条（真机「重连后首条消息重复」）。
        val message = decoded.copy(
            seq = data.long("seq") ?: decoded.seq,
            time = data.long("time") ?: decoded.time,
            role = data.string("role") ?: decoded.role,
            text = decoded.text.ifBlank { data.string("text") ?: "" },
            images = if (images.isNotEmpty()) images else decoded.images,
            source = decoded.source ?: decodeSource(data["source"] as? JsonObject),
        )
        return message.takeIf { it.text.isNotBlank() || it.seq != null || it.images.isNotEmpty() }
    }

    /** 事件顶层的 `images`（网关从 DSH content 块里拆出来的图片引用）。 */
    private fun decodeImages(data: JsonObject): List<MessageImage> {
        val raw = data["images"] as? JsonArray ?: return emptyList()
        return runCatching {
            GatewayJson.json.decodeFromJsonElement(ListSerializer(MessageImage.serializer()), raw)
        }.getOrDefault(emptyList()).filter { it.isUsable }
    }

    private fun decodeSource(value: JsonObject?): MessageSource? = value?.let { source ->
        runCatching {
            GatewayJson.json.decodeFromJsonElement(MessageSource.serializer(), source)
        }.getOrNull()
    }

    private fun decodeSessionSummary(data: JsonObject): SessionSummary? {
        // WS `session.updated` 的形状是 `{sessionId, summary:{...}}`；
        // REST/快照则直接是 SessionSummary 本身。
        val source = (data["session"] as? JsonObject)
            ?: (data["summary"] as? JsonObject)
            ?: data
        val summary = runCatching {
            GatewayJson.json.decodeFromJsonElement(SessionSummary.serializer(), source)
        }.getOrNull()
        return summary?.takeIf { it.id.isNotBlank() }
    }

    private fun isSelected(sessionId: String?): Boolean =
        sessionId == null || sessionId == _selectedSessionId.value

    private fun publishMessages() {
        _messages.value = assembler.snapshot()
    }

    /**
     * 过滤掉没有主键的会话、去重，并按 DSH 桌面端的规则隐藏噪音会话：
     *
     * 1. 空 `id`：DSH 的 session 主键字段是 `sessionId`，历史上出现过解出的 `id`
     *    为空串的情况（空 key 在 LazyColumn 里会直接抛 `Key "" was already used` 闪退）。
     * 2. 子代理会话：不在侧边栏展示（REST 已过滤，这里兜底 WS 增量）。
     * 3. 空白会话（`blank`，还没发过任何消息）：只保留「当前选中的那一个」。
     *    DSH 每次新建会话都会留下一条空会话，桌面端同样只显示当前空白会话，
     *    否则侧边栏会出现一堆标题是 `session-…`、点进去什么都没有的行。
     */
    private fun normalizeSessions(list: List<SessionSummary>): List<SessionSummary> {
        val selected = _selectedSessionId.value
        return list
            .filter { it.id.isNotBlank() && !it.isSubagent }
            .distinctBy { it.id }
            .filter { !it.isBlank || it.id == selected }
    }

    private fun upsertSession(summary: SessionSummary) {
        // 空 sessionId 会在 LazyColumn 里产生重复 key（""），直接丢弃；同一会话只保留一行。
        if (summary.id.isBlank()) return
        // 子代理会话、以及「不是当前选中」的空白会话都不进侧边栏（见 normalizeSessions）。
        if (summary.isSubagent) {
            _sessions.value = _sessions.value.filterNot { it.id == summary.id }
            return
        }
        if (summary.isBlank && summary.id != _selectedSessionId.value) {
            _sessions.value = _sessions.value.filterNot { it.id == summary.id }
            return
        }
        val current = _sessions.value
        val index = current.indexOfFirst { it.id == summary.id }
        _sessions.value = if (index >= 0) {
            val existing = current[index]
            current.toMutableList().also { list ->
                list[index] = existing.copy(
                    title = summary.title ?: existing.title,
                    workspaceId = summary.workspaceId ?: existing.workspaceId,
                    running = summary.running,
                    pendingInput = summary.pendingInput,
                    updatedAt = summary.updatedAt ?: existing.updatedAt,
                    lastSeq = summary.lastSeq ?: existing.lastSeq,
                    cwd = summary.cwd ?: existing.cwd,
                    // DSH 的 blank 只会 true → false（第一个 turn 之后），不会回退。
                    blank = existing.blank && summary.blank,
                )
            }
        } else {
            listOf(summary) + current
        }
        if (summary.id == _selectedSessionId.value) {
            _sessionRunning.value = summary.running
        }
    }

    /** 第一次发消息后会话不再是空白会话，本地先翻转，避免切走后被隐藏。 */
    private fun markSessionPrompted(sessionId: String) {
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId && it.blank) it.copy(blank = false) else it
        }
    }

    private fun updateSessionRunning(sessionId: String?, running: Boolean, seq: Long? = null) {
        val id = sessionId ?: _selectedSessionId.value ?: return
        val wasRunning = runningSessions.contains(id)
        if (running) {
            runningSessions.add(id)
        } else {
            runningSessions.remove(id)
        }
        _sessions.value = _sessions.value.map { if (it.id == id) it.copy(running = running) else it }
        if (id == _selectedSessionId.value) {
            _sessionRunning.value = running
        }
        if (wasRunning && !running) emitCompletion(id, seq)
    }

    /** 运行中的会话落到空闲：通知层据此弹「任务完成」；子代理会话不提醒。 */
    private fun emitCompletion(sessionId: String, seq: Long?) {
        // 补发/重放的历史事件（seq ≤ ready.seq）只是重建状态，不是刚跑完。
        val boundary = replayBoundary
        if (seq != null && boundary != null && seq <= boundary) return
        val summary = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        if (summary.isSubagent) return
        _sessionCompletions.tryEmit(
            SessionCompletion(
                sessionId = sessionId,
                title = summary.displayTitle,
                time = clock(),
            ),
        )
    }

    private fun addIntervention(intervention: Intervention) {
        if (intervention.eventId.isBlank()) return
        if (_pendingInterventions.value.any { it.eventId == intervention.eventId }) return
        _pendingInterventions.value = _pendingInterventions.value + intervention
    }

    private fun removeIntervention(eventId: String) {
        _pendingInterventions.value = _pendingInterventions.value.filterNot { it.eventId == eventId }
    }

    // ------------------------------------------------------------ 列表与会话操作

    fun selectWorkspace(workspaceId: String?) {
        _selectedWorkspaceId.value = workspaceId
        scope.launch {
            runCatching { transport.api.sessions(workspaceId) }
                .onSuccess { rawSessions ->
                    val sessions = normalizeSessions(rawSessions)
                    _sessions.value = sessions
                    sessions.firstOrNull()?.let { selectSessionInternal(it.id) }
                        ?: selectSessionInternal(null)
                }
                .onFailure { emitNotice(NoticeLevel.WARN, "会话列表加载失败：${it.toUserMessage()}") }
        }
    }

    fun selectSession(sessionId: String?) {
        selectSessionInternal(sessionId)
        // 用户点进某个对话时补拉一次待处理事件：WS 断线/后台期间漏掉的审批、提问
        // 也能在进入对话后立刻显示，而不是等到下次重连的全量刷新。
        refreshPendingInterventions()
    }

    /** 主动拉一次待处理审批/提问（当前实现由选会话/刷新触发）。 */
    fun refreshPendingInterventions() {
        if (_connection.value.endpoint == null) return
        scope.launch {
            runCatching { transport.api.interventions() }
                .onSuccess { _pendingInterventions.value = it }
                .onFailure { emitNotice(NoticeLevel.WARN, "待处理事件刷新失败：${it.toUserMessage()}") }
        }
    }

    private fun selectSessionInternal(sessionId: String?) {
        if (sessionId == _selectedSessionId.value) return
        val previous = _selectedSessionId.value
        if (previous != null) transport.unsubscribe(Topics.session(previous))
        _selectedSessionId.value = sessionId
        // 之前选中的空白会话不再是「当前会话」，从侧边栏隐藏（DSH 桌面端同样只留当前空白会话）。
        _sessions.value = _sessions.value.filterNot { it.isBlank && it.id != sessionId }
        historyJob?.cancel()
        assembler.reset(emptyList())
        _messages.value = emptyList()
        oldestSeq = null
        _hasOlderMessages.value = false
        _loadingOlderMessages.value = false
        _sessionRunning.value = false
        if (sessionId == null) return
        transport.subscribe(Topics.session(sessionId))
        val running = _sessions.value.firstOrNull { it.id == sessionId }?.running ?: false
        // 打开时已经在跑：记下来，这样用户退出后它结束时也能收到「任务完成」。
        if (running) runningSessions.add(sessionId)
        _sessionRunning.value = running
        historyJob = scope.launch { loadHistory(sessionId) }
    }

    private suspend fun loadHistory(sessionId: String) {
        val page = runCatching {
            transport.api.messages(sessionId = sessionId, limit = HISTORY_LIMIT)
        }.getOrElse { error ->
            emitNotice(NoticeLevel.WARN, "历史加载失败：${error.toUserMessage()}")
            return
        }
        if (_selectedSessionId.value != sessionId) return
        assembler.reset(page.messages.toHistoryChatMessages())
        // 服务端历史里没有「已作答提问」：把本机缓存按锚点插回去，切走再回来不丢。
        restoreAnsweredQuestions(sessionId)
        publishMessages()
        // 记下最老一条消息的 seq 作为下一页游标；`hasMore` 决定顶部是否还能继续加载。
        oldestSeq = page.messages.mapNotNull { it.seq }.minOrNull()
        _hasOlderMessages.value = page.hasMore && oldestSeq != null
        runCatching { transport.api.sessionState(sessionId) }.getOrNull()?.let { state ->
            if (_selectedSessionId.value == sessionId) _sessionRunning.value = state.running
        }
        // 首屏只拿最近一页，DSH 桌面端却是「整轮」一起展示：一轮很长时（例如一次
        // 30 分钟的连续实现），展开的「工作步骤」会缺前半段，尤其是过程中的助手
        // 正文（Now I'll implement…）只在最早那几条消息里。这里把最后一段工作补齐。
        prefetchTurnStart(sessionId)
    }

    /**
     * 静默向前翻页，直到加载到「最后一段工作」的起点（user / system 边界）为止。
     *
     * 只补最后一段工作，不把整个会话历史都拉下来；[MAX_TURN_PREFETCH_PAGES] 兜底，
     * 避免异常会话无限翻页。
     */
    private suspend fun prefetchTurnStart(sessionId: String) {
        var pages = 0
        while (pages < MAX_TURN_PREFETCH_PAGES) {
            if (_selectedSessionId.value != sessionId) return
            if (!_hasOlderMessages.value) return
            val first = assembler.snapshot().firstOrNull() ?: return
            if (first.role == ChatRole.USER || first.role == ChatRole.SYSTEM) return
            if (!loadOlderPage(sessionId)) return
            pages++
        }
    }

    /**
     * 加载当前会话更早的一页历史（滚动到聊天顶部时调用）。
     *
     * 用 `beforeSeq = 已加载的最老 seq` 向网关要上一页，插到时间线头部；
     * 已存在的 seq/id 会去重，所以重连补发/重复触发不会出现重复消息。
     */
    fun loadOlderMessages() {
        val sessionId = _selectedSessionId.value ?: return
        if (!_hasOlderMessages.value || _loadingOlderMessages.value) return
        _loadingOlderMessages.value = true
        scope.launch {
            try {
                loadOlderPage(sessionId)
            } finally {
                _loadingOlderMessages.value = false
            }
        }
    }

    /** 拉一页更早历史并插到头部；返回是否真的插入了新消息。 */
    private suspend fun loadOlderPage(sessionId: String): Boolean {
        val before = oldestSeq ?: return false
        return try {
            val page = transport.api.messages(
                sessionId = sessionId,
                beforeSeq = before,
                limit = OLDER_HISTORY_LIMIT,
            )
            if (_selectedSessionId.value != sessionId) return false
            val added = assembler.prepend(page.messages.toHistoryChatMessages())
            if (added > 0) publishMessages()
            val older = page.messages.mapNotNull { it.seq }.minOrNull()
            if (older != null && (oldestSeq == null || older < oldestSeq!!)) oldestSeq = older
            _hasOlderMessages.value = page.hasMore && added > 0
            added > 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitNotice(NoticeLevel.WARN, "更早的消息加载失败：${error.toUserMessage()}")
            _hasOlderMessages.value = false
            false
        }
    }

    /**
     * 新建会话并选中。
     *
     * [workspaceId] 为空时落在当前选中的工作区；侧边栏没有选中工作区时
     * 显式落到 DSH 的默认工作区（没有默认工作区才交给网关默认行为）。
     * 侧边栏项目行右侧的「新对话」会显式传入该项目的 workspaceId，
     * 对应网关 `POST /v1/sessions {workspaceId}`，新会话直接归属该项目。
     */
    suspend fun createSession(
        title: String? = null,
        workspaceId: String? = null,
    ): String? {
        if (_connection.value.endpoint == null) {
            emitNotice(NoticeLevel.WARN, "尚未连接网关")
            return null
        }
        val targetWorkspaceId = workspaceId
            ?: _selectedWorkspaceId.value
            ?: _workspaces.value.firstOrNull { it.isDefaultWorkspace }?.key
        val response = try {
            transport.api.createSession(workspaceId = targetWorkspaceId, title = title)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitNotice(NoticeLevel.ERROR, "新建会话失败：${error.toUserMessage()}")
            return null
        }
        val sessionId = response.resolvedSessionId
        if (sessionId.isNullOrBlank()) {
            emitNotice(NoticeLevel.ERROR, "新建会话失败：响应缺少 sessionId")
            return null
        }
        // 先切到新会话再入库：新会话此刻还是「空白会话」，只有作为当前选中会话才可见。
        selectSessionInternal(sessionId)
        upsertSession(
            SessionSummary(
                id = sessionId,
                workspaceId = targetWorkspaceId,
                title = response.session?.title ?: "新会话",
                updatedAt = clock(),
                agentPreset = response.agentPreset,
                blank = true,
            ),
        )
        return sessionId
    }

    // ------------------------------------------------------------ 本机操作

    suspend fun sendMessage(text: String, mode: String = SendMessageRequest.MODE_QUEUE): Boolean {
        val sessionId = _selectedSessionId.value
        if (sessionId == null) {
            emitNotice(NoticeLevel.WARN, "请先选择会话")
            return false
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        assembler.appendLocalUser(trimmed, clock())
        publishMessages()
        return try {
            transport.api.sendMessage(sessionId, trimmed, mode)
            markSessionPrompted(sessionId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            assembler.removeLocalUser(trimmed)
            publishMessages()
            emitNotice(NoticeLevel.ERROR, "发送失败：${error.toUserMessage()}")
            false
        }
    }

    /**
     * 切换当前会话下一次 prompt 使用的模型 / 思考强度（DSH `session/selectModel`）。
     *
     * 成功时**乐观更新**本地 `sessions` 里该会话的 `projections.values.modelSelection.next`：
     * 输入框不必等下一次 `/v1/sessions` 刷新就能显示新选择；服务端投影到达后会覆盖。
     *
     * @return 是否已提交给网关；无选中会话、目录为空/不支持该 provider、接口失败都返回 false。
     */
    suspend fun selectModel(
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): Boolean {
        val sessionId = _selectedSessionId.value
        if (sessionId == null) {
            emitNotice(NoticeLevel.WARN, "请先选择会话")
            return false
        }
        if (provider.isBlank() || model.isBlank()) {
            emitNotice(NoticeLevel.WARN, "模型参数无效")
            return false
        }
        val catalog = _modelCatalog.value
        if (catalog.isEmpty) {
            emitNotice(NoticeLevel.WARN, "桌面 DSH 暂无可路由的模型")
            return false
        }
        if (!catalog.canRoute(provider)) {
            emitNotice(NoticeLevel.WARN, "桌面 DSH 不支持该模型提供方：$provider")
            return false
        }
        return try {
            transport.api.selectModel(sessionId, provider, model, reasoningEffort)
            applyModelSelection(sessionId, ModelSelection(provider, model, reasoningEffort))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitNotice(NoticeLevel.WARN, "切换模型失败：${error.toUserMessage()}")
            false
        }
    }

    /**
     * 切换当前会话的沙箱权限预设（`read-only` / `workspace-write` / `danger-full-access`）。
     *
     * 成功时乐观更新 `projections.values.permissions.currentValue`，失败发 WARN 通知并保持原值。
     */
    suspend fun selectPermission(preset: String): Boolean {
        val sessionId = _selectedSessionId.value
        if (sessionId == null) {
            emitNotice(NoticeLevel.WARN, "请先选择会话")
            return false
        }
        val value = preset.trim()
        if (value.isEmpty()) {
            emitNotice(NoticeLevel.WARN, "权限预设无效")
            return false
        }
        return try {
            transport.api.selectPermission(sessionId, value)
            applyPermissionPreset(sessionId, value)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitNotice(NoticeLevel.WARN, "切换权限失败：${error.toUserMessage()}")
            false
        }
    }

    /** 乐观更新：只替换目标会话的 `modelSelection.next`，其余字段（title/permissions…）保留。 */
    private fun applyModelSelection(sessionId: String, selection: ModelSelection) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) session.withModelSelection(selection) else session
        }
    }

    /** 乐观更新：只替换目标会话的 `permissions.currentValue`，其余字段保留。 */
    private fun applyPermissionPreset(sessionId: String, preset: String) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) session.withPermissionPreset(preset) else session
        }
    }

    suspend fun interrupt(): Boolean {
        val sessionId = _selectedSessionId.value ?: return false
        return try {
            transport.api.interrupt(sessionId)
            emitNotice(NoticeLevel.INFO, "已请求停止当前回合")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitNotice(NoticeLevel.ERROR, "停止失败：${error.toUserMessage()}")
            false
        }
    }

    /** 提交审批/提问答案的结果：区分「网关接受」与「别的设备已经处理」。 */
    private enum class AnswerOutcome { ACCEPTED, ALREADY_RESOLVED, FAILED }

    suspend fun answer(eventId: String, value: JsonElement): Boolean =
        submitAnswer(eventId, value) != AnswerOutcome.FAILED

    /**
     * 提交一次审批/提问答案。
     *
     * 404/409 表示这次请求已经被别的设备处理：本地待处理事件照样移除，但答案没有被采纳，
     * 调用方不能把它当成「已生效的回答」（提问卡片据此不补记录）。
     */
    private suspend fun submitAnswer(eventId: String, value: JsonElement): AnswerOutcome {
        if (eventId.isBlank()) return AnswerOutcome.FAILED
        return try {
            transport.api.answerIntervention(eventId, value)
            removeIntervention(eventId)
            AnswerOutcome.ACCEPTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GatewayException) {
            if (error.isNotFound || error.isConflict) {
                removeIntervention(eventId)
                emitNotice(NoticeLevel.INFO, "该请求已在其他设备处理")
                AnswerOutcome.ALREADY_RESOLVED
            } else {
                emitNotice(NoticeLevel.ERROR, "提交失败：${error.userMessage()}")
                AnswerOutcome.FAILED
            }
        } catch (error: Throwable) {
            emitNotice(NoticeLevel.ERROR, "提交失败：${error.toUserMessage()}")
            AnswerOutcome.FAILED
        }
    }

    suspend fun allowOnce(eventId: String): Boolean =
        answer(eventId, JsonPrimitive(InterventionAnswer.ALLOWED_ONCE))

    suspend fun reject(eventId: String): Boolean =
        answer(eventId, JsonPrimitive(InterventionAnswer.REJECTED))

    /**
     * 提交提问答案批（每个问题一项，`custom` 为自定义回复）。
     *
     * [eventId] 必须是 waterfall 的真 `eventId`（不是 WS 事件帧的 `ev-<seq>`），
     * 否则网关 404、桌面端弹窗不会消失（见 `Intervention.fromEvent`）。
     */
    suspend fun answerQuestion(eventId: String, answers: List<QuestionAnswer>): Boolean {
        // 先取卡片再提交：提交成功后 repository 会把待处理事件移除，题目就没了。
        val intervention = _pendingInterventions.value.firstOrNull { it.eventId == eventId }
        return when (submitAnswer(eventId, InterventionAnswer.question(answers))) {
            // 只有网关真正接受时才补卡片：别的设备已处理时，这次选择并没有生效。
            AnswerOutcome.ACCEPTED -> {
                recordAnsweredQuestion(intervention, answers)
                true
            }
            // 本地待处理事件已经移除，交互层面算成功（只是不记卡片）。
            AnswerOutcome.ALREADY_RESOLVED -> true
            AnswerOutcome.FAILED -> false
        }
    }

    /**
     * 把「已作答的提问」补进当前对话时间线（对齐桌面端：选项与答案在聊天里可见）。
     *
     * 只补当前会话的：别的对话里的提问不该出现在这里。同一次提问只记一条。
     */
    private fun recordAnsweredQuestion(intervention: Intervention?, answers: List<QuestionAnswer>) {
        val card = intervention ?: return
        if (!card.isQuestion) return
        val questions = card.questions
        if (questions.isEmpty() || answers.isEmpty()) return
        val sessionId = card.sessionId
        if (sessionId != null && sessionId != _selectedSessionId.value) return
        val id = "question-${card.eventId}"
        // 锚点取「作答前时间线里最大的消息 seq」：重建后插到第一条 seq 更大的消息之前，
        // 后续助手回复仍在卡片后面。
        val afterSeq = assembler.snapshot().mapNotNull { it.seq }.maxOrNull()
        val time = clock()
        // 先给正在流式的助手消息收尾：卡片之后到达的增量必须另起一条，
        // 否则后续正文会继续长在卡片上方，时间线顺序就错了。
        assembler.finishStreaming()
        if (assembler.appendAnsweredQuestion(questions, answers, id = id, time = time)) {
            publishMessages()
            val sessionId = card.sessionId ?: _selectedSessionId.value ?: return
            val cached = answeredQuestions[sessionId].orEmpty().filterNot { it.id == id }
            answeredQuestions[sessionId] =
                (cached + AnsweredQuestionCard(id, questions, answers, time, afterSeq))
                    .takeLast(MAX_ANSWERED_QUESTIONS_PER_SESSION)
        }
    }

    /** 历史重建后把本机缓存的已作答提问插回时间线（位置按作答时的 seq 锚点）。 */
    private fun restoreAnsweredQuestions(sessionId: String) {
        val cards = answeredQuestions[sessionId] ?: return
        for (card in cards) {
            assembler.insertAnsweredQuestion(card.toChatMessage(), card.afterSeq)
        }
    }

    /** 简化版本：只带选项 label；同样在成功后补时间线卡片。 */
    suspend fun answerQuestion(eventId: String, answers: Map<String, List<String>>): Boolean =
        answerQuestion(eventId, answers.map { (id, selected) -> QuestionAnswer(id = id, selected = selected) })

    fun close() {
        disconnect()
        runCatching { transport.shutdown() }
    }

    private fun emitNotice(level: NoticeLevel, text: String) {
        _notices.tryEmit(GatewayNotice(level = level, text = text, at = clock()))
    }

    companion object {
        /** 首屏历史条数（最近一页）。 */
        const val HISTORY_LIMIT = 100

        /** 向上翻页时每次加载的更早历史条数。 */
        const val OLDER_HISTORY_LIMIT = 100

        /**
         * 打开会话时静默向前翻页，补齐「最后一段工作」的起点；最多翻这么多页，
         * 防止异常会话（没有 user 边界）无限请求。
         */
        private const val MAX_TURN_PREFETCH_PAGES = 20

        /** 首连（REST 自举）最多重试次数，之后转入 ERROR 等用户手动重连。 */
        private const val MAX_BOOTSTRAP_ATTEMPTS = 3

        /** 已作答提问卡片的缓存上限：最多记多少个会话、每个会话多少条。 */
        private const val MAX_ANSWERED_QUESTION_SESSIONS = 16
        private const val MAX_ANSWERED_QUESTIONS_PER_SESSION = 20

    }
}
