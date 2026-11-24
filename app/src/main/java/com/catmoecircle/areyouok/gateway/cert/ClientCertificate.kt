package com.catmoecircle.areyouok.gateway.cert

/**
 * 一张客户端证书（私钥 + 证书链），按 PKCS#12 字节保存。
 *
 * 公网边的网关强制双向 TLS：握手时由 [ClientCerts] 里的 KeyManager 出示这张
 * 证书。私钥只存在于本机 [p12] 里（PKCS#12 由 [password] 保护），密码是本机
 * 随机生成的，仅用于重新打开 p12，**不要写日志**。
 *
 * 三种来源：
 * - 局域网自动领证（A9，见 `ClientCertEnrollment`）：网关签发，只存叶子 + CA；
 * - 网关面板导出 .p12 → 用户在设置页导入（A7）；
 * - 测试 fixture（`app/src/test/resources/mtls/client-fixture.p12`）。
 *
 * @property serial 证书 serialNumber 十六进制（大写，无前缀）
 * @property subject 证书主题 DN（RFC2253），自动领证时是 `CN=<deviceId>`
 * @property issuer 签发这证书的 CA DN（RFC2253）；KeyManager 按它匹配网关 CA
 * @property notAfter 到期时间（ms epoch）
 * @property label 展示名（CN 或用户备注）
 * @property p12 完整 PKCS#12 字节（私钥 + 证书链）
 * @property password 本机随机生成的 PKCS#12 密码
 */
data class ClientCertificate(
    val serial: String,
    val subject: String,
    val issuer: String,
    val notAfter: Long,
    val label: String,
    val p12: ByteArray,
    val password: String,
) {
    /** 列表里显示的短 serial（前 8 位；serial 缺失时退回 subject）。 */
    val shortSerial: String get() = serial.take(8).ifBlank { subject }

    /** 是否已经过期（默认按当前时间判断）。 */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = notAfter <= nowMs

    /** 距离过期还有多少毫秒（已过期为负数）。 */
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long = notAfter - nowMs

    // data class 默认按引用比较 ByteArray，这里必须按内容比较：
    // 设置页列表与单测都靠等值判断（从存储读回来的新实例要与内存里的相等）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClientCertificate) return false
        return serial == other.serial &&
            subject == other.subject &&
            issuer == other.issuer &&
            notAfter == other.notAfter &&
            label == other.label &&
            password == other.password &&
            p12.contentEquals(other.p12)
    }

    override fun hashCode(): Int {
        var result = serial.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + issuer.hashCode()
        result = 31 * result + notAfter.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + p12.contentHashCode()
        return result
    }
}
