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

    override fun selectedOutboundTag(): String? = delegate.selectedOutboundTag()

    override fun selectOutboundTag(tag: String): Boolean = delegate.selectOutboundTag(tag)

    // These inputs are disjoint: HEV counts phone traffic, and the core counts
    // only LAN ingress. Whole-core counters would count the phone twice.
    override fun traffic(): CoreTraffic? {
        val phone = adapter.traffic()
        val lan = delegate.lanTraffic()
        if (phone == null && lan == null) return null
        return CoreTraffic(
            (phone?.upload ?: 0) + (lan?.upload ?: 0),
            (phone?.download ?: 0) + (lan?.download ?: 0)
        )
    }

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
