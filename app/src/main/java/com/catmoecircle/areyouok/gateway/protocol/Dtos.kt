package com.catmoecircle.areyouok.gateway.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `/v1` REST 的 DTO。
 *
 * 契约（Lead 定稿，见 `docs/dsh-gateway-impl-plan.md` §4 与交接文档 §6）：
 * - 成功 `{ok:true, ...}`；失败 `{ok:false, error:{code,message}}`，HTTP 状态同时正确；
 * - 所有字段都尽量给默认值并忽略未知字段：网关仍在演进，缺字段不应该让客户端整页崩掉。
 */

@Serializable
data class ErrorDetail(
    val code: String = "unknown",
    val message: String = "",
)

@Serializable
data class ErrorBody(
    val ok: Boolean = false,
    val error: ErrorDetail? = null,
)

/** GET /v1/health（局域网免认证，公网 403）。 */
@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val name: String? = null,
    val v: Int? = null,
    val dsh: String? = null,
    val lan: Boolean = false,
    val paired: Boolean = false,
    val uptimeMs: Long? = null,
)

/** 工作区/项目（DSH 原样形状：主键是 `workspaceId`，展示名在 `title`/`path`）。 */
@Serializable
data class Workspace(
    val id: String = "",
    val name: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    @SerialName("workspaceId") val workspaceId: String? = null,
    val path: String? = null,
    val kind: String? = null,
    /** DSH `workspace/follow` baseline 里的项目成员会话（侧边栏二级列表的数据源）。 */
    val sessionIds: List<String> = emptyList(),
) {
    /** 稳定主键：DSH 用 `workspaceId`，兼容旧 mock 的 `id`。 */
    val key: String
        get() = workspaceId?.takeIf { it.isNotBlank() } ?: id

    val displayName: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: (cwd ?: path)?.trimEnd('/', '\\')?.substringAfterLast('/')
            ?: workspaceId?.takeIf { it.isNotBlank() }
            ?: id

    val displayPath: String?
        get() = cwd ?: path

    /**
     * 是否为 DSH 首次启动自动创建的「默认工作区」。
     *
     * DSH 的自动标题固定是 `default-workspace`（见 `DEFAULT_WORKSPACE_DIRECTORY`），
     * 桌面端用 `workspaceDisplayTitle()` 把它按当前语言显示成「默认工作区」，
     * 其他标题一律原样显示；本应用同样把它的侧边栏项目标题显示成「默认工作区」。
     *
     * 判断与桌面端一致：只对 `title` 做精确匹配（用户改名成别的标题后就是一个普通项目）。
     */
    val isDefaultWorkspace: Boolean
        get() = title == DEFAULT_WORKSPACE_TITLE

    companion object {
        /** DSH `DEFAULT_WORKSPACE_DIRECTORY`：默认工作区的自动标题。 */
        const val DEFAULT_WORKSPACE_TITLE = "default-workspace"
    }
}

/** 会话摘要（REST `/v1/sessions` 与 WS snapshot 都是 DSH 原样形状：主键字段名是 `sessionId`）。 */
@Serializable
data class SessionSummary(
    @SerialName("sessionId") val id: String = "",
    val workspaceId: String? = null,
    val title: String? = null,
    val running: Boolean = false,
    val pendingInput: Boolean = false,
    val updatedAt: Long? = null,
    val lastSeq: Long? = null,
    @SerialName("createdAt") val createdAt: Long? = null,
    val agentPreset: String? = null,
    val cwd: String? = null,
    /** DSH：会话的折叠前缀里还没有任何 turn（一次消息都没发过）。 */
    val blank: Boolean = false,
    /** 子代理会话的父会话 id；普通会话为空。 */
    @SerialName("parentSessionId") val parentSessionId: String? = null,
    /** `subagent` 表示子代理会话。 */
    val origin: String? = null,
    /** DSH 投影：标题等展示字段在 `projections.values` 里，而不是顶层 `title`。 */
    val projections: SessionProjections? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: projections?.values?.title?.takeIf { it.isNotBlank() }
            ?: id.take(8)

    /**
     * 空白会话：还没发过消息的“新建后没用”的会话。
     *
     * DSH 桌面端据此把它们从侧边栏隐藏（只留当前打开的那一个），
     * 否则会残留一堆点进去没有任何内容的 `session-…` 行。
     * `sessionListMetadata.blank` 明确为 `false` 时覆盖顶层 `blank`。
     */
    val isBlank: Boolean
        get() = blank && projections?.values?.sessionListMetadata?.blank != false

    /** 子代理会话：侧边栏不展示（DSH 在父会话的 subagent 目录里展示）。 */
    val isSubagent: Boolean
        get() = !parentSessionId.isNullOrBlank() || origin == "subagent"

    /** 当前会话的模型选择：优先投影里的 `next`（已选未用），回退 `lastUsed`。 */
    val modelSelection: ModelSelection?
        get() = projections?.values?.modelSelection?.current

    /** 当前会话的权限预设（如 `danger-full-access`）；没有投影时为 null。 */
    val permissionPreset: String?
        get() = projections?.values?.permissions?.currentValue?.takeIf { it.isNotBlank() }
}

/** DSH 会话投影（只取 UI 需要的字段，其余忽略）。 */
@Serializable
data class SessionProjections(
    val kind: String? = null,
    val asOfSeq: Long? = null,
    val values: SessionProjectionValues? = null,
)

@Serializable
data class SessionProjectionValues(
    val title: String? = null,
    /** 会话列表元数据：是否空白、最后一次用户提问时间。 */
    val sessionListMetadata: SessionListMetadata? = null,
    /**
     * 模型选择投影：`{"lastUsed":{…}|null,"next":{…}|null}`。
     *
     * `next` 是「下一次 prompt 生效」的选择（用户刚切完模型就是这个），
     * `lastUsed` 是上一次真正跑过 turn 的选择；桌面端输入框展示的是 `next`。
     */
    val modelSelection: SessionModelSelection? = null,
    /** 权限投影：`{"currentValue":"danger-full-access"}`。 */
    val permissions: SessionPermissions? = null,
)

/** DSH `sessionListMetadata` 投影：`blank` 为列表侧的空白标记。 */
@Serializable
data class SessionListMetadata(
    val blank: Boolean? = null,
    val lastPromptAt: Long? = null,
)

/** DSH `modelSelection` 投影。 */
@Serializable
data class SessionModelSelection(
    val lastUsed: ModelSelection? = null,
    val next: ModelSelection? = null,
) {
    /** UI 展示用的「当前选择」：优先 `next`（已选未用），否则 `lastUsed`。 */
    val current: ModelSelection?
        get() = next ?: lastUsed
}

/** DSH `permissions` 投影：当前会话的权限预设。 */
@Serializable
data class SessionPermissions(
    val currentValue: String = "",
)

/** GET /v1/sessions/{id}/state：`state` 是 DSH 原样对象，客户端忽略。 */
@Serializable
data class SessionState(
    val ok: Boolean = true,
    val sessionId: String? = null,
    val running: Boolean = false,
    val pendingInput: Boolean = false,
    val state: JsonElement? = null,
)

/** 历史的单条消息（REST 与事件共用形状）。 */
@Serializable
data class Message(
    val seq: Long? = null,
    val id: String? = null,
    val role: String = "assistant",
    val text: String = "",
    val time: Long? = null,
    val kind: String? = null,
    /** 历史消息里的思考链（网关从 reasoning 内容块单独拆出，与正文分开）。 */
    val reasoning: String? = null,
    /** DSH 用户消息来源：`user` 是真人输入，其它 kind 是机器注入（后台任务/上下文…）。 */
    val source: MessageSource? = null,
    /**
     * 消息里携带的图片（DSH `ImageAttachmentRef`）。
     *
     * 客户端只拿引用，不下载：真正显示时按需向网关要预览字节
     * （`GET /v1/sessions/{id}/attachments/{attachmentId}`）。
     */
    val images: List<MessageImage> = emptyList(),
    /** 消息携带的文件附件（用户随 prompt 一起发来的文件）。 */
    val files: List<MessageFile> = emptyList(),
)

/**
 * 一张已入库图片的引用（DSH `ImageAttachmentRef` 的客户端镜像）。
 *
 * `attachmentId / mediaType / width / height / bytes` 必须原样回传给网关，
 * 宿主会拿它们和存储里的字节做校验。
 */
@Serializable
data class MessageImage(
    val attachmentId: String = "",
    val mediaType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    val name: String? = null,
) {
    val isUsable: Boolean
        get() = attachmentId.isNotBlank() &&
            mediaType?.startsWith("image/") == true &&
            width != null && height != null && bytes != null
}

/**
 * 一个已入库文件附件的引用（DSH `FileAttachmentRef` 的客户端镜像）。
 *
 * 文件内容由模型按宿主机上的只读路径自行读取；客户端只用它展示文件名和大小。
 */
@Serializable
data class MessageFile(
    val attachmentId: String = "",
    val name: String = "",
    val bytes: Long? = null,
) {
    val isUsable: Boolean get() = attachmentId.isNotBlank() && name.isNotBlank()
}

/**
 * DSH `user/message.data.source`。
 *
 * `kind` 是判别字段：真人输入是 `user`；`tool-jobs`（后台任务完成通知）、
 * `subagent-settled`、`agent-message`、`runtime-context`、`skill-catalog`、
 * `time-context`、`user-approval`、`compact-checkpoint` 都是机器生成内容。
 */
@Serializable
data class MessageSource(
    val kind: String? = null,
    val form: String? = null,
    val summary: String? = null,
    val senderSessionId: String? = null,
    val rpcId: String? = null,
)

@Serializable
data class MessagesPage(
    val ok: Boolean = true,
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
    val cursor: Long? = null,
)

/**
 * 待处理的审批 / 提问。
 *
 * REST 形状：`{eventId,sessionId,kind,createdAt,data}`
 * WS `approval.request` 的 data 是扁平对象，构造时直接塞进 [data]。
 */
@Serializable
data class Intervention(
    val eventId: String = "",
    val sessionId: String? = null,
    /** `approval` 或 `question`。 */
    val kind: String = KIND_APPROVAL,
    val createdAt: Long? = null,
    val data: JsonObject? = null,
    val status: String = STATUS_PENDING,
) {
    /** REST 的 kind 形如 `approval.request` / `question.request`，WS 事件形如 `approval` / `question`。 */
    val isQuestion: Boolean get() = kind.startsWith("question", ignoreCase = true)
    val isApproval: Boolean get() = !isQuestion

    val toolName: String? get() = data?.string("toolName") ?: data?.string("tool")
    val callId: String? get() = data?.string("callId")
    val reason: String? get() = data?.string("reason") ?: data?.string("prompt")

    /**
     * 审批的展示文案。
     *
     * DSH 的 `request.displayReason` 是按语言分组的对象（`{en,zh,ja,…}`），
     * 例如「允许本次操作使用 workspace-write 权限：…」；没有时才退回原始 [reason]。
     * 本应用界面是中文，优先取 `zh`，再取 `en`，最后取第一个非空字符串。
     */
    val displayReason: String?
        get() {
            val request = data?.get("request") as? JsonObject
            val localized = (request?.get("displayReason") as? JsonObject)
                ?: (data?.get("displayReason") as? JsonObject)
            if (localized != null) {
                val preferred = sequenceOf("zh", "en")
                    .mapNotNull { key -> localized.string(key)?.takeIf { it.isNotBlank() } }
                    .firstOrNull()
                if (preferred != null) return preferred
                for (value in localized.values) {
                    if (value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
                        return value.content
                    }
                }
            }
            return (request?.string("displayReason") ?: data?.string("displayReason")) ?: reason
        }

    val questions: List<Question>
        get() {
            val array = (data?.get("questions") as? JsonArray)
                ?: (data?.get("request") as? JsonObject)?.get("questions") as? JsonArray
                ?: return emptyList()
            return array.mapNotNull { element ->
                runCatching { GatewayJson.json.decodeFromJsonElement(Question.serializer(), element) }
                    .getOrNull()
            }
        }

    companion object {
        const val KIND_APPROVAL = "approval"
        const val KIND_QUESTION = "question"
        const val STATUS_PENDING = "pending"

        /** 只保留属于 [sessionId] 的待处理事件：别的对话里的审批/提问不在当前对话里弹出。 */
        fun pendingForSession(interventions: List<Intervention>, sessionId: String?): List<Intervention> {
            val id = sessionId?.takeIf { it.isNotBlank() } ?: return emptyList()
            return interventions.filter { it.sessionId == id }
        }

        /** 有 pending 事件的会话 id 集合（侧边栏会话行显示橙色「!」标记）。 */
        fun pendingSessionIds(interventions: List<Intervention>): Set<String> =
            interventions.mapNotNull { it.sessionId?.takeIf { id -> id.isNotBlank() } }.toSet()

        /**
         * 由 WS 事件构造：事件 data 可能是扁平的，也可能把真正的负载放在 `data` 字段。
         *
         * [eventId] 是 WS 帧 id（网关给每条事件分配的 `ev-<seq>`），**不是** waterfall 的
         * `eventId`。回答审批/提问必须用 data 里的真 `eventId`，否则网关会 404
         * 「intervention is unknown or already settled」，桌面端弹窗不消失。
         */
        fun fromEvent(eventId: String?, sessionId: String?, kind: String?, data: JsonObject?): Intervention {
            val payload = (data?.get("data") as? JsonObject) ?: data
            val resolvedId = data?.string("eventId")?.takeIf { it.isNotBlank() }
                ?: payload?.string("eventId")?.takeIf { it.isNotBlank() }
                ?: eventId
                ?: ""
            val resolvedSession = sessionId
                ?: data?.string("sessionId")
                ?: payload?.string("sessionId")
            return Intervention(
                eventId = resolvedId,
                sessionId = resolvedSession,
                kind = kind ?: KIND_APPROVAL,
                createdAt = System.currentTimeMillis(),
                data = payload,
            )
        }
    }
}

/**
 * DSH `AskUserQuestionItem`（见 `dsh-user-questions/types`）。
 *
 * 主问题文案在 `question` 字段；`prompt`/`header` 只是兼容旧网关/mock 的兜底来源。
 */
@Serializable
data class Question(
    val id: String = "",
    val question: String? = null,
    val prompt: String? = null,
    val header: String? = null,
    val detail: String? = null,
    val multiSelect: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
) {
    /** 展示用的主问题文案（DSH `question` 优先）。 */
    val text: String
        get() = question?.takeIf { it.isNotBlank() }
            ?: prompt?.takeIf { it.isNotBlank() }
            ?: header?.takeIf { it.isNotBlank() }
            ?: ""
}

@Serializable
data class QuestionOption(
    val label: String = "",
    val description: String? = null,
)

/** 一个问题的回答（DSH `AskUserQuestionAnswerItem`）：`selected` 是选项 label，`custom` 是自定义回复。 */
@Serializable
data class QuestionAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

// ---------------------------------------------------------------- 模型目录

/** 思考强度选项（DSH `ModelCatalog.groups[].models[].reasoning.efforts[]`）。 */
@Serializable
data class ReasoningEffort(
    val id: String = "",
    val name: String? = null,
    val description: String? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: id
}

/** DSH 模型的可选思考强度（`reasoning` 字段）。 */
@Serializable
data class ModelReasoning(
    val efforts: List<ReasoningEffort> = emptyList(),
    /** 默认强度 id（如 `high`）；DSH 也可能不返回。 */
    val defaultEffort: String? = null,
) {
    val effortIds: List<String>
        get() = efforts.map { it.id }.filter { it.isNotBlank() }

    fun effort(id: String?): ReasoningEffort? = efforts.firstOrNull { it.id == id }
}

/**
 * 模型目录里的单个模型（`groups[].models[]`）。
 *
 * 旧网关的扁平形状（`providers[].models[]` / `models[]` / 裸数组）也复用这个类：
 * - 模型未自带 `provider` 时由所属 group/provider 补齐；
 * - wire 模型 id 优先取 [model]（旧形状），否则取 [id]（新形状）。
 */
@Serializable
data class ModelInfo(
    /** DSH 的模型 id（`session/selectModel` 的 `model` 字段）。 */
    val id: String = "",
    val provider: String? = null,
    /** 旧形状把 wire id 放在 `model` 里。 */
    val model: String? = null,
    val label: String? = null,
    val name: String? = null,
    val description: String? = null,
    val reasoning: ModelReasoning? = null,
    /** 旧形状：普通字符串数组的思考强度。 */
    val reasoningEfforts: List<String> = emptyList(),
) {
    val displayName: String
        get() = label?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: modelId

    /** 传给 `session/selectModel` 的 wire 模型 id。 */
    val modelId: String
        get() = model?.takeIf { it.isNotBlank() } ?: id

    /** UI 展示用的补充说明。 */
    val displayDescription: String?
        get() = description?.takeIf { it.isNotBlank() }

    /** 思考强度选项：新形状优先 `reasoning.efforts`，否则兼容旧的字符串数组。 */
    val efforts: List<ReasoningEffort>
        get() = reasoning?.efforts?.takeIf { it.isNotEmpty() }
            ?: reasoningEfforts.map { ReasoningEffort(id = it) }

    /** 默认思考强度 id。 */
    val defaultEffort: String?
        get() = reasoning?.defaultEffort?.takeIf { it.isNotBlank() }
}

/** 模型分组（DSH 按 provider 分组，`groups[].id` 就是 provider id）。 */
@Serializable
data class ModelGroup(
    val id: String = "",
    val name: String? = null,
    val models: List<ModelInfo> = emptyList(),
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: id
}

/** 目录里拉取失败的 provider（DSH `failures[]`）。 */
@Serializable
data class ModelFailure(
    val id: String = "",
    val name: String? = null,
    val message: String? = null,
)

/**
 * 一次模型选择。
 *
 * `catalog.default` 与 `projections.values.modelSelection.lastUsed|next` 共用这个形状。
 */
@Serializable
data class ModelSelection(
    val provider: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
) {
    val isBlank: Boolean
        get() = provider.isBlank() || model.isBlank()
}

/**
 * GET /v1/models 的 `catalog`（DSH `ModelCatalog`）。
 *
 * 真实形状：`{default:{provider,model,reasoningEffort?}|null,routableProviders:[…],groups:[…],failures:[]}`。
 */
@Serializable
data class ModelCatalog(
    val default: ModelSelection? = null,
    /** 当前可路由的 provider id（网关从 DSH 原样透传）。 */
    val routableProviders: List<String> = emptyList(),
    val groups: List<ModelGroup> = emptyList(),
    val failures: List<ModelFailure> = emptyList(),
) {
    /** 全部模型拍平（`provider` 用 group.id 补齐），UI 的下拉列表直接消费。 */
    val models: List<ModelInfo>
        get() = groups.flatMap { group ->
            group.models.map { model ->
                if (model.provider.isNullOrBlank()) model.copy(provider = group.id) else model
            }
        }

    /** 空目录：没有任何可选模型/默认选择/可路由 provider。 */
    val isEmpty: Boolean
        get() = models.isEmpty() && default == null && routableProviders.isEmpty()

    /** 是否可以把 [provider] 交给 DSH：目录没给白名单时按「不限制」处理。 */
    fun canRoute(provider: String): Boolean =
        routableProviders.isEmpty() || routableProviders.contains(provider)

    /** 按 (provider, wire model id) 找模型。 */
    fun model(provider: String, model: String): ModelInfo? =
        models.firstOrNull { it.provider == provider && it.modelId == model }

    /** 某个 provider 下的模型。 */
    fun groupModels(provider: String): List<ModelInfo> =
        models.filter { it.provider == provider }
}

/** 旧网关的 provider 分组形状（`catalog.providers[]`），只用于兼容解析。 */
@Serializable
data class ModelProvider(
    val id: String = "",
    val name: String? = null,
    val models: List<ModelInfo> = emptyList(),
)

/**
 * GET /v1/models 的响应信封。
 *
 * `catalog` 保留成 [JsonElement]：DSH 真实形状是 `{default,routableProviders,groups,failures}`，
 * 旧网关还可能是 `providers[]` / 扁平 `models[]` / 裸数组 / `null`，统一由 [catalog] 归一化。
 *（`catalog` 属性与同名函数在 Kotlin/JVM 上不冲突：属性是 getter，函数是方法。）
 */
@Serializable
data class ModelsResponse(
    val ok: Boolean = true,
    val catalog: JsonElement? = null,
) {
    /** 统一解析成 [ModelCatalog]；任何无法识别的形状都返回空目录，不抛异常。 */
    fun catalog(): ModelCatalog {
        val element = catalog ?: return ModelCatalog()
        return when (element) {
            is JsonArray -> legacyCatalog(
                legacyModels = element,
                providers = null,
                base = ModelCatalog(),
            )
            is JsonObject -> {
                val base = runCatching {
                    GatewayJson.json.decodeFromJsonElement(ModelCatalog.serializer(), element)
                }.getOrDefault(ModelCatalog())
                // 新形状（groups）优先；没有 groups 才尝试旧形状。
                if (base.groups.isNotEmpty()) return base
                legacyCatalog(
                    legacyModels = element["models"] as? JsonArray,
                    providers = element["providers"] as? JsonArray,
                    base = base,
                )
            }
            else -> ModelCatalog()
        }
    }

    private fun legacyCatalog(
        legacyModels: JsonArray?,
        providers: JsonArray?,
        base: ModelCatalog,
    ): ModelCatalog {
        val groups = buildList {
            providers.orEmpty().forEach { providerElement ->
                val provider = runCatching {
                    GatewayJson.json.decodeFromJsonElement(ModelProvider.serializer(), providerElement)
                }.getOrNull() ?: return@forEach
                val models = provider.models.map { model ->
                    if (model.provider.isNullOrBlank()) model.copy(provider = provider.id) else model
                }
                if (models.isNotEmpty()) {
                    add(ModelGroup(id = provider.id, name = provider.name, models = models))
                }
            }
            legacyModels?.let { array ->
                val models = decodeModels(array)
                if (models.isNotEmpty()) add(ModelGroup(models = models))
            }
        }
        return if (groups.isEmpty()) base else base.copy(groups = groups)
    }

    private fun decodeModels(array: JsonArray): List<ModelInfo> = array.mapNotNull { element ->
        runCatching { GatewayJson.json.decodeFromJsonElement(ModelInfo.serializer(), element) }.getOrNull()
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}

// ---------------------------------------------------------------- 权限目录

/** 权限预设选项（`GET /v1/permissions` → `catalog.options[]`）。 */
@Serializable
data class PermissionOption(
    val value: String = "",
    val name: String? = null,
    val description: String? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: value
}

/**
 * 权限目录：DSH 本机可用的沙箱权限预设。
 *
 * 实测本机 3 项：`read-only` / `workspace-write` / `danger-full-access`，
 * 默认 `workspace-write`。网关未实现该端点时客户端保持空目录，UI 隐藏权限入口。
 */
@Serializable
data class PermissionCatalog(
    val options: List<PermissionOption> = emptyList(),
    /**
     * 新建会话时默认授予的预设。
     *
     * 注意：DSH 这里给的是**选项对象数组**（`{"value","name"}`），不是字符串数组；
     * 写成 `List<String>` 会让整份目录解析失败、权限入口在真机上直接消失。
     */
    val defaultOptions: List<PermissionOption> = emptyList(),
    val defaultPreset: String? = null,
) {
    val isEmpty: Boolean
        get() = options.isEmpty()

    /** 默认预设的 wire 值（兼容想按字符串消费的调用方）。 */
    val defaultOptionValues: List<String>
        get() = defaultOptions.map { it.value }

    /**
     * 新建空白会话时权限菜单应勾选的档位（只用于 UI 展示，不写回网关）。
     *
     * 优先级：
     * 1. `defaultPreset`，但必须真实存在于 `options` 里（否则菜单无法勾选）；
     * 2. `options[1]`（第二档）；
     * 3. `options.firstOrNull()`；
     * 4. 空目录返回 null。
     *
     * 为什么第二档是兜底：DSH 新建会话默认授予 `workspace-write`，
     * 而 `/v1/permissions` 的选项顺序固定为 read-only / workspace-write /
     * danger-full-access，所以第二档就是 DSH 的默认预设；
     * 网关没给 `defaultPreset` 时用它，避免菜单打开后一格都不选。
     */
    val defaultSelectionValue: String?
        get() = defaultPreset?.takeIf { preset -> preset.isNotBlank() && option(preset) != null }
            ?: options.getOrNull(1)?.value
            ?: options.firstOrNull()?.value

    fun option(value: String): PermissionOption? =
        options.firstOrNull { it.value == value }

    /** 展示名：目录里有就用本地化名字，否则退回 wire 值。 */
    fun displayName(value: String): String =
        option(value)?.displayName ?: value
}

/** GET /v1/permissions 的响应信封 `{ok,catalog,error?}`。 */
@Serializable
data class PermissionCatalogResponse(
    val ok: Boolean = true,
    val catalog: PermissionCatalog? = null,
    val error: ErrorDetail? = null,
)

/**
 * 会话当前应展示的权限档位（输入框左下角权限控件的取值）。
 *
 * - 有 `projections.values.permissions.currentValue`：直接用（用户显式切过 / 跑过 turn）；
 * - 没有投影且是新建后还没发过消息的空白会话：DSH 此时不会写权限投影，
 *   回退到 [PermissionCatalog.defaultSelectionValue]，否则菜单打开后没有任何勾选、
 *   图标也退化成兜底的锁；
 * - 其他情况（老网关 / 非空白会话缺投影）：返回 null，交给 UI 原有兜底逻辑。
 */
fun SessionSummary.effectivePermissionPreset(catalog: PermissionCatalog): String? =
    projections?.values?.permissions?.currentValue?.takeIf { it.isNotBlank() }
        ?: if (blank) catalog.defaultSelectionValue else null

/** 网关命令执行结果摘要（DSH `CommandResult`）。 */
@Serializable
data class CommandResult(
    val kind: String = "",
    val text: String? = null,
)

/** POST /v1/sessions/{id}/permission 的响应：`{ok,sessionId,preset,result:{kind,text?}}`。 */
@Serializable
data class PermissionResult(
    val ok: Boolean = true,
    val sessionId: String? = null,
    val preset: String? = null,
    val result: CommandResult? = null,
)

// ---------------------------------------------------------------- 会话投影的乐观更新辅助

/**
 * 乐观更新：只替换 `projections.values.modelSelection.next`，
 * 其余投影字段（title / permissions / sessionListMetadata …）原样保留。
 */
fun SessionSummary.withModelSelection(selection: ModelSelection): SessionSummary = copy(
    projections = projections.withValues { values ->
        values.copy(
            modelSelection = (values.modelSelection ?: SessionModelSelection()).copy(next = selection),
        )
    },
)

/** 乐观更新：只替换 `projections.values.permissions.currentValue`，其余字段原样保留。 */
fun SessionSummary.withPermissionPreset(preset: String): SessionSummary = copy(
    projections = projections.withValues { values ->
        values.copy(
            permissions = (values.permissions ?: SessionPermissions()).copy(currentValue = preset),
        )
    },
)

private fun SessionProjections?.withValues(
    transform: (SessionProjectionValues) -> SessionProjectionValues,
): SessionProjections {
    val base = this ?: SessionProjections()
    return base.copy(values = transform(base.values ?: SessionProjectionValues()))
}

/** GET /v1/events?sinceSeq= 补发兜底（WS 不可用时的历史回放）。 */
@Serializable
data class ServerEvent(
    val id: String? = null,
    val seq: Long? = null,
    val ts: Long? = null,
    val topic: String? = null,
    val kind: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class EventsPage(
    val ok: Boolean = true,
    val seq: Long? = null,
    val gap: Boolean = false,
    val events: List<ServerEvent> = emptyList(),
)

// ---------------------------------------------------------------- 请求/响应体

@Serializable
data class PairRequest(
    val code: String,
    val deviceName: String,
    val deviceId: String,
)

@Serializable
data class PairResponse(
    val ok: Boolean = true,
    val deviceToken: String = "",
    val deviceId: String? = null,
    val expiresAt: Long? = null,
)

@Serializable
data class CreateSessionRequest(
    val workspaceId: String? = null,
    val cwd: String? = null,
    val title: String? = null,
)

@Serializable
data class SendMessageRequest(
    val text: String,
    val mode: String = MODE_QUEUE,
    /**
     * 已上传文件的回执 id（`POST /v1/sessions/{id}/files` 的返回值）。
     *
     * 网关把它们拼成 DSH prompt 的 `{type:'file', receiptId}` 内容块；null 时
     * 整个字段不出现在 JSON 里（`explicitNulls=false`），纯文本消息完全不变。
     */
    val files: List<String>? = null,
) {
    companion object {
        const val MODE_QUEUE = "queue"
        const val MODE_STEER = "steer"
    }
}

/** 客户端本地待上传的一个文件（不是线格式，只是 [SendMessageRequest.files] 的输入）。 */
class OutgoingAttachment(
    val name: String,
    val mimeType: String?,
    val bytes: ByteArray,
)

/** POST /v1/sessions/{id}/model 的请求体。 */
@Serializable
data class SelectModelRequest(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

/** POST /v1/sessions/{id}/permission 的请求体。 */
@Serializable
data class SelectPermissionRequest(
    val preset: String,
)

@Serializable
data class AnswerRequest(
    val value: JsonElement,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
    val sessionId: String? = null,
    val accepted: Boolean? = null,
)

/** POST /v1/sessions/{id}/files 的响应：`{ok, receiptId, file:{…}, image?:{…}}`。 */
@Serializable
data class UploadFileResponse(
    val ok: Boolean = true,
    val sessionId: String? = null,
    /** 下一条消息引用这个文件时使用的回执 id。 */
    val receiptId: String? = null,
    /** 普通文件的入库引用（带 mediaType 上传图片时可能为空，改看 [image]）。 */
    val file: MessageFile? = null,
    /** 图片归一化后的 `ImageAttachmentRef`（原样保留，客户端暂不消费）。 */
    val image: JsonElement? = null,
)

/** POST /v1/sessions 的响应：`{ok,sessionId,agentPreset?}`。 */
@Serializable
data class CreateSessionResponse(
    val ok: Boolean = true,
    val sessionId: String? = null,
    val agentPreset: String? = null,
    val session: SessionSummary? = null,
    val id: String? = null,
) {
    val resolvedSessionId: String?
        get() = sessionId ?: session?.id ?: id
}

/** 审批/提问答案的取值（§6.3）。 */
object InterventionAnswer {
    const val ALLOWED_ONCE = "allowed-once"
    const val REJECTED = "rejected"
    const val CANCELLED = "cancelled"

    /**
     * 提问答案批：`{"answers":[{"id":"q1","selected":["A"],"custom":"补充"}]}`。
     *
     * DSH 的 `AskUserQuestionAnswerItem`：`custom` 可选；两者都空表示「跳过该问题」。
     * 每个 question 必须恰好出现一次（桌面端语义），因此按 [Question.id] 顺序输出。
     */
    fun question(answers: List<QuestionAnswer>): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put(
                "answers",
                kotlinx.serialization.json.buildJsonArray {
                    answers.forEach { answer ->
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("id", JsonPrimitive(answer.id))
                                put(
                                    "selected",
                                    kotlinx.serialization.json.buildJsonArray {
                                        answer.selected.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                answer.custom?.takeIf { it.isNotBlank() }?.let {
                                    put("custom", JsonPrimitive(it))
                                }
                            },
                        )
                    }
                },
            )
        }

    /** 只带选项 label 的简化版本（旧调用方/测试）。 */
    fun question(answers: Map<String, List<String>>): JsonObject =
        question(answers.map { (id, selected) -> QuestionAnswer(id = id, selected = selected) })
}
