package xyz.a202132.app.core.xray

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.a202132.app.AppConfig
import xyz.a202132.app.core.CoreRequest
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.platform.IpPrefix
import xyz.a202132.app.platform.TunnelSpec
import xyz.a202132.app.util.XrayConfigGenerator

internal object XrayVpnConfig {
    fun tunnel(mode: IPv6RoutingMode): TunnelSpec {
        val addresses = mutableListOf<IpPrefix>()
        val routes = mutableListOf<IpPrefix>()
        if (mode != IPv6RoutingMode.ONLY) {
            addresses += IpPrefix("172.19.0.1", 30)
            routes += IpPrefix("0.0.0.0", 0)
        }
        if (mode != IPv6RoutingMode.DISABLED) {
            addresses += IpPrefix("fdfe:dcba:9876::1", 126)
            routes += IpPrefix("::", 0)
        }
        return TunnelSpec(AppConfig.VPN_MTU, addresses, routes,
            listOf(if (mode == IPv6RoutingMode.ONLY) "fdfe:dcba:9876::2" else "172.19.0.2"))
    }

    fun generate(request: CoreRequest, cnRules: JsonObject? = null): String {
        val base = JsonParser.parseString(XrayConfigGenerator().generateTestConfig(
            request.node, request.socksPort, request.hysteriaUpMbps, request.hysteriaDownMbps
        )).asJsonObject
        return compose(base, request, cnRules)
    }

    internal fun compose(base: JsonObject, request: CoreRequest, cnRules: JsonObject? = null): String {
        val inbound = base.getAsJsonArray("inbounds")[0].asJsonObject
        inbound.addProperty("tag", "internal-socks-in")
        base.getAsJsonArray("outbounds")[0].asJsonObject.addProperty("tag", "proxy")
        val rules = JsonArray()
        if (request.isVpn) {
            val settings = request.settings!!
            val strategy = when (settings.ipv6RoutingMode) {
                IPv6RoutingMode.DISABLED -> "UseIPv4"
                IPv6RoutingMode.ONLY -> "UseIPv6"
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
            base.getAsJsonArray("inbounds").add(JsonObject().apply {
                addProperty("tag", "tun-in")
                addProperty("protocol", "tun")
                add("settings", JsonObject().apply {
                    addProperty("name", "firefly")
                    addProperty("MTU", AppConfig.VPN_MTU)
                })
                add("sniffing", inbound.get("sniffing").deepCopy())
            })
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
            rules.add(rule("dns-out").apply {
                addProperty("port", "53")
                add("inboundTag", strings("tun-in", "internal-socks-in"))
            })
            rules.add(rule("direct").apply { add("inboundTag", strings("dns-bootstrap")) })
            rules.add(rule("proxy").apply { add("inboundTag", strings("dns-remote")) })
            rules.add(rule("direct").apply { add("domain", strings("full:${request.node.server}")) })
            rules.add(rule("direct").apply {
                add("ip", strings("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
                    "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10"))
            })
            val localDomains = strings("full:${request.node.server}")
            if (settings.proxyMode == ProxyMode.SMART) {
                require(cnRules?.get("schema")?.asInt == 1) { "缺少公共分流规则" }
                val snapshot = requireNotNull(cnRules)
                val domains = snapshot.getAsJsonArray("domains")
                val ips = snapshot.getAsJsonArray("ips")
                require(domains.size() > 0 && ips.size() > 0)
                rules.add(rule("direct").apply { add("domain", domains.deepCopy()) })
                rules.add(rule("direct").apply { add("ip", ips.deepCopy()) })
                domains.forEach { localDomains.add(it.deepCopy()) }
            }
            base.add("dns", JsonObject().apply {
                addProperty("queryStrategy", when (settings.ipv6RoutingMode) {
                    IPv6RoutingMode.DISABLED -> "UseIPv4"
                    IPv6RoutingMode.ONLY -> "UseIPv6"
                    else -> "UseIP"
                })
                addProperty("disableFallbackIfMatch", true)
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", AppConfig.VPN_DNS_CHINA)
                        addProperty("tag", "dns-bootstrap")
                        addProperty("skipFallback", true)
                        add("domains", localDomains)
                    })
                    add(JsonObject().apply {
                        addProperty("address", "https://${AppConfig.VPN_DNS_PRIMARY}/dns-query")
                        addProperty("tag", "dns-remote")
                    })
                })
            })
        }
        // The first outbound is the default proxy. A catch-all rule here would
        // prevent IPIfNonMatch from resolving domains for the SMART geo-IP rules.
        base.add("routing", JsonObject().apply {
            addProperty("domainStrategy", if (request.isVpn) "IPIfNonMatch" else "AsIs")
            add("rules", rules)
        })
        return base.toString()
    }

    private fun rule(outbound: String) = JsonObject().apply {
        addProperty("type", "field")
        addProperty("outboundTag", outbound)
    }
    private fun strings(vararg values: String) = JsonArray().apply { values.forEach(::add) }
}
