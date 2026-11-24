package com.catmoecircle.areyouok.gateway

import android.content.Context
import android.os.Build
import android.util.Log
import com.catmoecircle.areyouok.gateway.cert.ClientCertEnrollment
import com.catmoecircle.areyouok.gateway.cert.ClientCertStore
import com.catmoecircle.areyouok.gateway.cert.ClientCertificate
import com.catmoecircle.areyouok.gateway.cert.ClientCerts
import com.catmoecircle.areyouok.gateway.cert.KeystoreClientCertStore
import com.catmoecircle.areyouok.gateway.cert.Pkcs12Tools
import com.catmoecircle.areyouok.gateway.discovery.GatewayDiscovery
import com.catmoecircle.areyouok.gateway.pairing.KeystoreTokenStore
import com.catmoecircle.areyouok.gateway.pairing.PairInvite
import com.catmoecircle.areyouok.gateway.pairing.PairingManager
import com.catmoecircle.areyouok.gateway.pairing.TokenStore
import com.catmoecircle.areyouok.gateway.repository.ConnectionPhase
import com.catmoecircle.areyouok.gateway.repository.ConnectionState
import com.catmoecircle.areyouok.gateway.repository.GatewayRepository
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import com.catmoecircle.areyouok.gateway.transport.GatewayPins
import com.catmoecircle.areyouok.gateway.transport.GatewayTls
import com.catmoecircle.areyouok.gateway.transport.OkHttpGatewayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    /** 客户端证书持久化（公网强制双向 TLS，见 `docs/dsh-gateway-mtls.md` §7.1）。 */
    val clientCerts: ClientCertStore = KeystoreClientCertStore(appContext)

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

    /** 自动领证失败过的「网关 + CA 指纹」：一次安装里不重复打端点（重连不刷屏）。 */
    private val autoEnrollFailedFor: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val autoEnrollRunning = AtomicBoolean(false)

    init {
        // 进程启动时把持久化的客户端证书装进内存表：握手时 KeyManager 直接可用。
        ClientCerts.load(clientCerts)
        observeAutoEnroll()
    }

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

    /** 前台/后台状态（可观察）：决定是否还要发「任务完成」等后台提醒。 */
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

    // ------------------------------------------------------ 客户端证书（公网 mTLS）

    /** 设置页「客户端证书」列表（内存表快照，按安装顺序）。 */
    fun clientCertificateSummary(): List<ClientCertificate> = ClientCerts.snapshot()

    /**
     * 从文件导入网关导出的 .p12（A7）。
     *
     * 先校验能被 PKCS#12 打开，再装进内存表并加密落盘。
     *
     * @throws IllegalArgumentException 密码错误/文件损坏/没有私钥（中文消息，可直接展示）
     */
    fun importClientCertificate(bytes: ByteArray, password: String): ClientCertificate {
        val certificate = ClientCertEnrollment.fromPkcs12(bytes, password)
        if (!ClientCerts.install(certificate)) {
            throw IllegalArgumentException("证书无法导入：PKCS#12 里的私钥与证书链无法使用")
        }
        clientCerts.save(certificate)
        return certificate
    }

    /** 删除一张本机客户端证书；不存在时返回 false。 */
    fun removeClientCertificate(serial: String): Boolean {
        if (ClientCerts.snapshot().none { it.serial == serial }) return false
        ClientCerts.remove(serial)
        clientCerts.remove(serial)
        return true
    }

    /**
     * 局域网连接成功后自动领证（A9）+ 快过期自动续期。
     *
     * 只监听「刚连上」这一瞬：连接态后续的 serverSeq 更新不再触发。整套流程
     * 都吞异常：老网关没有端点（404/403）、网络失败、续期失败都只记日志，
     * 不阻塞连接与聊天。
     */
    private fun observeAutoEnroll() {
        scope.launch {
            var wasConnected = false
            repository.connection.collect { state ->
                if (state.phase != ConnectionPhase.CONNECTED) {
                    wasConnected = false
                    return@collect
                }
                if (wasConnected) return@collect
                wasConnected = true
                val endpoint = state.endpoint ?: return@collect
                // 领证要发 HTTP 请求：放到独立协程，别堵住连接状态流。
                scope.launch { maybeAutoEnroll(endpoint) }
            }
        }
    }

    /** 真正执行「查 CA → 判断是否缺证书/快过期 → CSR 领证 → 覆盖本地条目」。 */
    private suspend fun maybeAutoEnroll(endpoint: GatewayEndpoint) {
        // 只有局域网明文边（http://）能领证；公网边（https）没有 /v1/pki/* 路由。
        if (!endpoint.baseUrl.startsWith("http://", ignoreCase = true)) return
        if (!autoEnrollRunning.compareAndSet(false, true)) return
        try {
            val ca = try {
                transport.api.caCertificate().ca
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // 老网关没有该端点（404/403）：静默跳过，不影响连接。
                Log.i(TAG, "网关没有客户端证书端点，跳过自动领证", error)
                return
            }
            val caCertificate = Pkcs12Tools.parseCertificatePem(ca?.certificate) ?: return
            val issuerDn = Pkcs12Tools.rfc2253(caCertificate.subjectX500Principal)
            val fingerprint = GatewayTls.normalizeFingerprint(ca?.fingerprint)
                ?: GatewayTls.fingerprintOf(caCertificate)
            val cacheKey = endpoint.serverId + "|" + fingerprint
            if (autoEnrollFailedFor.contains(cacheKey)) return
            // 该 CA 下的证书还在有效期内（距过期 > 30 天）就不动它。
            val now = System.currentTimeMillis()
            if (ClientCerts.hasFreshCertificate(issuerDn, now, ClientCertEnrollment.RENEW_BEFORE_MS)) return

            // 缺证书，或最近一张不足 30 天/已过期：重新走一次 CSR 领证。
            // 网关会撤销同 deviceId 的旧证书；本机按 issuer 覆盖旧条目（续期是本机行为）。
            val certificate = ClientCertEnrollment.enroll(transport.api, tokenStore.deviceId(), deviceName)
            if (certificate == null) {
                autoEnrollFailedFor += cacheKey
                Log.w(TAG, "自动领取客户端证书失败（不影响连接），设置页可手动导入 .p12")
                return
            }
            clientCerts.save(certificate)
            ClientCerts.serialsForIssuer(issuerDn)
                .filter { it != certificate.serial }
                .forEach { stale ->
                    ClientCerts.remove(stale)
                    clientCerts.remove(stale)
                }
            autoEnrollFailedFor -= cacheKey
            Log.i(TAG, "已自动领取客户端证书 " + certificate.serial + "（CA " + certificate.issuer + "）")
        } finally {
            autoEnrollRunning.set(false)
        }
    }

    companion object {
        private const val TAG = "GatewayRuntime"

        @Volatile
        private var instance: GatewayRuntime? = null

        fun get(context: Context): GatewayRuntime =
            instance ?: synchronized(this) {
                instance ?: GatewayRuntime(context).also { instance = it }
            }
    }
}
