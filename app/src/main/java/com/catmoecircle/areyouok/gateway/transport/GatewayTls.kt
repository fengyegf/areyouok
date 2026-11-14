package com.catmoecircle.areyouok.gateway.transport

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 自签证书固定（certificate pinning），只作用于 App 自己，不碰系统信任库。
 *
 * dsh-gateway 的公网边（`:7789`）用首次启动生成的自签证书。把证书装进
 * Android 系统信任库既麻烦（要用户手动操作、部分 ROM 还不允许）又不该由
 * 一个局域网工具去做。这里的做法是：
 *
 *   1. 插件把证书 DER（base64url）和 SHA-256 指纹写进公网版配对二维码；
 *   2. 扫码时 [GatewayPins] 记住「主机 → 证书」，[GatewaySettings] 落盘保存；
 *   3. [PinnedTrustManager] 在 TLS 握手阶段放行这张已固定的证书，
 *      [PinnedHostnameVerifier] 再按主机名确认「就是这张证书」。
 *
 * 未被固定的主机仍然走系统默认校验（公共 CA 签发的证书照常工作），
 * 固定过的主机则**只认**这张证书：即使攻击者拿到合法 CA 签发的证书，
 * 也会因为指纹不匹配而被拒绝。
 */
object GatewayTls {

    /** 指纹统一成 64 位小写十六进制。 */
    private val FINGERPRINT_PATTERN = Regex("^[0-9a-f]{64}$")

    private const val HEX = "0123456789abcdef"

    /**
     * 归一化二维码/设置里拿到的指纹：
     * 兼容 `sha256/` 前缀、`AA:BB:…` 分组、大小写。无法识别时返回 null。
     */
    fun normalizeFingerprint(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val stripped = text
            .replace("sha256/", "", ignoreCase = true)
            .replace("sha-256/", "", ignoreCase = true)
            .replace(":", "")
            .replace(" ", "")
            .lowercase()
        return stripped.takeIf { FINGERPRINT_PATTERN.matches(it) }
    }

    /** 证书 DER 的 SHA-256 指纹（小写十六进制）。 */
    fun fingerprintOf(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return builder.toString()
    }

    fun fingerprintOf(cert: X509Certificate): String = fingerprintOf(cert.encoded)

    /** `AA:BB:…` 形式，设置页展示用。 */
    fun formatFingerprint(fingerprint: String): String =
        fingerprint.uppercase().chunked(2).joinToString(":")

    /** 解码二维码里的证书（DER 的 base64url，允许省略 padding）。 */
    fun decodeCertificate(base64: String?): ByteArray? {
        val text = base64?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            val normalized = text.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            Base64.getDecoder().decode(padded)
        }.getOrNull()
    }

    /** 系统默认的 X509 校验器（公共 CA、企业根证书等照常生效）。 */
    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("no X509TrustManager available")
    }

    /** 同时信任系统 CA 与已固定自签证书的 TrustManager。 */
    val trustManager: X509TrustManager by lazy { PinnedTrustManager(defaultTrustManager()) }

    /** 使用 [trustManager] 的 SSLSocketFactory（OkHttp 建连时使用）。 */
    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trustManager), SecureRandom()) }
            .socketFactory
    }

    /** 按主机名判定固定证书的 HostnameVerifier。 */
    val hostnameVerifier: HostnameVerifier by lazy { PinnedHostnameVerifier() }
}

/**
 * App 进程内的证书固定表。
 *
 * 键是主机名（小写、IPv6 去掉方括号），值是指纹 + 可选的 DER 字节。
 * 只存在于本进程和本 App 的 SharedPreferences 里，不写入系统证书库。
 */
object GatewayPins {

    /** 一台网关固定过的证书。 */
    data class Pin(val fingerprint: String, val der: ByteArray?)

    private val pins = ConcurrentHashMap<String, Pin>()

    /** 主机名归一化：小写，IPv6 去掉方括号。 */
    fun normalizeHost(host: String?): String =
        host.orEmpty().trim().lowercase().removePrefix("[").removeSuffix("]")

    /** 固定一张证书；指纹缺失时由 DER 现算。两者都没有时返回 null。 */
    fun install(host: String, der: ByteArray?, fingerprint: String?): Pin? {
        val key = normalizeHost(host)
        if (key.isEmpty()) return null
        val bytes = der?.takeIf { it.isNotEmpty() }?.copyOf()
        val normalized = GatewayTls.normalizeFingerprint(fingerprint)
            ?: bytes?.let { GatewayTls.fingerprintOf(it) }
            ?: return null
        val pin = Pin(normalized, bytes)
        pins[key] = pin
        return pin
    }

    /** 从持久化字符串恢复一张固定证书。 */
    fun installStored(host: String, encoded: String?): Pin? {
        val pin = decode(encoded) ?: return null
        pins[normalizeHost(host)] = pin
        return pin
    }

    fun lookup(host: String?): Pin? = pins[normalizeHost(host)]

    fun fingerprintOf(host: String?): String? = lookup(host)?.fingerprint

    fun clear(host: String?) {
        pins.remove(normalizeHost(host))
    }

    fun clearAll() {
        pins.clear()
    }

    /**
     * 是否命中任意一张已固定证书。
     *
     * TrustManager 拿不到主机名，所以这里只回答「这张证书我们认识吗」；
     * 「它是否属于当前主机」由 [PinnedHostnameVerifier] 收紧。
     */
    fun matchesAny(cert: X509Certificate): Boolean {
        if (pins.isEmpty()) return false
        val der = cert.encoded
        val fingerprint = GatewayTls.fingerprintOf(der)
        return pins.values.any { pin ->
            pin.fingerprint == fingerprint || (pin.der != null && pin.der.contentEquals(der))
        }
    }

    /** 这台主机的固定证书是否就是 [cert]。 */
    fun matches(host: String?, cert: X509Certificate): Boolean {
        val pin = lookup(host) ?: return false
        return pin.fingerprint == GatewayTls.fingerprintOf(cert)
    }

    /** 序列化成 `<指纹>|<DER base64>`，供 SharedPreferences 保存。 */
    fun encode(pin: Pin): String =
        pin.fingerprint + "|" + (pin.der?.let { Base64.getEncoder().encodeToString(it) } ?: "")

    /** 反序列化 [encode] 的结果；损坏时返回 null（宁可重新扫码，也不放错证书）。 */
    fun decode(encoded: String?): Pin? {
        val text = encoded?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fingerprint = GatewayTls.normalizeFingerprint(text.substringBefore('|')) ?: return null
        val derText = text.substringAfter('|', "")
        val der = derText.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        return Pin(fingerprint, der)
    }
}

/** 放行「已固定证书」，其余交给系统默认校验。 */
internal class PinnedTrustManager(private val system: X509TrustManager) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("证书链为空")
        if (GatewayPins.matchesAny(leaf)) return
        system.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/**
 * 固定过证书的主机只认那张证书；其它主机保持系统默认的主机名校验。
 *
 * 这一步不能省：TrustManager 不知道主机名，若只在那里放行，一张被固定的
 * 自签证书会在任意主机名下“通用”。把主机绑定收回这里才能保持 pin 的语义。
 */
internal class PinnedHostnameVerifier(
    private val fallback: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : HostnameVerifier {

    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val host = hostname?.trim()?.lowercase().orEmpty()
        if (host.isEmpty()) return false
        val pin = GatewayPins.lookup(host)
        if (pin == null) return fallback.verify(hostname, session)
        val leaf = runCatching {
            session?.peerCertificates?.firstOrNull() as? X509Certificate
        }.getOrNull() ?: return false
        return GatewayPins.matches(host, leaf)
    }
}
