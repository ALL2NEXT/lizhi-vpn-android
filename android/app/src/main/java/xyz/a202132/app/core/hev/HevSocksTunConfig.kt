package xyz.a202132.app.core.hev

import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.platform.IpPrefix
import xyz.a202132.app.platform.TunnelSpec

/** HEV's external-TUN configuration; the core behind it only needs SOCKS5. */
internal object HevSocksTunConfig {
    private const val MAPPED_DNS = "198.18.0.2"
    private const val MAPPED_NETWORK = "198.18.0.0"
    private const val MAPPED_NETMASK = "255.255.0.0"

    fun tunnel(mode: IPv6RoutingMode, mappedDns: Boolean = true): TunnelSpec {
        require(mode != IPv6RoutingMode.ONLY) {
            "HEV fallback does not support IPv6-only routing; use the core native TUN"
        }
        val addresses = mutableListOf<IpPrefix>()
        val routes = mutableListOf<IpPrefix>()
        if (mode != IPv6RoutingMode.ONLY) {
            addresses += IpPrefix("172.19.0.1", 30)
            routes += IpPrefix("0.0.0.0", 0)
        }
        if (mode != IPv6RoutingMode.DISABLED) {
            addresses += IpPrefix("fdfe:dcba:9876::1", 126)
            routes += IpPrefix("::", 0)
        }
        val dns = if (mappedDns) MAPPED_DNS else AppConfig.VPN_DNS_PRIMARY
        return TunnelSpec(AppConfig.VPN_MTU, addresses, routes, listOf(dns))
    }

    fun render(socksPort: Int, mappedDns: Boolean = true): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: firefly-hev")
        appendLine("  mtu: ${AppConfig.VPN_MTU}")
        appendLine("  multi-queue: false")
        appendLine("  icmp: 'off'")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: $socksPort")
        appendLine("  udp: 'udp'")
        appendLine("  pipeline: false")
        if (mappedDns) {
            appendLine("mapdns:")
            appendLine("  address: $MAPPED_DNS")
            appendLine("  port: 53")
            appendLine("  network: $MAPPED_NETWORK")
            appendLine("  netmask: $MAPPED_NETMASK")
            appendLine("  cache-size: 10000")
        }
        appendLine("misc:")
        appendLine("  max-session-count: 1200")
        appendLine("  log-level: error")
    }
}
