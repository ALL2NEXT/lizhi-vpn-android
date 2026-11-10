package io.github.HanJason233.chimera.data.model

/** IPv4 和 IPv6 同时启用时优先使用的地址族。 */
enum class IpAddressFamily {
    IPV4,
    IPV6
}

/** 控制哪些 IP 地址族进入 VPN 路由，以及两者同时启用时的优先顺序。 */
data class IpFamilyRoutingSettings(
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val preferredFamily: IpAddressFamily = IpAddressFamily.IPV4
) {
    fun normalized(): IpFamilyRoutingSettings {
        if (!ipv4Enabled && !ipv6Enabled) return DEFAULT
        val effectivePreference = when {
            preferredFamily == IpAddressFamily.IPV4 && ipv4Enabled -> IpAddressFamily.IPV4
            preferredFamily == IpAddressFamily.IPV6 && ipv6Enabled -> IpAddressFamily.IPV6
            ipv4Enabled -> IpAddressFamily.IPV4
            else -> IpAddressFamily.IPV6
        }
        return copy(preferredFamily = effectivePreference)
    }

    fun withIpv4Enabled(enabled: Boolean): IpFamilyRoutingSettings {
        if (!enabled && !ipv6Enabled) return this
        return copy(ipv4Enabled = enabled).normalized()
    }

    fun withIpv6Enabled(enabled: Boolean): IpFamilyRoutingSettings {
        if (!enabled && !ipv4Enabled) return this
        return copy(ipv6Enabled = enabled).normalized()
    }

    fun withPreferredFamily(family: IpAddressFamily): IpFamilyRoutingSettings =
        if (family == IpAddressFamily.IPV4 && ipv4Enabled ||
            family == IpAddressFamily.IPV6 && ipv6Enabled
        ) {
            copy(preferredFamily = family)
        } else {
            this
        }

    /** 解析 DNS 策略，并确保不会选择已关闭的地址族。 */
    fun resolveDnsStrategy(
        strategy: DnsDomainStrategy,
        forServerAddress: Boolean = false
    ): DnsDomainStrategy {
        val settings = normalized()
        if (!settings.ipv4Enabled) return DnsDomainStrategy.IPV6_ONLY
        if (!settings.ipv6Enabled) return DnsDomainStrategy.IPV4_ONLY
        if (strategy != DnsDomainStrategy.AUTO) return strategy
        if (forServerAddress) return DnsDomainStrategy.PREFER_IPV4
        return when (settings.preferredFamily) {
            IpAddressFamily.IPV4 -> DnsDomainStrategy.PREFER_IPV4
            IpAddressFamily.IPV6 -> DnsDomainStrategy.PREFER_IPV6
        }
    }

    fun xrayTargetStrategy(): String {
        val settings = normalized()
        if (!settings.ipv4Enabled) return "UseIPv6"
        if (!settings.ipv6Enabled) return "UseIPv4"
        return when (settings.preferredFamily) {
            IpAddressFamily.IPV4 -> "UseIPv4v6"
            IpAddressFamily.IPV6 -> "UseIPv6v4"
        }
    }

    companion object {
        val DEFAULT = IpFamilyRoutingSettings()
    }
}
