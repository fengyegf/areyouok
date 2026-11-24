package com.catmoecircle.areyouok.gateway.cert

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 客户端证书的持久化接口。
 *
 * 真机实现见 [KeystoreClientCertStore]（Android Keystore AES/GCM 加密后存
 * SharedPreferences）；单测用 [InMemoryClientCertStore]。
 */
interface ClientCertStore {
    fun list(): List<ClientCertificate>

    fun save(cert: ClientCertificate)

    fun remove(serial: String)

    fun clear()
}

/** JVM 单测/降级用的内存实现。 */
class InMemoryClientCertStore(initial: List<ClientCertificate> = emptyList()) : ClientCertStore {

    private val certs = LinkedHashMap<String, ClientCertificate>()

    init {
        initial.forEach { certs[it.serial] = it }
    }

    @Synchronized
    override fun list(): List<ClientCertificate> = certs.values.toList()

    @Synchronized
    override fun save(cert: ClientCertificate) {
        certs[cert.serial] = cert
    }

    @Synchronized
    override fun remove(serial: String) {
        certs.remove(serial)
    }

    @Synchronized
    override fun clear() {
        certs.clear()
    }
}

/**
 * 证书表的 JSON 编解码（纯 JVM，单测直接覆盖）。
 *
 * 落盘格式是 JSON 数组，p12 用 base64；密码也在里面（整个 JSON 会被
 * Keystore 加密后落盘）。解码遇到损坏条目逐条跳过、整体坏掉返回空表：
 * 证书是「有更好、没有就重新领」的数据，绝不因为存储坏了让 App 起不来。
 */
internal object ClientCertCodec {

    @Serializable
    private data class StoredCertificate(
        val serial: String = "",
        val subject: String = "",
        val issuer: String = "",
        val notAfter: Long = 0L,
        val label: String = "",
        val p12: String = "",
        val password: String = "",
    )

    private val serializer = ListSerializer(StoredCertificate.serializer())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(certs: List<ClientCertificate>): String = json.encodeToString(
        serializer,
        certs.map { cert ->
            StoredCertificate(
                serial = cert.serial,
                subject = cert.subject,
                issuer = cert.issuer,
                notAfter = cert.notAfter,
                label = cert.label,
                p12 = Base64.getEncoder().encodeToString(cert.p12),
                password = cert.password,
            )
        },
    )

    fun decode(text: String?): List<ClientCertificate> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, raw).mapNotNull { stored ->
                if (stored.serial.isBlank()) return@mapNotNull null
                val p12 = runCatching { Base64.getDecoder().decode(stored.p12) }.getOrNull()
                    ?: return@mapNotNull null
                if (p12.isEmpty()) return@mapNotNull null
                ClientCertificate(
                    serial = stored.serial,
                    subject = stored.subject,
                    issuer = stored.issuer,
                    notAfter = stored.notAfter,
                    label = stored.label,
                    p12 = p12,
                    password = stored.password,
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Android Keystore(AES/GCM) 加密后存 SharedPreferences：键 = `"certs"`，
 * 值是加密的 JSON 数组（[ClientCertCodec]）。
 *
 * 明文 p12（含私钥）不落盘；损坏时自愈为空表并 `warn`，下次局域网连接会
 * 重新自动领证（A9）。
 */
class KeystoreClientCertStore(context: Context) : ClientCertStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    override fun list(): List<ClientCertificate> = synchronized(lock) {
        val stored = prefs.getString(KEY_CERTS, null) ?: return emptyList()
        val plain = runCatching { decrypt(stored) }.getOrElse { error ->
            Log.w(TAG, "客户端证书解密失败，清空本地证书表", error)
            clearLocked()
            return emptyList()
        }
        ClientCertCodec.decode(plain)
    }

    override fun save(cert: ClientCertificate) = synchronized(lock) {
        val updated = list().filterNot { it.serial == cert.serial } + cert
        writeLocked(updated)
    }

    override fun remove(serial: String) = synchronized(lock) {
        writeLocked(list().filterNot { it.serial == serial })
    }

    override fun clear() = synchronized(lock) {
        clearLocked()
    }

    private fun writeLocked(certs: List<ClientCertificate>) {
        runCatching {
            val encoded = ClientCertCodec.encode(certs)
            prefs.edit().putString(KEY_CERTS, encrypt(encoded)).apply()
        }.onFailure { Log.e(TAG, "客户端证书保存失败", it) }
    }

    private fun clearLocked() {
        prefs.edit().remove(KEY_CERTS).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > GCM_IV_LENGTH) { "密文长度非法" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val payload = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    /** 与 [com.catmoecircle.areyouok.gateway.pairing.KeystoreTokenStore] 同一套 Keystore 用法。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "ClientCertStore"
        private const val PREFS_NAME = "gateway_client_certs"
        private const val KEY_CERTS = "certs"
        private const val KEY_ALIAS = "areyouok_gateway_certs_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
