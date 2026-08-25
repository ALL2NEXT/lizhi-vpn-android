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
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProtocolCoreSettings

/** 为每个节点生成独立 SOCKS 桥接入站，并统一交由一个 Xray 进程运行。 */
class WindowsXrayConfigGenerator {
    private val gson = Gson()

    fun generate(
        nodes: List<DesktopNode>,
        bridgePorts: Map<String, Int>,
        settings: DesktopSettings = DesktopSettings()
    ): String {
        require(nodes.isNotEmpty()) { "Xray 节点列表为空" }
        require(nodes.all { it.type in XRAY_SUPPORTED_TYPES }) { "包含 Xray 不支持的协议" }
        require(nodes.all { bridgePorts[it.id] in 1..65_535 }) { "Xray 桥接端口无效" }

        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply {
                nodes.forEach { node -> add(createBridgeInbound(node, bridgePorts.getValue(node.id))) }
            })
            add("outbounds", JsonArray().apply {
                nodes.forEach { node ->
                    add(createNodeOutbound(node, outboundTag(node.id), settings))
                }
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

    fun generateTestConfig(
        node: DesktopNode,
        bridgePort: Int = 20_800,
        settings: DesktopSettings = DesktopSettings()
    ): String = generate(listOf(node), mapOf(node.id to bridgePort), settings)

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

    private fun createNodeOutbound(
        node: DesktopNode,
        tag: String,
        settings: DesktopSettings
    ): JsonObject = when (node.type) {
        NodeType.VLESS -> createVlessOutbound(node, tag)
        NodeType.VMESS -> createVmessOutbound(node, tag)
        NodeType.TROJAN -> createTrojanOutbound(node, tag)
        NodeType.HYSTERIA2 -> createHysteria2Outbound(node, tag, settings)
        NodeType.WIREGUARD -> createWireGuardOutbound(node, tag)
        NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node, tag)
        NodeType.SOCKS -> createSocksOutbound(node, tag)
        NodeType.HTTP -> createHttpOutbound(node, tag)
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

    /** 按 Xray 的 hysteria 出站 + hysteria 传输格式生成 Hysteria2 配置。 */
    private fun createHysteria2Outbound(
        node: DesktopNode,
        tag: String,
        settings: DesktopSettings
    ): JsonObject {
        val normalizedLink = node.rawLink.replaceFirst(Regex("(?i)^hy2://"), "hysteria2://")
        val uri = URI(normalizedLink.replace(" ", "%20"))
        val query = uri.queryParameters()
        val password = uri.rawUserInfo?.urlDecode().orEmpty()
        require(password.isNotBlank()) { "Hysteria2 认证信息为空" }
        val portSpec = query.first("mport", "ports") ?: extractHysteria2PortSpec(normalizedLink)
        val hasPortHopping = portSpec.contains(',') || portSpec.contains('-')
        val obfsType = query.first("obfs")
        val obfsPassword = query.first("obfs-password", "obfs_password", "obfsPassword")
        require(obfsType.isNullOrBlank() || obfsType.equals("salamander", true)) {
            "Xray Hysteria2 仅支持 salamander 混淆"
        }
        if (!obfsType.isNullOrBlank()) {
            require(!obfsPassword.isNullOrBlank()) { "Hysteria2 salamander 混淆密码为空" }
        }

        return JsonObject().apply {
            addProperty("protocol", "hysteria")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                addProperty("version", 2)
                addProperty("address", node.server)
                addProperty("port", node.port)
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "hysteria")
                addProperty("security", "tls")
                add("tlsSettings", createTlsSettings(query, node.server, listOf("h3")))
                add("hysteriaSettings", JsonObject().apply {
                    addProperty("version", 2)
                    addProperty("auth", password)
                    query.first("udpIdleTimeout", "udp_idle_timeout")
                        ?.removeSuffix("s")
                        ?.toIntOrNull()
                        ?.takeIf { it in 2..600 }
                        ?.let { addProperty("udpIdleTimeout", it) }
                })

                val useBandwidth = settings.hysteriaBandwidthEnabled &&
                    (settings.hysteriaUpMbps > 0 || settings.hysteriaDownMbps > 0)
                if (!obfsType.isNullOrBlank() || useBandwidth || hasPortHopping) {
                    add("finalmask", JsonObject().apply {
                        if (!obfsType.isNullOrBlank()) {
                            add("udp", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "salamander")
                                    add("settings", JsonObject().apply {
                                        addProperty("password", obfsPassword)
                                    })
                                })
                            })
                        }
                        if (useBandwidth || hasPortHopping) {
                            add("quicParams", JsonObject().apply {
                                if (useBandwidth) {
                                    addProperty("congestion", "brutal")
                                    if (settings.hysteriaUpMbps > 0) {
                                        addProperty("brutalUp", "${settings.hysteriaUpMbps} mbps")
                                    }
                                    if (settings.hysteriaDownMbps > 0) {
                                        addProperty("brutalDown", "${settings.hysteriaDownMbps} mbps")
                                    }
                                }
                                if (hasPortHopping) {
                                    add("udpHop", JsonObject().apply {
                                        addProperty("ports", portSpec)
                                        addProperty("interval", parseHysteria2HopInterval(query))
                                    })
                                }
                            })
                        }
                    })
                }
            })
            add("mux", JsonObject().apply { addProperty("enabled", false) })
        }
    }

    private fun createWireGuardOutbound(node: DesktopNode, tag: String): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        val secretKey = uri.rawUserInfo?.urlDecode() ?: query.first("private_key", "privateKey")
        val publicKey = query.first("publickey", "peer_public_key", "peerPublicKey")
        require(!secretKey.isNullOrBlank()) { "WireGuard 私钥为空" }
        require(!publicKey.isNullOrBlank()) { "WireGuard 对端公钥为空" }
        val addresses = parseCsv(query.first("address", "local_address", "localAddress"))
            .ifEmpty { listOf("10.0.0.2/32") }

        return JsonObject().apply {
            addProperty("protocol", "wireguard")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                addProperty("secretKey", secretKey)
                add("address", JsonArray().apply { addresses.forEach { add(it) } })
                addProperty("noKernelTun", true)
                query.first("mtu")?.toIntOrNull()?.let { addProperty("mtu", it) }
                query.first("workers")?.toIntOrNull()?.let { addProperty("workers", it) }
                query.first("reserved")
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.takeIf { it.size == 3 }
                    ?.let { reserved ->
                        add("reserved", JsonArray().apply { reserved.forEach { add(it) } })
                    }
                add("peers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("publicKey", publicKey)
                        query.first("presharedkey", "pre_shared_key", "preSharedKey")
                            ?.let { addProperty("preSharedKey", it) }
                        addProperty("endpoint", formatEndpoint(node.server, node.port))
                        query.first("keepalive", "keepAlive", "persistent_keepalive")
                            ?.toIntOrNull()
                            ?.takeIf { it >= 0 }
                            ?.let { addProperty("keepAlive", it) }
                        parseCsv(query.first("allowed_ips", "allowedIPs"))
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { allowed ->
                                add("allowedIPs", JsonArray().apply { allowed.forEach { add(it) } })
                            }
                    })
                })
            })
        }
    }

    private fun createSocksOutbound(node: DesktopNode, tag: String): JsonObject {
        require(
            !node.rawLink.startsWith("socks4://", true) &&
                !node.rawLink.startsWith("socks4a://", true)
        ) { "Xray SOCKS 出站仅支持 SOCKS5，SOCKS4/4a 请使用 sing-box" }
        val credentials = node.uri().rawUserInfo?.urlDecode().orEmpty().split(':', limit = 2)
        return createSimpleProxyOutbound(
            "socks",
            tag,
            node,
            credentials.firstOrNull(),
            credentials.getOrNull(1)
        )
    }

    private fun createHttpOutbound(node: DesktopNode, tag: String): JsonObject {
        val uri = node.uri()
        val query = uri.queryParameters()
        val credentials = uri.rawUserInfo?.urlDecode().orEmpty().split(':', limit = 2)
        return createSimpleProxyOutbound(
            "http",
            tag,
            node,
            credentials.firstOrNull(),
            credentials.getOrNull(1)
        ).apply {
            if (node.rawLink.startsWith("https://", true)) {
                add("streamSettings", JsonObject().apply {
                    addProperty("network", "tcp")
                    addProperty("security", "tls")
                    add("tlsSettings", createTlsSettings(query, node.server))
                })
            }
        }
    }

    private fun createSimpleProxyOutbound(
        protocol: String,
        tag: String,
        node: DesktopNode,
        username: String?,
        password: String?
    ) = JsonObject().apply {
        addProperty("protocol", protocol)
        addProperty("tag", tag)
        add("settings", JsonObject().apply {
            addProperty("address", node.server)
            addProperty("port", node.port)
            username?.takeIf(String::isNotBlank)?.let {
                addProperty("user", it)
                addProperty("pass", password.orEmpty())
            }
        })
    }

    private fun createTlsSettings(
        query: Map<String, List<String>>,
        server: String,
        defaultAlpn: List<String> = emptyList()
    ): JsonObject {
        val pinnedCertificate = query.first("pinSHA256", "pinnedPeerCertSha256", "pcs")
        val verifyByName = query.first("verifyPeerCertByName", "vcn")
        val insecure = query.enabled(
            "allowInsecure",
            "allow_insecure",
            "insecure",
            "skip-cert-verify"
        )
        require(!insecure || !pinnedCertificate.isNullOrBlank() || !verifyByName.isNullOrBlank()) {
            "当前 Xray 已移除 allowInsecure，请让服务端提供 pcs/pinSHA256 证书指纹"
        }
        val alpn = parseCsv(query.first("alpn")).ifEmpty { defaultAlpn }
        return JsonObject().apply {
            addProperty("serverName", query.first("sni", "serverName", "host") ?: server)
            if (alpn.isNotEmpty()) add("alpn", JsonArray().apply { alpn.forEach { add(it) } })
            query.first("fp", "fingerprint", "client-fingerprint")
                ?.let { addProperty("fingerprint", it) }
            pinnedCertificate?.let { addProperty("pinnedPeerCertSha256", it) }
            verifyByName?.let { addProperty("verifyPeerCertByName", it) }
        }
    }

    private fun extractHysteria2PortSpec(link: String): String {
        val authority = link.substringAfter("//").substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
        return if (authority.startsWith("[")) {
            authority.substringAfter(']', "").removePrefix(":")
        } else {
            authority.substringAfterLast(':', "")
        }
    }

    private fun parseHysteria2HopInterval(query: Map<String, List<String>>): Int {
        val raw = query.first("hopInterval", "hop_interval", "interval")
            ?.trim()
            ?.lowercase()
            ?: return 30
        val seconds = when {
            raw.endsWith("ms") -> raw.removeSuffix("ms").toDoubleOrNull()?.div(1_000)?.toInt()
            raw.endsWith("s") -> raw.removeSuffix("s").toDoubleOrNull()?.toInt()
            raw.endsWith("m") -> raw.removeSuffix("m").toDoubleOrNull()?.times(60)?.toInt()
            else -> raw.toIntOrNull()
        }
        return (seconds ?: 30).coerceAtLeast(5)
    }

    private fun formatEndpoint(server: String, port: Int): String =
        if (':' in server && !server.startsWith("[")) "[$server]:$port" else "$server:$port"

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
                query.first("spx", "spiderX"),
                query.first("pinSHA256", "pinnedPeerCertSha256", "pcs"),
                query.first("verifyPeerCertByName", "vcn")
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
                vmess.get("spx").stringOrNull(),
                vmess.get("pinSHA256").stringOrNull()
                    ?: vmess.get("pinnedPeerCertSha256").stringOrNull()
                    ?: vmess.get("pcs").stringOrNull(),
                vmess.get("verifyPeerCertByName").stringOrNull()
                    ?: vmess.get("vcn").stringOrNull()
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
        spiderX: String?,
        pinnedCertificate: String?,
        verifyByName: String?
    ) {
        when (security) {
            "tls" -> {
                streamSettings.addProperty("security", "tls")
                streamSettings.add("tlsSettings", JsonObject().apply {
                    require(
                        !allowInsecure ||
                            !pinnedCertificate.isNullOrBlank() ||
                            !verifyByName.isNullOrBlank()
                    ) {
                        "当前 Xray 已移除 allowInsecure，请让服务端提供 pcs/pinSHA256 证书指纹"
                    }
                    addProperty("serverName", sni ?: server)
                    if (alpn.isNotEmpty()) add("alpn", JsonArray().apply { alpn.forEach { add(it) } })
                    fingerprint?.let { addProperty("fingerprint", it) }
                    pinnedCertificate?.let { addProperty("pinnedPeerCertSha256", it) }
                    verifyByName?.let { addProperty("verifyPeerCertByName", it) }
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
