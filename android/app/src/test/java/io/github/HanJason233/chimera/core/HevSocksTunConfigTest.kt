package io.github.HanJason233.chimera.core

import org.junit.Assert.*
import org.junit.Test
import io.github.HanJason233.chimera.core.hev.HevSocksTunConfig
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode

class HevSocksTunConfigTest {
    @Test fun coreOwnedDnsPreservesIpv6InDualStack() {
        val spec = HevSocksTunConfig.tunnel(IPv6RoutingMode.PREFER)
        assertTrue(spec.addresses.any { it.address == "172.19.0.1" })
        assertTrue(spec.addresses.any { ':' in it.address })
        assertTrue(spec.routes.any { it.address == "0.0.0.0" })
        assertTrue(spec.routes.any { it.address == "::" })
        assertEquals(listOf(io.github.HanJason233.chimera.AppConfig.VPN_DNS_PRIMARY), spec.dnsServers)

        val endpoint = LocalSocksEndpoint("127.0.0.1", 20800, SocksCredentials("vpn", "secret"))
        val yaml = HevSocksTunConfig.render(endpoint)
        assertTrue(yaml.contains("address: 127.0.0.1"))
        assertTrue(yaml.contains("username: 'vpn'"))
        assertTrue(yaml.contains("password: 'secret'"))
        assertFalse(yaml.contains("mapdns:"))
    }
}
