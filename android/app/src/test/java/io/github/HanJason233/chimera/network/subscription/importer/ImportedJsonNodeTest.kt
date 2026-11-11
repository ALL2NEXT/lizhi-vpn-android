package io.github.HanJason233.chimera.network.subscription.importer

import io.github.HanJason233.chimera.network.subscription.SubscriptionParser

import android.app.Application
import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.core.singbox.SingBoxConfigGenerator
import io.github.HanJason233.chimera.core.xray.XrayConfigGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ImportedJsonNodeTest {
    private val uuid = "11111111-1111-1111-1111-111111111111"
    private fun nodes(content: String): List<Node> = SubscriptionParser().parseSubscription(content)
    private fun singBox(node: Node): JsonObject =
        JsonParser.parseString(SingBoxConfigGenerator().generateTestConfig(node))
            .asJsonObject.getAsJsonArray("outbounds").first {
                it.asJsonObject.get("tag").asString == "proxy"
            }.asJsonObject
    private fun xray(node: Node): JsonObject =
        JsonParser.parseString(XrayConfigGenerator().generateTestConfig(node))
            .asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject

    @Test fun singBoxVlessJsonBecomesUsableUriForBothCoresAndDropsOtherSettings() {
        val node = nodes("""
            {"log":{"level":"debug"},"inbounds":[{"type":"tun"}],
              "outbounds":[{"type":"vless","tag":"Japan","server":"node.example","server_port":443,
                "uuid":"${uuid}","tls":{"enabled":true,"server_name":"sni.example",
                "utls":{"enabled":true,"fingerprint":"chrome"}},
                "transport":{"type":"ws","path":"/websocket","headers":{"Host":"host.example"}},
                "multiplex":{"enabled":true}},{"type":"direct"}],"route":{"final":"Japan"}}
        """.trimIndent()).single()
        assertEquals("Japan", node.name)
        assertFalse(node.rawLink.contains("chimera_"))
        assertFalse(node.rawLink.contains("multiplex"))
        assertEquals("/websocket", Uri.parse(node.rawLink).getQueryParameter("path"))
        val sb = singBox(node)
        assertEquals(uuid, sb.get("uuid").asString)
        assertEquals("host.example", sb.getAsJsonObject("transport")
            .getAsJsonObject("headers").get("Host").asString)
        val xr = xray(node)
        assertEquals("sni.example", xr.getAsJsonObject("streamSettings")
            .getAsJsonObject("tlsSettings").get("serverName").asString)
        assertEquals("/websocket", xr.getAsJsonObject("streamSettings")
            .getAsJsonObject("wsSettings").get("path").asString)
        assertFalse(xr.getAsJsonObject("mux").get("enabled").asBoolean)
    }

    @Test fun xrayRealityJsonKeepsConnectionParametersForBothCores() {
        val node = nodes("""
            {"outbounds":[{"protocol":"vless","tag":"Reality",
              "settings":{"vnext":[{"address":"reality.example","port":443,"users":[{
                "id":"${uuid}","encryption":"none","flow":"xtls-rprx-vision"}]}]},
              "streamSettings":{"network":"tcp","security":"reality",
                "realitySettings":{"serverName":"sni.example","fingerprint":"chrome",
                  "publicKey":"public-key","shortId":"abcd"}},
              "mux":{"enabled":true}}]}
        """.trimIndent()).single()
        val uri = Uri.parse(node.rawLink)
        assertEquals("public-key", uri.getQueryParameter("pbk"))
        assertEquals("xtls-rprx-vision", uri.getQueryParameter("flow"))
        assertEquals("public-key", singBox(node).getAsJsonObject("tls")
            .getAsJsonObject("reality").get("public_key").asString)
        assertEquals("abcd", xray(node).getAsJsonObject("streamSettings")
            .getAsJsonObject("realitySettings").get("shortId").asString)
    }

    @Test fun extractsEachXrayServerAndUserAsAnIndependentUri() {
        val imported = nodes("""
            {"protocol":"vmess","settings":{"vnext":[
              {"address":"one.example","port":443,"users":[{"id":"${uuid}"},{"id":"22222222-2222-2222-2222-222222222222"}]},
              {"address":"two.example","port":8443,"users":[{"id":"${uuid}"}]}]}}
        """.trimIndent())
        assertEquals(3, imported.size)
        assertEquals(listOf("one.example", "one.example", "two.example"), imported.map { it.server })
        imported.forEach { node ->
            val outgoing = xray(node).getAsJsonObject("settings").getAsJsonArray("vnext")
            assertEquals(1, outgoing.size())
            assertEquals(1, outgoing[0].asJsonObject.getAsJsonArray("users").size())
            assertEquals(node.server, outgoing[0].asJsonObject.get("address").asString)
        }
    }

    @Test fun vmessTlsAndTransportSurviveJsonToUriToBothCores() {
        val node = nodes("""
            {"type":"vmess","tag":"VMess","server":"vmess.example","server_port":443,
              "uuid":"${uuid}","security":"auto","alter_id":0,
              "tls":{"enabled":true,"server_name":"tls.example","alpn":["h2"]},
              "transport":{"type":"grpc","service_name":"service"}}
        """.trimIndent()).single()
        assertTrue(node.rawLink.startsWith("vmess://"))
        assertEquals("service", singBox(node).getAsJsonObject("transport").get("service_name").asString)
        assertEquals("service", xray(node).getAsJsonObject("streamSettings")
            .getAsJsonObject("grpcSettings").get("serviceName").asString)
    }

    @Test fun httpProxyInsideJsonIsLocalConfiguration() {
        val json = """{"outbounds":[{"type":"http","server":"proxy.example","server_port":8080,"username":"user","password":"password"}]}"""
        assertTrue(SubscriptionInputDetector.detect(json) is SubscriptionImportInput.FullContent)
        val node = nodes(json).single()
        assertEquals("http", singBox(node).get("type").asString)
        val server = xray(node).getAsJsonObject("settings")
        assertEquals("proxy.example", server.get("address").asString)
        assertEquals("user", server.get("user").asString)
        assertEquals(node.server, nodes(xray(node).toString()).single().server)
    }

    @Test fun simplifiedXraySettingsAreAlsoConverted() {
        val imported = nodes("""
            [{"protocol":"vless","settings":{"address":"vless.example","port":443,
                "id":"${uuid}","encryption":"none"}},
             {"protocol":"vmess","settings":{"address":"vmess.example","port":443,"id":"${uuid}","security":"auto"}},
             {"protocol":"trojan","settings":{"address":"trojan.example","port":443,"password":"secret"}},
             {"protocol":"shadowsocks","settings":{"address":"ss.example","port":443,"method":"aes-128-gcm","password":"secret"}},
             {"protocol":"socks","settings":{"address":"socks.example","port":1080,"user":"user","pass":"pass"}}]
        """.trimIndent())
        assertEquals(5, imported.size)
        imported.forEach { node ->
            assertEquals(node.server, nodes(xray(node).toString()).single().server)
        }
    }

    @Test fun xhttpJsonProducesUriUsableByXray() {
        val node = nodes("""
            {"protocol":"vless","settings":{"vnext":[{"address":"xhttp.example","port":443,
                "users":[{"id":"${uuid}","encryption":"none"}]}]},
              "streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/xhttp",
                "host":"host.example","mode":"auto","extra":{"scMaxEachPostBytes":1000000}}}}
        """.trimIndent()).single()
        val transport = xray(node).getAsJsonObject("streamSettings").getAsJsonObject("xhttpSettings")
        assertEquals("/xhttp", transport.get("path").asString)
        assertEquals("auto", transport.get("mode").asString)
        assertEquals(1000000, transport.getAsJsonObject("extra").get("scMaxEachPostBytes").asInt)
    }

    @Test fun singBoxWireguardEndpointBecomesUriAndEndpointAgain() {
        val node = nodes("""
            {"endpoints":[{"type":"wireguard","tag":"WG","private_key":"secret",
              "address":["10.0.0.2/32"],"peers":[{"address":"wg.example","port":51820,
              "public_key":"public-key","pre_shared_key":"psk","reserved":[1,2,3]}]}]}
        """.trimIndent()).single()
        assertEquals("public-key", Uri.parse(node.rawLink).getQueryParameter("publickey"))
        val config = JsonParser.parseString(SingBoxConfigGenerator().generateTestConfig(node)).asJsonObject
        val peer = config.getAsJsonArray("endpoints")[0].asJsonObject.getAsJsonArray("peers")[0].asJsonObject
        assertEquals("wg.example", peer.get("address").asString)
        assertEquals("psk", peer.get("pre_shared_key").asString)
    }
}
