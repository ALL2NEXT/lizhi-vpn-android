package xyz.a202132.app.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import xyz.a202132.app.core.xray.XrayVpnConfig
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.PerAppProxySettings
import xyz.a202132.app.data.repository.VpnRuntimeSettings
import java.io.File

class XrayVpnConfigTest {
    private val node = Node("sample", "sample", NodeType.SOCKS, "127.0.0.1", 34567, "")
    private fun base(): JsonObject = JsonParser.parseString("""
        {"inbounds":[{"listen":"127.0.0.1","port":20800,"protocol":"socks","settings":{"udp":true},
        "sniffing":{"enabled":true,"routeOnly":true,"destOverride":["http","tls","quic"]}}],
        "outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":34567}]}},
        {"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}]}
    """).asJsonObject

    private fun request(mode: ProxyMode, ipv6: IPv6RoutingMode) = CoreRequest(node, 20800,
        VpnRuntimeSettings(mode, ipv6, 0, 0, ProtocolCoreSettings(), PerAppProxySettings(false, emptySet())))

    @Test fun vpnUsesHevSocksInboundAndRoutesDnsBeforePrivateNetworks() {
        val result = JsonParser.parseString(XrayVpnConfig.compose(base(), request(ProxyMode.GLOBAL, IPv6RoutingMode.DISABLED))).asJsonObject
        val inputs = result.getAsJsonArray("inbounds").map { it.asJsonObject }
        assertEquals(1, inputs.count { it.get("protocol").asString == "socks" })
        assertEquals(setOf("internal-socks-in"), inputs.map { it.get("tag").asString }.toSet())
        val rules = result.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("dns-out", rules[0].asJsonObject.get("outboundTag").asString)
        assertEquals("53", rules[0].asJsonObject.get("port").asString)
        assertEquals("proxy", result.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
        assertFalse(rules.any { it.asJsonObject.has("network") })
        val dns = result.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "dns-out" }.asJsonObject
        assertEquals("skip", dns.getAsJsonObject("settings").get("nonIPQuery").asString)
        assertEquals("proxy", dns.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("dialerProxy").asString)
        assertNotEquals("172.19.0.2", dns.getAsJsonObject("settings").get("address").asString)
        assertFalse(result.toString().contains("bridge"))
    }

    @Test fun vpnDnsTrafficUsesTheHevOwnedSocksInbound() {
        val result = JsonParser.parseString(
            XrayVpnConfig.compose(base(), request(ProxyMode.GLOBAL, IPv6RoutingMode.DISABLED))
        ).asJsonObject
        val dnsRule = result.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(listOf("internal-socks-in"), dnsRule.getAsJsonArray("inboundTag").map { it.asString })
        assertFalse(result.toString().contains("tun-in"))
    }

    @Test fun headlessProbeNeverAcquiresTun() {
        val result = JsonParser.parseString(XrayVpnConfig.compose(base(), CoreRequest(node, 20800))).asJsonObject
        assertEquals(1, result.getAsJsonArray("inbounds").size())
        assertFalse(result.has("dns"))
    }

    @Test fun smartModeRequiresExplicitRuleResources() {
        try {
            XrayVpnConfig.compose(base(), request(ProxyMode.SMART, IPv6RoutingMode.DISABLED))
            fail("Missing rules must not silently become global proxy")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun exportsEveryModeForPinnedXrayValidation() {
        val rules = JsonParser.parseString(File("src/test/resources/routes-cn.json").readText()).asJsonObject
        val destination = File("build/native-config-fixtures").apply { mkdirs() }
        ProxyMode.entries.forEach { mode ->
            IPv6RoutingMode.entries.forEach { ipv6 ->
                val json = XrayVpnConfig.compose(base(), request(mode, ipv6), rules)
                File(destination, "${mode.name}-${ipv6.name}.json").writeText(json)
            }
        }
    }
}
