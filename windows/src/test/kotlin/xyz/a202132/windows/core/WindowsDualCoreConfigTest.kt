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
        val certificatePin = "00".repeat(32)
        val vmessJson = """{"v":"2","ps":"vmess","add":"example.com","port":"443","id":"00000000-0000-0000-0000-000000000001","aid":"0","scy":"auto","net":"ws","path":"/ws","host":"example.com","tls":"tls","sni":"example.com"}"""
        val vmess = Base64.getUrlEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())
        val nodes = listOf(
            node(
                "vless",
                NodeType.VLESS,
                "vless://00000000-0000-0000-0000-000000000000@example.com:443" +
                    "?security=tls&sni=example.com&insecure=1&pcs=$certificatePin"
            ),
            node("vmess", NodeType.VMESS, "vmess://$vmess"),
            node("trojan", NodeType.TROJAN, "trojan://password@example.com:443?sni=example.com"),
            node(
                "hy2",
                NodeType.HYSTERIA2,
                "hy2://password@example.com:443?obfs=salamander&obfs-password=mask" +
                    "&mport=443%2C8443&hop_interval=10s&sni=example.com"
            ),
            node("wg", NodeType.WIREGUARD, wireGuardLink()),
            node("ss", NodeType.SHADOWSOCKS, shadowsocksLink()),
            node("socks", NodeType.SOCKS, "socks5://user:password@example.com:1080"),
            node("http", NodeType.HTTP, "https://user:password@example.com:443?sni=example.com")
        )
        val ports = nodes.mapIndexed { index, node -> node.id to (20_810 + index) }.toMap()
        val root = JsonParser.parseString(
            WindowsXrayConfigGenerator().generate(nodes, ports)
        ).asJsonObject

        assertEquals(8, root.getAsJsonArray("inbounds").size())
        assertEquals(
            listOf("vless", "vmess", "trojan", "hysteria", "wireguard", "shadowsocks", "socks", "http"),
            root.getAsJsonArray("outbounds").take(8).map {
                it.asJsonObject.get("protocol").asString
            }
        )
        assertTrue(root.getAsJsonObject("routing").getAsJsonArray("rules").size() == 8)
        val hysteriaStream = root.getAsJsonArray("outbounds")[3].asJsonObject
            .getAsJsonObject("streamSettings")
        assertEquals("hysteria", hysteriaStream.get("network").asString)
        assertEquals(2, hysteriaStream.getAsJsonObject("hysteriaSettings").get("version").asInt)
        val quicParams = hysteriaStream.getAsJsonObject("finalmask").getAsJsonObject("quicParams")
        assertEquals("443,8443", quicParams.getAsJsonObject("udpHop").get("ports").asString)
        val vlessTls = root.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("streamSettings")
            .getAsJsonObject("tlsSettings")
        assertEquals(certificatePin, vlessTls.get("pinnedPeerCertSha256").asString)
        assertTrue(!vlessTls.has("allowInsecure"))
    }

    private fun shadowsocksLink(): String {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:password".toByteArray())
        return "ss://$credentials@example.com:8388#ss"
    }

    private fun wireGuardLink(): String =
        "wireguard://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=@example.com:51820" +
            "?publickey=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE%3D" +
            "&address=10.0.0.2%2F32&reserved=1%2C2%2C3"

    private fun node(id: String, type: NodeType, rawLink: String) = DesktopNode(
        id = id,
        name = id,
        type = type,
        server = "example.com",
        port = when (type) {
            NodeType.SHADOWSOCKS -> 8388
            NodeType.SOCKS -> 1080
            NodeType.WIREGUARD -> 51820
            else -> 443
        },
        rawLink = rawLink
    )
}
