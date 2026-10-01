package io.github.HanJason233.chimera.core.hev

import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.platform.IpPrefix
import io.github.HanJason233.chimera.platform.TunnelSpec

/** HEV's external-TUN configuration; the core behind it only needs SOCKS5. */
internal object HevSocksTunConfig {
    fun tunnel(mode: IPv6RoutingMode): TunnelSpec {
        val addresses = mutableListOf(IpPrefix("172.19.0.1", 30))
        val routes = mutableListOf(IpPrefix("0.0.0.0", 0))
        if (mode != IPv6RoutingMode.DISABLED) {
            addresses += IpPrefix("fdfe:dcba:9876::1", 126)
            routes += IpPrefix("::", 0)
        }
        return TunnelSpec(AppConfig.VPN_MTU, addresses, routes, listOf(AppConfig.VPN_DNS_PRIMARY))
    }

    fun render(endpoint: LocalSocksEndpoint): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: firefly-hev")
        appendLine("  mtu: ${AppConfig.VPN_MTU}")
        appendLine("  multi-queue: false")
        appendLine("  icmp: 'off'")
        appendLine("socks5:")
        appendLine("  address: ${endpoint.address}")
        appendLine("  port: ${endpoint.port}")
        endpoint.credentials?.let { credentials ->
            appendLine("  username: '${yamlString(credentials.username)}'")
            appendLine("  password: '${yamlString(credentials.password)}'")
        }
        appendLine("  udp: 'udp'")
        appendLine("  pipeline: false")
        appendLine("misc:")
        appendLine("  max-session-count: 1200")
        appendLine("  log-level: error")
    }

    private fun yamlString(value: String): String = value.replace("'", "''")
}
