package io.github.HanJason233.chimera.core.xray

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.CoreRequest
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.LinkTypeTestTargets
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.util.XrayConfigGenerator

internal object XrayVpnConfig {
    fun generate(request: CoreRequest, cnRules: JsonObject? = null): String {
        val base = JsonParser.parseString(XrayConfigGenerator().generateCoreConfig(
            request.node,
            request.endpoint,
            request.hysteriaUpMbps,
            request.hysteriaDownMbps,
            request.settings?.lanInbound ?: io.github.HanJason233.chimera.data.model.LanInboundSettings()
        )).asJsonObject
        return compose(base, request, cnRules)
    }

    /** 将多个单节点配置合并为一个 Xray 进程，每个 SOCKS 入站只允许命中对应节点。 */
    fun generateUrlTestBatch(requests: List<CoreRequest>): String {
        require(requests.isNotEmpty()) { "URL Test 批次至少需要一个节点" }
        require(requests.none(CoreRequest::isVpn)) { "批量测速配置不能包含 VPN 请求" }
        val bases = requests.map { request ->
            JsonParser.parseString(
                XrayConfigGenerator().generateCoreConfig(
                    request.node,
                    request.endpoint,
                    request.hysteriaUpMbps,
                    request.hysteriaDownMbps
                )
            ).asJsonObject
        }
        return composeUrlTestBatch(bases)
    }

    internal fun composeUrlTestBatch(bases: List<JsonObject>): String {
        require(bases.isNotEmpty()) { "URL Test 批次至少需要一个基础配置" }
        val inbounds = JsonArray()
        val outbounds = JsonArray()
        val rules = JsonArray()
        bases.forEachIndexed { index, base ->
            val inbound = base.getAsJsonArray("inbounds")?.firstOrNull()?.asJsonObject?.deepCopy()
                ?: error("Xray 基础配置缺少入站")
            val outbound = base.getAsJsonArray("outbounds")?.firstOrNull()?.asJsonObject?.deepCopy()
                ?: error("Xray 基础配置缺少节点出站")
            val inboundTag = "urltest-in-$index"
            val outboundTag = "urltest-out-$index"
            inbounds.add(inbound.apply { addProperty("tag", inboundTag) })
            outbounds.add(outbound.apply { addProperty("tag", outboundTag) })
            rules.add(rule(outboundTag).apply { add("inboundTag", strings(inboundTag)) })
        }
        return JsonObject().apply {
            bases.first().getAsJsonObject("log")?.let { add("log", it.deepCopy()) }
            add("inbounds", inbounds)
            add("outbounds", outbounds)
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", rules)
            })
        }.toString()
    }

    internal fun compose(base: JsonObject, request: CoreRequest, cnRules: JsonObject? = null): String {
        val inbound = base.getAsJsonArray("inbounds")[0].asJsonObject
        inbound.addProperty("tag", "internal-socks-in")
        base.getAsJsonArray("outbounds")[0].asJsonObject.addProperty("tag", "proxy")
        val rules = JsonArray()
        if (request.isVpn) {
            val settings = request.settings!!
            val dnsSettings = settings.dnsSettings
            val smartInboundTags = buildList {
                if (settings.proxyMode == ProxyMode.SMART) add("internal-socks-in")
                if (settings.lanInbound.enabled && settings.lanInbound.proxyMode == ProxyMode.SMART) {
                    add("lan-socks-in")
                }
            }
            val strategy = when (settings.ipv6RoutingMode) {
                IPv6RoutingMode.DISABLED -> "UseIPv4"
                IPv6RoutingMode.PREFER -> "UseIPv6v4"
                IPv6RoutingMode.ENABLED -> "UseIPv4v6"
            }
            base.getAsJsonArray("outbounds").forEach { element ->
                val outbound = element.asJsonObject
                if (outbound.get("protocol").asString == "freedom") {
                    outbound.add("settings", JsonObject().apply { addProperty("targetStrategy", strategy) })
                } else if (outbound.get("tag")?.asString == "proxy") {
                    val stream = outbound.getAsJsonObject("streamSettings") ?: JsonObject().also {
                        outbound.add("streamSettings", it)
                    }
                    val sockopt = stream.getAsJsonObject("sockopt") ?: JsonObject().also { stream.add("sockopt", it) }
                    sockopt.addProperty("domainStrategy", strategy)
                }
            }
            base.getAsJsonArray("outbounds").add(JsonObject().apply {
                addProperty("protocol", "dns")
                addProperty("tag", "dns-out")
                add("settings", JsonObject().apply {
                    addProperty("nonIPQuery", "skip")
                    addProperty("network", "tcp")
                    addProperty("address", AppConfig.VPN_DNS_PRIMARY)
                    addProperty("port", 53)
                })
                // Forward SRV/TXT/HTTPS and other non-address queries through the
                // chosen proxy, instead of sending them back to the virtual TUN DNS IP.
                add("streamSettings", JsonObject().apply {
                    add("sockopt", JsonObject().apply { addProperty("dialerProxy", "proxy") })
                })
            })
            if (dnsSettings.fakeDnsEnabled) {
                base.add("fakeDns", if (settings.ipv6RoutingMode == IPv6RoutingMode.DISABLED) {
                    JsonObject().apply {
                        addProperty("ipPool", "198.18.0.0/15")
                        addProperty("poolSize", 65_535)
                    }
                } else {
                    JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("ipPool", "198.18.0.0/15")
                            addProperty("poolSize", 32_768)
                        })
                        add(JsonObject().apply {
                            addProperty("ipPool", "fc00::/18")
                            addProperty("poolSize", 32_768)
                        })
                    }
                })
                base.getAsJsonArray("inbounds").forEach { element ->
                    val sniffing = element.asJsonObject.getAsJsonObject("sniffing")
                        ?: JsonObject().also { element.asJsonObject.add("sniffing", it) }
                    val overrides = sniffing.getAsJsonArray("destOverride")
                        ?: JsonArray().also { sniffing.add("destOverride", it) }
                    if (!overrides.any { it.asString.equals("fakedns", ignoreCase = true) }) {
                        overrides.add("fakedns")
                    }
                }
            }
            rules.add(rule("dns-out").apply {
                addProperty("port", "53")
                add(
                    "inboundTag",
                    if (settings.lanInbound.enabled) {
                        strings("internal-socks-in", "lan-socks-in")
                    } else {
                        strings("internal-socks-in")
                    }
                )
            })
            rules.add(rule("direct").apply { add("inboundTag", strings("dns-bootstrap")) })
            rules.add(rule("proxy").apply { add("inboundTag", strings("dns-remote")) })
            rules.add(rule("proxy").apply {
                add("domain", strings(*LinkTypeTestTargets.hosts.map { "full:$it" }.toTypedArray()))
            })
            rules.add(rule("direct").apply { add("domain", strings("full:${request.node.server}")) })
            rules.add(rule("direct").apply {
                add("ip", strings("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
                    "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10"))
            })
            val localDomains = strings("full:${request.node.server}")
            if (smartInboundTags.isNotEmpty()) {
                fun scopedSmartRule(outbound: String) = rule(outbound).apply {
                    add("inboundTag", strings(*smartInboundTags.toTypedArray()))
                }
                val enabledSmartRules = settings.smartRoutingSettings.rules.filter { it.enabled }
                val needsDomesticRules = enabledSmartRules.any {
                    it.type == SmartRoutingRuleType.DOMESTIC_DOMAIN ||
                        it.type == SmartRoutingRuleType.DOMESTIC_IP
                }
                val domesticDomains: JsonArray?
                val domesticIps: JsonArray?
                if (needsDomesticRules) {
                    require(cnRules?.get("schema")?.asInt == 1) { "Missing routing rules" }
                    val snapshot = requireNotNull(cnRules)
                    domesticDomains = snapshot.getAsJsonArray("domains")
                    domesticIps = snapshot.getAsJsonArray("ips")
                    require(domesticDomains.size() > 0 && domesticIps.size() > 0)
                } else {
                    domesticDomains = null
                    domesticIps = null
                }
                enabledSmartRules.forEach { smartRule ->
                    when (smartRule.type) {
                        SmartRoutingRuleType.DOMESTIC_DOMAIN -> {
                            val domains = requireNotNull(domesticDomains)
                            rules.add(scopedSmartRule(smartRule.action.configTag).apply {
                                add("domain", domains.deepCopy())
                            })
                            if (smartRule.action == SmartRoutingRuleAction.DIRECT) {
                                domains.forEach { localDomains.add(it.deepCopy()) }
                            }
                        }
                        SmartRoutingRuleType.DOMESTIC_IP -> {
                            rules.add(scopedSmartRule(smartRule.action.configTag).apply {
                                add("ip", requireNotNull(domesticIps).deepCopy())
                            })
                        }
                        SmartRoutingRuleType.CUSTOM_DOMAIN,
                        SmartRoutingRuleType.CUSTOM_IP,
                        SmartRoutingRuleType.CUSTOM_RULE -> {
                            val domains = smartRule.domains.ifEmpty {
                                if (smartRule.type == SmartRoutingRuleType.CUSTOM_DOMAIN) {
                                    smartRule.values
                                } else emptyList()
                            }
                            val ips = smartRule.ips.ifEmpty {
                                if (smartRule.type == SmartRoutingRuleType.CUSTOM_IP) {
                                    smartRule.values
                                } else emptyList()
                            }
                            val hasNonProcessCondition = domains.isNotEmpty() || ips.isNotEmpty() ||
                                smartRule.ports.isNotBlank() || smartRule.network.isNotBlank() ||
                                smartRule.protocols.isNotEmpty()
                            if (hasNonProcessCondition) {
                                rules.add(scopedSmartRule(smartRule.action.configTag).apply {
                                    if (domains.isNotEmpty()) {
                                        add(
                                            "domain",
                                            strings(*domains.map(::normalizeXrayDomain).toTypedArray())
                                        )
                                    }
                                    if (ips.isNotEmpty()) add("ip", strings(*ips.toTypedArray()))
                                    if (smartRule.ports.isNotBlank()) addProperty("port", smartRule.ports)
                                    if (smartRule.network.isNotBlank()) addProperty("network", smartRule.network)
                                    if (smartRule.protocols.isNotEmpty()) {
                                        add("protocol", strings(*smartRule.protocols.toTypedArray()))
                                    }
                                })
                            }
                            if (
                                smartRule.action == SmartRoutingRuleAction.DIRECT &&
                                ips.isEmpty() && smartRule.ports.isBlank() &&
                                smartRule.network.isBlank() && smartRule.protocols.isEmpty()
                            ) {
                                domains.forEach { localDomains.add(normalizeXrayDomain(it)) }
                            }
                        }
                    }
                }
            }
            base.add("dns", JsonObject().apply {
                addProperty("queryStrategy", resolveXrayDnsStrategy(
                    dnsSettings.remoteDomainStrategy,
                    settings.ipv6RoutingMode
                ))
                addProperty("disableFallbackIfMatch", true)
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", firstDnsAddress(
                            dnsSettings.directDns,
                            DnsSettings.DEFAULT_DIRECT_DNS
                        ))
                        addProperty("tag", "dns-bootstrap")
                        addProperty("skipFallback", true)
                        addProperty("queryStrategy", resolveXrayDnsStrategy(
                            dnsSettings.directDomainStrategy,
                            settings.ipv6RoutingMode
                        ))
                        add("domains", localDomains)
                    })
                    if (dnsSettings.fakeDnsEnabled) {
                        add(JsonObject().apply {
                            addProperty("address", "fakedns")
                            addProperty("tag", "dns-fake")
                            addProperty("finalQuery", true)
                            addProperty("queryStrategy", resolveXrayDnsStrategy(
                                dnsSettings.remoteDomainStrategy,
                                settings.ipv6RoutingMode
                            ))
                        })
                    }
                    add(JsonObject().apply {
                        addProperty("address", firstDnsAddress(
                            dnsSettings.remoteDns,
                            DnsSettings.DEFAULT_REMOTE_DNS
                        ))
                        addProperty("tag", "dns-remote")
                        addProperty("queryStrategy", resolveXrayDnsStrategy(
                            dnsSettings.remoteDomainStrategy,
                            settings.ipv6RoutingMode
                        ))
                    })
                })
            })
        }
        // The first outbound is the default proxy. A catch-all rule here would
        // prevent IPIfNonMatch from resolving domains for the SMART geo-IP rules.
        base.add("routing", JsonObject().apply {
            addProperty(
                "domainStrategy",
                request.settings?.smartRoutingSettings?.domainStrategy?.xrayValue ?: "AsIs"
            )
            add("rules", rules)
        })
        return base.toString()
    }

    private fun rule(outbound: String) = JsonObject().apply {
        addProperty("type", "field")
        addProperty("outboundTag", outbound)
    }
    private fun strings(vararg values: String) = JsonArray().apply { values.forEach(::add) }

    private fun normalizeXrayDomain(value: String): String {
        val normalized = value.trim()
        return if (normalized.substringBefore(':').lowercase() in setOf(
                "domain", "full", "regexp", "keyword", "geosite"
            )
        ) {
            normalized
        } else {
            "domain:$normalized"
        }
    }

    private fun firstDnsAddress(value: String, fallback: String): String =
        DnsSettings.normalizeServerList(value)
            ?.lineSequence()
            ?.firstOrNull()
            ?: fallback

    private fun resolveXrayDnsStrategy(
        strategy: DnsDomainStrategy,
        ipv6Mode: IPv6RoutingMode
    ): String = when (strategy) {
        DnsDomainStrategy.AUTO -> when (ipv6Mode) {
            IPv6RoutingMode.DISABLED -> "UseIPv4"
            IPv6RoutingMode.PREFER -> "UseIPv6v4"
            IPv6RoutingMode.ENABLED -> "UseIPv4v6"
        }
        DnsDomainStrategy.IPV4_ONLY -> "UseIPv4"
        DnsDomainStrategy.IPV6_ONLY -> "UseIPv6"
        DnsDomainStrategy.PREFER_IPV6 -> "UseIPv6v4"
        DnsDomainStrategy.PREFER_IPV4 -> "UseIPv4v6"
    }
}
