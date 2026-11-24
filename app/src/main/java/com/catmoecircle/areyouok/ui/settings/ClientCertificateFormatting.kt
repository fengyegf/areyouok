package com.catmoecircle.areyouok.ui.settings

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 客户端证书列表的纯格式化/判定函数（不依赖 Android，JVM 单测直接覆盖）。
 *
 * 设置页只负责把这些结果渲染成 MIUI 风格的行；到期判断、serial 截断、CN 提取
 * 都放在这里，避免 Compose 里散落字符串处理。
 */

/** 证书在列表里的展示状态：正常 / 即将到期 / 已过期。 */
internal enum class ClientCertificateState { VALID, EXPIRING_SOON, EXPIRED }

/** 到期前多少天开始提示「即将到期」。 */
internal const val CERTIFICATE_EXPIRING_SOON_DAYS: Long = 30L

/** 导入密码最短长度：网关面板导出 .p12 时要求 6~64 位，导入侧按同一门槛校验。 */
internal const val CERTIFICATE_PASSWORD_MIN_LENGTH: Int = 6

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/** 到期日（yyyy-MM-dd）；默认设备时区，单测注入 UTC 得到固定结果。 */
internal fun formatCertificateExpiry(notAfterMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(notAfterMs).atZone(zone).toLocalDate().toString()

/**
 * 列表里显示的 serial 前 8 位（大写、去掉 0x 前缀与空白）。
 *
 * 返回空串表示没有可展示的 serial，由 UI 退回占位文案。
 */
internal fun formatCertificateSerial(serial: String): String =
    serial.trim()
        .removePrefix("0x")
        .removePrefix("0X")
        .uppercase(Locale.US)
        .take(8)

/** 到期状态（[nowMs] 可注入，单测不依赖当前时间）。 */
internal fun certificateState(
    notAfterMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): ClientCertificateState {
    val remaining = notAfterMs - nowMs
    return when {
        remaining <= 0L -> ClientCertificateState.EXPIRED
        remaining <= CERTIFICATE_EXPIRING_SOON_DAYS * DAY_MS -> ClientCertificateState.EXPIRING_SOON
        else -> ClientCertificateState.VALID
    }
}

/**
 * 展示名：label 优先；否则取 subject DN 里的 CN；都没有返回 null（UI 用兜底文案）。
 */
internal fun certificateDisplayLabel(label: String, subject: String): String? {
    label.trim().takeIf { it.isNotBlank() }?.let { return it }
    return certificateCommonName(subject)
}

/**
 * 从 RFC2253 形式的 DN 里取 CN（处理 `\,` 转义），取不到返回 null。
 *
 * 只做设置页展示，不参与任何证书校验；解析失败按「没有 CN」处理。
 */
internal fun certificateCommonName(subject: String): String? {
    val text = subject.trim()
    if (text.isEmpty()) return null

    var index = 0
    while (index < text.length) {
        val equals = text.indexOf('=', index)
        if (equals < 0) return null
        val key = text.substring(index, equals).trim()

        var cursor = equals + 1
        var escaped = false
        val value = StringBuilder()
        while (cursor < text.length) {
            val char = text[cursor]
            when {
                escaped -> {
                    value.append(char)
                    escaped = false
                }

                char == '\\' -> escaped = true
                char == ',' -> break
                else -> value.append(char)
            }
            cursor++
        }
        if (key.equals("CN", ignoreCase = true)) {
            return value.toString().trim().takeIf { it.isNotBlank() }
        }
        // 跳过这一段的逗号；没有更多段时循环自然结束。
        index = cursor + 1
    }
    return null
}

/** 导入密码校验：至少 [CERTIFICATE_PASSWORD_MIN_LENGTH] 位。 */
internal fun isValidCertificatePassword(password: String): Boolean =
    password.length >= CERTIFICATE_PASSWORD_MIN_LENGTH
