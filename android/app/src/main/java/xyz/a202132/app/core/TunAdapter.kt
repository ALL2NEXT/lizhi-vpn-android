package xyz.a202132.app.core

import android.content.Context
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.platform.AndroidVpnHost

/**
 * Optional data-plane adapter for cores that expose a local SOCKS5 inbound
 * but do not provide a stable Android TUN FD integration.
 */
internal interface TunAdapter {
    suspend fun start(
        context: Context,
        host: AndroidVpnHost,
        socksPort: Int,
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
    CombinedCoreSession(core, adapter)

private class CombinedCoreSession(
    private val delegate: CoreSession,
    private val adapter: TunAdapterSession
) : CoreSession {
    override val core = delegate.core
    override val socksPort = delegate.socksPort

    override fun traffic(): CoreTraffic? = adapter.traffic() ?: delegate.traffic()

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
