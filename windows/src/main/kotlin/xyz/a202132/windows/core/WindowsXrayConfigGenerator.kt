package xyz.a202132.windows.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProtocolCoreSettings

/** 为每个节点生成独立 SOCKS 桥接入站，并统一交由一个 Xray 进程运行。 */
class WindowsXrayConfigGenerator {
    private val gson = Gson()

    fun generate(nodes: List<DesktopNode>, bridgePorts: Map<String, Int>): String {
        require(nodes.isNotEmpty()) { "Xray 节点列表为空" }
        require(nodes.all { it.type in XRAY_SUPPORTED_TYPES }) { "包含 Xray 不支持的协议" }
        require(nodes.all { bridgePorts[it.id] in 1..65_535 }) { "Xray 桥接端口无效" }

        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply {
                nodes.forEach { node -> add(createBridgeInbound(node, bridgePorts.getValue(node.id))) }
            })
            add("outbounds", JsonArray().apply {
                nodes.forEach { node -> add(createNodeOutbound(node, outboundTag(node.id))) }
                add(JsonObject().apply {
                    addProperty("protocol", "freedom")
                    addProperty("tag", DIRECT_TAG)
                })
                add(JsonObject().apply {
                    addProperty("protocol", "blackhole")
                    addProperty("tag", BLOCK_TAG)
                })
            })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", JsonArray().apply {
                    nodes.forEach { node ->
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            add("inboundTag", JsonArray().apply { add(inboundTag(node.id)) })
                            addProperty("outboundTag", outboundTag(node.id))
                        })
                    }
                })
            })
        })
    }

    fun generateTestConfig(node: DesktopNode, bridgePort: Int = 20_800): String =
        generate(listOf(node), mapOf(node.id to bridgePort))

    private fun createBridgeInbound(node: DesktopNode, port: Int) = JsonObject().apply {
        addProperty("listen", "127.0.0.1")
        addProperty("port", port)
        addProperty("protocol", "socks")
        addProperty("tag", inboundTag(node.id))
        add("settings", JsonObject().apply {
            addProperty("auth", "noauth")
            addProperty("udp", true)
            addProperty("ip", "127.0.0.1")
        })
        add("sniffing", JsonObject().apply {
            addProperty("enabled", true)
            addProperty("routeOnly", true)
            add("destOverride", JsonArray().apply {
                add("http")
                add("tls")
                add("quic")
            })
        })
    }

    private fun createNodeOutbound(node: DesktopNode, tag: String): JsonObject = when (node.type) {
        NodeType.VLESS -> createVlessOutbound(node, tag)
        NodeType.VMESS -> createVmessOutbound(node, tag)
        NodeType.TROJAN -> createTrojanOutbound(node, tag)
        NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node, tag)
        else -> error("Xray 不支持 ${node.type.protocol} 协议")
    }

    private fun createVlessOutbound(node: DesktopNode, tag: String): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        return JsonObject().apply {
            addProperty("protocol", "vless")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", node.server)
                        addProperty("port", node.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", uri.rawUserInfo?.urlDecode().orEmpty())
                                addProperty("encryption", query.first("encryption") ?: "none")
                                query.first("flow")?.let { addProperty("flow", it) }
                            })
                        })
                    })
                })
            })
            add("streamSettings", createUriStreamSettings(uri, node.server))
            add("mux", JsonObject().apply { addProperty("enabled", false) })
        }
    }

    private fun createVmessOutbound(node: DesktopNode, tag: String): JsonObject {
        val payload = node.rawLink.substringAfter("://").substringBefore('#')
        val vmess = JsonParser.parseString(decodeBase64(payload)).asJsonObject
        val address = vmess.get("add").stringOrNull() ?: node.server
        val port = vmess.get("port").intOrNull() ?: node.port
        val security = vmess.get("scy").stringOrNull()
            ?: vmess.get("security").stringOrNull()
            ?: "auto"
        return JsonObject().apply {
            addProperty("protocol", "vmess")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", vmess.get("id").stringOrNull().orEmpty())
                                addProperty("alterId", vmess.get("aid").intOrNull() ?: 0)
                                addProperty("security", security)
                            })
                        })
                    })
                })
            })
            add("streamSettings", createVmessStreamSettings(vmess, address))
            add("mux", JsonObject().apply { addProperty("enabled", false) })
        }
    }

    private fun createTrojanOutbound(node: DesktopNode, tag: String): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        return JsonObject().apply {
            addProperty("protocol", "trojan")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", node.server)
                        addProperty("port", node.port)
                        addProperty("password", uri.rawUserInfo?.urlDecode().orEmpty())
                        query.first("flow")?.let { addProperty("flow", it) }
                    })
                })
            })
            add("streamSettings", createUriStreamSettings(uri, node.server, tlsByDefault = true))
            add("mux", JsonObject().apply { addProperty("enabled", false) })
        }
    }

    private fun createShadowsocksOutbound(node: DesktopNode, tag: String): JsonObject {
        val rawPayload = node.rawLink.substringAfter("ss://").substringBefore('#')
        val query = rawPayload.substringAfter('?', "").queryParameters()
        require(query.first("plugin").isNullOrBlank()) {
            "Xray 不支持 Shadowsocks SIP003 插件节点"
        }
        val authority = rawPayload.substringBefore('?')
        val credentialToken = if ('@' in authority) {
            authority.substringBeforeLast('@')
        } else {
            decodeBase64(authority).substringBeforeLast('@')
        }
        val credentials = runCatching { decodeBase64(credentialToken) }
            .getOrDefault(credentialToken.urlDecode())
            .split(':', limit = 2)
        val method = credentials.firstOrNull().orEmpty()
        val password = credentials.getOrNull(1).orEmpty()
        require(method.isNotBlank() && password.isNotBlank()) { "Shadowsocks 认证信息无效" }

        return JsonObject().apply {
            addProperty("protocol", "shadowsocks")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", node.server)
                        addProperty("port", node.port)
                        addProperty("method", method)
                        addProperty("password", password)
                    })
                })
            })
        }
    }

    private fun createUriStreamSettings(
        uri: URI,
        server: String,
        tlsByDefault: Boolean = false
    ): JsonObject {
        val query = uri.queryParameters()
        val network = normalizeTransport(query.first("type", "network"))
        return JsonObject().apply {
            addProperty("network", network)
            applyUriTransport(this, network, query)
            val security = query.first("security")
                ?.lowercase()
                ?.let { if (it == "xtls") "tls" else it }
                ?.takeUnless { it == "none" }
                ?: if (tlsByDefault) "tls" else null
            applySecurity(
                this,
                security,
                server,
                query.first("sni", "serverName", "host"),
                parseCsv(query.first("alpn")),
                query.first("fp", "fingerprint", "client-fingerprint"),
                query.enabled("allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"),
                query.first("pbk", "publicKey"),
                query.first("sid", "shortId"),
                query.first("spx", "spiderX")
            )
        }
    }

    private fun createVmessStreamSettings(vmess: JsonObject, server: String): JsonObject {
        val network = normalizeTransport(
            vmess.get("net").stringOrNull() ?: vmess.get("network").stringOrNull()
        )
        return JsonObject().apply {
            addProperty("network", network)
            applyVmessTransport(this, network, vmess)
            applySecurity(
                this,
                vmess.get("tls").stringOrNull()?.lowercase()?.takeUnless { it == "none" },
                server,
                vmess.get("sni").stringOrNull() ?: vmess.get("host").stringList().firstOrNull(),
                vmess.get("alpn").stringList(),
                vmess.get("fp").stringOrNull() ?: vmess.get("fingerprint").stringOrNull(),
                vmess.get("allowInsecure").booleanOrNull()
                    ?: vmess.get("allow_insecure").booleanOrNull()
                    ?: false,
                vmess.get("pbk").stringOrNull(),
                vmess.get("sid").stringOrNull(),
                vmess.get("spx").stringOrNull()
            )
        }
    }

    private fun applyUriTransport(
        settings: JsonObject,
        network: String,
        query: Map<String, List<String>>
    ) {
        val host = query.first("host").orEmpty()
        val path = query.first("path") ?: "/"
        val headerType = query.first("headerType", "header_type") ?: "none"
        when (network) {
            "tcp" -> settings.add("tcpSettings", createTcpSettings(headerType, host, path))
            "ws" -> settings.add("wsSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) add("headers", JsonObject().apply { addProperty("Host", host) })
                query.first("ed", "maxEarlyData", "max_early_data")
                    ?.toIntOrNull()?.takeIf { it > 0 }?.let { addProperty("maxEarlyData", it) }
                query.first("eh", "earlyDataHeaderName", "early_data_header_name")
                    ?.let { addProperty("earlyDataHeaderName", it) }
            })
            "httpupgrade" -> settings.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) addProperty("host", host)
            })
            "xhttp" -> settings.add("xhttpSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) addProperty("host", host)
                query.first("mode")?.let { addProperty("mode", it) }
                query.first("extra")?.let { raw ->
                    runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { add("extra", it) }
                }
            })
            "h2" -> settings.add("httpSettings", JsonObject().apply {
                addProperty("path", path)
                add("host", JsonArray().apply { parseCsv(host).forEach { add(it) } })
            })
            "grpc" -> settings.add("grpcSettings", JsonObject().apply {
                addProperty("serviceName", query.first("serviceName", "service_name") ?: path.removePrefix("/"))
                query.first("authority")?.let { addProperty("authority", it) }
                addProperty("multiMode", query.first("mode").equals("multi", true))
            })
            "kcp" -> settings.add("kcpSettings", JsonObject().apply {
                add("header", JsonObject().apply { addProperty("type", normalizeKcpHeader(headerType)) })
                query.first("seed")?.let { addProperty("seed", it) }
            })
            "quic" -> settings.add("quicSettings", JsonObject().apply {
                addProperty("security", query.first("quicSecurity") ?: "none")
                addProperty("key", query.first("key") ?: "")
                add("header", JsonObject().apply { addProperty("type", headerType) })
            })
        }
    }

    private fun applyVmessTransport(settings: JsonObject, network: String, vmess: JsonObject) {
        val hostValues = vmess.get("host").stringList()
        val host = hostValues.joinToString(",")
        val path = vmess.get("path").stringOrNull() ?: "/"
        val headerType = vmess.get("type").stringOrNull()
            ?: vmess.get("headerType").stringOrNull()
            ?: "none"
        when (network) {
            "tcp" -> settings.add("tcpSettings", createTcpSettings(headerType, host, path))
            "ws" -> settings.add("wsSettings", JsonObject().apply {
                addProperty("path", path)
                hostValues.firstOrNull()?.let { value ->
                    add("headers", JsonObject().apply { addProperty("Host", value) })
                }
                (vmess.get("ed").intOrNull() ?: vmess.get("maxEarlyData").intOrNull())
                    ?.takeIf { it > 0 }?.let { addProperty("maxEarlyData", it) }
                (vmess.get("eh").stringOrNull() ?: vmess.get("earlyDataHeaderName").stringOrNull())
                    ?.let { addProperty("earlyDataHeaderName", it) }
            })
            "httpupgrade" -> settings.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", path)
                hostValues.firstOrNull()?.let { addProperty("host", it) }
            })
            "xhttp" -> settings.add("xhttpSettings", JsonObject().apply {
                addProperty("path", path)
                hostValues.firstOrNull()?.let { addProperty("host", it) }
                vmess.get("mode").stringOrNull()?.let { addProperty("mode", it) }
                vmess.get("extra").stringOrNull()?.let { raw ->
                    runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { add("extra", it) }
                }
            })
            "h2" -> settings.add("httpSettings", JsonObject().apply {
                addProperty("path", path)
                add("host", JsonArray().apply { hostValues.forEach { add(it) } })
            })
            "grpc" -> settings.add("grpcSettings", JsonObject().apply {
                addProperty("serviceName", vmess.get("serviceName").stringOrNull() ?: path.removePrefix("/"))
                vmess.get("authority").stringOrNull()?.let { addProperty("authority", it) }
                addProperty("multiMode", vmess.get("mode").stringOrNull().equals("multi", true))
            })
            "kcp" -> settings.add("kcpSettings", JsonObject().apply {
                add("header", JsonObject().apply { addProperty("type", normalizeKcpHeader(headerType)) })
                vmess.get("seed").stringOrNull()?.let { addProperty("seed", it) }
            })
            "quic" -> settings.add("quicSettings", JsonObject().apply {
                addProperty("security", vmess.get("host").stringOrNull() ?: "none")
                addProperty("key", vmess.get("path").stringOrNull() ?: "")
                add("header", JsonObject().apply { addProperty("type", headerType) })
            })
        }
    }

    private fun createTcpSettings(headerType: String, host: String, path: String) =
        JsonObject().apply {
            add("header", JsonObject().apply {
                if (headerType.equals("http", true)) {
                    addProperty("type", "http")
                    add("request", JsonObject().apply {
                        add("path", JsonArray().apply {
                            parseCsv(path).ifEmpty { listOf("/") }.forEach { add(it) }
                        })
                        add("headers", JsonObject().apply {
                            val hosts = parseCsv(host)
                            if (hosts.isNotEmpty()) {
                                add("Host", JsonArray().apply { hosts.forEach { add(it) } })
                            }
                        })
                    })
                } else {
                    addProperty("type", "none")
                }
            })
        }

    private fun applySecurity(
        streamSettings: JsonObject,
        security: String?,
        server: String,
        sni: String?,
        alpn: List<String>,
        fingerprint: String?,
        allowInsecure: Boolean,
        publicKey: String?,
        shortId: String?,
        spiderX: String?
    ) {
        when (security) {
            "tls" -> {
                streamSettings.addProperty("security", "tls")
                streamSettings.add("tlsSettings", JsonObject().apply {
                    addProperty("serverName", sni ?: server)
                    addProperty("allowInsecure", allowInsecure)
                    if (alpn.isNotEmpty()) add("alpn", JsonArray().apply { alpn.forEach { add(it) } })
                    fingerprint?.let { addProperty("fingerprint", it) }
                })
            }
            "reality" -> {
                streamSettings.addProperty("security", "reality")
                streamSettings.add("realitySettings", JsonObject().apply {
                    addProperty("serverName", sni ?: server)
                    addProperty("fingerprint", fingerprint ?: "chrome")
                    publicKey?.let { addProperty("publicKey", it) }
                    shortId?.let { addProperty("shortId", it) }
                    spiderX?.let { addProperty("spiderX", it) }
                })
            }
        }
    }

    private fun normalizeTransport(value: String?): String = when (value?.trim()?.lowercase()) {
        null, "", "none", "tcp", "raw" -> "tcp"
        "websocket" -> "ws"
        "gun" -> "grpc"
        "http-upgrade" -> "httpupgrade"
        "splithttp", "split-http" -> "xhttp"
        "http", "h2" -> "h2"
        "mkcp" -> "kcp"
        else -> value.trim().lowercase()
    }

    private fun normalizeKcpHeader(value: String): String = when (value.lowercase()) {
        "none", "srtp", "utp", "wechat-video", "dtls", "wireguard", "dns" -> value.lowercase()
        else -> "none"
    }

    private fun DesktopNode.uri(): URI = URI(rawLink.replace(" ", "%20"))

    private fun URI.queryParameters(): Map<String, List<String>> = rawQuery.orEmpty().queryParameters()

    private fun String.queryParameters(): Map<String, List<String>> = split('&')
        .filter(String::isNotBlank)
        .map { part ->
            part.substringBefore('=').urlDecode() to part.substringAfter('=', "").urlDecode()
        }
        .groupBy({ it.first }, { it.second })

    private fun Map<String, List<String>>.first(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            entries.firstOrNull { it.key.equals(key, true) }?.value?.firstOrNull()
        }

    private fun Map<String, List<String>>.enabled(vararg keys: String): Boolean =
        first(*keys)?.let { it == "1" || it.equals("true", true) } == true

    private fun JsonElement?.stringOrNull(): String? = runCatching {
        this?.takeUnless(JsonElement::isJsonNull)?.asString
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun JsonElement?.intOrNull(): Int? = runCatching { this?.asInt }.getOrNull()

    private fun JsonElement?.booleanOrNull(): Boolean? = runCatching {
        when (val value = this?.asString?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> value?.toBooleanStrictOrNull()
        }
    }.getOrNull()

    private fun JsonElement?.stringList(): List<String> = when {
        this == null || isJsonNull -> emptyList()
        isJsonArray -> asJsonArray.mapNotNull { it.stringOrNull() }
        else -> parseCsv(stringOrNull())
    }

    private fun parseCsv(value: String?): List<String> = value.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun String.urlDecode(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)

    private fun decodeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private fun inboundTag(nodeId: String) = "bridge-in-$nodeId"
    private fun outboundTag(nodeId: String) = "proxy-$nodeId"

    companion object {
        val XRAY_SUPPORTED_TYPES: Set<NodeType> =
            ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet()
        private const val DIRECT_TAG = "direct"
        private const val BLOCK_TAG = "block"
    }
}
