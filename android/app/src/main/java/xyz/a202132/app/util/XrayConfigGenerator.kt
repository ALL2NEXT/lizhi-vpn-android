package xyz.a202132.app.util

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.a202132.app.core.LocalSocksEndpoint
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProtocolCoreSettings

/** Builds a single-node native Xray configuration with a local probe/API entry. */
class XrayConfigGenerator {
    private val gson = Gson()

    fun generateTestConfig(
        node: Node,
        socksPort: Int = 20_800,
        hysteriaUpMbps: Int = 0,
        hysteriaDownMbps: Int = 0
    ): String = generateCoreConfig(
        node,
        LocalSocksEndpoint.unauthenticated(socksPort),
        hysteriaUpMbps,
        hysteriaDownMbps
    )

    internal fun generateCoreConfig(
        node: Node,
        endpoint: LocalSocksEndpoint,
        hysteriaUpMbps: Int = 0,
        hysteriaDownMbps: Int = 0
    ): String {
        require(node.type in XRAY_SUPPORTED_TYPES) { "Unsupported Xray protocol" }
        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply { add(createSocksInbound(endpoint)) })
            add("outbounds", JsonArray().apply {
                add(createNodeOutbound(node, "proxy", hysteriaUpMbps, hysteriaDownMbps))
                add(JsonObject().apply {
                    addProperty("protocol", "freedom")
                    addProperty("tag", DIRECT_TAG)
                })
                add(JsonObject().apply {
                    addProperty("protocol", "blackhole")
                    addProperty("tag", BLOCK_TAG)
                })
            })
        })
    }

    private fun createSocksInbound(endpoint: LocalSocksEndpoint): JsonObject = JsonObject().apply {
        addProperty("listen", endpoint.address)
        addProperty("port", endpoint.port)
        addProperty("protocol", "socks")
        addProperty("tag", "internal-socks-in")
        add("settings", JsonObject().apply {
            val credentials = endpoint.credentials
            addProperty("auth", if (credentials == null) "noauth" else "password")
            credentials?.let {
                add("accounts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("user", it.username)
                        addProperty("pass", it.password)
                    })
                })
            }
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
        node: Node,
        tag: String,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int
    ): JsonObject = when (node.type) {
        NodeType.VLESS -> createVlessOutbound(node, tag)
        NodeType.VMESS -> createVmessOutbound(node, tag)
        NodeType.TROJAN -> createTrojanOutbound(node, tag)
        NodeType.HYSTERIA2 -> createHysteria2Outbound(
            node,
            tag,
            hysteriaUpMbps,
            hysteriaDownMbps
        )
        NodeType.WIREGUARD -> createWireGuardOutbound(node, tag)
        NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node, tag)
        NodeType.SOCKS -> createSocksOutbound(node, tag)
        NodeType.HTTP -> createHttpOutbound(node, tag)
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

    /** 按 Xray 的 hysteria 出站 + hysteria 传输格式生成 Hysteria2 配置。 */
    private fun createHysteria2Outbound(
        node: Node,
        tag: String,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int
    ): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val normalizedLink = rawLink.replaceFirst(Regex("(?i)^hy2://"), "hysteria2://")
        val uri = Uri.parse(normalizedLink)
        val password = uri.userInfo.orEmpty()
        require(password.isNotBlank()) { "Hysteria2 认证信息为空" }

        val portSpec = queryParamFirst(uri, "mport", "ports")
            ?: extractHysteria2PortSpec(normalizedLink)
        val hasPortHopping = portSpec.contains(',') || portSpec.contains('-')
        val obfsType = queryParamFirst(uri, "obfs")
        val obfsPassword = queryParamFirst(uri, "obfs-password", "obfs_password", "obfsPassword")
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
                add("tlsSettings", createUriTlsSettings(uri, node.server, defaultAlpn = listOf("h3")))
                add("hysteriaSettings", JsonObject().apply {
                    addProperty("version", 2)
                    addProperty("auth", password)
                    queryParamFirst(uri, "udpIdleTimeout", "udp_idle_timeout")
                        ?.removeSuffix("s")
                        ?.toIntOrNull()
                        ?.takeIf { it in 2..600 }
                        ?.let { addProperty("udpIdleTimeout", it) }
                })

                val needsQuicParams = hysteriaUpMbps > 0 || hysteriaDownMbps > 0 || hasPortHopping
                if (!obfsType.isNullOrBlank() || needsQuicParams) {
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
                        if (needsQuicParams) {
                            add("quicParams", JsonObject().apply {
                                if (hysteriaUpMbps > 0 || hysteriaDownMbps > 0) {
                                    addProperty("congestion", "brutal")
                                    if (hysteriaUpMbps > 0) {
                                        addProperty("brutalUp", "$hysteriaUpMbps mbps")
                                    }
                                    if (hysteriaDownMbps > 0) {
                                        addProperty("brutalDown", "$hysteriaDownMbps mbps")
                                    }
                                }
                                if (hasPortHopping) {
                                    add("udpHop", JsonObject().apply {
                                        addProperty("ports", portSpec)
                                        addProperty("interval", parseHysteria2HopInterval(uri))
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

    private fun createWireGuardOutbound(node: Node, tag: String): JsonObject {
        val uri = Uri.parse(node.getRawLinkPlain())
        val secretKey = uri.userInfo ?: queryParamFirst(uri, "private_key", "privateKey")
        val publicKey = queryParamFirst(uri, "publickey", "peer_public_key", "peerPublicKey")
        require(!secretKey.isNullOrBlank()) { "WireGuard 私钥为空" }
        require(!publicKey.isNullOrBlank()) { "WireGuard 对端公钥为空" }
        val addresses = parseCsvParams(
            queryParamFirst(uri, "address", "local_address", "localAddress")
        ).ifEmpty { listOf("10.0.0.2/32") }

        return JsonObject().apply {
            addProperty("protocol", "wireguard")
            addProperty("tag", tag)
            add("settings", JsonObject().apply {
                addProperty("secretKey", secretKey)
                add("address", JsonArray().apply { addresses.forEach(::add) })
                addProperty("noKernelTun", true)
                queryParamFirst(uri, "mtu")?.toIntOrNull()?.let { addProperty("mtu", it) }
                queryParamFirst(uri, "workers")?.toIntOrNull()?.let { addProperty("workers", it) }
                queryParamFirst(uri, "reserved")?.let(::parseWireGuardReserved)?.let { reserved ->
                    add("reserved", JsonArray().apply { reserved.forEach(::add) })
                }
                add("peers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("publicKey", publicKey)
                        queryParamFirst(uri, "presharedkey", "pre_shared_key", "preSharedKey")
                            ?.let { addProperty("preSharedKey", it) }
                        addProperty("endpoint", formatEndpoint(node.server, node.port))
                        queryParamFirst(uri, "keepalive", "keepAlive", "persistent_keepalive")
                            ?.toIntOrNull()
                            ?.takeIf { it >= 0 }
                            ?.let { addProperty("keepAlive", it) }
                        parseCsvParams(queryParamFirst(uri, "allowed_ips", "allowedIPs"))
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { allowed ->
                                add("allowedIPs", JsonArray().apply { allowed.forEach(::add) })
                            }
                    })
                })
            })
        }
    }

    private fun createSocksOutbound(node: Node, tag: String): JsonObject {
        val rawLink = node.getRawLinkPlain()
        require(!rawLink.startsWith("socks4://", true) && !rawLink.startsWith("socks4a://", true)) {
            "Xray SOCKS 出站仅支持 SOCKS5，SOCKS4/4a 请使用 sing-box"
        }
        val uri = Uri.parse(rawLink)
        val credentials = uri.userInfo.orEmpty().split(':', limit = 2)
        return createSimpleProxyOutbound(
            protocol = "socks",
            tag = tag,
            node = node,
            username = credentials.firstOrNull(),
            password = credentials.getOrNull(1)
        )
    }

    private fun createHttpOutbound(node: Node, tag: String): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val credentials = uri.userInfo.orEmpty().split(':', limit = 2)
        return createSimpleProxyOutbound(
            protocol = "http",
            tag = tag,
            node = node,
            username = credentials.firstOrNull(),
            password = credentials.getOrNull(1)
        ).apply {
            if (rawLink.startsWith("https://", true)) {
                add("streamSettings", JsonObject().apply {
                    addProperty("network", "tcp")
                    addProperty("security", "tls")
                    add("tlsSettings", createUriTlsSettings(uri, node.server))
                })
            }
        }
    }

    private fun createSimpleProxyOutbound(
        protocol: String,
        tag: String,
        node: Node,
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

    private fun createUriTlsSettings(
        uri: Uri,
        server: String,
        defaultAlpn: List<String> = emptyList()
    ): JsonObject {
        val pinnedCertificate = queryParamFirst(
            uri,
            "pinSHA256",
            "pinnedPeerCertSha256",
            "pcs"
        )
        val verifyByName = queryParamFirst(uri, "verifyPeerCertByName", "vcn")
        val insecure = queryParamEnabled(
            uri,
            "allowInsecure",
            "allow_insecure",
            "insecure",
            "skip-cert-verify"
        )
        require(!insecure || !pinnedCertificate.isNullOrBlank() || !verifyByName.isNullOrBlank()) {
            "当前 Xray 已移除 allowInsecure，请让服务端提供 pcs/pinSHA256 证书指纹"
        }
        val alpn = parseCsvParams(queryParamFirst(uri, "alpn")).ifEmpty { defaultAlpn }
        return JsonObject().apply {
            addProperty("serverName", queryParamFirst(uri, "sni", "serverName", "host") ?: server)
            if (alpn.isNotEmpty()) add("alpn", JsonArray().apply { alpn.forEach { add(it) } })
            queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint")
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
            authority.substringAfterLast(':', nodePortFallback = "")
        }
    }

    private fun String.substringAfterLast(
        delimiter: Char,
        nodePortFallback: String
    ): String = if (lastIndexOf(delimiter) >= 0) substring(lastIndexOf(delimiter) + 1) else nodePortFallback

    private fun parseHysteria2HopInterval(uri: Uri): Int {
        val raw = queryParamFirst(uri, "hopInterval", "hop_interval", "interval")
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
            spiderX = queryParamFirst(uri, "spx", "spiderX"),
            pinnedCertificate = queryParamFirst(
                uri,
                "pinSHA256",
                "pinnedPeerCertSha256",
                "pcs"
            ),
            verifyByName = queryParamFirst(uri, "verifyPeerCertByName", "vcn")
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
            spiderX = parseJsonString(vmess.get("spx")),
            pinnedCertificate = parseJsonString(vmess.get("pinSHA256"))
                ?: parseJsonString(vmess.get("pinnedPeerCertSha256"))
                ?: parseJsonString(vmess.get("pcs")),
            verifyByName = parseJsonString(vmess.get("verifyPeerCertByName"))
                ?: parseJsonString(vmess.get("vcn"))
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


    companion object {
        val XRAY_SUPPORTED_TYPES: Set<NodeType> = ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet()
        private const val DIRECT_TAG = "direct"
        private const val BLOCK_TAG = "block"
    }
}
