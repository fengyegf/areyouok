package com.catmoecircle.areyouok.gateway.cert

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * 进程内客户端证书表 + 握手时的 [X509ExtendedKeyManager]。
 *
 * 为什么不用 `KeyManagerFactory`：证书来自三处（自动领证、导入的 .p12、测试
 * fixture），每张的 PKCS#12 密码都是独立的；内存里再拼一个 KeyStore 只会把
 * 密码语义搅在一起。这里改成「安装时就把私钥/证书链解析好放进表」，KeyManager
 * 直接按表回答 JSSE，握手期间不做任何 IO。
 *
 * KeyManager 选择规则（契约 §7.1）：
 * 1. 优先选「证书 issuer DN ∈ 服务端 CertificateRequest 给出的可接受 CA（issuers）」
 *    的那一条；
 * 2. 只有一个条目时退回它（老网关的 CertificateRequest 可能不带 CA 名单）；
 * 3. 否则返回 null（宁可不发证书，也不要发一张必然被拒的）。
 *
 * 只有 [KeyManager] 在 TLS 层使用；[load]/[install]/[remove] 由
 * `GatewayRuntime` 在连接与设置页操作时调用。
 */
object ClientCerts {

    /** 一条已解析的证书：原始数据 + 私钥 + 证书链（internal 供 KeyManager 读取）。 */
    internal class Loaded(
        val cert: ClientCertificate,
        val key: PrivateKey,
        val chain: List<X509Certificate>,
    )

    /**
     * KeyManager 选择只需这四样；单测直接注入合成的条目列表。
     *
     * [notAfter] 只在「同一 CA 下有多张证书」时用于优先选最新的一张
     * （续期/重复导入后旧证书可能还留着）。
     */
    internal data class Entry(
        val alias: String,
        val issuerDn: String,
        val subjectDn: String,
        val notAfter: Long = 0L,
    )

    private val lock = Any()

    /** serial → 已解析证书（保序，展示顺序稳定）。 */
    private val loaded = LinkedHashMap<String, Loaded>()

    private val manager = ClientKeyManager()

    /**
     * 从持久化存储恢复证书表（进程启动时调用）。
     *
     * 打不开的 p12（存储损坏/密码变了）直接跳过，不阻塞启动：设置页会少一张，
     * 用户重新导入或回局域网自动领证即可。
     */
    fun load(store: ClientCertStore) {
        val items = runCatching { store.list() }.getOrDefault(emptyList())
        synchronized(lock) {
            loaded.clear()
            for (cert in items) {
                open(cert)?.let { loaded[it.cert.serial] = it }
            }
        }
    }

    /** 校验能被 PKCS#12 打开后才收；成功返回 true。 */
    fun install(cert: ClientCertificate): Boolean {
        val item = open(cert) ?: return false
        synchronized(lock) { loaded[cert.serial] = item }
        return true
    }

    fun remove(serial: String) {
        synchronized(lock) { loaded.remove(serial) }
    }

    fun snapshot(): List<ClientCertificate> = synchronized(lock) { loaded.values.map { it.cert } }

    fun keyManager(): X509ExtendedKeyManager = manager

    fun subjectDn(serial: String): String? = synchronized(lock) { loaded[serial]?.cert?.subject }

    /** 本机是否有该 CA（issuer DN）签发的证书。 */
    fun hasIssuer(issuerDn: String): Boolean {
        if (issuerDn.isBlank()) return false
        return synchronized(lock) { loaded.values.any { sameDn(it.cert.issuer, issuerDn) } }
    }

    /**
     * 该 CA 下是否有一张「在 [nowMs] + [renewBeforeMs] 之后才过期」的证书。
     *
     * 自动领证用它判断要不要续期：没有匹配 CA 的证书、或最近一张快过期/已过期，
     * 都在局域网连接成功后静默重走一次 CSR 领证（网关侧会撤销同 deviceId 的旧证书）。
     */
    fun hasFreshCertificate(issuerDn: String, nowMs: Long, renewBeforeMs: Long): Boolean {
        if (issuerDn.isBlank()) return false
        val deadline = nowMs + renewBeforeMs
        return synchronized(lock) {
            loaded.values.any { it.cert.notAfter > deadline && sameDn(it.cert.issuer, issuerDn) }
        }
    }

    /** 该 CA 签发的全部 serial（续期后按 issuer 覆盖本机旧条目）。 */
    fun serialsForIssuer(issuerDn: String): List<String> {
        if (issuerDn.isBlank()) return emptyList()
        return synchronized(lock) {
            loaded.values.filter { sameDn(it.cert.issuer, issuerDn) }.map { it.cert.serial }
        }
    }

    /**
     * 选择客户端证书别名（纯函数，单测覆盖三条规则）。
     *
     * @param entries 本机可用证书的最小信息
     * @param keyType JSSE 要求的密钥类型（RSA；本机证书恒为 RSA-2048）
     * @param issuers 服务端 CertificateRequest 给出的可接受 CA 名单（可能为 null）
     */
    internal fun selectAlias(
        entries: List<Entry>,
        keyType: String?,
        issuers: Array<out X500Principal>?,
    ): String? {
        if (entries.isEmpty()) return null
        if (!keyType.isNullOrBlank() && !keyType.contains("RSA", ignoreCase = true)) return null
        val accepted = issuers.orEmpty().mapNotNull { normalizeDn(it.name) }
        val matching = entries.filter { entry -> accepted.any { sameDn(entry.issuerDn, it) } }
        return when {
            // 同一 CA 下有多张时选最新到期的一张：续期/重复导入后旧证书可能还在表里。
            matching.isNotEmpty() -> matching.maxByOrNull { it.notAfter }?.alias
            entries.size == 1 -> entries.first().alias
            else -> null
        }
    }

    /** 两个 DN 是否是同一个名字（X500Principal 做规范化比较）。 */
    internal fun sameDn(left: String?, right: String?): Boolean {
        val first = left?.takeIf { it.isNotBlank() } ?: return false
        val second = right?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { X500Principal(first).equals(X500Principal(second)) }
            .getOrElse { first.equals(second, ignoreCase = true) }
    }

    internal fun normalizeDn(dn: String?): String? =
        dn?.takeIf { it.isNotBlank() }?.let { runCatching { X500Principal(it).name }.getOrNull() ?: it }

    /** 当前证书表里所有证书的最小信息（KeyManager 用）。 */
    internal fun entriesForHandshake(): List<Entry> = synchronized(lock) {
        loaded.values.map {
            Entry(
                alias = it.cert.serial,
                issuerDn = it.cert.issuer,
                subjectDn = it.cert.subject,
                notAfter = it.cert.notAfter,
            )
        }
    }

    /** 别名 → 已解析材料（别名就是 serial）。 */
    internal fun loadedFor(alias: String?): Loaded? {
        val key = alias ?: return null
        return synchronized(lock) { loaded[key] }
    }

    /** 打开 PKCS#12；失败（损坏/密码不匹配）返回 null。 */
    private fun open(cert: ClientCertificate): Loaded? = runCatching {
        val opened = Pkcs12Tools.open(cert.p12, cert.password)
        Loaded(cert = cert, key = opened.key, chain = opened.chain)
    }.getOrNull()

    /**
     * 按 [selectAlias] 的规则回答 JSSE。
     *
     * JSSE 在握手时调用 [chooseClientAlias]（SSLSocket）或
     * [chooseEngineClientAlias]（SSLEngine），拿到别名后再用
     * [getPrivateKey]/[getCertificateChain] 取材料。
     */
    private class ClientKeyManager : X509ExtendedKeyManager() {

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = choose(arrayKeyType(keyType), issuers)

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String? = choose(arrayKeyType(keyType), issuers)

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out Principal>?,
        ): Array<String>? {
            val aliases = candidates(singleKeyType(keyType), issuers).map { it.alias }
            return aliases.takeIf { it.isNotEmpty() }?.toTypedArray()
        }

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out Principal>?,
        ): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = null

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            ClientCerts.loadedFor(alias)?.chain?.toTypedArray()

        override fun getPrivateKey(alias: String?): PrivateKey? = ClientCerts.loadedFor(alias)?.key

        private fun choose(type: String?, issuers: Array<out Principal>?): String? =
            ClientCerts.selectAlias(
                entries = ClientCerts.entriesForHandshake(),
                keyType = type,
                issuers = issuerPrincipals(issuers),
            )

        /** 与 [selectAlias] 同一套规则，但返回全部命中项（JSSE 用于协商）。 */
        private fun candidates(type: String?, issuers: Array<out Principal>?): List<Entry> {
            val entries = ClientCerts.entriesForHandshake()
            if (entries.isEmpty()) return emptyList()
            if (type != null && !type.contains("RSA", ignoreCase = true)) return emptyList()
            val accepted = issuers.orEmpty().mapNotNull { ClientCerts.normalizeDn(it.name) }
            val matching = entries.filter { entry -> accepted.any { ClientCerts.sameDn(entry.issuerDn, it) } }
            return when {
                matching.isNotEmpty() -> matching
                entries.size == 1 -> entries
                else -> emptyList()
            }
        }

        /**
         * 本机证书恒为 RSA-2048：请求里出现 RSA 就按 RSA 处理，否则保留原标记，
         * 让 [selectAlias] 落空（不要发错类型的证书）。
         */
        private fun arrayKeyType(keyType: Array<out String>?): String? = when {
            keyType == null -> null
            keyType.any { it.contains("RSA", ignoreCase = true) } -> "RSA"
            else -> keyType.firstOrNull() ?: "UNKNOWN"
        }

        private fun singleKeyType(keyType: String?): String? = when {
            keyType == null -> null
            keyType.contains("RSA", ignoreCase = true) -> "RSA"
            else -> keyType
        }

        private fun issuerPrincipals(issuers: Array<out Principal>?): Array<out X500Principal>? =
            issuers?.map { principal ->
                principal as? X500Principal ?: X500Principal(principal.name)
            }?.toTypedArray()
    }
}
