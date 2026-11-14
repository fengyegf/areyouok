package com.catmoecircle.areyouok.gateway.pairing

import java.net.URLDecoder
import java.net.URLEncoder
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.GatewayTls

/**
 * 配对二维码/深链的内容：
 * `dshgw://pair?url=<urlencoded>&lan=<http url>&pub=<https url>&mode=pub&cert=<base64url DER>&code=<>&fp=<>&n=<>`。
 *
 * 局域网走 `http://<ip>:7788`（明文，**不需要证书**、默认也不需要 token）；
 * 公网走 `https://…:7789`，需要配对码与证书指纹，插件切到「公网 HTTPS」版本时
 * 二维码还会带上自签证书本体（DER 的 base64url）：App 直接把它固定在自己进程内，
 * **不需要把证书装进系统信任库**。旧版只带 `url` 的深链仍然兼容。
 */
data class PairInvite(
    val baseUrl: String,
    val code: String = "",
    val fingerprint: String? = null,
    val name: String? = null,
    val raw: String = "",
    /** 二维码里的明文 HTTP 候选（局域网，免证书）。 */
    val lanUrl: String? = null,
    /** 二维码里的公网 HTTPS 候选。 */
    val publicUrl: String? = null,
    /** 二维码是为公网 HTTPS 生成的（`mode=pub`），连接时优先走 HTTPS。 */
    val preferPublic: Boolean = false,
    /** 二维码里内嵌的自签证书（DER 的 base64url，可省略 padding）。 */
    val certificateBase64: String? = null,
) {
    /** 首选明文 HTTP 基址（局域网直连，不涉及证书）。 */
    val httpUrl: String?
        get() = listOfNotNull(
            lanUrl,
            baseUrl.takeIf { it.startsWith("http://", ignoreCase = true) },
            publicUrl?.takeIf { it.startsWith("http://", ignoreCase = true) },
        ).firstOrNull()

    /** 公网 HTTPS 基址（仅在需要时使用，需证书校验/pin）。 */
    val httpsUrl: String?
        get() = listOfNotNull(publicUrl, baseUrl.takeIf { it.startsWith("https://", ignoreCase = true) })
            .firstOrNull()

    val isLan: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)
    val needsCode: Boolean get() = code.isBlank()

    /** 二维码里内嵌的证书 DER 字节；没带或解不出来时为 null。 */
    fun certificateDer(): ByteArray? = GatewayTls.decodeCertificate(certificateBase64)

    fun toEndpoint(token: String? = null): GatewayEndpoint =
        GatewayEndpoint(baseUrl = baseUrl, token = token, serverId = baseUrl, name = name)
}

/** 纯 JVM 解析（不依赖 android.net.Uri），JVM 单测直接覆盖。 */
object PairInviteParser {

    const val SCHEME = "dshgw"
    private val CODE_PATTERN = Regex("^[A-Za-z0-9_\\-]{4,128}$")
    private val HTTP_PATTERN = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** 深链、手动粘贴统一入口。 */
    fun parse(raw: String): PairInvite? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return if (text.startsWith("$SCHEME:", ignoreCase = true)) {
            parseDeepLink(text)
        } else {
            parseManual(text)
        }
    }

    /**
     * 解析 `dshgw://pair?url=…&lan=…&pub=…&code=…&fp=…&n=…`。
     *
     * 这里不用 [URI]：扫码结果里可能带未编码的空格/中文（`n=书房 PC`），
     * `java.net.URI` 会直接抛异常；手动切分 query 更宽容。
     * 有 `lan`（明文 HTTP）时优先选它，局域网连接不需要任何证书。
     */
    fun parseDeepLink(raw: String): PairInvite? {
        val text = raw.trim()
        if (!text.startsWith("$SCHEME:", ignoreCase = true)) return null
        val body = text.substringAfter(':').removePrefix("//")
        val pathEnd = body.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) body.length else it }
        val authority = body.substring(0, pathEnd).trim('/').substringBefore('/')
        if (authority.isNotBlank() && !authority.equals("pair", ignoreCase = true)) return null
        val query = body.substringAfter('?', "").substringBefore('#')
        val params = parseQuery(query)
        val lanRaw = params["lan"]?.takeIf { HTTP_PATTERN.containsMatchIn(it) }
        val pubRaw = params["pub"]?.takeIf { HTTP_PATTERN.containsMatchIn(it) }
        val urlRaw = params["url"] ?: params["base"] ?: params["addr"]
        // `mode=pub` 是插件「公网 HTTPS」二维码版本的标记：优先公网，避免手机
        // 恰好也在同一局域网时又悄悄回落到明文 HTTP。旧二维码没有 mode，保持局域网优先。
        val modeRaw = params["mode"]?.trim()?.lowercase()
        val preferPublic = modeRaw == "pub" || modeRaw == "public" || modeRaw == "https"
        val chosen = if (preferPublic) {
            listOfNotNull(pubRaw, urlRaw, lanRaw).firstNotNullOfOrNull { GatewayEndpoint.normalizeBaseUrl(it) }
        } else {
            listOfNotNull(lanRaw, urlRaw, pubRaw).firstNotNullOfOrNull { GatewayEndpoint.normalizeBaseUrl(it) }
        } ?: return null
        return PairInvite(
            baseUrl = chosen,
            code = params["code"].orEmpty(),
            fingerprint = params["fp"]?.takeIf { it.isNotBlank() },
            name = params["n"]?.takeIf { it.isNotBlank() },
            raw = text,
            lanUrl = lanRaw?.let { GatewayEndpoint.normalizeBaseUrl(it) },
            publicUrl = pubRaw?.let { GatewayEndpoint.normalizeBaseUrl(it) },
            preferPublic = preferPublic,
            certificateBase64 = (params["cert"] ?: params["crt"])?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 手动输入：`http://192.168.1.5:7788`，或 `http://x:7789 123456`，
     * 或两行（第一行地址，第二行配对码）。
     */
    fun parseManual(raw: String): PairInvite? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val urlToken = tokens.firstOrNull { it.contains("://") } ?: tokens.first()
        val codeToken = tokens.dropWhile { it == urlToken }.firstOrNull { CODE_PATTERN.matches(it) }
        val baseUrl = GatewayEndpoint.normalizeBaseUrl(urlToken) ?: return null
        return PairInvite(
            baseUrl = baseUrl,
            code = codeToken.orEmpty(),
            raw = text,
        )
    }

    /** 生成深链（管理页/测试用）。 */
    fun build(
        baseUrl: String,
        code: String,
        fingerprint: String? = null,
        name: String? = null,
        lanUrl: String? = null,
        publicUrl: String? = null,
        preferPublic: Boolean = false,
        certificate: String? = null,
    ): String {
        val params = buildList {
            add("url=${encode(baseUrl)}")
            if (!lanUrl.isNullOrBlank()) add("lan=${encode(lanUrl)}")
            if (!publicUrl.isNullOrBlank()) add("pub=${encode(publicUrl)}")
            if (preferPublic) add("mode=pub")
            if (!certificate.isNullOrBlank()) add("cert=${encode(certificate)}")
            if (code.isNotBlank()) add("code=${encode(code)}")
            if (!fingerprint.isNullOrBlank()) add("fp=${encode(fingerprint)}")
            if (!name.isNullOrBlank()) add("n=${encode(name)}")
        }
        return "$SCHEME://pair?" + params.joinToString("&")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null
            else decode(pair.substring(0, index)) to decode(pair.substring(index + 1))
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
