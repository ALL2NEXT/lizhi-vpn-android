package xyz.a202132.app.core

import org.junit.Assert.*
import org.junit.Test
import xyz.a202132.app.core.hev.HevSocksTunConfig
import xyz.a202132.app.data.model.IPv6RoutingMode

class HevSocksTunConfigTest {
    @Test fun mappedDnsUsesFakeNetworkAndPreservesIpv6InDualStack() {
        val spec = HevSocksTunConfig.tunnel(IPv6RoutingMode.PREFER)
        assertTrue(spec.addresses.any { it.address == "172.19.0.1" })
        assertTrue(spec.addresses.any { ':' in it.address })
        assertTrue(spec.routes.any { it.address == "0.0.0.0" })
        assertTrue(spec.routes.any { it.address == "::" })
        assertEquals(listOf("198.18.0.2"), spec.dnsServers)

        val yaml = HevSocksTunConfig.render(20800)
        assertTrue(yaml.contains("address: 198.18.0.2"))
        assertTrue(yaml.contains("network: 198.18.0.0"))
    }

    @Test fun ipv6OnlyFallsBackToCoreNativeTun() {
        try {
            HevSocksTunConfig.tunnel(IPv6RoutingMode.ONLY)
            fail("HEV fallback must reject IPv6-only mode")
        } catch (_: IllegalArgumentException) { }
    }
}
