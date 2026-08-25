package xyz.a202132.windows.core

import com.google.gson.JsonParser
import java.nio.file.Paths
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProxyMode

class WindowsDualCoreConfigTest {
    @Test
    fun `sing box keeps node identity while bridging through xray`() {
        val node = node(
            id = "xray-node",
            type = NodeType.VLESS,
            rawLink = "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls"
        )
        val root = JsonParser.parseString(
            WindowsSingBoxConfigGenerator().generate(
                nodes = listOf(node),
                selectedNodeId = node.id,
                proxyMode = ProxyMode.SMART,
                geoSiteCn = Paths.get("geosite-cn.srs"),
                geoIpCn = Paths.get("geoip-cn.srs"),
                xrayBridgePorts = mapOf(node.id to 20_801)
            )
        ).asJsonObject
        val selector = root.getAsJsonArray("outbounds")[0].asJsonObject
        val bridge = root.getAsJsonArray("outbounds")[1].asJsonObject

        assertEquals(node.id, selector.get("default").asString)
        assertEquals("socks", bridge.get("type").asString)
        assertEquals(node.id, bridge.get("tag").asString)
        assertEquals("127.0.0.1", bridge.get("server").asString)
        assertEquals(20_801, bridge.get("server_port").asInt)
        val xrayBypass = root.getAsJsonObject("route").getAsJsonArray("rules").first {
            it.asJsonObject.getAsJsonArray("process_name")?.any { name ->
                name.asString.equals("xray.exe", ignoreCase = true)
            } == true
        }.asJsonObject
        assertEquals("direct", xrayBypass.get("outbound").asString)
    }

    @Test
    fun `xray creates an isolated bridge for every supported protocol`() {
        val vmessJson = """{"v":"2","ps":"vmess","add":"example.com","port":"443","id":"00000000-0000-0000-0000-000000000001","aid":"0","scy":"auto","net":"ws","path":"/ws","host":"example.com","tls":"tls","sni":"example.com"}"""
        val vmess = Base64.getUrlEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())
        val nodes = listOf(
            node(
                "vless",
                NodeType.VLESS,
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls&sni=example.com"
            ),
            node("vmess", NodeType.VMESS, "vmess://$vmess"),
            node("trojan", NodeType.TROJAN, "trojan://password@example.com:443?sni=example.com"),
            node("ss", NodeType.SHADOWSOCKS, shadowsocksLink())
        )
        val ports = nodes.mapIndexed { index, node -> node.id to (20_810 + index) }.toMap()
        val root = JsonParser.parseString(
            WindowsXrayConfigGenerator().generate(nodes, ports)
        ).asJsonObject

        assertEquals(4, root.getAsJsonArray("inbounds").size())
        assertEquals(
            listOf("vless", "vmess", "trojan", "shadowsocks"),
            root.getAsJsonArray("outbounds").take(4).map {
                it.asJsonObject.get("protocol").asString
            }
        )
        assertTrue(root.getAsJsonObject("routing").getAsJsonArray("rules").size() == 4)
    }

    private fun shadowsocksLink(): String {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:password".toByteArray())
        return "ss://$credentials@example.com:8388#ss"
    }

    private fun node(id: String, type: NodeType, rawLink: String) = DesktopNode(
        id = id,
        name = id,
        type = type,
        server = "example.com",
        port = if (type == NodeType.SHADOWSOCKS) 8388 else 443,
        rawLink = rawLink
    )
}
