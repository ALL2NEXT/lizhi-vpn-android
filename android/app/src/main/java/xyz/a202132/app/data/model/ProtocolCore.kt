package xyz.a202132.app.data.model

/** Android 上用于处理代理协议的运行核心。 */
enum class ProxyCore(val displayName: String) {
    SING_BOX("sing-box"),
    XRAY("Xray"),
    /** Reserved identity; availability is determined by the backend registry. */
    MIHOMO("Mihomo")
}

/**
 * 按协议保存核心选择。
 *
 * assignments 支持任意已注册内核；xrayProtocols 保留旧版本偏好迁移。
 * 未设置的协议沿用 sing-box 默认值，升级不会静默改变已有选择。
 */
data class ProtocolCoreSettings(
    val xrayProtocols: Set<NodeType> = DEFAULT_XRAY_PROTOCOLS,
    val assignments: Map<NodeType, ProxyCore> = emptyMap()
) {
    fun coreFor(type: NodeType): ProxyCore =
        assignments[type] ?: if (type in XRAY_CAPABLE_PROTOCOLS && type in xrayProtocols) {
            ProxyCore.XRAY
        } else {
            ProxyCore.SING_BOX
        }

    fun usesXray(type: NodeType): Boolean = coreFor(type) == ProxyCore.XRAY

    companion object {
        /** 核心选择器中展示的真实节点协议；UNKNOWN 仅用于解析失败时兜底。 */
        val DISPLAY_PROTOCOLS: List<NodeType> = NodeType.entries.filterNot {
            it == NodeType.UNKNOWN
        }

        /** 首次安装或尚未保存选择时，所有协议默认由 sing-box 处理。 */
        val DEFAULT_XRAY_PROTOCOLS: Set<NodeType> = emptySet()

        val XRAY_CAPABLE_PROTOCOLS: List<NodeType> = listOf(
            NodeType.VLESS,
            NodeType.VMESS,
            NodeType.TROJAN,
            NodeType.HYSTERIA2,
            NodeType.WIREGUARD,
            NodeType.SHADOWSOCKS,
            NodeType.SOCKS,
            NodeType.HTTP
        )
    }
}
