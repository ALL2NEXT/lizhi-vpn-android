package io.github.HanJason233.chimera.util

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore

class NodeProtocolDisplayTest {
    @Test
    fun displaysProtocolSecurityAndTransportTogether() {
        assertEquals(
            "vless+reality+tcp+sing-box",
            node(
                NodeType.VLESS,
                "vless://id@example.com:443?security=reality&type=tcp"
            ).protocolDisplayLabel(FULL_DISPLAY)
        )
        assertEquals(
            "hy2+tls+quic+sing-box",
            node(
                NodeType.HYSTERIA2,
                "hy2://password@example.com:443?insecure=0"
            ).protocolDisplayLabel(FULL_DISPLAY)
        )
    }

    @Test
    fun explicitlyDisplaysMissingSecurityForVlessAndVmess() {
        assertEquals(
            "vless+none+ws+sing-box",
            node(
                NodeType.VLESS,
                "vless://id@example.com:80?security=none&type=websocket"
            ).protocolDisplayLabel(FULL_DISPLAY)
        )
        assertEquals(
            "vless+none+grpc+sing-box",
            node(
                NodeType.VLESS,
                "vless://id@example.com:80?type=gun"
            ).protocolDisplayLabel(FULL_DISPLAY)
        )
        assertEquals(
            "vmess+tls+ws+sing-box",
            node(
                NodeType.VMESS,
                vmessLink("""{"add":"example.com","port":"443","id":"id","tls":"tls","net":"ws"}""")
            ).protocolDisplayLabel(FULL_DISPLAY)
        )
    }

    @Test
    fun displaysIntrinsicSecurityAndTransportForModernProtocols() {
        assertEquals("trojan+tls+tcp+sing-box", node(NodeType.TROJAN, "trojan://password@example.com").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals("anytls+tls+tcp+sing-box", node(NodeType.ANYTLS, "anytls://password@example.com").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals("tuic+tls+quic+sing-box", node(NodeType.TUIC, "tuic://id:password@example.com").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals("naive+tls+h2+sing-box", node(NodeType.NAIVE, "naive+https://user:password@example.com").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals("naive+tls+quic+sing-box", node(NodeType.NAIVE, "naive+https://user:password@example.com?quic=1").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals("wg+udp+sing-box", node(NodeType.WIREGUARD, "wireguard://key@example.com").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals("http+tls+tcp+sing-box", node(NodeType.HTTP, "https://example.com").protocolDisplayLabel(FULL_DISPLAY))
    }

    @Test
    fun preservesSocksSubtypeAndShadowsocksPluginTransport() {
        assertEquals("socks4a+none+tcp+sing-box", node(NodeType.SOCKS, "socks4a://example.com").protocolDisplayLabel(FULL_DISPLAY))
        assertEquals(
            "ss+ws+sing-box",
            node(
                NodeType.SHADOWSOCKS,
                "ss://token@example.com:443?plugin=v2ray-plugin%3Bmode%3Dwebsocket"
            ).protocolDisplayLabel(FULL_DISPLAY)
        )
    }

    @Test
    fun independentlyControlsEveryDisplayLayer() {
        val realityNode = node(
            NodeType.VLESS,
            "vless://id@example.com:443?security=reality&type=tcp"
        )
        assertEquals(
            "reality+tcp+sing-box",
            realityNode.protocolDisplayLabel(FULL_DISPLAY.copy(showProtocol = false))
        )
        assertEquals(
            "vless+tcp+sing-box",
            realityNode.protocolDisplayLabel(FULL_DISPLAY.copy(showSecurity = false))
        )
        assertEquals(
            "vless+reality+sing-box",
            realityNode.protocolDisplayLabel(FULL_DISPLAY.copy(showTransport = false))
        )
        assertEquals(
            "vless+reality+tcp",
            realityNode.protocolDisplayLabel(FULL_DISPLAY.copy(showCore = false))
        )
        assertEquals(
            "",
            realityNode.protocolDisplayLabel(
                NodeDisplaySettings(
                    showProtocol = false,
                    showSecurity = false,
                    showTransport = false,
                    showCore = false
                )
            )
        )
    }

    @Test
    fun displaysTheCurrentlySelectedCore() {
        val coreSettings = ProtocolCoreSettings(
            assignments = mapOf(NodeType.TROJAN to ProxyCore.XRAY)
        )
        assertEquals(
            "vless+reality+tcp+sing-box",
            node(
                NodeType.VLESS,
                "vless://id@example.com:443?security=reality&type=tcp"
            ).protocolDisplayLabel(FULL_DISPLAY, coreSettings)
        )
        assertEquals(
            "trojan+tls+tcp+xray",
            node(
                NodeType.TROJAN,
                "trojan://password@example.com:443"
            ).protocolDisplayLabel(FULL_DISPLAY, coreSettings)
        )
    }

    @Test
    fun hidesTheLabelByDefault() {
        assertEquals(
            "",
            node(
                NodeType.VLESS,
                "vless://id@example.com:443?security=reality&type=tcp"
            ).protocolDisplayLabel()
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

    private companion object {
        val FULL_DISPLAY = NodeDisplaySettings(
            showProtocol = true,
            showSecurity = true,
            showTransport = true,
            showCore = true
        )
    }
}
