package xyz.a202132.app.util

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProtocolCoreSettings

/** 为每个节点生成独立 SOCKS 桥接入站，并统一交由一个 Xray 进程运行。 */
class XrayConfigGenerator {
    private val gson = Gson()

    fun generateConfig(nodes: List<Node>, bridgePorts: Map<String, Int>): String {
        require(nodes.isNotEmpty()) { "Xray 节点列表为空" }
        require(nodes.all { it.type in XRAY_SUPPORTED_TYPES }) { "包含 Xray 不支持的协议" }
        require(nodes.all { bridgePorts[it.id] in 1..65_535 }) { "Xray 桥接端口无效" }

        val config = JsonObject().apply {
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
        }
        return gson.toJson(config)
    }

    fun generateTestConfig(node: Node, bridgePort: Int = 20_800): String =
        generateConfig(listOf(node), mapOf(node.id to bridgePort))

    private fun createBridgeInbound(node: Node, port: Int): JsonObject = JsonObject().apply {
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

    private fun createNodeOutbound(node: Node, tag: String): JsonObject = when (node.type) {
        NodeType.VLESS -> createVlessOutbound(node, tag)
        NodeType.VMESS -> createVmessOutbound(node, tag)
        NodeType.TROJAN -> createTrojanOutbound(node, tag)
        NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node, tag)
        else -> error("Xray 不支持 ${node.type.protocol} 协议")
    }

    private fun createVlessOutbound(node: Node, tag: String): JsonObject {
        val uri = Uri.parse(node.getRawLinkPlain())
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
                                addProperty("id", uri.userInfo.orEmpty())
                                addProperty("encryption", queryParamFirst(uri, "encryption") ?: "none")
                                queryParamFirst(uri, "flow")?.let { addProperty("flow", it) }
                            })
                        })
                    })
                })
            })
            add("streamSettings", createUriStreamSettings(uri, node.server))
            add("mux", JsonObject().apply { addProperty("enabled", false) })
        }
    }

    private fun createVmessOutbound(node: Node, tag: String): JsonObject {
        val payload = node.getRawLinkPlain().removePrefix("vmess://")
        val vmess = gson.fromJson(decodeBase64Compat(payload), JsonObject::class.java)
        val address = parseJsonString(vmess.get("add")) ?: node.server
        val port = parseJsonInt(vmess.get("port")) ?: node.port
        val security = parseJsonString(vmess.get("scy"))
            ?: parseJsonString(vmess.get("security"))
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
                                addProperty("id", parseJsonString(vmess.get("id")).orEmpty())
                                addProperty("alterId", parseJsonInt(vmess.get("aid")) ?: 0)
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

    private fun createTrojanOutbound(node: Node, tag: String): JsonObject {
        val uri = Uri.parse(node.getRawLinkPlain())
        return JsonObject().apply {
            addProperty("protocol", "trojan")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", node.server)
                        addProperty("port", node.port)
                        addProperty("password", uri.userInfo.orEmpty())
                        queryParamFirst(uri, "flow")?.let { addProperty("flow", it) }
                    })
                })
            })
            add("streamSettings", createUriStreamSettings(uri, node.server, tlsByDefault = true))
            add("mux", JsonObject().apply { addProperty("enabled", false) })
        }
    }

    private fun createShadowsocksOutbound(node: Node, tag: String): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        require(uri.getQueryParameter("plugin").isNullOrBlank()) {
            "Xray 不支持 Shadowsocks SIP003 插件节点"
        }
        val methodPassword = parseShadowsocksMethodPassword(
            rawLink.removePrefix("ss://").substringBefore("?").substringBefore("#")
        ).split(":", limit = 2)
        val method = methodPassword.getOrNull(0).orEmpty()
        val password = methodPassword.getOrNull(1).orEmpty()
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
        uri: Uri,
        server: String,
        tlsByDefault: Boolean = false
    ): JsonObject {
        val network = normalizeXrayTransport(queryParamFirst(uri, "type", "network"))
        val settings = JsonObject().apply { addProperty("network", network) }
        applyUriTransport(settings, network, uri)
        val security = queryParamFirst(uri, "security")
            ?.lowercase()
            ?.let { if (it == "xtls") "tls" else it }
            ?.takeUnless { it == "none" }
            ?: if (tlsByDefault) "tls" else null
        applySecurity(
            streamSettings = settings,
            security = security,
            server = server,
            sni = queryParamFirst(uri, "sni", "serverName", "host"),
            alpn = parseCsvParams(queryParamFirst(uri, "alpn")),
            fingerprint = queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint"),
            allowInsecure = queryParamEnabled(
                uri,
                "allowInsecure",
                "allow_insecure",
                "insecure",
                "skip-cert-verify"
            ),
            publicKey = queryParamFirst(uri, "pbk", "publicKey"),
            shortId = queryParamFirst(uri, "sid", "shortId"),
            spiderX = queryParamFirst(uri, "spx", "spiderX")
        )
        return settings
    }

    private fun createVmessStreamSettings(vmess: JsonObject, server: String): JsonObject {
        val network = normalizeXrayTransport(
            parseJsonString(vmess.get("net")) ?: parseJsonString(vmess.get("network"))
        )
        val settings = JsonObject().apply { addProperty("network", network) }
        applyVmessTransport(settings, network, vmess)
        applySecurity(
            streamSettings = settings,
            security = parseJsonString(vmess.get("tls"))?.lowercase()?.takeUnless { it == "none" },
            server = server,
            sni = parseJsonString(vmess.get("sni"))
                ?: parseJsonStringList(vmess.get("host")).firstOrNull(),
            alpn = parseJsonStringList(vmess.get("alpn")),
            fingerprint = parseJsonString(vmess.get("fp"))
                ?: parseJsonString(vmess.get("fingerprint")),
            allowInsecure = parseJsonBoolean(vmess.get("allowInsecure"))
                ?: parseJsonBoolean(vmess.get("allow_insecure"))
                ?: false,
            publicKey = parseJsonString(vmess.get("pbk")),
            shortId = parseJsonString(vmess.get("sid")),
            spiderX = parseJsonString(vmess.get("spx"))
        )
        return settings
    }

    private fun applyUriTransport(settings: JsonObject, network: String, uri: Uri) {
        val host = queryParamFirst(uri, "host").orEmpty()
        val path = uri.getQueryParameter("path") ?: "/"
        val headerType = queryParamFirst(uri, "headerType", "header_type") ?: "none"
        when (network) {
            "tcp" -> settings.add("tcpSettings", createTcpSettings(headerType, host, path))
            "ws" -> settings.add("wsSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) add("headers", JsonObject().apply { addProperty("Host", host) })
                queryParamFirst(uri, "ed", "maxEarlyData", "max_early_data")
                    ?.toIntOrNull()?.takeIf { it > 0 }?.let { addProperty("maxEarlyData", it) }
                queryParamFirst(uri, "eh", "earlyDataHeaderName", "early_data_header_name")
                    ?.let { addProperty("earlyDataHeaderName", it) }
            })
            "httpupgrade" -> settings.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) addProperty("host", host)
            })
            "xhttp" -> settings.add("xhttpSettings", JsonObject().apply {
                addProperty("path", path)
                if (host.isNotBlank()) addProperty("host", host)
                queryParamFirst(uri, "mode")?.let { addProperty("mode", it) }
                queryParamFirst(uri, "extra")?.let { raw ->
                    runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { add("extra", it) }
                }
            })
            "h2" -> settings.add("httpSettings", JsonObject().apply {
                addProperty("path", path)
                add("host", JsonArray().apply { parseCsvParams(host).forEach { add(it) } })
            })
            "grpc" -> settings.add("grpcSettings", JsonObject().apply {
                addProperty("serviceName", queryParamFirst(uri, "serviceName", "service_name") ?: path.removePrefix("/"))
                queryParamFirst(uri, "authority")?.let { addProperty("authority", it) }
                addProperty("multiMode", queryParamFirst(uri, "mode").equals("multi", true))
            })
            "kcp" -> settings.add("kcpSettings", JsonObject().apply {
                add("header", JsonObject().apply { addProperty("type", normalizeKcpHeader(headerType)) })
                queryParamFirst(uri, "seed")?.let { addProperty("seed", it) }
            })
            "quic" -> settings.add("quicSettings", JsonObject().apply {
                addProperty("security", queryParamFirst(uri, "quicSecurity") ?: "none")
                addProperty("key", queryParamFirst(uri, "key") ?: "")
                add("header", JsonObject().apply { addProperty("type", headerType) })
            })
        }
    }

    private fun applyVmessTransport(settings: JsonObject, network: String, vmess: JsonObject) {
        val hostValues = parseJsonStringList(vmess.get("host"))
        val host = hostValues.joinToString(",")
        val path = parseJsonString(vmess.get("path")) ?: "/"
        val headerType = parseJsonString(vmess.get("type"))
            ?: parseJsonString(vmess.get("headerType"))
            ?: "none"
        when (network) {
            "tcp" -> settings.add("tcpSettings", createTcpSettings(headerType, host, path))
            "ws" -> settings.add("wsSettings", JsonObject().apply {
                addProperty("path", path)
                hostValues.firstOrNull()?.let { value ->
                    add("headers", JsonObject().apply { addProperty("Host", value) })
                }
                (parseJsonInt(vmess.get("ed")) ?: parseJsonInt(vmess.get("maxEarlyData")))
                    ?.takeIf { it > 0 }?.let { addProperty("maxEarlyData", it) }
                (parseJsonString(vmess.get("eh")) ?: parseJsonString(vmess.get("earlyDataHeaderName")))
                    ?.let { addProperty("earlyDataHeaderName", it) }
            })
            "httpupgrade" -> settings.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", path)
                hostValues.firstOrNull()?.let { addProperty("host", it) }
            })
            "xhttp" -> settings.add("xhttpSettings", JsonObject().apply {
                addProperty("path", path)
                hostValues.firstOrNull()?.let { addProperty("host", it) }
                parseJsonString(vmess.get("mode"))?.let { addProperty("mode", it) }
                parseJsonString(vmess.get("extra"))?.let { raw ->
                    runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { add("extra", it) }
                }
            })
            "h2" -> settings.add("httpSettings", JsonObject().apply {
                addProperty("path", path)
                add("host", JsonArray().apply { hostValues.forEach { add(it) } })
            })
            "grpc" -> settings.add("grpcSettings", JsonObject().apply {
                addProperty(
                    "serviceName",
                    parseJsonString(vmess.get("serviceName")) ?: path.removePrefix("/")
                )
                parseJsonString(vmess.get("authority"))?.let { addProperty("authority", it) }
                addProperty("multiMode", parseJsonString(vmess.get("mode")).equals("multi", true))
            })
            "kcp" -> settings.add("kcpSettings", JsonObject().apply {
                add("header", JsonObject().apply { addProperty("type", normalizeKcpHeader(headerType)) })
                parseJsonString(vmess.get("seed"))?.let { addProperty("seed", it) }
            })
            "quic" -> settings.add("quicSettings", JsonObject().apply {
                addProperty("security", parseJsonString(vmess.get("host")) ?: "none")
                addProperty("key", parseJsonString(vmess.get("path")) ?: "")
                add("header", JsonObject().apply { addProperty("type", headerType) })
            })
        }
    }

    private fun createTcpSettings(headerType: String, host: String, path: String): JsonObject =
        JsonObject().apply {
            add("header", JsonObject().apply {
                if (headerType.equals("http", true)) {
                    addProperty("type", "http")
                    add("request", JsonObject().apply {
                        add("path", JsonArray().apply {
                            parseCsvParams(path).ifEmpty { listOf("/") }.forEach { add(it) }
                        })
                        add("headers", JsonObject().apply {
                            val hosts = parseCsvParams(host)
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

    private fun normalizeXrayTransport(value: String?): String = when (value?.trim()?.lowercase()) {
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

    private fun inboundTag(nodeId: String) = "bridge-in-$nodeId"
    private fun outboundTag(nodeId: String) = "proxy-$nodeId"

    companion object {
        val XRAY_SUPPORTED_TYPES: Set<NodeType> = ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet()
        private const val DIRECT_TAG = "direct"
        private const val BLOCK_TAG = "block"
    }
}
