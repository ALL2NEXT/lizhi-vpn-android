package io.github.HanJason233.chimera.data.model

enum class IpAddressProbeSource(val displayName: String) {
    IPIFY("ipify"),
    IPLARK("IPLark"),
    ICANHAZIP("icanhazip")
}

/** 首页手动 IP 出口检测的协议、探针来源和超时设置。 */
data class IpAddressTestSettings(
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = true,
    val enabledProbeSources: Set<IpAddressProbeSource> = IpAddressProbeSource.entries.toSet(),
    val timeoutSeconds: Int = 8
) {
    fun normalized(): IpAddressTestSettings = copy(
        ipv4Enabled = ipv4Enabled || !ipv6Enabled,
        enabledProbeSources = enabledProbeSources.ifEmpty { setOf(IpAddressProbeSource.IPIFY) },
        timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    )

    fun withIpv4Enabled(enabled: Boolean): IpAddressTestSettings =
        if (!enabled && !ipv6Enabled) this else copy(ipv4Enabled = enabled).normalized()

    fun withIpv6Enabled(enabled: Boolean): IpAddressTestSettings =
        if (!enabled && !ipv4Enabled) this else copy(ipv6Enabled = enabled).normalized()

    fun withProbeSourceEnabled(source: IpAddressProbeSource, enabled: Boolean): IpAddressTestSettings {
        val updated = if (enabled) enabledProbeSources + source else enabledProbeSources - source
        return if (updated.isEmpty()) this else copy(enabledProbeSources = updated).normalized()
    }

    companion object {
        const val MIN_TIMEOUT_SECONDS = 3
        const val MAX_TIMEOUT_SECONDS = 15
        val DEFAULT = IpAddressTestSettings()
    }
}
