package com.catmoecircle.areyouok.gateway.cert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.security.auth.x500.X500Principal

/**
 * PKCS#12 / 证书文本的小工具（纯 JVM，单测可直接用）。
 *
 * 只做两件事：
 * 1. 打开网关导出或自动领证生成的 PKCS#12（`KeyStore("PKCS12")`），取第一个
 *    `PrivateKeyEntry` 的私钥 + 证书链；
 * 2. 反过来，把私钥 + 证书链打成 PKCS#12（自动领证时用本机随机密码保护）。
 *
 * 错误统一抛 [IllegalArgumentException]，消息是可直接展示的中文。
 */
internal object Pkcs12Tools {

    /** 打开后的材料。 */
    internal class Opened(val key: PrivateKey, val chain: List<X509Certificate>)

    /** 打开 PKCS#12；密码错误/文件损坏/没有私钥都抛中文 [IllegalArgumentException]。 */
    fun open(bytes: ByteArray, password: String): Opened {
        if (bytes.isEmpty()) throw IllegalArgumentException("证书文件为空")
        val keyStore = try {
            KeyStore.getInstance(PKCS12).apply {
                load(ByteArrayInputStream(bytes), password.toCharArray())
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("证书文件密码错误或文件已损坏", error)
        }
        val aliases = try {
            java.util.Collections.list(keyStore.aliases())
        } catch (error: Exception) {
            throw IllegalArgumentException("证书文件密码错误或文件已损坏", error)
        }
        for (alias in aliases) {
            val protection = KeyStore.PasswordProtection(password.toCharArray())
            val entry = runCatching { keyStore.getEntry(alias, protection) }.getOrNull()
            if (entry is KeyStore.PrivateKeyEntry) {
                val chain = entry.certificateChain.mapNotNull { it as? X509Certificate }
                if (chain.isNotEmpty()) return Opened(entry.privateKey, chain)
            }
        }
        throw IllegalArgumentException("证书文件里没有可用的私钥与证书链（请导入包含私钥的 .p12）")
    }

    /** 把私钥 + 证书链打成 PKCS#12（密码必须是本机随机生成的）。 */
    fun build(key: PrivateKey, chain: List<X509Certificate>, password: String): ByteArray {
        require(chain.isNotEmpty()) { "证书链为空" }
        val keyStore = KeyStore.getInstance(PKCS12)
        keyStore.load(null, null)
        keyStore.setKeyEntry(KEY_ALIAS, key, password.toCharArray(), chain.toTypedArray())
        return ByteArrayOutputStream().use { out ->
            keyStore.store(out, password.toCharArray())
            out.toByteArray()
        }
    }

    /** 本机 PKCS#12 密码：SecureRandom 24 字节 → base64（只在本机重新打开 p12 时用）。 */
    fun randomPassword(): String {
        val random = ByteArray(PASSWORD_BYTES)
        SecureRandom().nextBytes(random)
        return Base64.getEncoder().encodeToString(random)
    }

    /** 证书 serial 十六进制（大写，无前缀），与网关 `clients.json` 的 serial 对齐。 */
    fun serialHex(cert: X509Certificate): String {
        val value = cert.serialNumber
        val hex = if (value.signum() < 0) value.abs().toString(16) else value.toString(16)
        return hex.uppercase()
    }

    /** RFC2253 形式的 DN（网关用 X500Principal.getName() 的同一形态）。 */
    fun rfc2253(name: X500Principal): String = name.getName(X500Principal.RFC2253)

    /** 展示名：CN，否则 serial 前 8 位（契约 §7.1）。 */
    fun labelOf(cert: X509Certificate): String =
        cnOf(cert.subjectX500Principal).ifBlank { serialHex(cert).take(8) }

    /** 从 DN 里取 CN（RFC2253 字符串足够，不依赖 javax.naming）。 */
    fun cnOf(name: X500Principal): String {
        for (part in splitDn(name.getName(X500Principal.RFC2253))) {
            if (part.startsWith("CN=", ignoreCase = true)) return unescapeDn(part.substring(3))
        }
        return ""
    }

    /** 解析 PEM 证书（网关 `/v1/pki/ca`、enroll 响应里的 certificate/caCertificate）。 */
    fun parseCertificatePem(pem: String?): X509Certificate? {
        val text = pem?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) as? X509Certificate
        }.getOrNull()
    }

    /** 按未转义的逗号切分 DN（CN 里可能有 `\,`）。 */
    private fun splitDn(dn: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (char in dn) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' -> {
                    current.append(char)
                    escaped = true
                }

                char == ',' -> {
                    parts += current.toString()
                    current.setLength(0)
                }

                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /** 去掉 DN 值里的反斜杠转义。 */
    private fun unescapeDn(value: String): String {
        val out = StringBuilder(value.length)
        var escaped = false
        for (char in value) {
            if (escaped) {
                out.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                out.append(char)
            }
        }
        if (escaped) out.append('\\')
        return out.toString()
    }

    private const val PKCS12 = "PKCS12"
    private const val KEY_ALIAS = "client"
    private const val PASSWORD_BYTES = 24
}
