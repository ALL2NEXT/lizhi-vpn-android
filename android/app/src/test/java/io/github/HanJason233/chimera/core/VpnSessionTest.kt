package io.github.HanJason233.chimera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun lanTrafficIsAddedWithoutAddingWholeCoreTrafficAgain() {
        var lan = CoreTraffic(300, 400)
        val core = object : CoreSession {
            override val core = ProxyCore.SING_BOX
            override val endpoint = LocalSocksEndpoint.unauthenticated(20_800)
            override fun traffic() = CoreTraffic(900, 900)
            override fun lanTraffic() = lan
            override fun close() { lan = CoreTraffic(350, 480) }
        }
        val dataPlane = object : TunAdapterSession {
            override fun traffic() = CoreTraffic(100, 200)
            override fun close() {}
        }
        val session = combineCoreSession(core, dataPlane)
        assertEquals(CoreTraffic(400, 600), session.traffic())
        session.close()
        assertEquals(CoreTraffic(450, 680), session.traffic())
    }

    @Test fun lanTrafficCanArriveBeforeTheFirstHevSnapshot() {
        var lan: CoreTraffic? = null
        val core = object : CoreSession {
            override val core = ProxyCore.XRAY
            override val endpoint = LocalSocksEndpoint.unauthenticated(20_800)
            override fun lanTraffic() = lan
            override fun close() {}
        }
        val dataPlane = object : TunAdapterSession {
            override fun traffic(): CoreTraffic? = null
            override fun close() {}
        }
        val session = combineCoreSession(core, dataPlane)
        assertNull(session.traffic())
        lan = CoreTraffic(50, 80)
        assertEquals(lan, session.traffic())
    }
}
