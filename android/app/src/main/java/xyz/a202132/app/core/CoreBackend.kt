package xyz.a202132.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.data.repository.VpnRuntimeSettings
import xyz.a202132.app.platform.AndroidVpnHost

/** Control-plane contract. Packet buffers never cross this boundary. */
internal interface CoreBackend {
    val id: ProxyCore
    val capabilities: CoreCapabilities
    suspend fun prepare(context: Context, request: CoreRequest): PreparedCore
    suspend fun start(
        context: Context,
        scope: CoroutineScope,
        prepared: PreparedCore,
        host: AndroidVpnHost?,
        onFailure: (String) -> Unit
    ): CoreSession
}

internal data class CoreCapabilities(
    val protocols: Set<NodeType>,
    /** The core must expose a local SOCKS5 inbound for the HEV data plane. */
    val socks5Inbound: Boolean = true
)

internal data class CoreRequest(
    val node: Node,
    val socksPort: Int,
    val settings: VpnRuntimeSettings? = null,
    val hysteriaUpMbps: Int = settings?.hysteriaUpMbps ?: 0,
    val hysteriaDownMbps: Int = settings?.hysteriaDownMbps ?: 0
) {
    val isVpn: Boolean get() = settings != null
}

internal data class PreparedCore(val core: ProxyCore, val request: CoreRequest, val config: String)

internal data class CoreTraffic(val upload: Long, val download: Long)

internal interface CoreSession : AutoCloseable {
    val core: ProxyCore
    val socksPort: Int
    fun traffic(): CoreTraffic?
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
