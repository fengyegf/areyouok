package com.catmoecircle.areyouok.gateway.transport

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

        private val IPV4_PATTERN = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /**
         * 识别「手机连不上」的虚拟地址，命中时返回一句可直接展示的中文提示。
         *
         * 线上事故：网关把 Clash/Mihomo 的 TUN 网卡地址（`198.18.0.1`）当成公网地址
         * 写进了配对二维码。手机自己的 VPN/TUN 也占用 `198.18.0.0/15`，连接被本机 TUN
         * 劫持，最后只抛一个 SSLException，界面显示成「TLS 握手失败（证书指纹不匹配？）」
         * ——其实指纹是匹配的。这类地址扫码后必然连不上，所以在配对前就拦下来，直接
         * 让用户换地址，而不是让人去猜证书。
         *
         * 命中：`198.18.0.0/15`（Clash/Mihomo TUN）、`240.0.0.0/4`（保留段）、
         * `0.0.0.0/8`（未指定）、`127.0.0.0/8`（回环）、`::1`、`fe80::/10`（链路本地）。
         * Tailscale 的 `100.64.0.0/10` 和常见局域网段（192.168/10/172.16）**不算**：
         * 它们对手机是可达的，命中会误伤正常配对。解析失败返回 null。
         */
        fun virtualAddressHint(baseUrl: String): String? {
            val normalized = normalizeBaseUrl(baseUrl) ?: return null
            val host = normalized.toHttpUrlOrNull()?.host ?: return null
            val address = GatewayPins.normalizeHost(host)
            if (!isVirtualAddress(address)) return null
            return "地址 $address 是代理/TUN 网卡或本机回环的虚拟地址，手机连不上；" +
                "请改用电脑的局域网地址或公网 IPv6 地址重新生成二维码。"
        }

        /** 主机名是否落在「手机连不上」的虚拟地址段。 */
        private fun isVirtualAddress(host: String): Boolean {
            if (host.isEmpty()) return false
            if (host == "::1") return true
            val match = IPV4_PATTERN.matchEntire(host)
            if (match != null) {
                val first = match.groupValues[1].toIntOrNull() ?: return false
                val second = match.groupValues[2].toIntOrNull() ?: return false
                return (first == 198 && (second == 18 || second == 19)) || // Clash/Mihomo TUN
                    first >= 240 || // 240.0.0.0/4 保留段
                    first == 0 || // 0.0.0.0/8 未指定
                    first == 127 // 127.0.0.0/8 回环
            }
            return isLinkLocalIpv6(host)
        }

        /** `fe80::/10` 的前 10 位固定，所以首段落在 fe80–febf。 */
        private fun isLinkLocalIpv6(host: String): Boolean {
            if (!host.contains(':')) return false
            return host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb")
        }
    }
}
