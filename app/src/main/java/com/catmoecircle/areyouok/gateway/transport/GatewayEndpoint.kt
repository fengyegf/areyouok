package com.catmoecircle.areyouok.gateway.transport

/**
 * 一个网关实例的连接信息。
 *
 * [baseUrl] 是去掉尾部 `/` 的根地址（如 `http://192.168.1.5:7788`）；
 * REST 走 `baseUrl + /v1/...`，WebSocket 走 `ws(s)://.../v1/stream`。
 */
data class GatewayEndpoint(
    val baseUrl: String,
    val token: String? = null,
    val serverId: String = baseUrl,
    val name: String? = null,
) {
    val wsUrl: String
        get() = when {
            baseUrl.startsWith("https://", ignoreCase = true) ->
                "wss://" + baseUrl.substring("https://".length)
            baseUrl.startsWith("http://", ignoreCase = true) ->
                "ws://" + baseUrl.substring("http://".length)
            else -> "ws://$baseUrl"
        } + "/v1/stream"

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: baseUrl

    fun withToken(token: String?): GatewayEndpoint = copy(token = token)

    companion object {
        /**
         * 把用户输入/二维码里的地址归一化：
         * - 自动补 `http://`（局域网）或保留 `https://`；
         * - 去掉尾部 `/`；
         * - 允许粘贴带 `/v1` 或 `/v1/stream` 的地址。
         */
        fun normalizeBaseUrl(raw: String): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            value = value.substringBefore('#').substringBefore('?')
            value = value.trimEnd('/')
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                value = "http://$value"
            }
            value = value.trimEnd('/')
            value = value.removeSuffix("/v1/stream").removeSuffix("/v1").trimEnd('/')
            return if (value.substringAfter("://").isBlank()) null else value
        }
    }
}
