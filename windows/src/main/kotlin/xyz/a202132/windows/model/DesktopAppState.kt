package xyz.a202132.windows.model

import java.nio.file.Path

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

enum class ProxyMode {
    SMART,
    GLOBAL
}

enum class Ipv6RoutingMode {
    ONLY,
    PREFER,
    ENABLED,
    DISABLED
}

enum class TunStack(val configValue: String) {
    GVISOR("gvisor"),
    SYSTEM("system"),
    MIXED("mixed")
}

enum class AppScreen {
    MAIN,
    NODE_LIST,
    ACCOUNT_SETTINGS,
    BASIC_SETTINGS,
    FEATURES,
    ABOUT
}

enum class NodeCategory {
    SUBSCRIPTION,
    FAVORITES
}

enum class NodeType(val protocol: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    HYSTERIA2("hysteria2"),
    ANYTLS("anytls"),
    TUIC("tuic"),
    NAIVE("naive"),
    WIREGUARD("wireguard"),
    SHADOWSOCKS("shadowsocks"),
    SOCKS("socks"),
    HTTP("http")
}

data class DesktopNode(
    val id: String,
    val name: String,
    val type: NodeType,
    val server: String,
    val port: Int,
    val rawLink: String,
    val countryCode: String? = null,
    val tcpingLatency: Int = UNTESTED,
    val urlTestLatency: Int = UNTESTED,
    val downloadMbps: Float = 0f,
    val isAvailable: Boolean = true,
    val isFavorite: Boolean = false,
    val subscriptionSourceId: String? = null,
    val subscriptionSourceName: String? = null
) {
    fun displayName(): String = name.removeFlagEmoji().ifBlank { "$server:$port" }

    fun resolvedCountryCode(): String? = CountryCodeResolver.normalizeCountryCode(countryCode)
        ?: CountryCodeResolver.fromNodeName(name)

    fun flagEmoji(): String {
        val embedded = name.findFlagEmoji()
        if (embedded != null) return embedded
        val normalized = resolvedCountryCode() ?: return "\uD83C\uDF10"
        return normalized.map { char ->
            String(Character.toChars(0x1F1E6 + char.code - 'A'.code))
        }.joinToString("")
    }

    companion object {
        const val UNTESTED = -1
        const val FAILED = -2

        private fun String.findFlagEmoji(): String? {
            var index = 0
            while (index < length) {
                val codePoint = codePointAt(index)
                val charCount = Character.charCount(codePoint)
                if (codePoint in 0x1F1E6..0x1F1FF && index + charCount < length) {
                    val nextCodePoint = codePointAt(index + charCount)
                    if (nextCodePoint in 0x1F1E6..0x1F1FF) {
                        return String(Character.toChars(codePoint)) +
                            String(Character.toChars(nextCodePoint))
                    }
                }
                index += charCount
            }
            return null
        }

        private fun String.removeFlagEmoji(): String {
            val result = StringBuilder()
            var index = 0
            while (index < length) {
                val codePoint = codePointAt(index)
                val charCount = Character.charCount(codePoint)
                if (codePoint in 0x1F1E6..0x1F1FF) {
                    if (index + charCount < length) {
                        val nextCodePoint = codePointAt(index + charCount)
                        if (nextCodePoint in 0x1F1E6..0x1F1FF) {
                            index += charCount + Character.charCount(nextCodePoint)
                            continue
                        }
                    }
                    index += charCount
                    continue
                }
                result.append(String(Character.toChars(codePoint)))
                index += charCount
            }
            return result.toString().trim()
        }
    }
}

data class DesktopAccount(
    val uid: String,
    val nickname: String = "流萤用户",
    val accountType: String = "guest",
    val email: String = "",
    val brand: String = "",
    val model: String = "",
    val osVersion: String = "",
    val appVersion: String = "",
    val deviceBlocked: Boolean = false
)

data class DesktopBackendSettings(
    val websiteUrl: String = "",
    val feedbackEmail: String = "",
    val feedbackUrl: String = "",
    val githubUrl: String = "",
    val telegramUrl: String = "",
    val registrationEnabled: Boolean = true,
    val requireAdminRegistrationCode: Boolean = false
)

data class DesktopSettings(
    val ipv6RoutingMode: Ipv6RoutingMode = Ipv6RoutingMode.DISABLED,
    val tunStack: TunStack = TunStack.SYSTEM,
    val rememberLastSelectedNode: Boolean = true,
    val autoDeleteTimeoutNodes: Boolean = false,
    val simplifiedNodeTestDisplay: Boolean = false,
    val hideFavoriteNodeGroup: Boolean = false,
    val tcpingTimeoutMs: Int = 3_000,
    val urlTestTimeoutMs: Int = 3_000,
    val urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
    val speedTestDownloadUrl: String = BandwidthTestTargets.DEFAULT_URL,
    val speedTestTimeoutMs: Int = 5_000,
    val tcpingConcurrency: Int = 16,
    val urlTestConcurrency: Int = 10,
    val bandwidthTestConcurrency: Int = 1,
    val vpnMtu: Int = 9_000,
    val smartConnectTcpingPercent: Int = 100,
    val smartConnectUrlTestPercent: Int = 50,
    val smartConnectSpeedTestPercent: Int = 50,
    val disconnectRecoveryEnabled: Boolean = false,
    val hysteriaBandwidthEnabled: Boolean = true,
    val hysteriaUpMbps: Int = 1_000,
    val hysteriaDownMbps: Int = 1_000
)

sealed interface CoreStatus {
    data object Checking : CoreStatus
    data object Missing : CoreStatus

    data class Available(
        val executable: Path,
        val version: String
    ) : CoreStatus

    data class Failed(
        val executable: Path?,
        val reason: String
    ) : CoreStatus
}

data class DesktopAppState(
    val vpnState: VpnState = VpnState.DISCONNECTED,
    val coreStatus: CoreStatus = CoreStatus.Checking,
    val xrayCoreStatus: CoreStatus = CoreStatus.Checking,
    val tunEnabled: Boolean = true,
    val proxyMode: ProxyMode = ProxyMode.SMART,
    val screen: AppScreen = AppScreen.MAIN,
    val drawerOpen: Boolean = false,
    val nodeCategory: NodeCategory = NodeCategory.SUBSCRIPTION,
    val selectedSubscriptionSourceId: String? = null,
    val nodes: List<DesktopNode> = emptyList(),
    val selectedNodeId: String? = null,
    val backendUrl: String = "",
    val account: DesktopAccount? = null,
    val backendSettings: DesktopBackendSettings = DesktopBackendSettings(),
    val backendConnected: Boolean = false,
    val isAccountBusy: Boolean = false,
    val isLoadingNodes: Boolean = true,
    val subscriptionSourceCount: Int = 0,
    val settings: DesktopSettings = DesktopSettings(),
    val protocolCoreSettings: ProtocolCoreSettings = ProtocolCoreSettings(),
    val isTesting: Boolean = false,
    val isHysteriaBandwidthAdapting: Boolean = false,
    val testingLabel: String? = null,
    val message: String? = null
) {
    val currentNode: DesktopNode?
        get() = selectedNodeId?.let { selectedId -> nodes.firstOrNull { it.id == selectedId } }

    val visibleNodes: List<DesktopNode>
        get() = when (nodeCategory) {
            NodeCategory.SUBSCRIPTION -> {
                val sourceId = selectedSubscriptionSourceId
                    ?: subscriptionGroups.firstOrNull()?.first
                sourceId?.let { selected ->
                    nodes.filter { it.subscriptionSourceId == selected }
                } ?: nodes.filter { it.subscriptionSourceId != null }
            }
            NodeCategory.FAVORITES -> nodes.filter(DesktopNode::isFavorite)
        }

    val subscriptionGroups: List<Pair<String, String>>
        get() = nodes.mapNotNull { node ->
            val id = node.subscriptionSourceId ?: return@mapNotNull null
            id to (node.subscriptionSourceName ?: "订阅节点")
        }.distinctBy { it.first }
}
