package com.catmoecircle.areyouok.gateway.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 设备令牌存储：按网关基址（serverId）保存 Bearer token。 */
interface TokenStore {
    fun tokenFor(serverId: String): String?
    fun saveToken(serverId: String, token: String)
    fun clearToken(serverId: String)

    /** 本机设备 id（配对时上报，稳定不变）。 */
    fun deviceId(): String
}

/** JVM 单测用的内存实现。 */
class InMemoryTokenStore(
    private val deviceIdValue: String = "test-device-" + UUID.randomUUID().toString().take(8),
) : TokenStore {
    private val tokens = mutableMapOf<String, String>()

    override fun tokenFor(serverId: String): String? = tokens[serverId]

    override fun saveToken(serverId: String, token: String) {
        tokens[serverId] = token
    }

    override fun clearToken(serverId: String) {
        tokens.remove(serverId)
    }

    override fun deviceId(): String = deviceIdValue

    fun snapshot(): Map<String, String> = tokens.toMap()
}

/**
 * Android Keystore + AES/GCM 加密后存 SharedPreferences。
 *
 * 明文令牌不落盘：prefs 里只有 `base64(iv || ciphertext)`；
 * deviceId 不是秘密，明文保存。
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    override fun tokenFor(serverId: String): String? {
        val stored = prefs.getString(prefKey(serverId), null) ?: return null
        return runCatching { decrypt(stored) }.getOrElse {
            Log.w(TAG, "令牌解密失败，清除该网关令牌", it)
            clearToken(serverId)
            null
        }
    }

    override fun saveToken(serverId: String, token: String) {
        runCatching {
            prefs.edit().putString(prefKey(serverId), encrypt(token)).apply()
        }.onFailure { Log.e(TAG, "令牌保存失败", it) }
    }

    override fun clearToken(serverId: String) {
        prefs.edit().remove(prefKey(serverId)).apply()
    }

    private fun prefKey(serverId: String): String = "token_" + serverId

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "密文长度非法" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val payload = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

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
        private const val TAG = "KeystoreTokenStore"
        private const val PREFS_NAME = "gateway_tokens"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ALIAS = "areyouok_gateway_token_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
