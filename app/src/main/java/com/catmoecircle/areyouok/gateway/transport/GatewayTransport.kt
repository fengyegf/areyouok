package com.catmoecircle.areyouok.gateway.transport

/**
 * WebSocket 客户端向 repository 推送的更新。
 *
 * 用普通 sealed interface 而不是把 [StreamFrame] 直接抛给上层，
 * 是为了区分「连接生命周期」和「协议事件」两类信息。
 */
sealed interface StreamUpdate {
    data class Ready(val frame: com.catmoecircle.areyouok.gateway.protocol.ReadyFrame) : StreamUpdate

    data class Event(val frame: com.catmoecircle.areyouok.gateway.protocol.EventFrame) : StreamUpdate

    data class Resynced(val reason: String?) : StreamUpdate

    data class ProtocolError(val code: String, val message: String) : StreamUpdate

    /** 连接断开/尚未建立；[attempt] 从 1 开始，[retryDelayMs] 是下一次重连等待时间。 */
    data class Disconnected(
        val cause: Throwable?,
        val attempt: Int,
        val retryDelayMs: Long,
    ) : StreamUpdate
}

/**
 * `/v1` 传输层：REST + WebSocket 长连接。
 *
 * 实现需保证：
 * - [stream] 自动重连（0.5→30s 退避 + 抖动），断线后按最后 seq `hello.resume`；
 * - 收到 `resync` 时从零开始重放（清空 seq）；
 * - 事件按 `seq`/`id` 去重（见 [SeqTracker]）；
 * - 订阅状态跨重连保持（[subscribe]/[unsubscribe]）。
 */
interface GatewayTransport {

    val api: GatewayApi

    fun stream(initialResumeSeq: Long = 0L): kotlinx.coroutines.flow.Flow<StreamUpdate>

    fun subscribe(topic: String)

    fun unsubscribe(topic: String)

    /** 主动关闭连接，之后不再自动重连。 */
    fun shutdown()
}
