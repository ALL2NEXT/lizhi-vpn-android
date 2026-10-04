package io.github.HanJason233.chimera.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import io.github.HanJason233.chimera.core.xray.XrayVpnConfig
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.VpnRuntimeSettings
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
        VpnRuntimeSettings(mode, ipv6, 0, 0, ProtocolCoreSettings()))

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

    @Test fun vpnSocksInboundRequiresSessionCredentials() {
        val authenticated = request(ProxyMode.GLOBAL, IPv6RoutingMode.DISABLED).copy(
            endpoint = LocalSocksEndpoint("127.0.0.1", 20800, SocksCredentials("vpn", "secret"))
        )
        val source = base()
        source.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("settings").apply {
            addProperty("auth", "password")
            add("accounts", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("user", "vpn"); addProperty("pass", "secret") })
            })
        }
        val result = JsonParser.parseString(XrayVpnConfig.compose(source, authenticated)).asJsonObject
        val settings = result.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("settings")
        assertEquals("password", settings.get("auth").asString)
        assertEquals("vpn", settings.getAsJsonArray("accounts")[0].asJsonObject.get("user").asString)
    }

    @Test fun vpnDnsTrafficUsesTheHevOwnedSocksInbound() {
        val result = JsonParser.parseString(
            XrayVpnConfig.compose(base(), request(ProxyMode.GLOBAL, IPv6RoutingMode.DISABLED))
        ).asJsonObject
        val dnsRule = result.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(listOf("internal-socks-in"), dnsRule.getAsJsonArray("inboundTag").map { it.asString })
        assertFalse(result.toString().contains("tun-in"))
    }

    @Test fun exitIpChecksPrecedeSmartDirectRules() {
        val result = JsonParser.parseString(
            XrayVpnConfig.compose(base(), request(ProxyMode.SMART, IPv6RoutingMode.DISABLED),
                JsonParser.parseString(File("src/test/resources/routes-cn.json").readText()).asJsonObject)
        ).asJsonObject
        val rules = result.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val index = rules.indexOfFirst { candidate ->
            candidate.getAsJsonArray("domain")?.any { it.asString == "full:api.ipify.org" } == true
        }
        assertTrue(index >= 0)
        assertEquals("proxy", rules[index].get("outboundTag").asString)
        val domesticIndex = rules.indexOfFirst { candidate ->
            candidate.getAsJsonArray("domain")?.any { it.asString == "domain:baidu.com" } == true
        }
        assertTrue(domesticIndex > index)
    }

    @Test fun headlessProbeNeverAcquiresTun() {
        val result = JsonParser.parseString(XrayVpnConfig.compose(base(), CoreRequest(node, 20800))).asJsonObject
        assertEquals(1, result.getAsJsonArray("inbounds").size())
        assertFalse(result.has("dns"))
    }

    @Test fun batchUrlTestMapsEachSocksInboundToItsOwnOutbound() {
        val second = base().apply {
            getAsJsonArray("inbounds")[0].asJsonObject.addProperty("port", 20801)
            getAsJsonArray("outbounds")[0].asJsonObject
                .getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
                .addProperty("address", "127.0.0.2")
        }
        val result = JsonParser.parseString(
            XrayVpnConfig.composeUrlTestBatch(listOf(base(), second))
        ).asJsonObject

        val inbounds = result.getAsJsonArray("inbounds").map { it.asJsonObject }
        val outbounds = result.getAsJsonArray("outbounds").map { it.asJsonObject }
        val rules = result.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf(20800, 20801), inbounds.map { it.get("port").asInt })
        assertEquals(listOf("urltest-in-0", "urltest-in-1"), inbounds.map { it.get("tag").asString })
        assertEquals(listOf("urltest-out-0", "urltest-out-1"), outbounds.map { it.get("tag").asString })
        assertEquals("urltest-in-1", rules[1].getAsJsonArray("inboundTag")[0].asString)
        assertEquals("urltest-out-1", rules[1].get("outboundTag").asString)
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
