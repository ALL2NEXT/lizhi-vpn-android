package xyz.a202132.app.data.model

import java.net.URI

/** DNS 域名解析策略；auto 会根据 IPv6 路由模式选择实际策略。 */
enum class DnsDomainStrategy(
    val configValue: String,
    val displayName: String
) {
    AUTO("auto", "自动"),
    PREFER_IPV6("prefer_ipv6", "优先 IPv6"),
    PREFER_IPV4("prefer_ipv4", "优先 IPv4"),
    IPV4_ONLY("ipv4_only", "仅 IPv4"),
    IPV6_ONLY("ipv6_only", "仅 IPv6");

    companion object {
        fun fromStorage(value: String?): DnsDomainStrategy =
            entries.firstOrNull { it.name == value || it.configValue == value } ?: AUTO
    }
}

data class DnsSettings(
    val remoteDns: String = DEFAULT_REMOTE_DNS,
    val remoteDomainStrategy: DnsDomainStrategy = DnsDomainStrategy.AUTO,
    val directDns: String = DEFAULT_DIRECT_DNS,
    val directDomainStrategy: DnsDomainStrategy = DnsDomainStrategy.AUTO,
    val serverDomainStrategy: DnsDomainStrategy = DnsDomainStrategy.AUTO,
    val dnsRoutingEnabled: Boolean = true,
    val fakeDnsEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_REMOTE_DNS = "https://dns.google/dns-query"
        const val DEFAULT_DIRECT_DNS = "https://223.5.5.5/dns-query"

        /** 支持一行一个 DNS 地址，兼容 NekoBox 的多服务器输入方式。 */
        fun normalizeServerList(value: String?): String? {
            val entries = value.orEmpty()
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            return entries.takeIf { it.isNotEmpty() && it.all(::isValidServer) }
                ?.joinToString("\n")
        }

        private fun isValidServer(value: String): Boolean {
            if (value.any(Char::isWhitespace)) return false
            val uri = runCatching {
                URI(if (value.contains("://")) value else "udp://$value")
            }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme !in setOf("udp", "tcp", "tls", "https", "h2c")) return false
            if (uri.host.isNullOrBlank()) return false
            return uri.port == -1 || uri.port in 1..65_535
        }
    }
}
