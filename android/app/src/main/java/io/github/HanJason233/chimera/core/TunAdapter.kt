package io.github.HanJason233.chimera.core

import android.content.Context
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.platform.TunProvider

/**
 * The single Android VPN data-plane adapter. Cores expose a local SOCKS5
 * inbound while HEV owns the Android TUN.
 */
internal interface TunAdapter {
    suspend fun start(
        context: Context,
        tunProvider: TunProvider,
        endpoint: LocalSocksEndpoint,
        ipv6RoutingMode: IPv6RoutingMode,
        onFailure: (String) -> Unit
    ): TunAdapterSession
}

internal interface TunAdapterSession : AutoCloseable {
    fun traffic(): CoreTraffic?
    override fun close()
}

/**
 * Owns the core and the packet adapter as one session. The adapter is closed
 * first so no new SOCKS requests arrive while the core is being torn down.
 */
internal fun combineCoreSession(core: CoreSession, adapter: TunAdapterSession): CoreSession =
    VpnSession(core, adapter)

private class VpnSession(
    private val delegate: CoreSession,
    private val adapter: TunAdapterSession
) : CoreSession {
    override val core = delegate.core
    override val endpoint = delegate.endpoint

    // HEV is the sole VPN traffic authority. Mixing counters from different
    // layers makes final accounting depend on sampling timing.
    override fun traffic(): CoreTraffic? = adapter.traffic()

    override fun close() {
        var failure: Throwable? = null
        try {
            adapter.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            delegate.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error
        }
        failure?.let { throw it }
    }
}
