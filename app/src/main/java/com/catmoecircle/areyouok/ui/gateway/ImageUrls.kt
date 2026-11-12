package com.catmoecircle.areyouok.ui.gateway

import com.catmoecircle.areyouok.gateway.protocol.MessageImage
import java.net.URLEncoder

/**
 * 消息里图片的预览地址（纯函数，JVM 单测覆盖）。
 *
 * 只拼链接，不取字节：真正加载时由网关按请求去 PC 上读那一份（附件或工作区文件），
 * 所以 PC 上删掉之后这里自然也就看不到图了，和桌面端一致。
 */

/**
 * 已入库图片的预览地址：
 * `/v1/sessions/{sessionId}/attachments/{attachmentId}?mediaType=…&width=…&height=…&bytes=…&name=…`。
 *
 * 宿主会拿这些字段和存储里的字节做校验，缺一个都读不出来。
 *
 * @returns 不可用的引用（缺 id/类型/尺寸）返回 null。
 */
fun attachmentPreviewUrl(image: MessageImage, sessionId: String?): String? {
    val sid = sessionId?.takeIf { it.isNotBlank() } ?: return null
    if (!image.isUsable) return null
    val mediaType = image.mediaType ?: return null
    val width = image.width ?: return null
    val height = image.height ?: return null
    val bytes = image.bytes ?: return null
    val query = buildList {
        add("mediaType=${encode(mediaType)}")
        add("width=$width")
        add("height=$height")
        add("bytes=$bytes")
        image.name?.takeIf { it.isNotBlank() }?.let { add("name=${encode(it)}") }
    }.joinToString("&")
    return "/v1/sessions/$sid/attachments/${encode(image.attachmentId)}?$query"
}

/**
 * 把 Markdown 图片地址变成可预览的链接。
 *
 * - 绝对 http(s) / `data:` 地址原样使用（第三方图床、内嵌图）；
 * - 其余按 PC 上的本地路径处理：交给网关按会话工作目录解析成图片预览。
 */
fun markdownImageUrl(raw: String, sessionId: String?): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("data:", ignoreCase = true)
    ) {
        return value
    }
    val sid = sessionId?.takeIf { it.isNotBlank() } ?: return null
    return "/v1/sessions/$sid/image?path=${encode(value)}"
}

/** 查询参数编码：空格用 `%20`（`+` 在路径里会被当成字面加号）。 */
private fun encode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
