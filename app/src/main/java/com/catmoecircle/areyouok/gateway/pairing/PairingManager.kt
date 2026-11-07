package com.catmoecircle.areyouok.gateway.pairing

import com.catmoecircle.areyouok.gateway.transport.GatewayApi
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint

/**
 * 配对流程：`POST /v1/pair` → 保存 deviceToken → 返回可连接 endpoint。
 *
 * 局域网免认证时可以直接用 [endpointFor] 构造无 token 的 endpoint。
 */
class PairingManager(
    private val api: GatewayApi,
    private val tokenStore: TokenStore,
) {

    data class Result(
        val endpoint: GatewayEndpoint,
        val deviceId: String,
        val expiresAt: Long?,
    )

    /** 局域网/手动地址：带上已保存的 token（如果有）。 */
    fun endpointFor(baseUrl: String, name: String? = null): GatewayEndpoint {
        val normalized = GatewayEndpoint.normalizeBaseUrl(baseUrl)
            ?: throw IllegalArgumentException("非法网关地址: $baseUrl")
        val token = tokenStore.tokenFor(normalized)
        return GatewayEndpoint(baseUrl = normalized, token = token, serverId = normalized, name = name)
    }

    /**
     * 公网配对。失败时抛 [com.catmoecircle.areyouok.gateway.transport.GatewayException]
     * （invalid_code / code_expired / too_many_attempts 等）。
     */
    suspend fun pair(invite: PairInvite, deviceName: String): Result {
        if (invite.code.isBlank()) {
            throw com.catmoecircle.areyouok.gateway.transport.GatewayException(
                "invalid_request",
                0,
                "缺少配对码",
            )
        }
        val deviceId = tokenStore.deviceId()
        val response = api.pair(
            baseUrl = invite.baseUrl,
            code = invite.code,
            deviceName = deviceName,
            deviceId = deviceId,
        )
        val token = response.deviceToken
        if (token.isBlank()) {
            throw com.catmoecircle.areyouok.gateway.transport.GatewayException(
                "invalid_response",
                0,
                "配对响应缺少 deviceToken",
            )
        }
        tokenStore.saveToken(invite.baseUrl, token)
        val endpoint = GatewayEndpoint(
            baseUrl = invite.baseUrl,
            token = token,
            serverId = invite.baseUrl,
            name = invite.name,
        )
        return Result(endpoint = endpoint, deviceId = deviceId, expiresAt = response.expiresAt)
    }

    fun forget(baseUrl: String) {
        GatewayEndpoint.normalizeBaseUrl(baseUrl)?.let { tokenStore.clearToken(it) }
    }
}
