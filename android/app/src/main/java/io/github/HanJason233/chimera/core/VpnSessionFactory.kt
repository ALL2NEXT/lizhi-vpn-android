package io.github.HanJason233.chimera.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.core.hev.HevSocksTunAdapter
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.data.repository.VpnRuntimeSettings
import io.github.HanJason233.chimera.platform.AndroidVpnHost
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Builds a complete VPN session without leaking data-plane concerns into a core backend. */
internal class VpnSessionFactory(
    private val registry: CoreRegistry = CoreBackends.registry,
    private val dataPlane: TunAdapter = HevSocksTunAdapter()
) {
    suspend fun prepare(
        context: Context,
        node: Node,
        settings: VpnRuntimeSettings,
        failoverNodes: List<Node> = emptyList()
    ): PreparedVpnSession = withContext(Dispatchers.IO) {
        val backend = registry.resolve(settings.protocolCoreSettings.coreFor(node.type), node)
        val outboundNodes = (listOf(node) + failoverNodes)
            .distinctBy(Node::id)
            .filter { candidate ->
                settings.protocolCoreSettings.coreFor(candidate.type) == backend.id &&
                    backend.supports(candidate)
            }
        val reservations = mutableListOf(PortReservation())
        try {
            val endpoint = LocalSocksEndpoint(
                LocalSocksEndpoint.LOOPBACK,
                reservations.first().port,
                SocksCredentials("vpn", UUID.randomUUID().toString().replace("-", ""))
            )
            val failoverProbeEndpoints = if (outboundNodes.size > 1) {
                outboundNodes.associate { candidate ->
                    val reservation = PortReservation().also(reservations::add)
                    candidate.id to LocalSocksEndpoint(LocalSocksEndpoint.LOOPBACK, reservation.port)
                }
            } else {
                emptyMap()
            }
            val request = CoreRequest(
                node = node,
                endpoint = endpoint,
                settings = settings,
                outboundNodes = outboundNodes,
                failoverProbeEndpoints = failoverProbeEndpoints
            )
            PreparedVpnSession(backend, backend.prepare(context, request), reservations, outboundNodes)
        } catch (error: Throwable) {
            reservations.forEach(PortReservation::close)
            throw error
        }
    }

    suspend fun start(
        context: Context,
        scope: CoroutineScope,
        prepared: PreparedVpnSession,
        host: AndroidVpnHost,
        onFailure: (String) -> Unit
    ): CoreSession {
        prepared.releasePortForBinding()
        val core = prepared.backend.start(context, scope, prepared.core, host, onFailure)
        return try {
            val adapter = dataPlane.start(
                context,
                host,
                prepared.endpoint,
                prepared.core.request.settings!!.ipFamilySettings,
                onFailure
            )
            combineCoreSession(core, adapter)
        } catch (error: Throwable) {
            core.close()
            throw error
        }
    }
}

internal class PreparedVpnSession(
    internal val backend: CoreBackend,
    internal val core: PreparedCore,
    private val reservations: List<PortReservation>,
    val outboundNodes: List<Node>
) : AutoCloseable {
    val selectedCore: ProxyCore get() = backend.id
    val endpoint: LocalSocksEndpoint get() = core.request.endpoint

    internal fun releasePortForBinding() = reservations.forEach(PortReservation::close)
    override fun close() = reservations.forEach(PortReservation::close)
}

internal class PortReservation : AutoCloseable {
    private val closed = AtomicBoolean()
    private val socket = ServerSocket(0, 1, InetAddress.getByName(LocalSocksEndpoint.LOOPBACK))
    val port: Int get() = socket.localPort

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
