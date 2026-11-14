package com.catmoecircle.areyouok.gateway

import android.content.Context
import android.os.Build
import com.catmoecircle.areyouok.gateway.discovery.GatewayDiscovery
import com.catmoecircle.areyouok.gateway.pairing.KeystoreTokenStore
import com.catmoecircle.areyouok.gateway.pairing.PairInvite
import com.catmoecircle.areyouok.gateway.pairing.PairingManager
import com.catmoecircle.areyouok.gateway.pairing.TokenStore
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.gateway.repository.GatewayRepository
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.GatewayPins
import com.catmoecircle.areyouok.gateway.transport.GatewayTls
import com.catmoecircle.areyouok.gateway.transport.OkHttpGatewayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * App 级单例：把 discovery / transport / pairing / repository 组装起来。
 *
 * UI 与前台服务共享同一实例，因此连接状态只有一份，通知与界面不会各连一条。
 */
class GatewayRuntime private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: GatewaySettings = GatewaySettings(appContext)
    val tokenStore: TokenStore = KeystoreTokenStore(appContext)
    val discovery: GatewayDiscovery = GatewayDiscovery(appContext, scope)

    private val httpClients: Pair<OkHttpClient, OkHttpClient> = OkHttpGatewayTransport.clients()
    private val restClient: OkHttpClient get() = httpClients.first
    private val streamClient: OkHttpClient get() = httpClients.second

    private val endpointRef = MutableStateFlow<GatewayEndpoint?>(null)

    val transport: OkHttpGatewayTransport = OkHttpGatewayTransport(
        restClient = restClient,
        streamClient = streamClient,
        endpointProvider = { endpointRef.value },
        deviceIdProvider = { tokenStore.deviceId() },
    )

    val pairing: PairingManager = PairingManager(transport.api, tokenStore)

    val repository: GatewayRepository = GatewayRepository(transport, scope)

    val connection: StateFlow<ConnectionState> get() = repository.connection

    /** 扫码/深链带进来的配对邀请，等待用户确认。 */
    private val _pendingInvite = MutableStateFlow<PairInvite?>(null)
    val pendingInvite: StateFlow<PairInvite?> = _pendingInvite.asStateFlow()

    /**
     * 通知点击要打开的会话。
     *
     * 通知的 `PendingIntent` 只带 sessionId；Activity 把它转成这个请求，ChatScreen
     * 消费后切到对应对话（等连接就绪/会话列表加载完再切，冷启动也不会被
     * 「默认选中第一个会话」覆盖）。
     */
    data class OpenSessionRequest(val sessionId: String, val stamp: Long)

    private val _openSessionRequest = MutableStateFlow<OpenSessionRequest?>(null)
    val openSessionRequest: StateFlow<OpenSessionRequest?> = _openSessionRequest.asStateFlow()

    fun requestOpenSession(sessionId: String) {
        if (sessionId.isBlank()) return
        _openSessionRequest.value = OpenSessionRequest(sessionId, System.currentTimeMillis())
    }

    fun consumeOpenSession() {
        _openSessionRequest.value = null
    }

    /**
     * App 是否在前台（`MainActivity.onStart`/`onStop` 维护）。
     *
     * 「任务完成」通知用它避免打扰：用户正看着这个会话时不再弹提醒。
     */
    private val _appVisible = MutableStateFlow(false)

    /** 前台/后台状态（可观察）：后台悬浮窗据此在 App 回到前台时自动收起。 */
    val appVisibleFlow: StateFlow<Boolean> = _appVisible.asStateFlow()

    val appVisible: Boolean get() = _appVisible.value

    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
    }

    val deviceName: String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }

    fun offerInvite(invite: PairInvite?) {
        _pendingInvite.value = invite
    }

    fun consumeInvite() {
        _pendingInvite.value = null
    }

    fun startDiscovery() {
        runCatching { discovery.start() }
    }

    fun stopDiscovery() {
        runCatching { discovery.stop() }
    }

    /** 局域网/手动地址直连（带上已保存的 token，如果有）。 */
    fun connectTo(rawBaseUrl: String, name: String? = null): GatewayEndpoint? {
        val endpoint = runCatching { pairing.endpointFor(rawBaseUrl, name) }.getOrNull() ?: return null
        connect(endpoint)
        return endpoint
    }

    fun connect(endpoint: GatewayEndpoint) {
        val withToken = endpoint.copy(
            token = endpoint.token ?: tokenStore.tokenFor(endpoint.serverId),
        )
        // 自签证书网关：进程重启后先把上次固定的证书恢复进内存，再建连接。
        restorePins(withToken.baseUrl)
        endpointRef.value = withToken
        settings.remember(withToken)
        repository.connect(withToken)
        // 已选定网关后不必继续 mDNS 浏览，省电。
        stopDiscovery()
    }

    /**
     * 固定扫码邀请里的自签证书（公网 HTTPS 网关）。
     *
     * 证书只进 App 自己的固定表：不装进系统信任库，也不需要用户去「设置 →
     * 安全 → 安装证书」。固定表随配对落盘，重启后 [connect] 会自动恢复。
     * 局域网明文邀请没有证书字段，这里什么都不做。
     */
    fun adoptInvite(invite: PairInvite?) {
        if (invite == null) return
        val target = invite.publicUrl ?: invite.baseUrl
        val host = target.toHttpUrlOrNull()?.host ?: return
        val der = invite.certificateDer()
        val fingerprint = GatewayTls.normalizeFingerprint(invite.fingerprint)
            ?: der?.let { GatewayTls.fingerprintOf(it) }
            ?: return
        val pin = GatewayPins.install(host, der, fingerprint) ?: return
        settings.savePinnedCertificate(host, GatewayPins.encode(pin))
    }

    /** 该网关固定过证书时返回其 SHA-256 指纹（设置页展示用）。 */
    fun pinnedFingerprint(baseUrl: String?): String? =
        GatewayPins.fingerprintOf(baseUrl?.toHttpUrlOrNull()?.host)

    /** 按主机名恢复持久化的固定证书，避免重连公网网关时 TLS 校验失败。 */
    private fun restorePins(baseUrl: String) {
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return
        if (GatewayPins.lookup(host) != null) return
        GatewayPins.installStored(host, settings.pinnedCertificate(host))
    }

    fun connectLast(): Boolean {
        val baseUrl = settings.lastBaseUrl ?: return false
        val endpoint = runCatching { pairing.endpointFor(baseUrl, settings.lastName) }.getOrNull()
            ?: return false
        connect(endpoint)
        return true
    }

    fun reconnect() {
        val endpoint = endpointRef.value
        if (endpoint != null) {
            connect(endpoint)
            return
        }
        // 断开连接或进程重启后没有内存里的 endpoint，回退到上次使用的网关。
        connectLast()
    }

    fun disconnect() {
        repository.disconnect()
        endpointRef.value = null
    }
    fun forget(baseUrl: String) {
        pairing.forget(baseUrl)
    }

    companion object {
        @Volatile
        private var instance: GatewayRuntime? = null

        fun get(context: Context): GatewayRuntime =
            instance ?: synchronized(this) {
                instance ?: GatewayRuntime(context).also { instance = it }
            }
    }
}
