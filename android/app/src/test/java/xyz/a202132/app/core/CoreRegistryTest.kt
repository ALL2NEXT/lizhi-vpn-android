package xyz.a202132.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Test
import xyz.a202132.app.data.model.*
import xyz.a202132.app.platform.AndroidVpnHost

class CoreRegistryTest {
    private class Backend(override val id: ProxyCore, protocols: Set<NodeType>) : CoreBackend {
        override val capabilities = CoreCapabilities(protocols, setOf(TunStack.GVISOR))
        override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = error("unused")
        override suspend fun start(context: Context, scope: CoroutineScope, prepared: PreparedCore,
            host: AndroidVpnHost?, onFailure: (String) -> Unit): CoreSession = error("unused")
    }
    @Test fun thirdBackendParticipatesWithoutAddingDispatchBranches() {
        val mihomo = Backend(ProxyCore.MIHOMO, setOf(NodeType.TROJAN))
        val registry = CoreRegistry(listOf(Backend(ProxyCore.SING_BOX, setOf(NodeType.VLESS)), mihomo))
        val node = Node("id", "node", NodeType.TROJAN, "example.com", 443, "")
        assertSame(mihomo, registry.resolve(ProxyCore.MIHOMO, node))
        assertEquals(listOf(ProxyCore.MIHOMO), registry.availableFor(NodeType.TROJAN))
    }
    @Test fun missingBackendFailsInsteadOfSilentlyUsingSingBox() {
        val registry = CoreRegistry(emptyList())
        try { registry.backend(ProxyCore.MIHOMO); fail("Missing core must fail") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("Mihomo")) }
    }
    @Test fun assignmentsPreserveLegacySettingsAndAllowThirdCore() {
        val settings = ProtocolCoreSettings(setOf(NodeType.VLESS), mapOf(NodeType.TROJAN to ProxyCore.MIHOMO))
        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.VLESS))
        assertEquals(ProxyCore.MIHOMO, settings.coreFor(NodeType.TROJAN))
        assertEquals(ProxyCore.SING_BOX, settings.coreFor(NodeType.TUIC))
    }

    @Test fun tunImplementationPreferenceResolvesAgainstBackendCapabilities() {
        val backend = Backend(ProxyCore.SING_BOX, setOf(NodeType.VLESS))
        assertEquals(CoreTunMode.NATIVE, backend.resolveTunMode(TunImplementation.AUTO))
        assertEquals(CoreTunMode.NATIVE, backend.resolveTunMode(TunImplementation.NATIVE))
        assertEquals(CoreTunMode.HEV_SOCKS5, backend.resolveTunMode(TunImplementation.HEV_SOCKS5))
    }
}
