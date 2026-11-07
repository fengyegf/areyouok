package com.catmoecircle.areyouok.gateway.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * dsh-gateway `/v1` 协议使用的 Json 配置。
 *
 * - 插件版本升级时可能多出字段，客户端一律忽略未知字段；
 * - WS 帧用 `t` 作为多态判别字段（见 [StreamFrame]）；
 * - `explicitNulls=false` 让可选字段缺省时不会写 `null`，减少帧体积；
 * - `coerceInputValues=true` 把服务端显式 `null` 折回字段默认值，避免解码崩溃。
 */
object GatewayJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        classDiscriminator = "t"
    }

    inline fun <reified T> decodeOrNull(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.getOrNull()

    fun decodeFrame(text: String): StreamFrame? =
        runCatching { json.decodeFromString(StreamFrame.serializer(), text) }.getOrNull()

    fun encodeFrame(frame: StreamFrame): String =
        json.encodeToString(StreamFrame.serializer(), frame)
}

/** 读取字符串字段；数字/布尔按字面量返回，缺失或 null 返回 null。 */
fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/** 读取长整数字段；支持字符串形式的数字。 */
fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}

/** 读取布尔字段；支持 "true"/"false" 字符串。 */
fun JsonObject.bool(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: when (primitive.content.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
}
