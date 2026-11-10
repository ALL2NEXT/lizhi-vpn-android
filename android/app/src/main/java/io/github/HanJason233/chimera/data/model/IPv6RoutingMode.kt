package io.github.HanJason233.chimera.data.model

/** 为迁移旧版本保存的 IPv6 路由设置而保留的兼容枚举。 */
@Deprecated("Use IpFamilyRoutingSettings")
enum class IPv6RoutingMode {
    DISABLED,
    ENABLED,
    PREFER
}

@Deprecated("Use IpFamilyRoutingSettings")
fun IPv6RoutingMode.toIpFamilyRoutingSettings(): IpFamilyRoutingSettings = when (this) {
    IPv6RoutingMode.DISABLED -> IpFamilyRoutingSettings()
    IPv6RoutingMode.ENABLED -> IpFamilyRoutingSettings(
        ipv4Enabled = true,
        ipv6Enabled = true,
        preferredFamily = IpAddressFamily.IPV4
    )
    IPv6RoutingMode.PREFER -> IpFamilyRoutingSettings(
        ipv4Enabled = true,
        ipv6Enabled = true,
        preferredFamily = IpAddressFamily.IPV6
    )
}

@Deprecated("Use IpFamilyRoutingSettings")
fun IpFamilyRoutingSettings.toLegacyIPv6RoutingMode(): IPv6RoutingMode = when {
    !ipv6Enabled -> IPv6RoutingMode.DISABLED
    preferredFamily == IpAddressFamily.IPV6 -> IPv6RoutingMode.PREFER
    else -> IPv6RoutingMode.ENABLED
}
