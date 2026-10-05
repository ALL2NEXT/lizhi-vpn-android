package io.github.HanJason233.chimera.util

import android.app.Application
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.core.CoreRequest
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.core.xray.XrayVpnConfig
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.VpnRuntimeSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class InboundRoutingConfigTest {
    private val node = Node("sample", "sample", NodeType.SOCKS, "127.0.0.1", 34567, "")
    private val cnRules = JsonParser.parseString(
        File("src/test/resources/routes-cn.json").readText()
    ).asJsonObject
    private val smartSettings = SmartRoutingSettings(SmartRoutingSettings.defaultRules() + listOf(
        SmartRoutingSettings.custom("Block", SmartRoutingRuleType.CUSTOM_DOMAIN,
            SmartRoutingRuleAction.BLOCK, listOf("blocked.example")),
        SmartRoutingSettings.custom("Direct", SmartRoutingRuleType.CUSTOM_DOMAIN,
            SmartRoutingRuleAction.DIRECT, listOf("direct.example")),
        SmartRoutingSettings.custom("Proxy", SmartRoutingRuleType.CUSTOM_DOMAIN,
            SmartRoutingRuleAction.PROXY, listOf("proxy.example")),
        SmartRoutingSettings.custom("IP", SmartRoutingRuleType.CUSTOM_IP,
            SmartRoutingRuleAction.DIRECT, listOf("203.0.113.8/32"))
    ))

    private fun settings(local: ProxyMode, lan: ProxyMode, enabled: Boolean = true) =
        VpnRuntimeSettings(local, IPv6RoutingMode.DISABLED, 0, 0, ProtocolCoreSettings(),
            lanInbound = LanInboundSettings(enabled = enabled, password = "secret", proxyMode = lan),
            smartRoutingSettings = smartSettings)

    private fun singBox(settings: VpnRuntimeSettings): JsonObject = JsonParser.parseString(
        SingBoxConfigGenerator().generateConfig(
            listOf(node), node.id, settings.proxyMode,
            dnsSettings = settings.dnsSettings,
            internalSocksEndpoint = LocalSocksEndpoint.unauthenticated(20800),
            lanInbound = settings.lanInbound,
            smartRoutingSettings = settings.smartRoutingSettings
        )
    ).asJsonObject

    private fun xray(settings: VpnRuntimeSettings, resources: JsonObject? = cnRules): JsonObject =
        JsonParser.parseString(XrayVpnConfig.generate(CoreRequest(node, 20800, settings), resources)).asJsonObject

    @Test
    fun singBoxSeparatesDomesticAndCustomRulesInEveryModeCombination() {
        ProxyMode.entries.forEach { local ->
            ProxyMode.entries.forEach { lan ->
                val config = singBox(settings(local, lan))
                val expected = expectedSmartTags(local, lan)
                val route = config.getAsJsonObject("route")
                val rules = route.getAsJsonArray("rules").map { it.asJsonObject }
                val scopedRules = rules.filter {
                    it.has("rule_set") || it.hasValue("domain", "blocked.example") ||
                        it.hasValue("domain", "direct.example") ||
                        it.hasValue("domain", "proxy.example") ||
                        it.hasValue("ip_cidr", "203.0.113.8/32")
                }
                assertEquals(if (expected.isEmpty()) 0 else 6, scopedRules.size)
                scopedRules.forEach { assertEquals(expected, it.values("inbound")) }
                assertEquals("proxy", route.get("final").asString)
                assertEquals(expected.isNotEmpty(), route.has("rule_set"))

                val dnsRules = config.getAsJsonObject("dns").getAsJsonArray("rules")
                    .map { it.asJsonObject }
                    .filter { it.has("rule_set") || it.hasValue("domain", "direct.example") }
                assertEquals(if (expected.isEmpty()) 0 else 2, dnsRules.size)
                dnsRules.forEach { assertEquals(expected, it.values("inbound")) }
                export("singbox", local, lan, config)
            }
        }
    }

    @Test
    fun xraySeparatesDomesticAndCustomRulesInEveryModeCombination() {
        ProxyMode.entries.forEach { local ->
            ProxyMode.entries.forEach { lan ->
                val config = xray(settings(local, lan))
                val expected = expectedSmartTags(local, lan)
                val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
                    .map { it.asJsonObject }
                val scopedRules = rules.filter {
                    it.getAsJsonArray("domain") == cnRules.getAsJsonArray("domains") ||
                        it.getAsJsonArray("ip") == cnRules.getAsJsonArray("ips") ||
                        it.hasValue("domain", "domain:blocked.example") ||
                        it.hasValue("domain", "domain:direct.example") ||
                        it.hasValue("domain", "domain:proxy.example") ||
                        it.hasValue("ip", "203.0.113.8/32")
                }
                assertEquals(if (expected.isEmpty()) 0 else 6, scopedRules.size)
                scopedRules.forEach { assertEquals(expected, it.values("inboundTag")) }
                assertEquals("proxy", config.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
                export("xray", local, lan, config)
            }
        }
    }

    @Test
    fun disabledLanSmartModeDoesNotActivateSmartRulesOrRequireResources() {
        val settings = settings(ProxyMode.GLOBAL, ProxyMode.SMART, enabled = false)
        val singBox = singBox(settings)
        assertEquals(1, singBox.getAsJsonArray("inbounds").size())
        assertFalse(singBox.getAsJsonObject("route").has("rule_set"))
        assertFalse(singBox.getAsJsonObject("route").getAsJsonArray("rules")
            .any { it.asJsonObject.hasValue("domain", "blocked.example") })
        val xray = xray(settings, resources = null)
        assertEquals(1, xray.getAsJsonArray("inbounds").size())
        assertFalse(xray.getAsJsonObject("routing").getAsJsonArray("rules")
            .any { it.asJsonObject.hasValue("domain", "domain:blocked.example") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun lanSmartModeRequiresResourcesEvenWhenPhoneIsGlobal() {
        xray(settings(ProxyMode.GLOBAL, ProxyMode.SMART), resources = null)
    }

    private fun expectedSmartTags(local: ProxyMode, lan: ProxyMode) = buildList {
        if (local == ProxyMode.SMART) add("internal-socks-in")
        if (lan == ProxyMode.SMART) add("lan-socks-in")
    }

    private fun JsonObject.values(key: String) = getAsJsonArray(key)?.map { it.asString }.orEmpty()
    private fun JsonObject.hasValue(key: String, value: String) = value in values(key)

    private fun export(core: String, local: ProxyMode, lan: ProxyMode, config: JsonObject) {
        val directory = File("build/inbound-routing-fixtures").apply { mkdirs() }
        File(directory, "$core-${local.name}-${lan.name}.json").writeText(config.toString())
    }
}
