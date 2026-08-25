package xyz.a202132.app.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyMode

class SingBoxXrayBridgeConfigTest {
    @Test
    fun replacesMappedNodeWithLoopbackSocksOutbound() {
        val xrayNode = node("xray", NodeType.VLESS, "vless://ignored")
        val config = JsonParser.parseString(
            SingBoxConfigGenerator().generateConfig(
                nodes = listOf(xrayNode),
                selectedNodeId = xrayNode.id,
                proxyMode = ProxyMode.GLOBAL,
                xrayBridgePorts = mapOf(xrayNode.id to 20_801)
            )
        ).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }
        val bridge = outbounds.first { it.get("tag")?.asString == xrayNode.id }

        assertEquals("socks", bridge.get("type").asString)
        assertEquals("127.0.0.1", bridge.get("server").asString)
        assertEquals(20_801, bridge.get("server_port").asInt)
    }

    @Test
    fun headlessUrlTestUsesTheSameXrayBridge() {
        val xrayNode = node("xray-hy2", NodeType.HYSTERIA2, "hysteria2://ignored")
        val config = JsonParser.parseString(
            SingBoxConfigGenerator().generateUrlTestConfig(
                nodes = listOf(xrayNode),
                xrayBridgePorts = mapOf(xrayNode.id to 20_802)
            )
        ).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }
        val bridge = outbounds.first { it.get("tag")?.asString == xrayNode.id }

        assertEquals("socks", bridge.get("type").asString)
        assertEquals("127.0.0.1", bridge.get("server").asString)
        assertEquals(20_802, bridge.get("server_port").asInt)
        assertEquals(
            listOf(xrayNode.id),
            outbounds.first().getAsJsonArray("outbounds").map { it.asString }
        )
    }

    private fun node(id: String, type: NodeType, rawLink: String) = Node(
        id = id,
        name = id,
        type = type,
        server = "example.com",
        port = 443,
        rawLink = rawLink
    )
}
