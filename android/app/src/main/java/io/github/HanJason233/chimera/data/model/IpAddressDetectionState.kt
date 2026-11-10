package io.github.HanJason233.chimera.data.model

/** 单个回显探针的结果；规范化地址用于合并格式不同但实际相同的 IP。 */
data class IpAddressProbeResult(
    val source: String,
    val address: String?,
    val normalizedAddress: String? = address
)

/** 多探针出口检测的运行状态和 IPv4、IPv6 结果。 */
data class IpAddressDetectionState(
    val isChecking: Boolean = false,
    val hasChecked: Boolean = false,
    val ipv4Results: List<IpAddressProbeResult> = emptyList(),
    val ipv6Results: List<IpAddressProbeResult> = emptyList()
)
