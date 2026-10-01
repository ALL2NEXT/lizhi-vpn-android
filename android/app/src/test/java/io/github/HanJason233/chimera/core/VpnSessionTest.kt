package io.github.HanJason233.chimera.core

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.HanJason233.chimera.data.model.ProxyCore

class VpnSessionTest {
    @Test fun dataPlaneOwnsTrafficAndClosesBeforeCore() {
        val events = mutableListOf<String>()
        val core = object : CoreSession {
            override val core = ProxyCore.XRAY
            override val endpoint = LocalSocksEndpoint.unauthenticated(20_800)
            override fun traffic() = CoreTraffic(900, 900)
            override fun close() { events += "core" }
        }
        val dataPlane = object : TunAdapterSession {
            override fun traffic() = CoreTraffic(100, 200)
            override fun close() { events += "data-plane" }
        }

        val session = combineCoreSession(core, dataPlane)
        assertEquals(CoreTraffic(100, 200), session.traffic())
        session.close()
        assertEquals(listOf("data-plane", "core"), events)
    }
}
