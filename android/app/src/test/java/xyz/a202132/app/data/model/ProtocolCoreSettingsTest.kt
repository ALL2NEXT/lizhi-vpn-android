package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCoreSettingsTest {
    @Test
    fun defaultsVlessAndVmessToXray() {
        val settings = ProtocolCoreSettings()

        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.VLESS))
        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.VMESS))
        assertEquals(ProxyCore.SING_BOX, settings.coreFor(NodeType.TROJAN))
        assertEquals(ProxyCore.SING_BOX, settings.coreFor(NodeType.SHADOWSOCKS))
    }

    @Test
    fun displaysEveryRealNodeProtocol() {
        assertEquals(
            NodeType.entries.filterNot { it == NodeType.UNKNOWN },
            ProtocolCoreSettings.DISPLAY_PROTOCOLS
        )
        assertFalse(ProtocolCoreSettings.DISPLAY_PROTOCOLS.contains(NodeType.UNKNOWN))
        assertEquals(
            listOf(
                NodeType.VLESS,
                NodeType.VMESS,
                NodeType.TROJAN,
                NodeType.HYSTERIA2,
                NodeType.WIREGUARD,
                NodeType.SHADOWSOCKS,
                NodeType.SOCKS,
                NodeType.HTTP
            ),
            ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS
        )
    }

    @Test
    fun selectsXrayOnlyForExplicitlySupportedProtocols() {
        val settings = ProtocolCoreSettings(
            setOf(NodeType.VLESS, NodeType.HYSTERIA2, NodeType.ANYTLS)
        )

        assertTrue(settings.usesXray(NodeType.VLESS))
        assertFalse(settings.usesXray(NodeType.VMESS))
        assertTrue(settings.usesXray(NodeType.HYSTERIA2))
        assertFalse(settings.usesXray(NodeType.ANYTLS))
    }
}
