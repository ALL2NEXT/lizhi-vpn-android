package io.github.HanJason233.chimera.core

import android.app.Application
import android.net.Uri
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.singbox.SingBoxConfigGenerator
import io.github.HanJason233.chimera.core.xray.XrayConfigGenerator
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.network.subscription.SubscriptionParser
import io.github.HanJason233.chimera.util.normalizeEchConfig
import io.github.HanJason233.chimera.util.parseShadowsocksMethodPassword
import io.github.HanJason233.chimera.util.parseSubscriptionEch
import io.github.HanJason233.chimera.util.uriTlsParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SingBoxGeneratedConfigTest {
    private val generator = SingBoxConfigGenerator()
    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val echConfig = "AEn+DQBFKwAgACABWIHUGj4u+PIggYXcR5JF0gYk3dCRioBW8uJq9H4mKAAIAAEAAQABAANAEnB1YmxpYy50bHMtZWNoLmRldgAA"

    private fun node(uri: String): Node = requireNotNull(SubscriptionParser().parseNodeLink(uri))

    private fun outbound(node: Node) = JsonParser.parseString(
        generator.generateTestConfig(node, 20800)
    ).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject

    private fun export(name: String, node: Node) {
        val directory = File("build/singbox-config-fixtures").apply { mkdirs() }
        File(directory, "$name-probe.json").writeText(generator.generateTestConfig(node, 20800))
        File(directory, "$name-vpn.json").writeText(generator.generateConfig(
            nodes = listOf(node), selectedNodeId = node.id, proxyMode = ProxyMode.GLOBAL,
            internalSocksEndpoint = LocalSocksEndpoint.unauthenticated(20800)
        ))
        val smart = JsonParser.parseString(generator.generateConfig(
            nodes = listOf(node), selectedNodeId = node.id, proxyMode = ProxyMode.SMART,
            internalSocksEndpoint = LocalSocksEndpoint.unauthenticated(20800)
        )).asJsonObject
        smart.getAsJsonObject("route").getAsJsonArray("rule_set").forEach { element ->
            val rule = element.asJsonObject
            rule.addProperty("path", File("src/main/assets/rule-sets", rule.get("path").asString)
                .absolutePath.replace('\\', '/'))
        }
        File(directory, "$name-smart-vpn.json").writeText(smart.toString())
        if (node.type in setOf(NodeType.VLESS, NodeType.VMESS, NodeType.TROJAN,
                NodeType.SHADOWSOCKS, NodeType.SOCKS, NodeType.HTTP, NodeType.WIREGUARD, NodeType.HYSTERIA2)) {
            val xrayDirectory = File("build/native-config-fixtures").apply { mkdirs() }
            File(xrayDirectory, "subscription-$name.json").writeText(
                XrayConfigGenerator().generateTestConfig(node, 20800)
            )
        }
    }

    @Test
    fun exitIpChecksPrecedeSmartDirectRules() {
        val node = node("socks://proxy.example:1080#sample")
        val config = JsonParser.parseString(generator.generateConfig(
            nodes = listOf(node), selectedNodeId = node.id, proxyMode = ProxyMode.SMART,
            internalSocksEndpoint = LocalSocksEndpoint.unauthenticated(20800)
        )).asJsonObject
        val rules = config.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        val index = rules.indexOfFirst { candidate ->
            candidate.getAsJsonArray("domain")?.any { it.asString == "api.ipify.org" } == true
        }
        assertTrue(index >= 0)
        assertEquals("proxy", rules[index].get("outbound").asString)
        val domesticIndex = rules.indexOfFirst { candidate ->
            candidate.getAsJsonArray("rule_set")?.any { it.asString == "geosite-cn" } == true
        }
        assertTrue(domesticIndex > index)
    }

    @Test
    fun shadowsocksCredentialsSurviveSubscriptionImportAndConfigurationGeneration() {
        val node = SubscriptionParser().parseSubscription("""
            proxies:
              - { name: Sample, type: ss, server: ss.example.com, port: 8388,
                  cipher: aes-128-gcm, password: "password:with@symbols+/%" }
        """.trimIndent()).single()
        val config = JsonParser.parseString(generator.generateTestConfig(node, 20800)).asJsonObject
        val outbound = config.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("aes-128-gcm", outbound.get("method").asString)
        assertEquals("password:with@symbols+/%", outbound.get("password").asString)
        export("shadowsocks", node)
    }

    @Test
    fun exportsLocallySuppliedSubscriptionForNativeValidation() {
        // Private subscription data stays outside tracked test resources.
        val source = (System.getenv("CHIMERA_TEST_SUBSCRIPTION")
            ?: System.getenv("FIREFLY_TEST_SUBSCRIPTION"))?.let(::File) ?: return
        val nodes = SubscriptionParser().parseSubscription(source.readText())
        assertTrue("The supplied subscription must contain nodes", nodes.isNotEmpty())
        nodes.forEachIndexed { index, node ->
            export("reference-$index", node)
        }
    }

    @Test
    fun echSurvivesYamlUriAndBothCoreConversions() {
        val sample = SubscriptionParser().parseSubscription("""
            proxies:
              - name: ECH
                type: vless
                server: edge.example.com
                port: 443
                uuid: $uuid
                tls: true
                servername: inner.example.com
                network: ws
                client-fingerprint: chrome
                ws-opts: { path: /ws, headers: { Host: edge.example.com } }
                ech-opts: { enable: true, config: "$echConfig" }
        """.trimIndent()).single()
        val tls = outbound(sample).getAsJsonObject("tls")
        val config = tls.getAsJsonObject("ech").getAsJsonArray("config").joinToString("\n") { it.asString }
        assertEquals(echConfig, normalizeEchConfig(config))
        val xray = JsonParser.parseString(XrayConfigGenerator().generateTestConfig(sample, 20800)).asJsonObject
        assertEquals(echConfig, xray.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings").get("echConfigList").asString)
        export("ech-inline", sample)
    }

    @Test
    fun literalBase64PlusIsPreservedAndPemIsAccepted() {
        val parameters = uriTlsParameters(Uri.parse("vless://$uuid@edge.example.com:443?ech=$echConfig"))
        assertEquals(echConfig, parseSubscriptionEch(parameters)!!.config)
        val pem = "-----BEGIN ECH CONFIGS-----\n$echConfig\n-----END ECH CONFIGS-----"
        assertEquals(echConfig, normalizeEchConfig(pem))
    }

    @Test
    fun vmessKeepsEchAndJsonTlsArrays() {
        val sample = SubscriptionParser().parseSubscription("""
            proxies:
              - { name: VMess ECH, type: vmess, server: edge.example.com, port: 443,
                  uuid: "$uuid", tls: true, network: ws, alpn: [h2, http/1.1],
                  client-fingerprint: chrome, ech-opts: { enable: true, config: "$echConfig" } }
        """.trimIndent()).single()
        val tls = outbound(sample).getAsJsonObject("tls")
        assertEquals(listOf("h2", "http/1.1"), tls.getAsJsonArray("alpn").map { it.asString })
        assertTrue(tls.getAsJsonObject("ech").get("enabled").asBoolean)
        export("vmess-ech", sample)
    }

    @Test
    fun disabledEchIsNotEnabledByAnExistingConfig() {
        val sample = node("trojan://secret@edge.example.com:443?echEnabled=false&echConfigList=$echConfig")
        assertFalse(outbound(sample).getAsJsonObject("tls").has("ech"))
    }

    @Test
    fun shadowsocksPlainAndLegacyCredentialsPreservePasswordDelimiters() {
        val credentials = "aes-256-gcm:secret@with:delimiter"
        assertEquals(credentials, parseShadowsocksMethodPassword("${Uri.encode(credentials)}@ss.example.com:8388"))
        val legacy = java.util.Base64.getEncoder().encodeToString("$credentials@ss.example.com:8388".toByteArray())
        assertEquals(credentials, parseShadowsocksMethodPassword(legacy))
    }

    @Test
    fun certificateFingerprintsAreNotConfusedWithBrowserFingerprints() {
        val link = io.github.HanJason233.chimera.network.subscription.importer.SubscriptionConverter.toStandardUris("""
            proxies:
              - { name: Certificate, type: trojan, server: edge.example.com, port: 443,
                  password: secret, fingerprint: "${"ab".repeat(32)}", client-fingerprint: chrome }
        """.trimIndent()).single()
        val uri = Uri.parse(link)
        assertEquals("chrome", uri.getQueryParameter("fp"))
        assertEquals("ab".repeat(32), uri.getQueryParameter("pinSHA256"))
    }

    @Test
    fun echDnsQueriesBootstrapDirectlyBeforeProxyIsAvailable() {
        val sample = node("vless://$uuid@edge.example.com:443?security=tls&sni=inner.example.com&echEnabled=true&echQueryServerName=public.example.com")
        val json = JsonParser.parseString(generator.generateConfig(
            listOf(sample), sample.id, ProxyMode.GLOBAL
        )).asJsonObject
        val rule = json.getAsJsonObject("dns").getAsJsonArray("rules")[0].asJsonObject
        assertEquals("dns-direct", rule.get("server").asString)
        assertEquals("public.example.com", rule.getAsJsonArray("domain")[0].asString)
        assertFalse(rule.has("query_type"))
        assertFalse(outbound(sample).getAsJsonObject("tls").getAsJsonObject("ech").has("config"))
        export("ech-dns", sample)
    }

    @Test
    fun echCustomDnsIsPreservedInSingBoxAndXray() {
        val sample = node("trojan://secret@edge.example.com:443?sni=inner.example.com&ech=" +
            Uri.encode("public.example.com+https://1.1.1.1/dns-query"))
        val config = JsonParser.parseString(generator.generateTestConfig(sample, 20800)).asJsonObject
        val dns = config.getAsJsonObject("dns")
        val tag = dns.getAsJsonArray("rules")[0].asJsonObject.get("server").asString
        val server = dns.getAsJsonArray("servers").first { it.asJsonObject.get("tag").asString == tag }.asJsonObject
        assertEquals("1.1.1.1", server.get("server").asString)
        assertFalse(server.has("detour"))
        export("ech-custom-dns", sample)
    }

    @Test
    fun realityDefaultsToTheRequiredUtlsFingerprint() {
        val sample = node("vless://$uuid@edge.example.com:443?security=reality&pbk=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE&sid=abcd&sni=public.example.com")
        assertEquals("chrome", outbound(sample).getAsJsonObject("tls").getAsJsonObject("utls").get("fingerprint").asString)
        export("reality", sample)
    }

    @Test
    fun quicProtocolsDoNotReceiveTcpUtlsAndHopIntervalUsesADuration() {
        val hy = node("hy2://secret@edge.example.com:443?fp=chrome&hopInterval=30&echEnabled=true")
        val tuic = node("tuic://$uuid:secret@edge.example.com:443?fp=chrome")
        assertFalse(outbound(hy).getAsJsonObject("tls").has("utls"))
        assertFalse(outbound(tuic).getAsJsonObject("tls").has("utls"))
        assertEquals("30s", outbound(hy).get("hop_interval").asString)
        export("hysteria2", hy)
        export("tuic", tuic)
    }

    @Test
    fun wireguardUsesTheNewEndpointFormatInEveryConfiguration() {
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        val sample = node("wireguard://${Uri.encode(key)}@wg.example.com:51820?publickey=${Uri.encode(key)}&address=10.0.0.2%2F32&reserved=1,2,3")
        val config = JsonParser.parseString(generator.generateTestConfig(sample, 20800)).asJsonObject
        assertFalse(config.getAsJsonArray("outbounds").any { it.asJsonObject.get("type").asString == "wireguard" })
        val endpoint = config.getAsJsonArray("endpoints")[0].asJsonObject
        assertEquals("10.0.0.2/32", endpoint.getAsJsonArray("address")[0].asString)
        assertEquals("wg.example.com", endpoint.getAsJsonArray("peers")[0].asJsonObject.get("address").asString)
        export("wireguard", sample)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedTransportCannotSilentlyBecomeTcp() {
        outbound(node("vless://$uuid@edge.example.com:443?type=xhttp"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun realityAndEchCannotBeCombined() {
        outbound(node("vless://$uuid@edge.example.com:443?security=reality&echEnabled=true"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedEchCannotBeSilentlyDropped() {
        outbound(node("trojan://secret@edge.example.com:443?echConfigList=invalid"))
    }

    @Test
    fun exportsOtherSupportedProtocolsForNativeStartup() {
        listOf(
            "trojan" to "trojan://secret@edge.example.com:443?type=grpc&serviceName=tunnel",
            "vless" to "vless://$uuid@edge.example.com:443?security=tls&type=httpupgrade&path=%2Ftunnel",
            "anytls" to "anytls://secret@edge.example.com:443?idle_session_timeout=30s&echEnabled=true",
            "socks" to "socks5://user:pass%3Aword@edge.example.com:1080",
            "https" to "https://user:pass%3Aword@edge.example.com:443?echEnabled=true"
        ).forEach { (name, uri) -> export(name, node(uri)) }
    }
}
