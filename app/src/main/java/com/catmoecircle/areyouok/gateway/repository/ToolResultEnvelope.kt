package com.catmoecircle.areyouok.gateway.repository

/**
 * DSH 工具结果的「信封」：结果正文外面还包着 `<path>` / `<type>` / `<content>` 标签。
 *
 * 真机现象：这些标签被当成普通正文显示，聊天里夹着几行灰字 `<path>…</path>`。
 * 标签其实是这次工具调用的元信息（输出落到哪个文件、是什么类型），应当并进
 * 「工具调用」卡片，只把 [content] 当输出展示。
 *
 * 纯 Kotlin，无 Android 依赖，JVM 单测直接覆盖。
 */
data class ToolResultEnvelope(
    /** `<path>` 标签里的文件路径（PC 上的绝对/相对路径），没有信封时为 null。 */
    val path: String?,
    /** `<type>` 标签里的输出类型（如 `image`），可缺省。 */
    val type: String?,
    /** `<content>` 标签之间的实际输出；没有信封时就是原文。 */
    val content: String,
    /** 是否识别出信封：false 表示 [content] 是未经改动的原文。 */
    val hadEnvelope: Boolean,
)

/**
 * 解析工具结果信封。
 *
 * 规则：
 * - `trimStart()` 后必须以 `<path>` 开头且能找到 `</path>`，才算信封（半截标签不当信封）；
 * - `path` 取标签之间的文本；
 * - 可选 `<type>…</type>`；
 * - `content` 取 `<content>` 与 `</content>` 之间；缺 `</content>`（DSH 截断时以 `…` 结尾）
 *   就取 `<content>` 之后全部；连 `<content>` 都没有时取剩余文本；
 * - 没有任何信封特征时 `hadEnvelope = false`，`content` 原样返回。
 */
fun parseToolResultEnvelope(text: String): ToolResultEnvelope {
    val lead = text.trimStart()
    if (!lead.startsWith(TAG_PATH_OPEN)) return noEnvelope(text)
    val pathEnd = lead.indexOf(TAG_PATH_CLOSE)
    if (pathEnd < 0) return noEnvelope(text)
    val path = lead.substring(TAG_PATH_OPEN.length, pathEnd).trim().takeIf { it.isNotEmpty() }

    var rest = lead.substring(pathEnd + TAG_PATH_CLOSE.length).trimStart()
    val type = if (rest.startsWith(TAG_TYPE_OPEN)) {
        val typeEnd = rest.indexOf(TAG_TYPE_CLOSE)
        if (typeEnd < 0) {
            null
        } else {
            val value = rest.substring(TAG_TYPE_OPEN.length, typeEnd).trim()
            rest = rest.substring(typeEnd + TAG_TYPE_CLOSE.length).trimStart()
            value.takeIf { it.isNotEmpty() }
        }
    } else {
        null
    }

    val contentStart = rest.indexOf(TAG_CONTENT_OPEN)
    if (contentStart < 0) {
        return ToolResultEnvelope(path = path, type = type, content = rest.trim(), hadEnvelope = true)
    }
    val bodyStart = contentStart + TAG_CONTENT_OPEN.length
    val contentEnd = rest.indexOf(TAG_CONTENT_CLOSE, bodyStart)
    val content = if (contentEnd >= 0) rest.substring(bodyStart, contentEnd) else rest.substring(bodyStart)
    return ToolResultEnvelope(path = path, type = type, content = content.trim(), hadEnvelope = true)
}

/**
 * 信封路径的最后一段文件名（`\` 与 `/` 都认），用来放在工具行收起态的摘要里。
 * 路径为空、或只以分隔符结尾时返回 null。
 */
fun toolPathFileName(path: String?): String? {
    val value = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val name = value.substringAfterLast('\\').substringAfterLast('/').trim()
    return name.takeIf { it.isNotEmpty() }
}

private fun noEnvelope(text: String) =
    ToolResultEnvelope(path = null, type = null, content = text, hadEnvelope = false)

private const val TAG_PATH_OPEN = "<path>"
private const val TAG_PATH_CLOSE = "</path>"
private const val TAG_TYPE_OPEN = "<type>"
private const val TAG_TYPE_CLOSE = "</type>"
private const val TAG_CONTENT_OPEN = "<content>"
private const val TAG_CONTENT_CLOSE = "</content>"
