package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.ChatRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工作步骤的活动分类与摘要（纯 Kotlin，无 Compose / Android 依赖，JVM 单测覆盖）。
 *
 * 对齐 DSH 桌面客户端 `process-activity` 的做法：
 * - 一次工具调用按工具名归入「读取 / 搜索 / 命令 / 网页…」等活动类别，标题显示「正在运行命令」；
 * - 运行中的那一步再带一行明细（命令、路径等参数），标题显示「正在运行命令 · git status」；
 * - 分组结束后不再显示活动，改为按出现次数排序的「已读取文件、执行了命令」摘要。
 */
internal enum class WorkActivity {
    THINKING,
    READ,
    READ_IMAGE,
    SEARCH,
    WRITE,
    EDIT,
    COMMANDS,
    CODE,
    WEB_SEARCH,
    WEB_FETCH,
    SUBAGENTS,
    PLAN,
    QUESTIONS,
    TOOLS,
}

private val COMMAND_TOOLS = setOf("bash", "pwsh", "exec_command", "write_stdin", "ops_bash")
private val PLAN_TOOLS = setOf("todo_write", "create_goal", "update_goal", "get_goal")
private val QUESTION_TOOLS = setOf("ask_user_question", "request_user_input")
private val SUBAGENT_TOOLS = setOf("subagent", "subagent_fork", "workflow")

/** 工具名 → 活动类别；未知工具归入 [WorkActivity.TOOLS]（「正在调用工具」）。 */
internal fun workActivityOf(toolName: String?): WorkActivity {
    val name = toolName?.trim()?.lowercase().orEmpty()
    return when {
        name.isEmpty() -> WorkActivity.TOOLS
        name == "read" || name == "read_files" || name == "ops_read_files" -> WorkActivity.READ
        name == "read_image" -> WorkActivity.READ_IMAGE
        name == "grep" || name == "glob" || name.endsWith("_inspect") -> WorkActivity.SEARCH
        name == "write" || name == "ops_write_files" -> WorkActivity.WRITE
        name == "edit" || name == "apply_patch" -> WorkActivity.EDIT
        name in COMMAND_TOOLS || name.startsWith("terminal_") -> WorkActivity.COMMANDS
        name == "run_code" -> WorkActivity.CODE
        name == "web_search" -> WorkActivity.WEB_SEARCH
        name == "web_fetch" -> WorkActivity.WEB_FETCH
        name in SUBAGENT_TOOLS || name.startsWith("subagent_") -> WorkActivity.SUBAGENTS
        name in PLAN_TOOLS -> WorkActivity.PLAN
        name in QUESTION_TOOLS -> WorkActivity.QUESTIONS
        else -> WorkActivity.TOOLS
    }
}

/** 运行中标题的明细字段优先级（与 DSH 客户端一致）。 */
private val DETAIL_KEYS = listOf(
    "title", "description", "objective", "task", "task_name", "name",
    "question", "questions", "prompt", "message", "command", "cmd",
    "queries", "query", "pattern", "url", "uri", "file_path", "path",
    "target", "action", "status",
)

private const val DETAIL_MAX_CHARS = 160
private val whitespace = Regex("\\s+")
private val detailJson = Json { isLenient = true }

/**
 * 运行中工具的一行明细：优先取参数里第一个有文本的字段（命令、路径、查询…），
 * 没有可用参数时退回工具名。结果压成单行、最多 160 个字符。
 */
internal fun liveToolDetail(toolName: String?, arguments: String?): String {
    val argumentsObject = runCatching {
        arguments?.takeIf { it.isNotBlank() }?.let { detailJson.parseToJsonElement(it) as? JsonObject }
    }.getOrNull()
    if (argumentsObject != null) {
        for (key in DETAIL_KEYS) {
            val detail = detailText(argumentsObject[key])
            if (detail.isNotEmpty()) return normalizeDetail(detail)
        }
    }
    return normalizeDetail(toolName.orEmpty())
}

private fun detailText(element: JsonElement?): String = when (element) {
    is JsonPrimitive -> if (element.isString) element.content else ""
    is JsonArray -> if (element.isNotEmpty() && element.all { it is JsonPrimitive && it.isString }) {
        element.joinToString(", ") { (it as JsonPrimitive).content }
    } else {
        ""
    }
    else -> ""
}

private fun normalizeDetail(text: String): String {
    val normalized = text.replace(whitespace, " ").trim()
    return if (normalized.length <= DETAIL_MAX_CHARS) {
        normalized
    } else {
        normalized.take(DETAIL_MAX_CHARS - 1).trimEnd() + "…"
    }
}

/** 一个工作步骤分组的内容摘要：按次数排序的活动、运行中的活动与它的一行明细。 */
internal data class WorkSummary(
    val counts: List<ActivityCount>,
    val running: WorkActivity?,
    val runningDetail: String,
) {
    data class ActivityCount(val activity: WorkActivity, val count: Int)

    /** 计数最高的活动；没有工具调用（只有思考）时为空。 */
    val leading: WorkActivity? get() = counts.firstOrNull()?.activity

    /** 收起后的标题最多列三类活动，更多时追加「等」。 */
    val titleActivities: List<WorkActivity> get() = counts.take(3).map { it.activity }

    val titleHasMore: Boolean get() = counts.size > 3
}

/**
 * 汇总一组工作步骤。
 *
 * - 工具调用按活动类别计数，次数降序、同次数按首次出现排序（标题取前三类）；
 * - 运行中的工具（[ChatMessage.running]）决定实时标题与明细；
 * - 没有运行中的工具时，流式思考链也算运行中，明细取最后一行非空思考。
 */
internal fun summarizeWork(items: List<ChatMessage>): WorkSummary {
    val counts = LinkedHashMap<WorkActivity, Int>()
    var running: WorkActivity? = null
    var runningDetail = ""

    for (item in items) {
        when (item.role) {
            ChatRole.TOOL -> {
                val name = item.meta?.takeIf { it.isNotBlank() }
                val activity = workActivityOf(name)
                counts[activity] = (counts[activity] ?: 0) + 1
                if (item.running) {
                    running = activity
                    runningDetail = liveToolDetail(name, item.arguments)
                }
            }
            ChatRole.THINKING -> {
                if (item.streaming) {
                    running = WorkActivity.THINKING
                    runningDetail = item.text.lineSequence()
                        .lastOrNull { it.isNotBlank() }
                        ?.let(::normalizeDetail)
                        .orEmpty()
                }
            }
            else -> Unit
        }
    }

    val ranked = counts.entries
        .sortedByDescending { it.value }
        .map { WorkSummary.ActivityCount(it.key, it.value) }
    return WorkSummary(counts = ranked, running = running, runningDetail = runningDetail)
}
