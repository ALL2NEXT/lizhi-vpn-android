package xyz.a202132.app.util

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.NodeDisplaySettings
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType

class NodeProtocolDisplayTest {
    @Test
    fun displaysProtocolSecurityAndTransportTogether() {
        assertEquals(
            "vless+reality+tcp",
            node(
                NodeType.VLESS,
                "vless://id@example.com:443?security=reality&type=tcp"
            ).protocolDisplayLabel()
        )
        assertEquals(
            "hy2+tls+quic",
            node(
                NodeType.HYSTERIA2,
                "hy2://password@example.com:443?insecure=0"
            ).protocolDisplayLabel()
        )
    }

    @Test
    fun explicitlyDisplaysMissingSecurityForVlessAndVmess() {
        assertEquals(
            "vless+none+ws",
            node(
                NodeType.VLESS,
                "vless://id@example.com:80?security=none&type=websocket"
            ).protocolDisplayLabel()
        )
        assertEquals(
            "vless+none+grpc",
            node(
                NodeType.VLESS,
                "vless://id@example.com:80?type=gun"
            ).protocolDisplayLabel()
        )
        assertEquals(
            "vmess+tls+ws",
            node(
                NodeType.VMESS,
                vmessLink("""{"add":"example.com","port":"443","id":"id","tls":"tls","net":"ws"}""")
            ).protocolDisplayLabel()
        )
    }

    @Test
    fun displaysIntrinsicSecurityAndTransportForModernProtocols() {
        assertEquals("trojan+tls+tcp", node(NodeType.TROJAN, "trojan://password@example.com").protocolDisplayLabel())
        assertEquals("anytls+tls+tcp", node(NodeType.ANYTLS, "anytls://password@example.com").protocolDisplayLabel())
        assertEquals("tuic+tls+quic", node(NodeType.TUIC, "tuic://id:password@example.com").protocolDisplayLabel())
        assertEquals("naive+tls+h2", node(NodeType.NAIVE, "naive+https://user:password@example.com").protocolDisplayLabel())
        assertEquals("naive+tls+quic", node(NodeType.NAIVE, "naive+https://user:password@example.com?quic=1").protocolDisplayLabel())
        assertEquals("wg+udp", node(NodeType.WIREGUARD, "wireguard://key@example.com").protocolDisplayLabel())
        assertEquals("http+tls+tcp", node(NodeType.HTTP, "https://example.com").protocolDisplayLabel())
    }

    @Test
    fun preservesSocksSubtypeAndShadowsocksPluginTransport() {
        assertEquals("socks4a+none+tcp", node(NodeType.SOCKS, "socks4a://example.com").protocolDisplayLabel())
        assertEquals(
            "ss+ws",
            node(
                NodeType.SHADOWSOCKS,
                "ss://token@example.com:443?plugin=v2ray-plugin%3Bmode%3Dwebsocket"
            ).protocolDisplayLabel()
        )
    }

    @Test
    fun independentlyControlsEveryDisplayLayer() {
        val realityNode = node(
            NodeType.VLESS,
            "vless://id@example.com:443?security=reality&type=tcp"
        )
        assertEquals(
            "reality+tcp",
            realityNode.protocolDisplayLabel(NodeDisplaySettings(showProtocol = false))
        )
        assertEquals(
            "vless+tcp",
            realityNode.protocolDisplayLabel(NodeDisplaySettings(showSecurity = false))
        )
        assertEquals(
            "vless+reality",
            realityNode.protocolDisplayLabel(NodeDisplaySettings(showTransport = false))
        )
        assertEquals(
            "",
            realityNode.protocolDisplayLabel(
                NodeDisplaySettings(
                    showProtocol = false,
                    showSecurity = false,
                    showTransport = false
                )
            )
        )
    }

    private fun node(type: NodeType, rawLink: String) = Node(
        id = type.protocol,
        name = type.protocol,
        type = type,
        server = "example.com",
        port = 443,
        rawLink = rawLink
    )

    private fun vmessLink(json: String): String =
        "vmess://${Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray())}"
}
