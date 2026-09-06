package xyz.a202132.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.data.model.TunImplementation
import xyz.a202132.app.data.model.TunStack
import xyz.a202132.app.data.repository.VpnRuntimeSettings
import xyz.a202132.app.platform.AndroidVpnHost

/** Control-plane contract. Packet buffers never cross this boundary. */
internal interface CoreBackend {
    val id: ProxyCore
    val capabilities: CoreCapabilities
    /** Selects the packet data plane for VPN sessions; HEV is the default. */
    val preferredTunMode: CoreTunMode get() = CoreTunMode.HEV_SOCKS5
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
    val tunStacks: Set<TunStack>,
    val nativeTun: Boolean = true,
    /** A fallback adapter can feed this core through its local SOCKS5 inbound. */
    val socks5Inbound: Boolean = true
)

internal enum class CoreTunMode {
    /** Core owns the Android TUN and its packet stack directly. */
    NATIVE,
    /** HEV owns the Android TUN and forwards packets to the core SOCKS5 inbound. */
    HEV_SOCKS5
}

internal data class CoreTunSelection(
    val mode: CoreTunMode,
    val stack: TunStack
)

/** Resolves one core's persisted TUN preference against its capabilities. */
internal fun CoreBackend.resolveTunSelection(preference: TunImplementation): CoreTunSelection {
    val selection = when (preference) {
        TunImplementation.HEV_SOCKS5 -> CoreTunSelection(CoreTunMode.HEV_SOCKS5, TunStack.GVISOR)
        TunImplementation.GVISOR -> CoreTunSelection(CoreTunMode.NATIVE, TunStack.GVISOR)
        TunImplementation.SYSTEM -> CoreTunSelection(CoreTunMode.NATIVE, TunStack.SYSTEM)
        TunImplementation.MIXED -> CoreTunSelection(CoreTunMode.NATIVE, TunStack.MIXED)
        // Legacy values are only used while migrating old global settings.
        TunImplementation.AUTO -> CoreTunSelection(CoreTunMode.HEV_SOCKS5, TunStack.GVISOR)
        TunImplementation.NATIVE -> CoreTunSelection(CoreTunMode.NATIVE, TunStack.GVISOR)
    }
    when (selection.mode) {
        CoreTunMode.NATIVE -> require(capabilities.nativeTun) {
            "${id.displayName} does not support native TUN"
        }
        CoreTunMode.HEV_SOCKS5 -> require(capabilities.socks5Inbound) {
            "${id.displayName} does not expose a SOCKS5 inbound required by HEV"
        }
    }
    if (selection.mode == CoreTunMode.NATIVE) {
        require(selection.stack in capabilities.tunStacks) {
            "${id.displayName} does not support the ${selection.stack.configValue} TUN stack"
        }
    }
    return selection
}

internal data class CoreRequest(
    val node: Node,
    val socksPort: Int,
    val settings: VpnRuntimeSettings? = null,
    val hysteriaUpMbps: Int = settings?.hysteriaUpMbps ?: 0,
    val hysteriaDownMbps: Int = settings?.hysteriaDownMbps ?: 0,
    val tunMode: CoreTunMode = CoreTunMode.NATIVE,
    val tunStack: TunStack = settings?.tunStack ?: TunStack.GVISOR
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
            when (backend.preferredTunMode) {
                CoreTunMode.NATIVE -> require(backend.capabilities.nativeTun) {
                    "${backend.id.displayName} requires native TUN support"
                }
                CoreTunMode.HEV_SOCKS5 -> require(backend.capabilities.socks5Inbound) {
                    "${backend.id.displayName} requires a SOCKS5 inbound for HEV fallback"
                }
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
