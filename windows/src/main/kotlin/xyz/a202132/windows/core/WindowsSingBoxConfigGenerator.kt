package xyz.a202132.windows.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.Ipv6RoutingMode
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProxyMode

class WindowsSingBoxConfigGenerator {
    private val gson = Gson()

    fun generate(
        nodes: List<DesktopNode>,
        selectedNodeId: String,
        proxyMode: ProxyMode,
        geoSiteCn: Path,
        geoIpCn: Path,
        settings: DesktopSettings = DesktopSettings(),
        clashApiPort: Int = 9090,
        tunEnabled: Boolean = true,
        proxyPort: Int = 0
    ): String {
        require(nodes.isNotEmpty()) { "没有可用于生成配置的节点" }
        val selected = nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.first()
        val nodeServers = nodes.map(DesktopNode::server).distinct()
        val (serverIps, serverDomains) = nodeServers.partition(::isIpAddress)

        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "info")
                addProperty("timestamp", true)
            })
            add("dns", createDns(proxyMode, serverDomains, settings.ipv6RoutingMode))
            add("inbounds", JsonArray().apply {
                if (tunEnabled) {
                    add(JsonObject().apply {
                        addProperty("type", "tun")
                        addProperty("tag", "tun-in")
                        addProperty("interface_name", "FireflyVPN")
                        add("address", JsonArray().apply {
                            if (settings.ipv6RoutingMode != Ipv6RoutingMode.ONLY) add("172.19.0.1/30")
                            if (settings.ipv6RoutingMode != Ipv6RoutingMode.DISABLED) add("fdfe:dcba:9876::1/126")
                        })
                        addProperty("mtu", settings.vpnMtu.coerceIn(576, 9_000))
                        addProperty("auto_route", true)
                        addProperty("strict_route", true)
                        add("route_address", JsonArray().apply {
                            if (settings.ipv6RoutingMode != Ipv6RoutingMode.ONLY) add("0.0.0.0/0")
                            if (settings.ipv6RoutingMode != Ipv6RoutingMode.DISABLED) add("::/0")
                        })
                        addProperty("stack", settings.tunStack.configValue)
                    })
                } else {
                    require(proxyPort in 1..65_535) { "本地代理端口无效：$proxyPort" }
                    add(JsonObject().apply {
                        addProperty("type", "mixed")
                        addProperty("tag", "mixed-in")
                        addProperty("listen", "127.0.0.1")
                        addProperty("listen_port", proxyPort)
                    })
                }
            })
            add("outbounds", createOutbounds(nodes, selected, settings))
            add("route", createRoute(proxyMode, serverDomains, serverIps, geoSiteCn, geoIpCn))
            add("experimental", JsonObject().apply {
                add("cache_file", JsonObject().apply { addProperty("enabled", true) })
                add("clash_api", JsonObject().apply {
                    addProperty("external_controller", "127.0.0.1:$clashApiPort")
                })
            })
        })
    }

    private fun createDns(
        mode: ProxyMode,
        nodeDomains: List<String>,
        ipv6RoutingMode: Ipv6RoutingMode
    ) = JsonObject().apply {
        add("servers", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "local")
                addProperty("type", "udp")
                addProperty("server", "223.5.5.5")
                addProperty("detour", "direct")
            })
            add(JsonObject().apply {
                addProperty("tag", "remote")
                addProperty("type", "tls")
                addProperty("server", "8.8.8.8")
                addProperty("detour", "proxy")
            })
        })
        add("rules", JsonArray().apply {
            if (nodeDomains.isNotEmpty()) {
                add(JsonObject().apply {
                    add("domain", nodeDomains.toJsonArray())
                    addProperty("action", "route")
                    addProperty("server", "local")
                })
            }
            if (mode == ProxyMode.SMART) {
                add(JsonObject().apply {
                    add("rule_set", JsonArray().apply { add("geosite-cn") })
                    addProperty("action", "route")
                    addProperty("server", "local")
                })
            }
        })
        addProperty("final", "remote")
        addProperty("strategy", when (ipv6RoutingMode) {
            Ipv6RoutingMode.ONLY -> "ipv6_only"
            Ipv6RoutingMode.PREFER -> "prefer_ipv6"
            Ipv6RoutingMode.ENABLED, Ipv6RoutingMode.DISABLED -> "prefer_ipv4"
        })
    }

    private fun createOutbounds(
        nodes: List<DesktopNode>,
        selected: DesktopNode,
        settings: DesktopSettings
    ) = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "selector")
            addProperty("tag", "proxy")
            add("outbounds", nodes.map(DesktopNode::id).toJsonArray())
            addProperty("default", selected.id)
        })
        nodes.forEach { node -> add(createNodeOutbound(node, settings)) }
        add(JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
            // sing-box 1.13 treats a completely default direct outbound as
            // an empty dialer. DNS detours to such an outbound are rejected
            // at runtime. Explicitly retain the core's default UDP behavior
            // to make this direct route non-empty without changing routing.
            addProperty("udp_fragment", true)
        })
    }

    private fun createNodeOutbound(node: DesktopNode, settings: DesktopSettings): JsonObject {
        val outbound = when (node.type) {
            NodeType.VLESS -> createVless(node)
            NodeType.VMESS -> createVmess(node)
            NodeType.TROJAN -> createTrojan(node)
            NodeType.HYSTERIA2 -> createHysteria2(node, settings)
            NodeType.ANYTLS -> createAnyTls(node)
            NodeType.TUIC -> createTuic(node)
            NodeType.NAIVE -> createNaive(node)
            NodeType.WIREGUARD -> createWireGuard(node)
            NodeType.SHADOWSOCKS -> createShadowsocks(node)
            NodeType.SOCKS -> createSocks(node)
            NodeType.HTTP -> createHttp(node)
        }
        outbound.addProperty("tag", node.id)
        return outbound
    }

    private fun createVless(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        return baseOutbound("vless", node).apply {
            addProperty("uuid", uri.rawUserInfo?.urlDecode().orEmpty())
            query.first("flow")?.let { addProperty("flow", it) }
            query.first("packetEncoding", "packet_encoding")?.let {
                addProperty("packet_encoding", it)
            }
            val security = query.first("security")
            if (security == "tls" || security == "reality") {
                add("tls", tls(node, query, reality = security == "reality"))
            }
            transport(query)?.let { add("transport", it) }
        }
    }

    private fun createVmess(node: DesktopNode): JsonObject {
        val encoded = node.rawLink.substringAfter("://").substringBefore('#')
        val source = JsonParser.parseString(decodeBase64(encoded)).asJsonObject
        return baseOutbound("vmess", node).apply {
            addProperty("server", source.get("add")?.asString ?: node.server)
            addProperty("server_port", source.get("port")?.asString?.toIntOrNull() ?: node.port)
            addProperty("uuid", source.get("id")?.asString.orEmpty())
            addProperty("alter_id", source.get("aid")?.asString?.toIntOrNull() ?: 0)
            addProperty("security", source.get("scy")?.asString ?: "auto")
            if (source.get("tls")?.asString == "tls") {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", source.get("sni")?.asString ?: node.server)
                    addProperty("insecure", source.get("allowInsecure")?.asString == "1")
                    source.get("fp")?.asString?.takeIf(String::isNotBlank)?.let { fingerprint ->
                        add("utls", utls(fingerprint))
                    }
                })
            }
            val query = mapOf(
                "type" to listOfNotNull(source.get("net")?.asString),
                "path" to listOfNotNull(source.get("path")?.asString),
                "host" to listOfNotNull(source.get("host")?.asString),
                "serviceName" to listOfNotNull(source.get("path")?.asString)
            )
            transport(query)?.let { add("transport", it) }
        }
    }

    private fun createTrojan(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        return baseOutbound("trojan", node).apply {
            addProperty("password", uri.rawUserInfo?.urlDecode().orEmpty())
            add("tls", tls(node, query))
            transport(query)?.let { add("transport", it) }
        }
    }

    private fun createHysteria2(node: DesktopNode, settings: DesktopSettings): JsonObject {
        val uri = node.uri(normalizeHy2 = true)
        val query = uri.queryParameters()
        return baseOutbound("hysteria2", node).apply {
            addProperty("password", uri.rawUserInfo?.urlDecode().orEmpty())
            if (settings.hysteriaBandwidthEnabled) {
                if (settings.hysteriaUpMbps > 0) addProperty("up_mbps", settings.hysteriaUpMbps)
                if (settings.hysteriaDownMbps > 0) addProperty("down_mbps", settings.hysteriaDownMbps)
            }
            add("tls", tls(node, query))
            val obfs = query.first("obfs")
            if (!obfs.isNullOrBlank()) {
                add("obfs", JsonObject().apply {
                    addProperty("type", obfs)
                    addProperty("password", query.first("obfs-password", "obfs_password").orEmpty())
                })
            }
        }
    }

    private fun createAnyTls(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        return baseOutbound("anytls", node).apply {
            addProperty("password", uri.rawUserInfo?.urlDecode().orEmpty())
            add("tls", tls(node, query))
        }
    }

    private fun createTuic(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        val credentials = uri.rawUserInfo?.urlDecode().orEmpty().split(':', limit = 2)
        return baseOutbound("tuic", node).apply {
            addProperty("uuid", query.first("uuid", "id") ?: credentials.firstOrNull().orEmpty())
            addProperty("password", query.first("password", "token") ?: credentials.getOrNull(1).orEmpty())
            add("tls", tls(node, query))
            query.first("congestion_control", "cc")?.let { addProperty("congestion_control", it) }
        }
    }

    private fun createNaive(node: DesktopNode): JsonObject {
        val uri = node.uri(normalizeNaive = true)
        val query = uri.queryParameters()
        val credentials = uri.rawUserInfo?.urlDecode().orEmpty().split(':', limit = 2)
        return baseOutbound("naive", node).apply {
            addProperty("username", query.first("username") ?: credentials.firstOrNull().orEmpty())
            addProperty("password", query.first("password") ?: credentials.getOrNull(1).orEmpty())
            add("tls", tls(node, query))
        }
    }

    private fun createWireGuard(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        return baseOutbound("wireguard", node).apply {
            addProperty("private_key", uri.rawUserInfo?.urlDecode() ?: query.first("private_key").orEmpty())
            addProperty("peer_public_key", query.first("publickey", "peer_public_key").orEmpty())
            add("local_address", (query.first("address") ?: "10.0.0.2/32").split(',').toJsonArray())
            query.first("presharedkey", "pre_shared_key")?.let { addProperty("pre_shared_key", it) }
        }
    }

    private fun createShadowsocks(node: DesktopNode): JsonObject {
        val payload = node.rawLink.substringAfter("ss://").substringBefore('#')
        val beforeServer = if ('@' in payload) payload.substringBeforeLast('@') else decodeBase64(payload)
        val credentials = runCatching { decodeBase64(beforeServer.substringBefore('?')) }
            .getOrDefault(beforeServer.substringBefore('?'))
            .split(':', limit = 2)
        return baseOutbound("shadowsocks", node).apply {
            addProperty("method", credentials.firstOrNull() ?: "aes-256-gcm")
            addProperty("password", credentials.getOrNull(1).orEmpty())
        }
    }

    private fun createSocks(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val credentials = uri.rawUserInfo?.urlDecode().orEmpty().split(':', limit = 2)
        return baseOutbound("socks", node).apply {
            addProperty("version", when {
                node.rawLink.startsWith("socks4a://", true) -> "4a"
                node.rawLink.startsWith("socks4://", true) -> "4"
                else -> "5"
            })
            credentials.firstOrNull()?.takeIf(String::isNotBlank)?.let {
                addProperty("username", it)
                addProperty("password", credentials.getOrNull(1).orEmpty())
            }
        }
    }

    private fun createHttp(node: DesktopNode): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        val credentials = uri.rawUserInfo?.urlDecode().orEmpty().split(':', limit = 2)
        return baseOutbound("http", node).apply {
            credentials.firstOrNull()?.takeIf(String::isNotBlank)?.let {
                addProperty("username", it)
                addProperty("password", credentials.getOrNull(1).orEmpty())
            }
            if (node.rawLink.startsWith("https://", true)) add("tls", tls(node, query))
        }
    }

    private fun baseOutbound(type: String, node: DesktopNode) = JsonObject().apply {
        addProperty("type", type)
        addProperty("server", node.server)
        addProperty("server_port", node.port)
    }

    private fun tls(
        node: DesktopNode,
        query: Map<String, List<String>>,
        reality: Boolean = false
    ) = JsonObject().apply {
        addProperty("enabled", true)
        addProperty("server_name", query.first("sni", "host") ?: node.server)
        addProperty("insecure", query.enabled("allowInsecure", "allow_insecure", "insecure"))
        query.first("alpn")?.split(',')?.filter(String::isNotBlank)?.let { values ->
            if (values.isNotEmpty()) add("alpn", values.toJsonArray())
        }
        query.first("fp", "fingerprint", "client-fingerprint")?.let { fingerprint ->
            add("utls", utls(fingerprint))
        }
        if (reality) {
            add("reality", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("public_key", query.first("pbk").orEmpty())
                addProperty("short_id", query.first("sid").orEmpty())
            })
        }
    }

    private fun utls(fingerprint: String) = JsonObject().apply {
        addProperty("enabled", true)
        addProperty("fingerprint", fingerprint)
    }

    private fun transport(query: Map<String, List<String>>): JsonObject? {
        val type = query.first("type", "network")?.lowercase() ?: return null
        return when (type) {
            "ws" -> JsonObject().apply {
                addProperty("type", "ws")
                addProperty("path", query.first("path") ?: "/")
                query.first("host")?.let { host ->
                    add("headers", JsonObject().apply { addProperty("Host", host) })
                }
            }
            "grpc" -> JsonObject().apply {
                addProperty("type", "grpc")
                addProperty("service_name", query.first("serviceName", "service_name").orEmpty())
            }
            "httpupgrade" -> JsonObject().apply {
                addProperty("type", "httpupgrade")
                addProperty("path", query.first("path") ?: "/")
                query.first("host")?.let { addProperty("host", it) }
            }
            else -> null
        }
    }

    private fun createRoute(
        mode: ProxyMode,
        nodeDomains: List<String>,
        nodeIps: List<String>,
        geoSiteCn: Path,
        geoIpCn: Path
    ) = JsonObject().apply {
        add("rules", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("action", "sniff")
            })
            add(JsonObject().apply {
                addProperty("protocol", "dns")
                addProperty("action", "hijack-dns")
            })
            add(JsonObject().apply {
                addProperty("ip_is_private", true)
                addProperty("outbound", "direct")
            })
            if (nodeDomains.isNotEmpty()) add(JsonObject().apply {
                add("domain", nodeDomains.toJsonArray())
                addProperty("outbound", "direct")
            })
            if (mode == ProxyMode.SMART) {
                add(JsonObject().apply {
                    add("rule_set", JsonArray().apply { add("geosite-cn") })
                    addProperty("outbound", "direct")
                })
                add(JsonObject().apply {
                    add("rule_set", JsonArray().apply { add("geoip-cn") })
                    addProperty("outbound", "direct")
                })
            }
            if (nodeIps.isNotEmpty()) add(JsonObject().apply {
                add("ip_cidr", nodeIps.map { if (':' in it) "$it/128" else "$it/32" }.toJsonArray())
                addProperty("outbound", "direct")
            })
        })
        if (mode == ProxyMode.SMART) {
            add("rule_set", JsonArray().apply {
                add(localRuleSet("geosite-cn", geoSiteCn))
                add(localRuleSet("geoip-cn", geoIpCn))
            })
        }
        addProperty("final", "proxy")
        addProperty("auto_detect_interface", true)
        addProperty("default_domain_resolver", "local")
    }

    private fun localRuleSet(tag: String, path: Path) = JsonObject().apply {
        addProperty("tag", tag)
        addProperty("type", "local")
        addProperty("format", "binary")
        addProperty("path", path.toAbsolutePath().toString().replace('\\', '/'))
    }

    private fun DesktopNode.uri(normalizeHy2: Boolean = false, normalizeNaive: Boolean = false): URI {
        var value = rawLink
        if (normalizeHy2) value = value.replaceFirst(Regex("(?i)^hy2://"), "hysteria2://")
        if (normalizeNaive) value = value.replaceFirst(Regex("(?i)^naive[+]https://"), "naive://")
        return URI(value.replace(" ", "%20"))
    }

    private fun URI.queryParameters(): Map<String, List<String>> = rawQuery.orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .map { part ->
            part.substringBefore('=').urlDecode() to part.substringAfter('=', "").urlDecode()
        }
        .groupBy({ it.first }, { it.second })

    private fun Map<String, List<String>>.first(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> entries.firstOrNull { it.key.equals(key, true) }?.value?.firstOrNull() }

    private fun Map<String, List<String>>.enabled(vararg keys: String): Boolean =
        first(*keys)?.let { it == "1" || it.equals("true", true) } == true

    private fun Iterable<String>.toJsonArray() = JsonArray().apply {
        this@toJsonArray.forEach { add(it) }
    }

    private fun String.urlDecode() = URLDecoder.decode(this, StandardCharsets.UTF_8)

    private fun decodeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private fun isIpAddress(value: String): Boolean =
        IPV4.matches(value) || ':' in value

    companion object {
        private val IPV4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    }
}
