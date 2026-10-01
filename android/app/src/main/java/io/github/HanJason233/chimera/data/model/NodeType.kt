package io.github.HanJason233.chimera.data.model

/**
 * 节点协议类型
 */
enum class NodeType(val protocol: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    HYSTERIA2("hysteria2"),
    ANYTLS("anytls"),
    TUIC("tuic"),
    NAIVE("naive"),
    WIREGUARD("wireguard"),
    SHADOWSOCKS("ss"),
    SOCKS("socks"),
    HTTP("http"),
    UNKNOWN("unknown")
}
