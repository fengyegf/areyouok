package com.catmoecircle.areyouok.gateway.cert

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * 最小 DER 编码器 + PKCS#10 CSR（局域网自动领证用）。
 *
 * 网关只接受 CSR 领证（私钥永不出手机），所以这里手写一个够用的 DER 编码：
 * CSR 只需要 SEQUENCE / INTEGER / OID / UTF8String / BIT STRING / NULL，
 * 不引第三方 ASN.1 依赖。签名固定 SHA256withRSA（契约非目标：算法不可协商）。
 *
 * 纯函数（[derLength]/[derOid]/[certificationRequestInfo]）都是 internal，
 * JVM 单测直接喂字节断言长度编码与签名。
 *
 * 生成的 CSR 结构（RFC 2986）：
 * ```
 * CertificationRequest ::= SEQUENCE {
 *   certificationRequestInfo  SEQUENCE {
 *     version        INTEGER {v1(0)},
 *     subject        Name,                    -- RDN: CN=<commonName>
 *     subjectPKInfo  SubjectPublicKeyInfo,    -- publicKey.encoded
 *     attributes     [0] IMPLICIT SET OF Attribute   -- 空 A0 00
 *   },
 *   signatureAlgorithm  SEQUENCE { OID sha256WithRSA, NULL },
 *   signature           BIT STRING
 * }
 * ```
 */
object Pkcs10 {

    /** CN 的 OID（2.5.4.3）。 */
    internal const val OID_COMMON_NAME = "2.5.4.3"

    /** sha256WithRSAEncryption 的 OID（1.2.840.113549.1.1.11）。 */
    internal const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"

    /** PEM 头尾标签（RFC 2986 的证书请求）。 */
    private const val PEM_LABEL = "CERTIFICATE REQUEST"

    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31

    /**
     * 生成一张 PKCS#10 CSR（PEM）。
     *
     * @param keyPair RSA 密钥对（公钥进 CSR，私钥只在本机签名）
     * @param commonName 证书主题 CN（自动领证时传 deviceId）
     */
    fun createCsr(keyPair: KeyPair, commonName: String): String =
        pemEncode(PEM_LABEL, createCsrDer(keyPair, commonName))

    /** CSR 的 DER 字节（[createCsr] 只是加 PEM 包装；单测用）。 */
    internal fun createCsrDer(keyPair: KeyPair, commonName: String): ByteArray {
        val info = certificationRequestInfo(keyPair.public, commonName)
        val algorithm = derSequence(derOid(OID_SHA256_WITH_RSA) + derNull())
        val signature = sign(info, keyPair.private)
        return derSequence(info + algorithm + derBitString(signature))
    }

    /** CertificationRequestInfo：version + subject(CN) + subjectPKInfo + 空 attributes。 */
    internal fun certificationRequestInfo(publicKey: PublicKey, commonName: String): ByteArray {
        val version = derInteger(0)
        val subject = derSequence(
            derSet(
                derSequence(derOid(OID_COMMON_NAME) + derUtf8String(commonName)),
            ),
        )
        // publicKey.encoded 就是 SubjectPublicKeyInfo 的 DER，整段嵌入。
        val subjectPublicKeyInfo = publicKey.encoded
        // attributes: [0] IMPLICIT SET OF Attribute，空集合即 A0 00。
        val attributes = byteArrayOf(0xA0.toByte(), 0x00)
        return derSequence(version + subject + subjectPublicKeyInfo + attributes)
    }

    /** SHA256withRSA 签名。 */
    internal fun sign(content: ByteArray, privateKey: PrivateKey): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(content)
            sign()
        }

    /**
     * DER 长度编码：< 0x80 用一字节短格式；否则 `0x80|字节数` + 大端长度。
     *
     * 例：127 → `7F`；128 → `81 80`；256 → `82 01 00`；65535 → `82 FF FF`。
     */
    internal fun derLength(length: Int): ByteArray {
        require(length >= 0) { "DER 长度不能为负" }
        if (length < 0x80) return byteArrayOf(length.toByte())
        var size = 0
        var remaining = length
        while (remaining > 0) {
            size++
            remaining = remaining ushr 8
        }
        val bytes = ByteArray(size + 1)
        bytes[0] = (0x80 or size).toByte()
        remaining = length
        for (index in size downTo 1) {
            bytes[index] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
        return bytes
    }

    /** 编码一个 DER TLV。 */
    internal fun derTlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    internal fun derSequence(content: ByteArray): ByteArray = derTlv(TAG_SEQUENCE, content)

    internal fun derSet(content: ByteArray): ByteArray = derTlv(TAG_SET, content)

    internal fun derNull(): ByteArray = byteArrayOf(TAG_NULL.toByte(), 0x00)

    internal fun derInteger(value: Int): ByteArray =
        derTlv(TAG_INTEGER, BigInteger.valueOf(value.toLong()).toByteArray())

    internal fun derUtf8String(value: String): ByteArray =
        derTlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    /** BIT STRING：首字节是未用位数（签名恒为 0）。 */
    internal fun derBitString(value: ByteArray): ByteArray =
        derTlv(TAG_BIT_STRING, byteArrayOf(0x00) + value)

    /** OID 点分编码：首字节 = 40*a + b，后续每段 base-128（最高位为续位）。 */
    internal fun derOid(oid: String): ByteArray {
        val parts = oid.split('.').map { part ->
            part.toIntOrNull() ?: throw IllegalArgumentException("非法 OID: $oid")
        }
        require(parts.size >= 2) { "非法 OID: $oid" }
        val out = ByteArrayOutputStream()
        out.write(40 * parts[0] + parts[1])
        for (index in 2 until parts.size) {
            var value = parts[index]
            require(value >= 0) { "非法 OID: $oid" }
            val chunks = mutableListOf<Int>()
            do {
                chunks.add(0, value and 0x7f)
                value = value ushr 7
            } while (value > 0)
            chunks.forEachIndexed { position, chunk ->
                out.write(if (position == chunks.lastIndex) chunk else chunk or 0x80)
            }
        }
        return derTlv(TAG_OID, out.toByteArray())
    }

    /** PEM 包装（64 字符换行，结尾带换行；node-forge/OpenSSL 都能读）。 */
    internal fun pemEncode(label: String, der: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n"
    }
}
