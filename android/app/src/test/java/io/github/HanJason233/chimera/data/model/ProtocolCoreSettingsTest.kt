package io.github.HanJason233.chimera.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCoreSettingsTest {
    @Test
    fun defaultsMasqueToXrayAndOtherProtocolsToSingBox() {
        val settings = ProtocolCoreSettings()

        ProtocolCoreSettings.DISPLAY_PROTOCOLS.forEach { nodeType ->
            val expected = if (nodeType == NodeType.MASQUE) ProxyCore.XRAY else ProxyCore.SING_BOX
            assertEquals(expected, settings.coreFor(nodeType))
        }
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
                NodeType.MASQUE,
                NodeType.WIREGUARD,
                NodeType.SHADOWSOCKS,
                NodeType.SOCKS,
                NodeType.HTTP
            ),
            ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS
        )
    }

    @Test
    fun selectsXrayOnlyForExplicitAssignments() {
        val settings = ProtocolCoreSettings(
            assignments = mapOf(
                NodeType.VLESS to ProxyCore.XRAY,
                NodeType.HYSTERIA2 to ProxyCore.XRAY
            )
        )

        assertTrue(settings.usesXray(NodeType.VLESS))
        assertFalse(settings.usesXray(NodeType.VMESS))
        assertTrue(settings.usesXray(NodeType.HYSTERIA2))
        assertFalse(settings.usesXray(NodeType.ANYTLS))
    }
}
