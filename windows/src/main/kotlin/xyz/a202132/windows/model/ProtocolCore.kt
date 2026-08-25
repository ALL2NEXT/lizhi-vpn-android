package xyz.a202132.windows.model

/** Windows 上用于处理代理协议的运行核心。 */
enum class ProxyCore {
    SING_BOX,
    XRAY
}

/**
 * 按协议保存核心选择。
 *
 * Xray 仅用于经过兼容验证的协议，其他协议始终由 sing-box 处理，
 * 避免核心升级后静默改变既有连接行为。
 */
data class ProtocolCoreSettings(
    val xrayProtocols: Set<NodeType> = DEFAULT_XRAY_PROTOCOLS
) {
    fun coreFor(type: NodeType): ProxyCore =
        if (type in XRAY_CAPABLE_PROTOCOLS && type in xrayProtocols) {
            ProxyCore.XRAY
        } else {
            ProxyCore.SING_BOX
        }

    fun usesXray(type: NodeType): Boolean = coreFor(type) == ProxyCore.XRAY

    companion object {
        /** Windows 节点解析器支持的全部真实协议。 */
        val DISPLAY_PROTOCOLS: List<NodeType> = NodeType.entries

        val DEFAULT_XRAY_PROTOCOLS: Set<NodeType> = setOf(
            NodeType.VLESS,
            NodeType.VMESS
        )

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

        fun fromStored(stored: Set<NodeType>?): ProtocolCoreSettings =
            ProtocolCoreSettings(
                (stored ?: DEFAULT_XRAY_PROTOCOLS).intersect(XRAY_CAPABLE_PROTOCOLS.toSet())
            )
    }
}
