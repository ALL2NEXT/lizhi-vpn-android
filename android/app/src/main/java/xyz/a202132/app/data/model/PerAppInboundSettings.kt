package xyz.a202132.app.data.model

/** Apps selected to enter the VPN or bypass its inbound route. */
data class PerAppInboundSettings(
    val enabled: Boolean = false,
    val bypassMode: Boolean = false,
    val selectedPackages: Set<String> = emptySet()
)
