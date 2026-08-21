package xyz.a202132.windows.core

import com.google.gson.JsonParser
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.Ipv6RoutingMode
import xyz.a202132.windows.model.TunStack

class WindowsSingBoxConfigGeneratorTest {
    @Test
    fun `keeps current smart route and selected outbound semantics`() {
        val node = requireNotNull(
            NodeLinkParser().parseNodeLink(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Tokyo"
            )
        )

        val config = WindowsSingBoxConfigGenerator().generate(
            nodes = listOf(node),
            selectedNodeId = node.id,
            proxyMode = ProxyMode.SMART,
            geoSiteCn = Paths.get("geosite-cn.srs"),
            geoIpCn = Paths.get("geoip-cn.srs")
        )
        val json = JsonParser.parseString(config).asJsonObject

        assertEquals("tun", json.getAsJsonArray("inbounds")[0].asJsonObject.get("type").asString)
        assertEquals(
            node.id,
            json.getAsJsonArray("outbounds")[0].asJsonObject.get("default").asString
        )
        assertTrue(json.getAsJsonObject("route").has("rule_set"))
    }

    @Test
    fun `applies Android network settings to the Windows tun config`() {
        val node = requireNotNull(
            NodeLinkParser().parseNodeLink(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Tokyo"
            )
        )
        val json = JsonParser.parseString(
            WindowsSingBoxConfigGenerator().generate(
                nodes = listOf(node),
                selectedNodeId = node.id,
                proxyMode = ProxyMode.GLOBAL,
                geoSiteCn = Paths.get("geosite-cn.srs"),
                geoIpCn = Paths.get("geoip-cn.srs"),
                settings = DesktopSettings(
                    ipv6RoutingMode = Ipv6RoutingMode.PREFER,
                    tunStack = TunStack.MIXED,
                    vpnMtu = 1500
                )
            )
        ).asJsonObject
        val tun = json.getAsJsonArray("inbounds")[0].asJsonObject

        assertEquals("mixed", tun.get("stack").asString)
        assertEquals(1500, tun.get("mtu").asInt)
        assertEquals("prefer_ipv6", json.getAsJsonObject("dns").get("strategy").asString)
        assertTrue(tun.getAsJsonArray("route_address").any { it.asString == "::/0" })
        val direct = json.getAsJsonArray("outbounds").first {
            it.asJsonObject.get("tag")?.asString == "direct"
        }.asJsonObject
        assertTrue(direct.get("udp_fragment").asBoolean)
    }
}
