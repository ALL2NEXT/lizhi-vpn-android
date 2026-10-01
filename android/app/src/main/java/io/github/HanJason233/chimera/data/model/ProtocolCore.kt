package io.github.HanJason233.chimera.data.model

/** Android 上用于处理代理协议的运行核心。 */
enum class ProxyCore(val displayName: String) {
    SING_BOX("sing-box"),
    XRAY("Xray")
}

/**
 * 按协议保存核心选择。
 */
data class ProtocolCoreSettings(
    val assignments: Map<NodeType, ProxyCore> = emptyMap()
) {
    fun coreFor(type: NodeType): ProxyCore =
        assignments[type] ?: ProxyCore.SING_BOX

    fun usesXray(type: NodeType): Boolean = coreFor(type) == ProxyCore.XRAY

    companion object {
        /** 核心选择器中展示的真实节点协议；UNKNOWN 仅用于解析失败时兜底。 */
        val DISPLAY_PROTOCOLS: List<NodeType> = NodeType.entries.filterNot {
            it == NodeType.UNKNOWN
        }

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
