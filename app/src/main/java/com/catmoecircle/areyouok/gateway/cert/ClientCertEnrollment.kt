package com.catmoecircle.areyouok.gateway.cert

import android.util.Log
import com.catmoecircle.areyouok.gateway.transport.GatewayApi
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * 局域网自动领证（A9）：本机生成密钥对，只把 CSR 交给网关。
 *
 * 流程：RSA-2048 → `CSR(CN=<deviceId>)` → `POST /v1/pki/enroll` → 用响应里的
 * 证书 + 本机私钥组 PKCS#12（本机随机密码）→ [ClientCerts.install]。
 * 私钥全程不出手机，网关只签发。
 *
 * 端点不存在（老网关 404）、公网边 403、网络失败都返回 null，由调用方
 * `Log.w` 并静默跳过：领证失败不阻塞连接。
 *
 * 续期也走这一条路径：局域网连接成功时若本机没有该 CA 签发的证书，或最近
 * 一张距过期不足 [RENEW_BEFORE_MS]，就重新领一次；网关会撤销同 deviceId 的
 * 旧证书，本机按 issuer 覆盖旧条目。
 */
internal object ClientCertEnrollment {

    /** 剩余寿命不足 30 天视为「快过期」：局域网连接成功后静默续期。 */
    const val RENEW_BEFORE_MS: Long = 30L * 24L * 60L * 60L * 1000L

    private const val TAG = "ClientCertEnrollment"
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048

    /**
     * 走一次完整领证。
     *
     * @return 装进 [ClientCerts] 的证书；任何一步失败都返回 null（不抛）。
     */
    suspend fun enroll(api: GatewayApi, deviceId: String, deviceName: String?): ClientCertificate? {
        val keyPair = runCatching {
            KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
                initialize(KEY_SIZE, SecureRandom())
            }.generateKeyPair()
        }.getOrElse { error ->
            warn("生成客户端证书密钥对失败", error)
            return null
        }
        val csr = Pkcs10.createCsr(keyPair, deviceId)
        // 同时带 deviceId（非空 UUID）：网关据此撤销同设备旧证书（续期）并支持按设备撤销。
        val response = runCatching { api.enrollClientCertificate(csr, deviceName, deviceId) }.getOrElse { error ->
            warn("自动领取客户端证书失败（网关不支持或网络失败）", error)
            return null
        }
        val leaf = Pkcs12Tools.parseCertificatePem(response.certificate)
        if (leaf == null) {
            warn("网关返回的客户端证书无法解析")
            return null
        }
        val chain = buildList {
            add(leaf)
            Pkcs12Tools.parseCertificatePem(response.caCertificate)?.let { add(it) }
        }
        val password = Pkcs12Tools.randomPassword()
        val p12 = runCatching { Pkcs12Tools.build(keyPair.private, chain, password) }.getOrElse { error ->
            warn("打包客户端证书 PKCS#12 失败", error)
            return null
        }
        val certificate = certificateOf(leaf, p12, password)
        if (!ClientCerts.install(certificate)) {
            warn("客户端证书无法装入本机证书表")
            return null
        }
        return certificate
    }

    /**
     * 记一条警告。
     *
     * JVM 单测里 `android.util.Log` 是「not mocked」的抛异常 stub；日志本身
     * 不该改变领证结果，所以这里吞掉日志失败（Android 上 Log 不会抛）。
     */
    private fun warn(message: String, error: Throwable? = null) {
        runCatching { Log.w(TAG, message, error) }
    }

    /**
     * 导入用户从网关导出的 .p12（A7）。
     *
     * @throws IllegalArgumentException 密码错误 / 文件损坏 / 没有私钥（中文消息）
     */
    fun fromPkcs12(bytes: ByteArray, password: String): ClientCertificate {
        val opened = Pkcs12Tools.open(bytes, password)
        val leaf = opened.chain.firstOrNull()
            ?: throw IllegalArgumentException("证书文件里没有可用的证书链")
        return certificateOf(leaf, bytes.copyOf(), password)
    }

    /** 叶子证书 + p12 → [ClientCertificate]（serial/issuer/label 统一在这里算）。 */
    private fun certificateOf(leaf: X509Certificate, p12: ByteArray, password: String): ClientCertificate =
        ClientCertificate(
            serial = Pkcs12Tools.serialHex(leaf),
            subject = Pkcs12Tools.rfc2253(leaf.subjectX500Principal),
            issuer = Pkcs12Tools.rfc2253(leaf.issuerX500Principal),
            notAfter = leaf.notAfter.time,
            label = Pkcs12Tools.labelOf(leaf),
            p12 = p12,
            password = password,
        )
}
