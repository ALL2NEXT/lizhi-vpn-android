package io.github.HanJason233.chimera.core.hev

import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.IpFamilyRoutingSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.toIpFamilyRoutingSettings
import io.github.HanJason233.chimera.platform.IpPrefix
import io.github.HanJason233.chimera.platform.TunnelSpec

/** HEV 的外部 TUN 配置；后端核心只需提供 SOCKS5 入站。 */
internal object HevSocksTunConfig {
    fun tunnel(settings: IpFamilyRoutingSettings): TunnelSpec {
        val normalized = settings.normalized()
        val addresses = mutableListOf<IpPrefix>()
        val routes = mutableListOf<IpPrefix>()
        if (normalized.ipv4Enabled) {
            addresses += IpPrefix("172.19.0.1", 30)
            routes += IpPrefix("0.0.0.0", 0)
        }
        if (normalized.ipv6Enabled) {
            addresses += IpPrefix("fdfe:dcba:9876::1", 126)
            routes += IpPrefix("::", 0)
        }
        val dnsServer = if (normalized.ipv4Enabled) {
            AppConfig.VPN_DNS_PRIMARY
        } else {
            AppConfig.VPN_DNS_PRIMARY_IPV6
        }
        return TunnelSpec(AppConfig.VPN_MTU, addresses, routes, listOf(dnsServer))
    }

    @Deprecated("Use IpFamilyRoutingSettings")
    fun tunnel(mode: IPv6RoutingMode): TunnelSpec = tunnel(mode.toIpFamilyRoutingSettings())

    fun render(endpoint: LocalSocksEndpoint): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: chimera-hev")
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
