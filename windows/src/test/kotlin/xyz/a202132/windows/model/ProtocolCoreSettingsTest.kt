package xyz.a202132.windows.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolCoreSettingsTest {
    @Test
    fun `vless and vmess use xray by default`() {
        val settings = ProtocolCoreSettings.fromStored(null)

        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.VLESS))
        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.VMESS))
        assertEquals(ProxyCore.SING_BOX, settings.coreFor(NodeType.TROJAN))
        assertEquals(ProxyCore.SING_BOX, settings.coreFor(NodeType.HYSTERIA2))
    }

    @Test
    fun `explicit empty selection keeps every protocol on sing box`() {
        val settings = ProtocolCoreSettings.fromStored(emptySet())

        NodeType.entries.forEach { type ->
            assertEquals(ProxyCore.SING_BOX, settings.coreFor(type))
        }
    }

    @Test
    fun `shows every desktop protocol and ignores unsupported xray selections`() {
        val settings = ProtocolCoreSettings(setOf(NodeType.VLESS, NodeType.HYSTERIA2))

        assertEquals(NodeType.entries, ProtocolCoreSettings.DISPLAY_PROTOCOLS)
        assertTrue(settings.usesXray(NodeType.VLESS))
        assertFalse(settings.usesXray(NodeType.HYSTERIA2))
    }
}
