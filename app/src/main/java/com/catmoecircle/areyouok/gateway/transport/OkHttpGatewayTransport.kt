package com.catmoecircle.areyouok.gateway.transport

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.catmoecircle.areyouok.gateway.protocol.AckFrame
import com.catmoecircle.areyouok.gateway.protocol.ErrorFrame
import com.catmoecircle.areyouok.gateway.protocol.EventFrame
import com.catmoecircle.areyouok.gateway.protocol.GatewayJson
import com.catmoecircle.areyouok.gateway.protocol.HelloFrame
import com.catmoecircle.areyouok.gateway.protocol.PingFrame
import com.catmoecircle.areyouok.gateway.protocol.PongFrame
import com.catmoecircle.areyouok.gateway.protocol.ReadyFrame
import com.catmoecircle.areyouok.gateway.protocol.ResumeState
import com.catmoecircle.areyouok.gateway.protocol.ResyncFrame
import com.catmoecircle.areyouok.gateway.protocol.StreamFrame
import com.catmoecircle.areyouok.gateway.protocol.SubFrame
import com.catmoecircle.areyouok.gateway.protocol.UnsubFrame

/**
 * OkHttp 实现的 `/v1/transport`：
 *
 * - REST 交给 [OkHttpGatewayApi]；
 * - WS `/v1/stream` 在本类中实现：hello+resume、app 级心跳、45s 判死、
 *   0.5→30s 指数退避重连（±30% 抖动，稳定 30s 重置）、`seq`/`id` 去重、
 *   `resync` 后清空 seq 重新 hello。
 */
class OkHttpGatewayTransport(
    restClient: OkHttpClient,
    private val streamClient: OkHttpClient,
    private val endpointProvider: () -> GatewayEndpoint?,
    private val deviceIdProvider: () -> String,
) : GatewayTransport {

    override val api: GatewayApi = OkHttpGatewayApi(restClient, endpointProvider)

    private val subscriptionLock = Any()
    private val subscriptions = LinkedHashSet<String>()
    private val activeSocket = AtomicReference<WebSocket?>(null)

    @Volatile
    private var shutdownRequested = false

    override fun subscribe(topic: String) {
        val added = synchronized(subscriptionLock) { subscriptions.add(topic) }
        if (added) {
            activeSocket.get()?.send(GatewayJson.encodeFrame(SubFrame(id = topic, topic = topic)))
        }
    }

    override fun unsubscribe(topic: String) {
        synchronized(subscriptionLock) { subscriptions.remove(topic) }
        activeSocket.get()?.send(GatewayJson.encodeFrame(UnsubFrame(id = topic)))
    }

    override fun shutdown() {
        shutdownRequested = true
        activeSocket.getAndSet(null)?.close(NORMAL_CLOSURE, "client shutdown")
    }

    override fun stream(initialResumeSeq: Long): Flow<StreamUpdate> = channelFlow {
        shutdownRequested = false
        val tracker = SeqTracker(initialResumeSeq)
        var resumeSeq = initialResumeSeq
        var attempt = 0

        while (isActive && !shutdownRequested) {
            val endpoint = endpointProvider()
            if (endpoint == null) {
                trySend(StreamUpdate.Disconnected(null, attempt + 1, 1_000L))
                delay(1_000L)
                continue
            }

            val openedAt = AtomicLong(0L)
            val lastInboundAt = AtomicLong(monotonicMs())
            val resyncRequested = AtomicBoolean(false)
            val closeSignal = CompletableDeferred<Throwable?>()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val now = monotonicMs()
                    openedAt.set(now)
                    lastInboundAt.set(now)
                    activeSocket.set(webSocket)
                    val hello = HelloFrame(
                        deviceId = deviceIdProvider(),
                        resume = if (resumeSeq > 0L) ResumeState(resumeSeq) else null,
                    )
                    webSocket.send(GatewayJson.encodeFrame(hello))
                    subscribeOnConnect(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastInboundAt.set(monotonicMs())
                    when (val frame: StreamFrame? = GatewayJson.decodeFrame(text)) {
                        null -> Unit

                        is ReadyFrame -> {
                            trySend(StreamUpdate.Ready(frame))
                        }

                        is EventFrame -> {
                            if (tracker.shouldApply(frame.seq, frame.id)) {
                                resumeSeq = tracker.lastAppliedSeq()
                                trySend(StreamUpdate.Event(frame))
                                frame.seq?.let {
                                    webSocket.send(GatewayJson.encodeFrame(AckFrame(it)))
                                }
                            }
                        }

                        is ResyncFrame -> {
                            resyncRequested.set(true)
                            trySend(StreamUpdate.Resynced(frame.reason))
                            webSocket.close(NORMAL_CLOSURE, "resync")
                        }

                        is ErrorFrame -> {
                            if (frame.code == "unauthenticated") {
                                closeSignal.complete(
                                    GatewayException(
                                        GatewayException.CODE_UNAUTHENTICATED,
                                        401,
                                        frame.message,
                                    ),
                                )
                            } else {
                                trySend(StreamUpdate.ProtocolError(frame.code, frame.message))
                            }
                        }

                        is PingFrame -> {
                            // 服务端 app 级心跳，回一个 pong。
                            webSocket.send(GatewayJson.encodeFrame(PongFrame(frame.ts)))
                        }

                        else -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    activeSocket.compareAndSet(webSocket, null)
                    val mapped = if (response != null && !response.isSuccessful) {
                        GatewayException(
                            code = when (response.code) {
                                401 -> GatewayException.CODE_UNAUTHENTICATED
                                403 -> GatewayException.CODE_FORBIDDEN_SOURCE
                                404 -> GatewayException.CODE_NOT_FOUND
                                else -> "http_${response.code}"
                            },
                            httpStatus = response.code,
                            detail = "WebSocket 握手失败 (${response.code})",
                            cause = t,
                        )
                    } else {
                        t
                    }
                    closeSignal.complete(mapped)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    activeSocket.compareAndSet(webSocket, null)
                    closeSignal.complete(null)
                }
            }

            val request = Request.Builder()
                .url(endpoint.wsUrl)
                .apply {
                    endpoint.token?.takeIf { it.isNotBlank() }?.let {
                        header("Authorization", "Bearer $it")
                    }
                }
                .build()

            try {
                streamClient.newWebSocket(request, listener)
            } catch (error: Throwable) {
                activeSocket.set(null)
                val delayMs = ReconnectPolicy.delayMs(++attempt)
                trySend(StreamUpdate.Disconnected(error, attempt, delayMs))
                delay(delayMs)
                continue
            }

            val heartbeat = launch {
                while (isActive) {
                    delay(ReconnectPolicy.PING_INTERVAL_MS)
                    val socket = activeSocket.get() ?: break
                    if (monotonicMs() - lastInboundAt.get() > ReconnectPolicy.IDLE_TIMEOUT_MS) {
                        socket.cancel()
                        closeSignal.complete(IOException("心跳超时（${ReconnectPolicy.IDLE_TIMEOUT_MS}ms 无帧）"))
                        break
                    }
                    socket.send(GatewayJson.encodeFrame(PingFrame(monotonicMs())))
                }
            }

            val cause = closeSignal.await()
            heartbeat.cancel()
            activeSocket.set(null)

            if (resyncRequested.get()) {
                tracker.reset()
                resumeSeq = 0L
                attempt = 0
                delay(RESYNC_PAUSE_MS)
                continue
            }

            if (shutdownRequested) break

            val stable = openedAt.get() > 0L &&
                monotonicMs() - openedAt.get() >= ReconnectPolicy.STABLE_RESET_MS
            attempt = if (stable) 1 else (attempt + 1).coerceAtMost(MAX_ATTEMPTS)
            val delayMs = ReconnectPolicy.delayMs(attempt)
            trySend(StreamUpdate.Disconnected(cause, attempt, delayMs))
            delay(delayMs)
        }
    }

    private fun subscribeOnConnect(webSocket: WebSocket) {
        val topics = synchronized(subscriptionLock) { subscriptions.toList() }
        topics.forEach { topic ->
            webSocket.send(GatewayJson.encodeFrame(SubFrame(id = topic, topic = topic)))
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val MAX_ATTEMPTS = 8
        private const val RESYNC_PAUSE_MS = 200L

        /** 默认 OkHttp 客户端：REST 带超时，WS 关闭读超时由 app 级心跳兜底。 */
        fun clients(): Pair<OkHttpClient, OkHttpClient> = GatewayHttpClients.rest() to GatewayHttpClients.stream()
    }
}

/** OkHttp 客户端工厂，保持 REST 与 WS 的超时策略分离。 */
object GatewayHttpClients {

    fun rest(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun stream(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()
}
