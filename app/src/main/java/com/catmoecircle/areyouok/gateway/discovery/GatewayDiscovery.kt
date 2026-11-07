package com.catmoecircle.areyouok.gateway.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.catmoecircle.areyouok.gateway.transport.GatewayEndpoint
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** mDNS 发现的网关实例（`_dshgw._tcp`）。 */
data class DiscoveredGateway(
    val serviceName: String,
    val name: String,
    val host: String?,
    val lanPort: Int,
    val pairPort: Int? = null,
    val apiPath: String = "/v1",
    val version: Int? = null,
    val addresses: List<String> = emptyList(),
) {
    /** 局域网 REST 基址；解析不到主机时返回 null。 */
    val baseUrl: String?
        get() = host?.let { hostPort(it, lanPort) }

    fun toEndpoint(token: String? = null): GatewayEndpoint? =
        baseUrl?.let { GatewayEndpoint(baseUrl = it, token = token, serverId = it, name = name) }

    companion object {
        fun hostPort(host: String, port: Int): String =
            if (host.contains(':')) "http://[$host]:$port" else "http://$host:$port"
    }
}

/** TXT 记录解析：`v=1,name=my-pc,lan=7788,pair=7789,api=/v1`（交接文档 §5.7）。 */
object GatewayTxt {
    fun parse(
        serviceName: String,
        attributes: Map<String, ByteArray>,
        host: String?,
        addresses: List<String> = emptyList(),
    ): DiscoveredGateway? {
        fun value(key: String): String? =
            attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

        val lanPort = value("lan")?.toIntOrNull()
        if (lanPort == null || lanPort !in 1..65535) return null
        val version = value("v")?.toIntOrNull()
        if (version != null && version != 1) return null
        return DiscoveredGateway(
            serviceName = serviceName,
            name = value("name") ?: serviceName,
            host = host ?: addresses.firstOrNull(),
            lanPort = lanPort,
            pairPort = value("pair")?.toIntOrNull(),
            apiPath = value("api") ?: "/v1",
            version = version,
            addresses = addresses,
        )
    }
}

/**
 * `NsdManager` 的 `_dshgw._tcp` 浏览器。
 *
 * NsdManager 同一时间只允许一个 resolve，因此发现到的服务先进队列，
 * 由单个 worker 串行解析，避免 `FAILURE_ALREADY_ACTIVE`。
 */
class GatewayDiscovery(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _gateways = MutableStateFlow<List<DiscoveredGateway>>(emptyList())
    val gateways: StateFlow<List<DiscoveredGateway>> = _gateways.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private val resolving = AtomicBoolean(false)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var worker: kotlinx.coroutines.Job? = null

    fun start() {
        val manager = nsdManager ?: return
        if (_running.value) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _running.value = true
                Log.d(TAG, "discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "service found: ${serviceInfo.serviceName}")
                queue.trySend(serviceInfo)
                ensureWorker()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _gateways.value = _gateways.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _running.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                _running.value = false
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed: $errorCode")
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "discoverServices failed", it)
                _running.value = false
                discoveryListener = null
            }
    }

    fun stop() {
        val manager = nsdManager ?: return
        discoveryListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        _running.value = false
        worker?.cancel()
        worker = null
    }

    fun refresh() {
        stop()
        _gateways.value = emptyList()
        start()
    }

    private fun ensureWorker() {
        if (resolving.compareAndSet(false, true)) {
            worker = scope.launch {
                try {
                    while (true) {
                        val info = queue.receive()
                        runCatching { resolveAndPublish(info) }
                    }
                } finally {
                    resolving.set(false)
                }
            }
        }
    }

    private suspend fun resolveAndPublish(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        val resolved = resolve(manager, serviceInfo) ?: return
        val addresses = addressList(resolved)
        val host = resolved.host?.hostAddress ?: addresses.firstOrNull()
        val gateway = GatewayTxt.parse(
            serviceName = resolved.serviceName ?: serviceInfo.serviceName,
            attributes = resolved.attributes.orEmpty(),
            host = host,
            addresses = addresses,
        ) ?: return
        _gateways.value = (_gateways.value.filterNot { it.serviceName == gateway.serviceName } + gateway)
            .sortedBy { it.name.lowercase() }
    }

    private suspend fun resolve(manager: NsdManager, serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "resolve failed ${info.serviceName}: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(info)
                }
            }
            runCatching { manager.resolveService(serviceInfo, listener) }
                .onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    private fun addressList(info: NsdServiceInfo): List<String> {
        val addresses = mutableListOf<String>()
        @Suppress("DEPRECATION")
        info.host?.hostAddress?.let { addresses += it }
        runCatching {
            // API 34+ 才有 hostAddresses；用反射避免编译期版本差异。
            val method = NsdServiceInfo::class.java.getMethod("getHostAddresses")
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (method.invoke(info) as? Collection<InetAddress>)?.forEach { address ->
                address.hostAddress?.let { addresses += it }
            }
        }
        return addresses.distinct().sortedBy { if (it.contains(':')) 1 else 0 }
    }

    /** 优先 IPv4，局域网 UDP 扫描/HTTP 直连在手机上更稳。 */
    fun preferredAddress(gateway: DiscoveredGateway): String? =
        gateway.addresses.firstOrNull { it.contains('.') } ?: gateway.host

    companion object {
        const val SERVICE_TYPE = "_dshgw._tcp"
        private const val TAG = "GatewayDiscovery"
    }
}
