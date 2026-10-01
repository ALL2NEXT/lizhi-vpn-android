package io.github.HanJason233.chimera.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProxyMode

class SingBoxConfigParsingTest {
    @Test
    fun normalizesKnownTransportAliases() {
        assertEquals("ws", normalizeTransportType("websocket"))
        assertEquals("grpc", normalizeTransportType("gun"))
        assertEquals("httpupgrade", normalizeTransportType("http-upgrade"))
        assertEquals("unsupported", normalizeTransportType("xhttp"))
    }

    @Test
    fun parsesHysteriaPortRangesWithoutChangingSinglePorts() {
        assertEquals(
            listOf("443", "1000:2000"),
            parseHysteria2ServerPorts("hysteria2://secret@example.com:443,1000-2000?sni=x")
        )
    }

    @Test
    fun parsesTypedJsonValuesSafely() {
        val json = JsonParser.parseString(
            """{"enabled":"yes","count":"7","invalid":{}}"""
        ).asJsonObject

        assertEquals(true, parseJsonBoolean(json.get("enabled")))
        assertEquals(7, parseJsonInt(json.get("count")))
        assertNull(parseJsonString(json.get("invalid")))
    }

    @Test
    fun usesCustomTargetInHeadlessUrlTestConfig() {
        val customUrl = "https://example.com/generate_204"
        val config = JsonParser.parseString(
            SingBoxConfigGenerator().generateUrlTestConfig(
                nodes = emptyList(),
                urlTestUrl = customUrl
            )
        ).asJsonObject

        assertEquals(
            customUrl,
            config.getAsJsonArray("outbounds")[0].asJsonObject.get("url").asString
        )
    }

    @Test
    fun batchUrlTestMapsEachSocksInboundToItsOwnOutbound() {
        val nodes = listOf(
            Node("node-a", "A", NodeType.SOCKS, "127.0.0.1", 10001, ""),
            Node("node-b", "B", NodeType.SOCKS, "127.0.0.1", 10002, "")
        )
        val base = JsonParser.parseString(
            """{"inbounds":[],"outbounds":[{"type":"urltest","tag":"auto"},
                {"type":"socks","tag":"node-a"},{"type":"socks","tag":"node-b"},
                {"type":"direct","tag":"direct"}],"route":{"final":"auto"},"experimental":{}}"""
        ).asJsonObject
        val result = JsonParser.parseString(
            SingBoxConfigGenerator().composeUrlTestBatchConfig(
                base,
                nodes,
                listOf(
                    LocalSocksEndpoint.unauthenticated(21000),
                    LocalSocksEndpoint.unauthenticated(21001)
                )
            )
        ).asJsonObject

        val inbounds = result.getAsJsonArray("inbounds").map { it.asJsonObject }
        val rules = result.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf(21000, 21001), inbounds.map { it.get("listen_port").asInt })
        assertEquals("urltest-in-1", rules[1].getAsJsonArray("inbound")[0].asString)
        assertEquals("node-b", rules[1].get("outbound").asString)
        assertEquals("node-a", result.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
        assertFalse(result.has("experimental"))
    }

    @Test
    fun dnsServersUseSingBox114DomainResolverFormat() {
        val method = SingBoxConfigGenerator::class.java.getDeclaredMethod(
            "createDnsConfig",
            ProxyMode::class.java,
            List::class.java,
            IPv6RoutingMode::class.java,
            DnsSettings::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val dns = method.invoke(
            SingBoxConfigGenerator(),
            ProxyMode.GLOBAL,
            emptyList<String>(),
            IPv6RoutingMode.DISABLED,
            DnsSettings(),
            false
        ) as com.google.gson.JsonObject

        val direct = dns.getAsJsonArray("servers")[1].asJsonObject
        assertFalse(direct.has("strategy"))
        assertFalse(direct.has("domain_strategy"))
        assertEquals(
            "dns-local",
            direct.getAsJsonObject("domain_resolver").get("server").asString
        )
    }
}
