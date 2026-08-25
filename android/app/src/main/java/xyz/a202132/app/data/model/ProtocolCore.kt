package xyz.a202132.app.data.model

/** Android 上用于处理代理协议的运行核心。 */
enum class ProxyCore {
    SING_BOX,
    XRAY
}

/**
 * 按协议保存核心选择。
 *
 * Xray 在此仅用于经过兼容验证的协议。其他节点类型固定交给 sing-box，
 * 即使未来版本的 Xray 能够解析这些协议，也不会因升级而静默改变运行行为。
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
        /** 核心选择器中展示的真实节点协议；UNKNOWN 仅用于解析失败时兜底。 */
        val DISPLAY_PROTOCOLS: List<NodeType> = NodeType.entries.filterNot {
            it == NodeType.UNKNOWN
        }

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
    }
}
