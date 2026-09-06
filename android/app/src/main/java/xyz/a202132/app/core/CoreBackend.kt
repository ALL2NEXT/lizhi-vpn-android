package xyz.a202132.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.data.repository.VpnRuntimeSettings
import xyz.a202132.app.platform.UnderlayController

/** Control-plane contract. Packet buffers never cross this boundary. */
internal interface CoreBackend {
    val id: ProxyCore
    val capabilities: CoreCapabilities
    suspend fun prepare(context: Context, request: CoreRequest): PreparedCore
    suspend fun start(
        context: Context,
        scope: CoroutineScope,
        prepared: PreparedCore,
        underlay: UnderlayController?,
        onFailure: (String) -> Unit
    ): CoreSession
}

internal enum class DnsHandling {
    /** The data plane forwards DNS packets unchanged and the proxy core answers them. */
    CORE
}

internal data class CoreCapabilities(
    val protocols: Set<NodeType>,
    /** The core must expose a local SOCKS5 inbound for the HEV data plane. */
    val socks5Inbound: Boolean = true,
    val dnsHandling: DnsHandling = DnsHandling.CORE
)

data class SocksCredentials(val username: String, val password: String) {
    init {
        require(username.isNotEmpty() && password.isNotEmpty())
        require(username.toByteArray(Charsets.UTF_8).size <= 255)
        require(password.toByteArray(Charsets.UTF_8).size <= 255)
    }
}

data class LocalSocksEndpoint(
    val address: String,
    val port: Int,
    val credentials: SocksCredentials? = null
) {
    init {
        require(address == LOOPBACK) { "Only a loopback SOCKS endpoint is supported" }
        require(port in 1..65_535) { "Invalid local SOCKS port" }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        fun unauthenticated(port: Int) = LocalSocksEndpoint(LOOPBACK, port)
    }
}

internal data class CoreRequest(
    val node: Node,
    val endpoint: LocalSocksEndpoint,
    val settings: VpnRuntimeSettings? = null,
    val hysteriaUpMbps: Int = settings?.hysteriaUpMbps ?: 0,
    val hysteriaDownMbps: Int = settings?.hysteriaDownMbps ?: 0
) {
    constructor(
        node: Node,
        socksPort: Int,
        settings: VpnRuntimeSettings? = null,
        hysteriaUpMbps: Int = settings?.hysteriaUpMbps ?: 0,
        hysteriaDownMbps: Int = settings?.hysteriaDownMbps ?: 0
    ) : this(node, LocalSocksEndpoint.unauthenticated(socksPort), settings, hysteriaUpMbps, hysteriaDownMbps)

    val isVpn: Boolean get() = settings != null
    val socksPort: Int get() = endpoint.port
}

internal data class PreparedCore(val core: ProxyCore, val request: CoreRequest, val config: String)

internal data class CoreTraffic(val upload: Long, val download: Long)

internal interface CoreSession : AutoCloseable {
    val core: ProxyCore
    val endpoint: LocalSocksEndpoint
    val socksPort: Int get() = endpoint.port
    fun traffic(): CoreTraffic? = null
    /** Idempotent; must release native resources before returning. */
    override fun close()
}

/** Registering a backend is the only dispatch point used by VPN and probes. */
internal class CoreRegistry(backends: List<CoreBackend>) {
    private val entries = backends.associateBy { it.id }

    init {
        backends.forEach { backend ->
            require(backend.capabilities.socks5Inbound) {
                "${backend.id.displayName} requires a SOCKS5 inbound for HEV"
            }
            require(backend.capabilities.dnsHandling == DnsHandling.CORE) {
                "${backend.id.displayName} must handle DNS forwarded by the data plane"
            }
        }
        require(entries.size == backends.size) { "重复注册内核" }
    }

    fun backend(id: ProxyCore): CoreBackend = entries[id]
        ?: error("${id.displayName} 内核尚未安装")

    fun availableFor(type: NodeType): List<ProxyCore> = entries.values
        .filter { type in it.capabilities.protocols }.map { it.id }

    fun resolve(id: ProxyCore, node: Node): CoreBackend = backend(id).also {
        require(node.type in it.capabilities.protocols) {
            "${id.displayName} 不支持 ${node.type.protocol} 协议"
        }
    }
}
