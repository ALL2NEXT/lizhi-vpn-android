package xyz.a202132.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Test
import xyz.a202132.app.data.model.*
import xyz.a202132.app.platform.UnderlayController

class CoreRegistryTest {
    private class Backend(
        override val id: ProxyCore,
        protocols: Set<NodeType>
    ) : CoreBackend {
        override val capabilities = CoreCapabilities(protocols)
        override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = error("unused")
        override suspend fun start(context: Context, scope: CoroutineScope, prepared: PreparedCore,
            underlay: UnderlayController?, onFailure: (String) -> Unit): CoreSession = error("unused")
    }
    @Test fun registeredXrayBackendParticipatesWithoutDispatchBranches() {
        val xray = Backend(ProxyCore.XRAY, setOf(NodeType.TROJAN))
        val registry = CoreRegistry(listOf(Backend(ProxyCore.SING_BOX, setOf(NodeType.VLESS)), xray))
        val node = Node("id", "node", NodeType.TROJAN, "example.com", 443, "")
        assertSame(xray, registry.resolve(ProxyCore.XRAY, node))
        assertEquals(listOf(ProxyCore.XRAY), registry.availableFor(NodeType.TROJAN))
    }
    @Test fun missingBackendFailsInsteadOfSilentlyUsingSingBox() {
        val registry = CoreRegistry(emptyList())
        try { registry.backend(ProxyCore.XRAY); fail("Missing core must fail") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("Xray")) }
    }
    @Test fun assignmentsPreserveLegacySettingsAndAllowExplicitCoreSelection() {
        val settings = ProtocolCoreSettings(setOf(NodeType.VLESS), mapOf(NodeType.TROJAN to ProxyCore.XRAY))
        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.VLESS))
        assertEquals(ProxyCore.XRAY, settings.coreFor(NodeType.TROJAN))
        assertEquals(ProxyCore.SING_BOX, settings.coreFor(NodeType.TUIC))
    }

    @Test fun everyBackendMustExposeTheHevSocksInbound() {
        val backend = Backend(ProxyCore.SING_BOX, setOf(NodeType.VLESS))
        val registry = CoreRegistry(listOf(backend))
        assertSame(backend, registry.backend(ProxyCore.SING_BOX))
        assertTrue(backend.capabilities.socks5Inbound)
    }
}
